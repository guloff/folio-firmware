package com.rustamguloff.foliolink.core.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

/** Байтовый прогресс передачи: (сделано, всего; -1 — неизвестно). */
typealias TransferProgress = (done: Long, total: Long) -> Unit

/**
 * Тело запроса: длина известна заранее, содержимое пишется в поток (файл не читается в память).
 */
interface HttpBody {
    val length: Long

    companion object {
        const val CHUNK = 16 * 1024
    }

    /** Пишет тело целиком; `onProgress` — сколько байт уже записано. */
    fun writeTo(out: OutputStream, onProgress: (Long) -> Unit = {})
}

/** Тело из массива в памяти (JSON, формы, небольшие файлы). */
class BytesBody(val bytes: ByteArray) : HttpBody {
    override val length: Long get() = bytes.size.toLong()

    override fun writeTo(out: OutputStream, onProgress: (Long) -> Unit) {
        var off = 0
        while (off < bytes.size) {
            val n = minOf(HttpBody.CHUNK, bytes.size - off)
            out.write(bytes, off, n)
            off += n
            onProgress(off.toLong())
        }
    }
}

/** Запрос к читалке. `url` — уже собранный и закодированный. */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    body: ByteArray? = null,
    /** Таймаут ожидания данных, мс (соединение — [HttpTransport.CONNECT_TIMEOUT_MS]). */
    val timeoutMs: Int = 15_000,
    /** Потоковое тело (большие файлы); по умолчанию — [body] из памяти. */
    val content: HttpBody? = body?.let(::BytesBody),
    /** Потолок тела ответа, байт: больше — [DeviceError.TooLarge], в память не читается. */
    val maxResponseBytes: Long = HttpTransport.MAX_JSON_RESPONSE_BYTES,
) {
    /**
     * Тело целиком в памяти: для виртуальной читалки и тестов. Сетевой транспорт пишет [content]
     * потоком и это свойство не трогает.
     */
    val body: ByteArray? by lazy {
        when (val c = content) {
            null -> null
            is BytesBody -> c.bytes
            else -> ByteArrayOutputStream(c.length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).also { c.writeTo(it) }.toByteArray()
        }
    }

    val path: String get() = runCatching { URI(url).rawPath }.getOrNull().orEmpty()

    /** Путь без процентного кодирования. */
    val decodedPath: String get() = runCatching { URI(url).path }.getOrNull().orEmpty()

    /** Параметры запроса, раскодированные. */
    val query: Map<String, String>
        get() = parseQuery(runCatching { URI(url).rawQuery }.getOrNull())

    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    companion object {
        fun parseQuery(raw: String?): Map<String, String> {
            if (raw.isNullOrEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val i = pair.indexOf('=')
                val k = PercentCoding.decode(if (i < 0) pair else pair.substring(0, i))
                val v = if (i < 0) "" else PercentCoding.decode(pair.substring(i + 1))
                out.putIfAbsent(k, v)
            }
            return out
        }
    }
}

class HttpResponse(
    val status: Int,
    val body: ByteArray,
    val contentType: String? = null,
) {
    val text: String get() = body.toString(Charsets.UTF_8)
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * Один HTTP-обмен с читалкой. Реализации: [UrlConnectionTransport] (сеть), `DemoTransport`
 * (виртуальная читалка), фейки в тестах. Сетевые сбои превращаются в [DeviceError.Unreachable] /
 * [DeviceError.Timeout]; код ответа не оценивается.
 */
interface HttpTransport {
    /** `progress` — отправленные байты для запроса с телом, иначе полученные. */
    suspend fun execute(request: HttpRequest, progress: TransferProgress? = null): HttpResponse

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000

        /** Потолок JSON-ответа читалки: с запасом на журнал сессий за годы, но не «сколько пришлёт». */
        const val MAX_JSON_RESPONSE_BYTES = 16L * 1024 * 1024

        /** Потолок скачиваемого файла (скриншоты, картинки сна — сотни килобайт). */
        const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024

        /** Транспорт для адреса: демо-читалка отвечает из памяти, остальные — по сети. */
        fun forEndpoint(endpoint: com.rustamguloff.foliolink.core.model.DeviceEndpoint): HttpTransport =
            if (endpoint.isDemo) DemoTransport() else UrlConnectionTransport()
    }
}

/** HttpURLConnection: одно соединение на запрос, без кэша и без переадресаций. */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = HttpTransport.CONNECT_TIMEOUT_MS,
) : HttpTransport {

    override suspend fun execute(request: HttpRequest, progress: TransferProgress?): HttpResponse =
        withContext(Dispatchers.IO) {
            val conn = try {
                URI(request.url).toURL().openConnection() as HttpURLConnection
            } catch (e: Exception) {
                throw DeviceError.InvalidAddress
            }
            coroutineScope {
                // Отмена корутины рвёт соединение: блокирующее чтение сразу выходит с ошибкой.
                val watcher = launch {
                    try { awaitCancellation() } finally { conn.disconnect() }
                }
                try {
                    perform(conn, request, progress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    throw map(e)
                } finally {
                    watcher.cancel()
                }
            }
        }

    private suspend fun perform(conn: HttpURLConnection, request: HttpRequest, progress: TransferProgress?): HttpResponse {
        conn.requestMethod = request.method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = request.timeoutMs
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        conn.doInput = true
        request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val body = request.content
        if (body != null) {
            conn.doOutput = true
            val total = body.length
            conn.setFixedLengthStreamingMode(total)
            progress?.invoke(0, total)
            conn.outputStream.use { out ->
                val context = coroutineContext
                body.writeTo(out) { done ->
                    context.ensureActive()
                    progress?.invoke(done, total)
                }
            }
        }
        val status = conn.responseCode
        val stream: InputStream? = if (status >= 400) conn.errorStream else runCatching { conn.inputStream }.getOrNull()
        val expected = conn.contentLengthLong
        val limit = request.maxResponseBytes
        // Content-Length — слово удалённой стороны: по нему не выделяем память, а только отказываем заранее.
        if (expected > limit) throw DeviceError.TooLarge(limit)
        val data = stream?.use { input ->
            if (body == null) progress?.invoke(0, expected)
            readLimited(input, limit) { done ->
                coroutineContext.ensureActive()
                if (body == null) progress?.invoke(done, expected)
            } ?: throw DeviceError.TooLarge(limit)
        } ?: ByteArray(0)
        if (body == null) progress?.invoke(data.size.toLong(), if (expected > 0) expected else data.size.toLong())
        return HttpResponse(status, data, conn.contentType)
    }

    companion object {
        /** Сетевое исключение → ошибка читалки. */
        fun map(e: IOException): DeviceError = when (e) {
            is SocketTimeoutException -> DeviceError.Timeout
            is UnknownHostException, is ConnectException, is NoRouteToHostException,
            is PortUnreachableException -> DeviceError.Unreachable
            is SocketException -> DeviceError.Unreachable // обрыв, сброс соединения, сеть пропала
            is InterruptedIOException -> DeviceError.Timeout
            else -> if (e.message?.contains("unexpected end of stream", ignoreCase = true) == true) {
                DeviceError.Unreachable
            } else {
                DeviceError.Other(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}

/**
 * Читает поток до конца, но не больше `limit` байт: null — данных больше (остаток не читается).
 * Одна реализация для ответов читалки, файлов из «Поделиться»/выбора и загрузки страниц.
 */
inline fun readLimited(input: InputStream, limit: Long, onProgress: (Long) -> Unit = {}): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(HttpBody.CHUNK)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > limit) return null
        out.write(buf, 0, n)
        onProgress(total)
    }
    return out.toByteArray()
}

/** Процентное кодирование UTF-8 для путей и параметров. */
object PercentCoding {
    private const val UNRESERVED = "-._~"

    /** Кодирует всё, кроме букв/цифр ASCII, `-._~` и символов из [keep]. */
    fun encode(s: String, keep: String = ""): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF)
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || ch in UNRESERVED || ch in keep)) {
                sb.append(ch)
            } else {
                sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
        return sb.toString()
    }

    /** Обратное к [encode]; `+` в параметрах формы — пробел. */
    fun decode(s: String, plusIsSpace: Boolean = true): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '%' && i + 2 < s.length -> {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { out.write(v); i += 3; continue }
                    out.write('%'.code); i++
                }
                ch == '+' && plusIsSpace -> { out.write(' '.code); i++ }
                ch.isHighSurrogate() && i + 1 < s.length -> {
                    out.write(s.substring(i, i + 2).toByteArray(Charsets.UTF_8)); i += 2
                }
                else -> { out.write(ch.toString().toByteArray(Charsets.UTF_8)); i++ }
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
