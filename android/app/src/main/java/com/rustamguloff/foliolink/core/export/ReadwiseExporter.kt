package com.rustamguloff.foliolink.core.export

import com.rustamguloff.foliolink.core.epub.prefixCodePoints
import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.Highlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// region CSV

/** Выделения в CSV-формате импорта Readwise (readwise.io/import_bulk). */
object ReadwiseCsv {
    val header = listOf("Highlight", "Title", "Author", "URL", "Note", "Location", "Date")

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.US)

    /** Поле RFC 4180: в кавычках, если есть запятая, кавычка, CR или LF; кавычки удваиваются. */
    fun field(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    /** "yyyy-MM-dd HH:mm:ss" в поясе `zone`; "" без даты. `created` — epoch-секунды. */
    fun dateString(created: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (created <= 0) "" else DATE.format(Instant.ofEpochSecond(created).atZone(zone))

    fun csv(highlights: List<Highlight>, books: List<Book>, zone: ZoneId = ZoneId.systemDefault()): String {
        val rows = ArrayList<String>()
        rows += header.joinToString(",")
        for (h in ReadwiseExport.ordered(highlights)) {
            val meta = ReadwiseExport.book(h, books)
            val columns = listOf(
                h.text, meta.title, meta.author, "", h.note,
                if (h.pct >= 0) h.pct.toString() else "", dateString(h.created, zone),
            )
            rows += columns.joinToString(",") { field(it) }
        }
        return rows.joinToString("\r\n") + "\r\n"
    }

    /** Пишет `FolioLink-Readwise.csv` в новую папку внутри `parentDir`. */
    suspend fun exportFile(highlights: List<Highlight>, books: List<Book>, parentDir: File): File =
        withContext(Dispatchers.IO) {
            val dir = File(parentDir, "FolioLinkReadwise-${UUID.randomUUID()}")
            if (!dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create $dir")
            File(dir, "FolioLink-Readwise.csv").apply { writeText(csv(highlights, books), Charsets.UTF_8) }
        }
}

/** Соответствие полей, общее для CSV и загрузки через API. */
object ReadwiseExport {
    data class BookMeta(val title: String, val author: String)

    /** По книге, затем по позиции и времени. */
    fun ordered(highlights: List<Highlight>): List<Highlight> =
        highlights.sortedWith(compareBy({ it.book }, { it.pct }, { it.created }))

    fun book(h: Highlight, books: List<Book>): BookMeta {
        val book = books.firstOrNull { it.path == h.book }
        val title = book?.displayTitle ?: h.title.ifEmpty {
            val name = h.book.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            if (dot > 0) name.substring(0, dot) else name
        }
        return BookMeta(title, book?.author ?: "")
    }
}

// endregion

// region API

/** Одно выделение для `POST /api/v2/highlights/`. Пустые поля в JSON опускаются. */
@Serializable
data class ReadwiseHighlight(
    val text: String,
    val title: String,
    val author: String? = null,
    val note: String? = null,
    val location: Int? = null,
    @SerialName("location_type") val locationType: String? = null,
    @SerialName("highlighted_at") val highlightedAt: String? = null,
    @SerialName("source_type") val sourceType: String = "foliolink",
    val category: String = "books",
) {
    companion object {
        /** Ограничения Readwise (длиннее — отклоняется). */
        const val MAX_TEXT = 8191
        const val MAX_TITLE = 511
        const val MAX_AUTHOR = 1024
        const val MAX_NOTE = 8191

        private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

        fun make(h: Highlight, books: List<Book>): ReadwiseHighlight {
            val meta = ReadwiseExport.book(h, books)
            return ReadwiseHighlight(
                text = h.text.prefixCodePoints(MAX_TEXT),
                title = meta.title.prefixCodePoints(MAX_TITLE),
                author = meta.author.takeIf { it.isNotEmpty() }?.prefixCodePoints(MAX_AUTHOR),
                note = h.note.takeIf { it.isNotEmpty() }?.prefixCodePoints(MAX_NOTE),
                location = h.pct.takeIf { it >= 0 },
                locationType = if (h.pct >= 0) "order" else null,
                highlightedAt = if (h.created > 0) ISO.format(Instant.ofEpochSecond(h.created)) else null,
            )
        }
    }
}

@Serializable
private data class ReadwiseUploadBody(val highlights: List<ReadwiseHighlight>)

/** Ошибки Readwise; текст для человека — в UI. */
sealed class ReadwiseException(message: String) : Exception(message) {
    /** 401/403: токен не принят. */
    data object InvalidToken : ReadwiseException("invalid token")

    /** 429; `retryAfter` — секунды из Retry-After, если есть. */
    data class RateLimited(val retryAfter: Int?) : ReadwiseException("rate limited, retry after $retryAfter")

    data class Server(val status: Int, val detail: String) : ReadwiseException("HTTP $status: $detail")
}

/** HTTP-запрос к Readwise. */
class ReadwiseRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
)

/** Ответ; `header()` ищет без учёта регистра. */
class ReadwiseResponse(val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0)) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** Сетевой слой клиента Readwise — подменяется в тестах. Сетевые сбои — IOException. */
fun interface ReadwiseTransport {
    suspend fun send(request: ReadwiseRequest): ReadwiseResponse
}

/** Транспорт на HttpURLConnection (Dispatchers.IO, таймаут 30 с). */
class UrlConnectionReadwiseTransport(private val timeoutMs: Int = 30_000) : ReadwiseTransport {
    override suspend fun send(request: ReadwiseRequest): ReadwiseResponse = withContext(Dispatchers.IO) {
        val conn = URI(request.url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            request.body?.let { body ->
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else runCatching { conn.inputStream }.getOrNull()
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val headers = conn.headerFields.orEmpty()
                .filterKeys { it != null }
                .mapValues { (_, v) -> v.orEmpty().joinToString(",") }
            ReadwiseResponse(status, headers, bytes)
        } finally {
            conn.disconnect()
        }
    }
}

/** Readwise API v2. Токен — собственный токен пользователя (readwise.io/access_token). */
class ReadwiseClient(token: String, private val transport: ReadwiseTransport = UrlConnectionReadwiseTransport()) {
    val token: String = token.trim()

    companion object {
        const val BASE_URL = "https://readwise.io/api/v2/"
        const val BATCH_SIZE = 100

        private val json = Json { encodeDefaults = true; explicitNulls = false }

        /** Ответ не-2xx → типизированная ошибка. */
        fun error(response: ReadwiseResponse): ReadwiseException = when (val status = response.status) {
            401, 403 -> ReadwiseException.InvalidToken
            429 -> ReadwiseException.RateLimited(response.header("Retry-After")?.trim()?.toIntOrNull())
            else -> {
                val text = strictUtf8(response.body.copyOf(minOf(response.body.size, 300)))?.trim() ?: ""
                ReadwiseException.Server(status, text.ifEmpty { reasonPhrase(status) })
            }
        }

        private fun strictUtf8(data: ByteArray): String? = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString()
        }.getOrNull()

        /** Стандартная фраза статуса (как `HTTPURLResponse.localizedString`, по-английски). */
        fun reasonPhrase(status: Int): String = when (status) {
            400 -> "bad request"
            404 -> "not found"
            405 -> "method not allowed"
            408 -> "request timed out"
            413 -> "request too large"
            500 -> "internal server error"
            501 -> "unimplemented"
            502 -> "bad gateway"
            503 -> "service unavailable"
            504 -> "gateway timed out"
            else -> "HTTP $status"
        }
    }

    private fun request(path: String, method: String, body: ByteArray? = null): ReadwiseRequest {
        val headers = linkedMapOf("Authorization" to "Token $token")
        if (body != null) headers["Content-Type"] = "application/json"
        return ReadwiseRequest(method, "$BASE_URL$path/", headers, body)
    }

    /** `GET /auth/`: 204 (или 200) — токен действителен, 401/403 — нет, иное — ошибка. */
    suspend fun checkToken(): Boolean {
        val response = transport.send(request("auth", "GET"))
        return when (response.status) {
            204, 200 -> true
            401, 403 -> false
            else -> throw error(response)
        }
    }

    /** Загружает пакетами по ≤ 100; при первой ошибке останавливается. Возвращает число отправленных. */
    suspend fun upload(highlights: List<ReadwiseHighlight>): Int {
        var sent = 0
        for (batch in highlights.chunked(BATCH_SIZE)) {
            val body = json.encodeToString(ReadwiseUploadBody.serializer(), ReadwiseUploadBody(batch))
            val response = transport.send(request("highlights", "POST", body.toByteArray(Charsets.UTF_8)))
            if (response.status !in 200..299) throw error(response)
            sent += batch.size
        }
        return sent
    }
}

// endregion

// region Токен

/**
 * Токен Readwise пользователя. Шифрование подключается реализацией (Keystore — в core/store);
 * здесь — контракт и простая файловая реализация.
 */
interface ReadwiseTokenStore {
    /** Сохранённый токен; null, если его нет или он пустой. */
    suspend fun token(): String?

    /** Сохраняет токен без пробелов по краям. */
    suspend fun save(token: String)

    suspend fun remove()
}

/**
 * Токен в JSON-файле `{"readwise":"…"}` (как файловый бэкенд PairingTokenStore на iOS).
 * Без шифрования: для отладки и тестов либо как основа для зашифрованной обёртки.
 */
class FileReadwiseTokenStore(private val file: File) : ReadwiseTokenStore {
    private val mutex = Mutex()

    override suspend fun token(): String? = mutex.withLock { read()[ACCOUNT]?.takeIf { it.isNotEmpty() } }

    override suspend fun save(token: String) = mutex.withLock {
        write(read() + (ACCOUNT to token.trim()))
    }

    override suspend fun remove() = mutex.withLock {
        write(read() - ACCOUNT)
    }

    private suspend fun read(): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else null }.getOrNull()
            ?: emptyMap()
    }

    private suspend fun write(all: Map<String, String>) = withContext(Dispatchers.IO) {
        com.rustamguloff.foliolink.core.store.SharedStore.writeAtomically(
            file, json.encodeToString(serializer, all).toByteArray(Charsets.UTF_8),
        )
    }

    private companion object {
        const val ACCOUNT = "readwise"
        val json = Json { ignoreUnknownKeys = true }
        val serializer = MapSerializer(String.serializer(), String.serializer())
    }
}

// endregion
