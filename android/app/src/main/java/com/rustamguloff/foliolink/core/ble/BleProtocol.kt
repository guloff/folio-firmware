package com.rustamguloff.foliolink.core.ble

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Канал синхронизации по Bluetooth LE (PROJECT/notes/api.md, раздел «BLE»).
// Всё в этом файле не зависит от android.bluetooth и покрывается JVM-тестами.

/** Раскладка GATT сервиса синхронизации Folio. UUID — часть протокола с прошивкой, не менять. */
object BleIds {
    val SERVICE: UUID = UUID.fromString("F0110001-7B3A-4C8E-9F1D-6A2B5C4D3E01")
    /** Write with response: одна JSON-команда, 1…512 байт UTF-8. */
    val REQUEST: UUID = UUID.fromString("F0110002-7B3A-4C8E-9F1D-6A2B5C4D3E01")
    /** Notify: кадры ответа. Подписаться до первой записи. */
    val RESPONSE: UUID = UUID.fromString("F0110003-7B3A-4C8E-9F1D-6A2B5C4D3E01")
    /** Client Characteristic Configuration Descriptor (включение уведомлений). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val ADVERTISED_NAME = "Folio"
    const val MAX_REQUEST_BYTES = 512
    /** Читалка предлагает 517. */
    const val PREFERRED_MTU = 517

    /** ATT-ошибки записи в request: предыдущий ответ ещё отправляется. */
    const val ATT_BUSY = 0x80
    /** Не включены уведомления на response. */
    const val ATT_NOT_SUBSCRIBED = 0x81
    /** Длина 0 или > 512. */
    const val ATT_BAD_LENGTH = 0x0D
}

/** Кадр ответа: `[seq u16 LE][flags][payload]`, кадр = MTU − 3 байт. */
object BleFrame {
    const val HEADER_SIZE = 3
    const val FLAG_LAST = 0x01
    const val FLAG_ERROR = 0x02
    /** Читалка не отвечает больше чем 64 КБ (длинные списки продолжаются через nextOffset). */
    const val MAX_RESPONSE_BYTES = 64 * 1024

    fun seq(frame: ByteArray): Int? =
        if (frame.size < HEADER_SIZE) null else (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8)

    fun flags(frame: ByteArray): Int? = if (frame.size < HEADER_SIZE) null else frame[2].toInt() and 0xFF

    fun isLast(frame: ByteArray): Boolean = ((flags(frame) ?: 0) and FLAG_LAST) != 0

    /** Режет payload так же, как читалка (для тестов и фейкового транспорта). */
    fun encode(payload: ByteArray, isError: Boolean, mtu: Int): List<ByteArray> {
        val chunk = maxOf(1, mtu - 3 - HEADER_SIZE)
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        var seq = 0
        do {
            val end = minOf(offset + chunk, payload.size)
            var flags = if (end == payload.size) FLAG_LAST else 0
            if (isError) flags = flags or FLAG_ERROR
            val frame = ByteArray(HEADER_SIZE + end - offset)
            frame[0] = (seq and 0xFF).toByte()
            frame[1] = ((seq shr 8) and 0xFF).toByte()
            frame[2] = flags.toByte()
            payload.copyInto(frame, HEADER_SIZE, offset, end)
            frames += frame
            offset = end
            seq = (seq + 1) and 0xFFFF
        } while (offset < payload.size)
        return frames
    }
}

/**
 * Склеивает один ответ из кадров-уведомлений.
 *
 * Кадры должны идти с seq 0, 1, 2… Пропуск, повтор, обрывок (< 3 байт) или флаг ERROR,
 * сменившийся посреди ответа, делают ответ [Result.Bad]: вызывающий его отбрасывает, ждёт
 * кадра LAST и повторяет запрос. Payload декодируется только после LAST, поэтому символ
 * UTF-8, разрезанный между кадрами, не страшен.
 */
class BleFrameAssembler {
    sealed interface Result {
        data object More : Result

        class Done(val payload: ByteArray, val isError: Boolean) : Result {
            override fun equals(other: Any?): Boolean =
                other is Done && other.isError == isError && other.payload.contentEquals(payload)
            override fun hashCode(): Int = payload.contentHashCode() * 31 + isError.hashCode()
            override fun toString(): String = "Done(${payload.size} B, isError=$isError)"
        }

        data class Bad(val problem: Problem) : Result
    }

    sealed interface Problem {
        data object Runt : Problem
        data class SeqGap(val expected: Int, val got: Int) : Problem
        data object FlagMismatch : Problem
        data object TooLarge : Problem
    }

    private var expected = 0
    private var buffer = java.io.ByteArrayOutputStream()
    private var errorFlag: Boolean? = null

    /** Ни одного кадра текущего ответа ещё не принято. */
    val isIdle: Boolean get() = expected == 0

    fun reset() {
        expected = 0
        buffer = java.io.ByteArrayOutputStream()
        errorFlag = null
    }

    fun push(frame: ByteArray): Result {
        val seq = BleFrame.seq(frame)
        val flags = BleFrame.flags(frame)
        if (seq == null || flags == null) {
            reset()
            return Result.Bad(Problem.Runt)
        }
        if (seq != expected) {
            val want = expected
            reset()
            return Result.Bad(Problem.SeqGap(want, seq))
        }
        val isError = (flags and BleFrame.FLAG_ERROR) != 0
        val known = errorFlag
        if (known != null && known != isError) {
            reset()
            return Result.Bad(Problem.FlagMismatch)
        }
        errorFlag = isError
        buffer.write(frame, BleFrame.HEADER_SIZE, frame.size - BleFrame.HEADER_SIZE)
        if (buffer.size() > BleFrame.MAX_RESPONSE_BYTES) {
            reset()
            return Result.Bad(Problem.TooLarge)
        }
        if ((flags and BleFrame.FLAG_LAST) != 0) {
            val payload = buffer.toByteArray()
            reset()
            return Result.Done(payload, isError)
        }
        expected = (expected + 1) and 0xFFFF
        return Result.More
    }
}

/** Одна JSON-команда `{"op":"…", …}`; отсутствующие поля не отправляются. */
data class BleRequest(
    val op: String,
    /** Только `auth`: HMAC-доказательство токена (см. [BleAuth]). */
    val proof: String? = null,
    val since: Long? = null,
    val offset: Int? = null,
    val limit: Int? = null,
    val book: String? = null,
    val epoch: Long? = null,
    val tzOffsetMin: Int? = null,
    /** Только `hello`: nonce телефона (32 hex) — читалка отвечает на него `sproof` в `auth` (SEC-034). */
    val cnonce: String? = null,
) {
    /** JSON с ключами по алфавиту, слэши не экранируются (как JSONEncoder в iOS). */
    fun toJson(): String {
        val fields = sortedMapOf<String, JsonPrimitive>()
        fields["op"] = JsonPrimitive(op)
        cnonce?.let { fields["cnonce"] = JsonPrimitive(it) }
        proof?.let { fields["proof"] = JsonPrimitive(it) }
        since?.let { fields["since"] = JsonPrimitive(it) }
        offset?.let { fields["offset"] = JsonPrimitive(it) }
        limit?.let { fields["limit"] = JsonPrimitive(it) }
        book?.let { fields["book"] = JsonPrimitive(it) }
        epoch?.let { fields["epoch"] = JsonPrimitive(it) }
        tzOffsetMin?.let { fields["tzOffsetMin"] = JsonPrimitive(it) }
        return JsonObject(fields).toString()
    }

    fun encoded(): ByteArray = toJson().toByteArray(Charsets.UTF_8)
}

/** Байтовый канал к читалке: GATT на телефоне, фейк в тестах. */
interface BleTransport {
    /** Пишет один запрос с ответом; ошибка ATT — [BleError.Gatt] с кодом (0x80 — занято). */
    suspend fun write(bytes: ByteArray)

    /** Следующий кадр-уведомление; [BleError.Timeout], если за [timeoutMs] кадра не было. */
    suspend fun nextFrame(timeoutMs: Long): ByteArray

    /** Выбрасывает кадры, оставшиеся от прежнего (брошенного) ответа. */
    suspend fun discardPendingFrames()
}

/**
 * Challenge-response BLE (SEC-003): токен сопряжения в эфир не уходит.
 * proof = HMAC-SHA256(ключ = SHA-256(байты токена), "FOLIO-BLE-v1\n" + байты nonce), hex строчными.
 * Читалка хранит ровно SHA-256(токена) и проверяет proof без самого токена.
 */
object BleAuth {
    private val LABEL = "FOLIO-BLE-v1\n".toByteArray(Charsets.UTF_8)
    private val SERVER_LABEL = "FOLIO-BLE-SRV-v1\n".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    /** Nonce телефона для `hello`: 16 случайных байт, 32 hex строчными. */
    fun newClientNonce(): String = ByteArray(16).also(random::nextBytes).let(::bytesToHex)

    /**
     * Ответ читалки (взаимная аутентификация): sproof = HMAC-SHA256(ключ = SHA-256(токена),
     * "FOLIO-BLE-SRV-v1\n" + cnonce (16 байт) + nonce (16 байт)), hex строчными. Ключ тот же, что у [proof].
     */
    fun serverProof(tokenHex: String, cnonceHex: String, nonceHex: String): String? {
        val token = hexToBytes(tokenHex)?.takeIf { it.size == 32 } ?: return null
        val cnonce = hexToBytes(cnonceHex)?.takeIf { it.size == 16 } ?: return null
        val nonce = hexToBytes(nonceHex)?.takeIf { it.size == 16 } ?: return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(token), "HmacSHA256"))
        mac.update(SERVER_LABEL)
        mac.update(cnonce)
        return bytesToHex(mac.doFinal(nonce))
    }

    /** Сверка `sproof` в постоянное время; неверный формат — false. */
    fun verifyServerProof(tokenHex: String, cnonceHex: String, nonceHex: String, sproof: String): Boolean {
        val expected = serverProof(tokenHex, cnonceHex, nonceHex)?.let(::hexToBytes) ?: return false
        val got = hexToBytes(sproof) ?: return false
        return MessageDigest.isEqual(expected, got)
    }

    /** null — токен не 32 байта hex или nonce не 16 байт hex. */
    fun proof(tokenHex: String, nonceHex: String): String? {
        val token = hexToBytes(tokenHex)?.takeIf { it.size == 32 } ?: return null
        val nonce = hexToBytes(nonceHex)?.takeIf { it.size == 16 } ?: return null
        val key = MessageDigest.getInstance("SHA-256").digest(token)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(LABEL)
        return bytesToHex(mac.doFinal(nonce))
    }

    /** Токен годится для [proof]: 64 hex-символа. */
    fun isValidToken(tokenHex: String): Boolean = hexToBytes(tokenHex)?.size == 32

    internal fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    internal fun bytesToHex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(digits[v shr 4]).append(digits[v and 0x0F])
        }
        return sb.toString()
    }
}

/** Ответ читалки не разобрался как ожидаемый JSON (шаг синхронизации записывается в failures). */
class BleDecodingException(val what: String) : Exception("invalid BLE response: $what")

/**
 * Ошибка канала (таймаут, разрыв, повреждённые кадры, GATT, слишком длинная команда, нет токена):
 * такая ошибка прерывает синхронизацию целиком. Ответ-ошибка одного шага ([BleError.Remote],
 * [BleError.PairingRequired]) — нет.
 */
val BleError.isLinkFailure: Boolean
    get() = when (this) {
        BleError.Timeout, BleError.Disconnected, BleError.NoToken -> true
        is BleError.Busy, is BleError.CorruptResponse, is BleError.Gatt, is BleError.CommandTooLong -> true
        is BleError.Remote, BleError.PairingRequired -> false
    }

/** 404 «нет такого маршрута» от старой прошивки: шаг пропускается молча, как по Wi-Fi. */
val BleError.Remote.isNotSupported: Boolean
    get() = status == 404 && (error.isEmpty() || error.startsWith("404 Not Found"))
