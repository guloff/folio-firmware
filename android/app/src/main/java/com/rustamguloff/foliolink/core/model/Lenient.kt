package com.rustamguloff.foliolink.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Прошивка пишется параллельно с приложением, поэтому разбор мягкий: отсутствующие поля
// получают нейтральные значения по умолчанию, числа принимаются строкой/дробью/булевым,
// булевы — числом. Так же устроены декодеры iOS-версии (KeyedDecodingContainer.lenient*).

/** Общий JSON для API, кэша и очереди. */
val FolioJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

fun JsonElement?.lenientLong(): Long? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> longOrNull
        ?: doubleOrNull?.toLong()
        ?: booleanOrNull?.let { if (it) 1L else 0L }
        ?: content.trim().toLongOrNull()
    else -> null
}

fun JsonElement?.lenientInt(): Int? = lenientLong()?.let {
    it.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}

fun JsonElement?.lenientBool(): Boolean? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> booleanOrNull ?: longOrNull?.let { it != 0L } ?: doubleOrNull?.let { it != 0.0 }
    else -> null
}

/** Строка из примитива (число превращается в текст); объект/массив/null — null. */
fun JsonElement?.lenientString(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}

fun JsonObject.int(key: String, fallback: Int = 0): Int = this[key].lenientInt() ?: fallback
fun JsonObject.long(key: String, fallback: Long = 0): Long = this[key].lenientLong() ?: fallback
fun JsonObject.bool(key: String, fallback: Boolean = false): Boolean = this[key].lenientBool() ?: fallback
fun JsonObject.string(key: String, fallback: String = ""): String = this[key].lenientString() ?: fallback
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** Int, который принимает 12, 12.0, "12", true. */
object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    override fun deserialize(decoder: Decoder): Int {
        val json = decoder as? JsonDecoder ?: return decoder.decodeInt()
        return json.decodeJsonElement().lenientInt() ?: 0
    }
}

object LenientLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
    override fun deserialize(decoder: Decoder): Long {
        val json = decoder as? JsonDecoder ?: return decoder.decodeLong()
        return json.decodeJsonElement().lenientLong() ?: 0L
    }
}

/** Boolean, который принимает true/false и 0/1. */
object LenientBoolSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientBool", PrimitiveKind.BOOLEAN)
    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
    override fun deserialize(decoder: Decoder): Boolean {
        val json = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        return json.decodeJsonElement().lenientBool() ?: false
    }
}

/** String, который принимает и число (прошивка иногда шлёт id числом). */
object LenientStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return json.decodeJsonElement().lenientString() ?: ""
    }
}

typealias LInt = @Serializable(with = LenientIntSerializer::class) Int
typealias LLong = @Serializable(with = LenientLongSerializer::class) Long
typealias LBool = @Serializable(with = LenientBoolSerializer::class) Boolean
typealias LString = @Serializable(with = LenientStringSerializer::class) String
