package com.rustamguloff.foliolink.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.UUID

// Модели FolioLink Companion API v1 (PROJECT/notes/api.md) и контракта v2.
// Имена полей совпадают с JSON прошивки; всё мягко декодируется (Lenient.kt).

@Serializable
data class DeviceInfo(
    val api: LInt = 1,
    val firmware: LString = "",
    val board: LString = "",
    /** -1 — неизвестно. */
    val battery: LInt = -1,
    val charging: LBool = false,
    /** 0, если прошивка не сообщает размер карты. */
    val sdTotalMB: LInt = 0,
    val sdFreeMB: LInt = 0,
    val clock: Clock = Clock(),
    val features: List<LString> = emptyList(),
    /** Контракт v2 §1; null на прошивке без сопряжения (там изменения открыты). */
    val pairing: Pairing? = null,
    /** Серийный номер, если прошивка его сообщает в info (по BLE — единственный способ сверить читалку). */
    val serial: String? = null,
    /** Стабильный идентификатор чипа (32 hex), отдаётся без токена; нет у прошивки 1.1.1. */
    val deviceId: String? = null,
) {
    @Serializable
    data class Clock(
        val available: LBool = false,
        val valid: LBool = false,
        val epoch: LLong = 0,
    )

    @Serializable
    data class Pairing(
        val required: LBool = true,
        /** С читалкой сопряжён хотя бы один телефон. */
        val paired: LBool = false,
        /** Токен из этого запроса действителен. */
        val authorized: LBool = false,
    )

    fun supports(feature: String): Boolean = features.isEmpty() || feature in features
}

@Serializable
data class DeviceStatus(
    val version: String? = null,
    val ip: String? = null,
    val mode: String? = null,
    val rssi: Int? = null,
    val freeHeap: Long? = null,
    val uptime: Long? = null,
    val device: String? = null,
    val serial: String? = null,
) {
    /** Серийный номер, если прошивка его знает (иначе она пишет "Not found"). */
    val knownSerial: String?
        get() = serial?.trim()?.takeIf { it.isNotEmpty() && it != "Not found" }
}

@Serializable
data class FileEntry(
    val name: LString = "",
    val size: LLong = 0,
    val isDirectory: LBool = false,
    val isEpub: LBool = false,
)

@Serializable
data class ReadingSession(
    val book: LString = "",
    val title: LString = "",
    /** UTC-секунды; 0, если часы не были установлены. */
    val start: LLong = 0,
    val secs: LInt = 0,
    val pages: LInt = 0,
    /** Процент прочитанного в конце сессии; -1 — неизвестно. */
    val pct: LInt = -1,
    /** "yyyy-MM-dd" или "" без часов. */
    val day: LString = "",
) {
    /** Устойчивый ключ для слияния инкрементальных синхронизаций без дублей. */
    val id: String get() = "$book|$start|$secs|$pages|$day"
    val hasClock: Boolean get() = start > 0 && day.isNotEmpty()
}

@Serializable
data class SessionsResponse(
    val sessions: List<ReadingSession> = emptyList(),
    /** BLE: ответ обрезан по 64 КБ, следующий запрос — с offset = nextOffset. */
    val more: LBool = false,
    val nextOffset: LInt = 0,
)

@Serializable
data class DeviceStats(
    val today: Today = Today(),
    val goalMinutes: LInt = 30,
    val streak: Streak = Streak(),
    val totals: Totals = Totals(),
    /** Только дни с активностью, по возрастанию. */
    val days: List<Day> = emptyList(),
) {
    @Serializable
    data class Today(val secs: LInt = 0, val pages: LInt = 0)

    @Serializable
    data class Streak(val current: LInt = 0, val longest: LInt = 0)

    @Serializable
    data class Totals(val secs: LInt = 0, val pages: LInt = 0, val sessions: LInt = 0, val books: LInt = 0)

    @Serializable
    data class Day(val d: LString = "", val s: LInt = 0, val p: LInt = 0)
}

enum class BookStatus(val raw: String) {
    WANT("want"), READING("reading"), DONE("done"), DROPPED("dropped");

    companion object {
        fun from(raw: String?): BookStatus? = entries.firstOrNull { it.raw == raw }
    }
}

@Serializable
data class Book(
    val path: LString = "",
    val title: LString = "",
    val author: LString = "",
    /** -1 — книга не открывалась. */
    val progress: LInt = -1,
    val lastRead: LLong = 0,
    val secs: LInt = 0,
    /** want | reading | done | dropped | "" (не задан). */
    val status: LString = "",
    val shelves: List<LString> = emptyList(),
    /** Место в «Недавних» на читалке (0 — открыта последней), -1 — нет. */
    val recent: LInt = -1,
) {
    val id: String get() = path
    val bookStatus: BookStatus? get() = BookStatus.from(status)
    val fileName: String get() = path.substringAfterLast('/')
    /**
     * Название из метаданных; нет — имя файла без расширения формата ("Book.fb2.zip" → "Book").
     * Название, которое на деле — имя `.fb2.zip` без одного `.zip` ("Book.fb2"), тоже сокращается.
     */
    val displayTitle: String
        get() = when {
            title.isEmpty() -> BookFiles.stem(fileName)
            format == BookFormat.FB2_ZIP && title.equals(fileName.removeSuffix(fileName.takeLast(4)), ignoreCase = true) ->
                BookFiles.stem(fileName)
            else -> title
        }
    val format: BookFormat? get() = BookFiles.format(fileName)
}

@Serializable
data class BooksResponse(
    val books: List<Book> = emptyList(),
    /** Контракт v2 §3: размер всей библиотеки; null на прошивке без пагинации. */
    val total: Int? = null,
    val offset: Int? = null,
    val limit: Int? = null,
    val more: LBool = false,
    val nextOffset: LInt = 0,
)

/** POST /api/inklink/pair/start → {"ok":true,"digits":6,"expiresIn":120}. */
@Serializable
data class PairStartResponse(val digits: LInt = 6, val expiresIn: LInt = 120)

@Serializable
data class Shelf(
    val id: LString = UUID.randomUUID().toString(),
    val name: LString = "",
    val books: List<LString> = emptyList(),
)

/**
 * `/.crosspoint/inklink/library.json`. POST заменяет документ целиком, поэтому неизвестные
 * ключи верхнего уровня и неизвестные ключи `goals` сохраняются как есть.
 */
@Serializable(with = LibraryDocSerializer::class)
data class LibraryDoc(
    val version: Int = 1,
    val shelves: List<Shelf> = emptyList(),
    val status: Map<String, String> = emptyMap(),
    val dailyMinutes: Int? = 30,
    val extraGoals: Map<String, JsonElement> = emptyMap(),
    val extra: Map<String, JsonElement> = emptyMap(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("version", version)
        put("shelves", FolioJson.encodeToJsonElement(shelves))
        put("status", JsonObject(status.mapValues { JsonPrimitive(it.value) }))
        val goals = extraGoals.toMutableMap()
        dailyMinutes?.let { goals["dailyMinutes"] = JsonPrimitive(it) }
        put("goals", JsonObject(goals))
        extra.forEach { (k, v) -> put(k, v) }
    }

    companion object {
        private val known = setOf("version", "shelves", "status", "goals")

        /**
         * Строгий разбор для чтения перед записью (POST заменяет документ целиком): не объект,
         * известный ключ неверного типа или полка, которая не декодируется, — [IllegalArgumentException].
         * Отсутствующие ключи допустимы (новая читалка отдаёт `{}`).
         */
        fun fromJsonStrict(e: JsonElement): LibraryDoc {
            val o = e as? JsonObject ?: throw IllegalArgumentException("library is not an object")
            o["version"]?.let { require(it is JsonPrimitive && !it.isString && it.lenientInt() != null) { "version" } }
            o["shelves"]?.let { v ->
                require(v is kotlinx.serialization.json.JsonArray) { "shelves" }
                v.forEach { shelf ->
                    require(shelf is JsonObject) { "shelf" }
                    require(shelf["id"].let { it is JsonPrimitive && it.isString }) { "shelf.id" }
                    shelf["name"]?.let { require(it is JsonPrimitive && it.isString) { "shelf.name" } }
                    shelf["books"]?.let { b ->
                        require(b is kotlinx.serialization.json.JsonArray && b.all { it is JsonPrimitive && it.isString }) { "shelf.books" }
                    }
                }
            }
            o["status"]?.let { v ->
                require(v is JsonObject && v.values.all { it is JsonPrimitive && it.isString }) { "status" }
            }
            o["goals"]?.let { require(it is JsonObject) { "goals" } }
            return fromJson(o)
        }

        fun fromJson(o: JsonObject): LibraryDoc {
            val shelves = o.array("shelves")?.mapNotNull {
                runCatching { FolioJson.decodeFromJsonElement<Shelf>(it) }.getOrNull()
            } ?: emptyList()
            val status = o.obj("status")?.mapNotNull { (k, v) -> v.lenientString()?.let { k to it } }?.toMap()
                ?: emptyMap()
            val goals = o.obj("goals")?.toMutableMap() ?: mutableMapOf()
            val daily = goals.remove("dailyMinutes").lenientInt()
            return LibraryDoc(
                version = o.int("version", 1),
                shelves = shelves,
                status = status,
                dailyMinutes = daily,
                extraGoals = goals,
                extra = o.filterKeys { it !in known },
            )
        }
    }
}

object LibraryDocSerializer : KSerializer<LibraryDoc> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun serialize(encoder: Encoder, value: LibraryDoc) {
        (encoder as JsonEncoder).encodeJsonElement(value.toJson())
    }
    override fun deserialize(decoder: Decoder): LibraryDoc {
        val e = (decoder as JsonDecoder).decodeJsonElement()
        return (e as? JsonObject)?.let { LibraryDoc.fromJson(it) } ?: LibraryDoc()
    }
}

@Serializable
data class Highlight(
    /** id на читалке; пустой у старых строк, которые нельзя править удалённо. */
    val id: LString = "",
    val book: LString = "",
    val title: LString = "",
    val text: LString = "",
    val note: LString = "",
    val chapter: LString = "",
    val pct: LInt = -1,
    val created: LLong = 0,
) {
    /** Устойчивый ключ для списков: id с читалки, иначе детерминированный локальный. */
    val key: String get() = id.ifEmpty { localId(book, created, pct, text) }

    /** Читалка может адресовать выделение (update/delete требуют id). */
    val canModify: Boolean get() = id.isNotEmpty()

    companion object {
        fun localId(book: String, created: Long, pct: Int, text: String): String =
            "local:$book|$created|$pct|${text.take(80)}"
    }
}

@Serializable
data class HighlightsResponse(
    val highlights: List<Highlight> = emptyList(),
    val more: LBool = false,
    val nextOffset: LInt = 0,
)

@Serializable
data class VocabWord(
    val word: LString = "",
    val context: LString = "",
    val book: LString = "",
    val created: LLong = 0,
    val count: LInt = 1,
) {
    val id: String get() = word
}

@Serializable
data class VocabResponse(
    val words: List<VocabWord> = emptyList(),
    val more: LBool = false,
    val nextOffset: LInt = 0,
)

@Serializable
data class RemoteFile(
    val name: LString = "",
    val path: LString = "",
    val size: LLong = 0,
    /** Название книги у скриншота из /screenshots/<название>/. */
    val book: String? = null,
) {
    val id: String get() = fullPath
    val fullPath: String get() = path.ifEmpty { "/$name" }
    val bookTitle: String? get() = book?.takeIf { it.isNotEmpty() }

    /**
     * Время снимка из имени файла: хвостовой epoch в "screenshot-<epoch>.bmp" или
     * "<title>_…_<epoch>.bmp" (секунды UTC); null, если его нет или он до синхронизации часов.
     */
    val capturedAt: Long?
        get() {
            val stem = name.substringBeforeLast('.', name)
            val digits = stem.takeLastWhile { it.isDigit() }
            val epoch = digits.toLongOrNull() ?: return null
            return epoch.takeIf { it > 1_000_000_000L }
        }
}

@Serializable
data class ScreenshotsResponse(
    val screenshots: List<RemoteFile> = emptyList(),
    val more: LBool = false,
    val nextOffset: LInt = 0,
)

@Serializable
data class SleepResponse(
    val dir: LString = "/sleep",
    val images: List<RemoteFile> = emptyList(),
    val mode: LInt = 0,
    /** Имя файла, который «Свой» показывает на каждом сне ("" — случайный). */
    val selected: LString = "",
)

@Serializable
enum class SettingType {
    @SerialName("toggle") TOGGLE,
    @SerialName("enum") ENUM,
    @SerialName("value") VALUE,
    @SerialName("string") STRING,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
data class DeviceSetting(
    val key: LString = "",
    val name: LString = "",
    val category: LString = "",
    val type: SettingType = SettingType.UNKNOWN,
    val value: JsonElement = JsonNull,
    val options: List<LString> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    val step: Int? = null,
) {
    val id: String get() = key
}

@Serializable
data class FontFamily(
    val name: LString = "",
    val sizes: List<LInt> = emptyList(),
    val files: List<File> = emptyList(),
) {
    @Serializable
    data class File(val name: LString = "", val size: LLong = 0)

    val id: String get() = name
}

@Serializable
data class FontsResponse(
    val families: List<FontFamily> = emptyList(),
    val maxFamilies: Int? = null,
)

/** Словарь StarDict на карте (`GET /api/inklink/dictionaries`, Folio 1.1). */
@Serializable
data class DictionaryInfo(
    /** Имя папки — им словарь выбирают и удаляют. */
    val name: LString = "",
    val path: LString = "",
    val stem: LString = "",
    /** `bookname` из .ifo; без .ifo — имя папки. */
    val title: LString = "",
    /** `wordcount` из .ifo; 0 — неизвестно. */
    val words: LInt = 0,
    /** `latin` | `cyrillic` | `other`. */
    val script: LString = "",
    val sizeKB: LLong = 0,
    val html: LBool = false,
    val synonyms: LBool = false,
    /** Есть `.qidx`: первый поиск на читалке будет быстрым. */
    val indexed: LBool = false,
) {
    val id: String get() = name
    val displayTitle: String get() = title.ifBlank { name }
}

@Serializable
data class DictionariesResponse(
    /** [ALL] — все словари, [OFF] — выключено, иначе имя папки. */
    val selected: LString = ALL,
    val root: LString = "/dictionaries",
    val dictionaries: List<DictionaryInfo> = emptyList(),
) {
    /** Выбор как его покажет читалка: папки, которой уже нет, — «все». */
    val effectiveSelection: String
        get() = when {
            selected == ALL || selected == OFF -> selected
            dictionaries.any { it.name == selected } -> selected
            else -> ALL
        }

    companion object {
        const val ALL = "*"
        const val OFF = "-"
    }
}

@Serializable
data class OkResponse(
    val ok: Boolean? = null,
    val accepted: Boolean? = null,
    /** firmware/apply: читалка сама показывает экран подтверждения перед прошивкой. */
    val confirmOnDevice: Boolean? = null,
    val error: String? = null,
) {
    val isOk: Boolean get() = ok ?: (error == null)
}

@Serializable
data class TimeRequest(val epoch: Long, val tzOffsetMin: Int)

/** Удобный разбор ответа целиком; кидает SerializationException на не-JSON. */
inline fun <reified T> decodeApi(text: String): T = FolioJson.decodeFromString(text)

/** Ответ-объект с "ok" без модели — для pair/finish и подобных. */
fun JsonObject.okFlag(): Boolean = this["ok"].lenientBool() ?: (this["error"] == null)

