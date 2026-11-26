package com.rustamguloff.foliolink.core.ble

import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.BooksResponse
import com.rustamguloff.foliolink.core.model.DeviceInfo
import com.rustamguloff.foliolink.core.model.DeviceStats
import com.rustamguloff.foliolink.core.model.FolioJson
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.model.HighlightsResponse
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.model.RemoteFile
import com.rustamguloff.foliolink.core.model.ScreenshotsResponse
import com.rustamguloff.foliolink.core.model.SessionsResponse
import com.rustamguloff.foliolink.core.model.VocabResponse
import com.rustamguloff.foliolink.core.model.VocabWord
import com.rustamguloff.foliolink.core.model.lenientBool
import com.rustamguloff.foliolink.core.model.lenientInt
import com.rustamguloff.foliolink.core.model.lenientString
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject

/**
 * Шлёт JSON-команды через [BleTransport] и возвращает склеенные ответы.
 *
 * Одна команда за раз: следующая запись — только после кадра LAST. Тела ответов байт-в-байт
 * равны HTTP-ответам `/api/inklink/…`, поэтому их читают те же модели, что и по Wi-Fi.
 */
class BleCommandClient(
    private val transport: BleTransport,
    private val frameTimeoutMs: Long = 10_000,
    attempts: Int = 3,
    private val busyDelayMs: Long = 250,
) {
    private val attempts = maxOf(1, attempts)
    private val mutex = Mutex()
    private val sentLog = mutableListOf<BleRequest>()

    /** Отправленные команды (для тестов и диагностики; в журнал не пишутся). */
    val sent: List<BleRequest> get() = synchronized(sentLog) { sentLog.toList() }

    // Обмен

    /**
     * Отправляет команду и возвращает payload. Повреждённый ответ (пропуск seq) отбрасывается
     * до LAST и команда повторяется; «занято» (ATT 0x80) — пауза и повтор. Всего до [attempts] попыток.
     */
    suspend fun send(request: BleRequest): ByteArray = mutex.withLock { exchange(request) }

    private suspend fun exchange(request: BleRequest): ByteArray {
        val body = request.encoded()
        if (body.size !in 1..BleIds.MAX_REQUEST_BYTES) throw BleError.CommandTooLong(body.size)
        synchronized(sentLog) { sentLog += request }
        var attempt = 0
        while (true) {
            attempt++
            currentCoroutineContext().ensureActive()
            transport.discardPendingFrames()
            try {
                transport.write(body)
            } catch (e: BleError.Gatt) {
                if (e.code != BleIds.ATT_BUSY) throw e
                if (attempt >= attempts) throw BleError.Busy(attempt)
                delay(busyDelayMs)
                continue
            }
            val answer = receive()
            if (answer != null) {
                if (answer.isError) throw remoteError(answer.payload, request.op)
                return answer.payload
            }
            if (attempt >= attempts) throw BleError.CorruptResponse(attempt)
        }
    }

    /** Кадры до LAST. null — ответ повреждён (и пропущен целиком). */
    private suspend fun receive(): BleFrameAssembler.Result.Done? {
        val assembler = BleFrameAssembler()
        var damaged = false
        while (true) {
            val frame = transport.nextFrame(frameTimeoutMs)
            if (damaged) {
                if (BleFrame.isLast(frame)) return null
                continue
            }
            when (val r = assembler.push(frame)) {
                BleFrameAssembler.Result.More -> continue
                is BleFrameAssembler.Result.Done -> return r
                is BleFrameAssembler.Result.Bad -> {
                    if (BleFrame.isLast(frame)) return null
                    damaged = true
                }
            }
        }
    }

    companion object {
        /** Тело ошибки: `{"ok":false,"error":"…","status":N[,"attemptsLeft":N]}`. */
        internal fun remoteError(payload: ByteArray, op: String): BleError {
            val text = payload.toString(Charsets.UTF_8)
            val obj = runCatching { FolioJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            val status = obj?.get("status").lenientInt() ?: 400
            val message = obj?.get("error").lenientString() ?: text.trim()
            val attemptsLeft = obj?.get("attemptsLeft").lenientInt()
            if (op == "auth" && status == 401) return BleError.Remote(401, message, attemptsLeft)
            if (status == 401) return BleError.PairingRequired
            return BleError.Remote(status, message.take(200), attemptsLeft)
        }
    }

    private fun parse(payload: ByteArray, what: String): JsonObject =
        runCatching { FolioJson.parseToJsonElement(payload.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: throw BleDecodingException(what)

    private fun <T> decode(obj: JsonObject, strategy: DeserializationStrategy<T>, what: String): T =
        try {
            FolioJson.decodeFromJsonElement(strategy, obj)
        } catch (e: Exception) {
            throw BleDecodingException(what)
        }

    private suspend fun <T> request(request: BleRequest, strategy: DeserializationStrategy<T>): T =
        decode(parse(send(request), request.op), strategy, request.op)

    // Команды

    /** `hello{cnonce}` → одноразовый nonce читалки (32 hex) для [authorize]. */
    suspend fun hello(cnonce: String? = null): String {
        val obj = parse(send(BleRequest(op = "hello", cnonce = cnonce)), "hello")
        return obj["nonce"].lenientString() ?: throw BleError.Remote(401, "no nonce in hello")
    }

    /** Ответ на `auth`: версия API и, у новой прошивки, доказательство читалки. */
    data class AuthReply(val api: Int, val sproof: String?)

    /** `auth{proof}`. Отказ — [BleError.Remote] со status 401. */
    suspend fun authorizeReply(proof: String): AuthReply {
        val obj = parse(send(BleRequest(op = "auth", proof = proof)), "auth")
        if (obj["ok"].lenientBool() != true) throw BleError.Remote(401, "auth refused")
        return AuthReply(obj["api"].lenientInt() ?: 1, obj["sproof"].lenientString()?.takeIf { it.isNotEmpty() })
    }

    /** `auth{proof}`; возвращает версию API читалки. */
    suspend fun authorize(proof: String): Int = authorizeReply(proof).api

    /** Первый шаг каждого соединения: `hello` → nonce, затем `auth{proof}` (см. [BleAuth]). */
    suspend fun auth(token: String): Int {
        if (!BleAuth.isValidToken(token)) throw BleError.NoToken
        val nonce = hello()
        val proof = BleAuth.proof(token, nonce) ?: throw BleError.Remote(401, "invalid nonce")
        return authorize(proof)
    }

    suspend fun info(): DeviceInfo = request(BleRequest(op = "info"), DeviceInfo.serializer())

    suspend fun stats(): DeviceStats = request(BleRequest(op = "stats"), DeviceStats.serializer())

    /** `time{epoch,tzOffsetMin}`; `{"ok":false}` без флага ERROR — тоже ошибка. */
    suspend fun setTime(epoch: Long, tzOffsetMin: Int) {
        val payload = send(BleRequest(op = "time", epoch = epoch, tzOffsetMin = tzOffsetMin))
        val obj = runCatching { FolioJson.parseToJsonElement(payload.toString(Charsets.UTF_8)) as? JsonObject }
            .getOrNull() ?: return
        if (obj["ok"].lenientBool() == false) throw BleError.Remote(200, obj["error"].lenientString() ?: "")
    }

    suspend fun sessions(since: Long): List<ReadingSession> =
        paged(BleRequest(op = "sessions", since = since.takeIf { it > 0 }), SessionsResponse.serializer()) { it.sessions }

    suspend fun highlights(since: Long = 0, book: String? = null): List<Highlight> =
        paged(BleRequest(op = "highlights", since = since.takeIf { it > 0 }, book = book),
            HighlightsResponse.serializer()) { it.highlights }

    suspend fun vocab(): List<VocabWord> =
        paged(BleRequest(op = "vocab"), VocabResponse.serializer()) { it.words }

    /** Только список: сами файлы скриншотов скачиваются по Wi-Fi. */
    suspend fun screenshots(): List<RemoteFile> =
        paged(BleRequest(op = "screenshots"), ScreenshotsResponse.serializer()) { it.screenshots }

    /**
     * Страницы по [pageSize] до `total` (контракт v2 §3); страница, обрезанная по 64 КБ,
     * продолжается с `nextOffset`. Пустая или повторная страница завершает обход.
     */
    suspend fun books(pageSize: Int = 100): List<Book> {
        val all = mutableListOf<Book>()
        val seen = HashSet<String>()
        var offset = 0
        repeat(1000) {
            val obj = parse(send(BleRequest(op = "books", offset = offset, limit = pageSize)), "books")
            val page = decode(obj, BooksResponse.serializer(), "books")
            val more = obj["more"].lenientBool() == true
            val fresh = page.books.filter { it.path !in seen }
            if (fresh.isEmpty()) return all
            all += fresh
            fresh.mapTo(seen) { it.path }
            val next = if (more) obj["nextOffset"].lenientInt() ?: (offset + page.books.size)
            else offset + page.books.size
            if (!more) {
                val total = page.total ?: return all
                if (all.size >= total) return all
            }
            if (next <= offset) return all
            offset = next
        }
        return all
    }

    /** Повторяет команду с `"offset":nextOffset`, пока ответ говорит `"more":true` и offset растёт. */
    private suspend fun <R, T> paged(
        base: BleRequest,
        strategy: DeserializationStrategy<R>,
        items: (R) -> List<T>,
    ): List<T> {
        val all = mutableListOf<T>()
        var offset = 0
        repeat(1000) {
            val request = base.copy(offset = offset.takeIf { it > 0 })
            val obj = parse(send(request), base.op)
            all += items(decode(obj, strategy, base.op))
            val more = obj["more"].lenientBool() == true
            val next = obj["nextOffset"].lenientInt()
            if (!more || next == null || next <= offset) return all
            offset = next
        }
        return all
    }
}
