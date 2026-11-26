package com.rustamguloff.foliolink.core.ble

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Отвечает на каждый записанный запрос кадрами из [respond] и запоминает записанное. */
class FakeBleTransport(
    writeErrors: List<Int> = emptyList(),
    private val respond: (request: ByteArray, index: Int) -> List<ByteArray>,
) : BleTransport {
    private val queue = ArrayDeque<ByteArray>()
    private val errors = ArrayDeque(writeErrors)
    val written = mutableListOf<ByteArray>()

    override suspend fun write(bytes: ByteArray) {
        errors.removeFirstOrNull()?.let { throw BleError.Gatt(it) }
        written += bytes
        queue.addAll(respond(bytes, written.size - 1))
    }

    override suspend fun nextFrame(timeoutMs: Long): ByteArray = queue.removeFirstOrNull() ?: throw BleError.Timeout

    override suspend fun discardPendingFrames() = queue.clear()

    /** Записанные запросы как канонический JSON. */
    val writtenJson: List<String> get() = written.map { BleFixture.canonical(it.toString(Charsets.UTF_8)) }

    val writtenOps: List<String>
        get() = written.map { Json.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject["op"]!!.jsonPrimitive.content }
}

/** Запрос как JSON-объект (для ответчиков фейкового транспорта). */
fun ByteArray.requestJson(): JsonObject = Json.parseToJsonElement(toString(Charsets.UTF_8)).jsonObject

fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.content?.toIntOrNull()

fun frames(body: String, error: Boolean = false, mtu: Int = 185): List<ByteArray> =
    BleFrame.encode(body.toByteArray(Charsets.UTF_8), error, mtu)

fun hex(s: String): ByteArray = requireNotNull(BleAuth.hexToBytes(s))

fun isValidUtf8(bytes: ByteArray): Boolean = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
    true
} catch (e: CharacterCodingException) {
    false
}

class UnexpectedRequest(what: String) : AssertionError("unexpected request: $what")

/**
 * test/resources/ble-golden-sync.json: настоящие кадры loopback симулятора Folio
 * (Tools/ble_fixture.py), по каноническому JSON запроса.
 */
class BleFixture(
    val token: String,
    val now: Long,
    val tzOffsetMin: Int,
    val mtu: Int,
    val incrementalSince: Long,
    val full: List<Exchange>,
    val incremental: List<Exchange>,
) {
    class Exchange(val request: String, val frames: List<ByteArray>)

    companion object {
        fun load(): BleFixture {
            val text = requireNotNull(BleFixture::class.java.classLoader!!.getResource("ble-golden-sync.json")).readText()
            val root = Json.parseToJsonElement(text).jsonObject
            fun exchanges(key: String) = root[key]!!.jsonArray.map { item ->
                val o = item.jsonObject
                Exchange(
                    request = canonical(o["request"]!!),
                    frames = o["frames"]!!.jsonArray.map { hex(it.jsonPrimitive.content) },
                )
            }
            return BleFixture(
                token = root["token"]!!.jsonPrimitive.content,
                now = root["now"]!!.jsonPrimitive.long,
                tzOffsetMin = root["tzOffsetMin"]!!.jsonPrimitive.int,
                mtu = root["mtu"]!!.jsonPrimitive.int,
                incrementalSince = root["incrementalSince"]!!.jsonPrimitive.long,
                full = exchanges("full"),
                incremental = exchanges("incremental"),
            )
        }

        fun canonical(json: String): String = canonical(Json.parseToJsonElement(json))

        /** Ключи объектов по алфавиту, без пробелов. */
        fun canonical(e: JsonElement): String = sorted(e).toString()

        private fun sorted(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { it.key to sorted(it.value) })
            is JsonArray -> JsonArray(e.map { sorted(it) })
            else -> e
        }

        /**
         * Транспорт, проигрывающий обмены; неизвестный запрос — провал теста. Эталон записан на
         * прошивке 1.1.1, которая поле `cnonce` в `hello` не знает и игнорирует (ответа `sproof`
         * в нём нет): при поиске обмена оно не учитывается.
         */
        fun transport(exchanges: List<Exchange>): FakeBleTransport {
            val table = exchanges.associate { it.request to it.frames }
            return FakeBleTransport { request, _ ->
                val key = withoutClientNonce(canonical(request.toString(Charsets.UTF_8)))
                table[key] ?: throw UnexpectedRequest(key)
            }
        }

        /** Запрос без `cnonce` (как его видит прошивка 1.1.1). */
        fun withoutClientNonce(json: String): String {
            val o = Json.parseToJsonElement(json).jsonObject
            return if ("cnonce" in o) canonical(JsonObject(o - "cnonce")) else json
        }

        /** Склеенный payload одного обмена. */
        fun payload(frames: List<ByteArray>): ByteArray {
            val a = BleFrameAssembler()
            for (f in frames) {
                val r = a.push(f)
                if (r is BleFrameAssembler.Result.Done) return r.payload
            }
            return ByteArray(0)
        }
    }
}

object BleTestAuth {
    const val HELLO = """{"ok":true,"nonce":"000102030405060708090a0b0c0d0e0f"}"""
}
