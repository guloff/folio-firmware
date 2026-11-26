package com.rustamguloff.foliolink.core.model

import kotlinx.serialization.KSerializer
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
import kotlinx.serialization.json.put
import java.net.URI
import java.util.UUID

/** Адрес читалки. */
@Serializable
data class DeviceEndpoint(
    val host: String,
    val port: Int = 80,
    val name: String = host,
) {
    val id: String get() = "$host:$port"

    /** http://host[:port] без завершающего слэша; IPv6 — в скобках. */
    val baseUrl: String
        get() {
            val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            return if (port == 80) "http://$h" else "http://$h:$port"
        }

    companion object {
        /** Разбирает "host", "host:port", "http://host:port/". */
        fun parse(raw: String): DeviceEndpoint? {
            var s = raw.trim()
            if (s.isEmpty()) return null
            if (!s.contains("://")) s = "http://$s"
            val uri = runCatching { URI(s) }.getOrNull() ?: return null
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")
            if (host.isNullOrEmpty()) return null
            return DeviceEndpoint(host = host, port = if (uri.port > 0) uri.port else 80)
        }
    }
}

/** Всё, что синхронизировано с читалки и хранится для работы без связи. Время — epoch-миллисекунды. */
@Serializable
data class SyncSnapshot(
    val device: DeviceEndpoint? = null,
    val info: DeviceInfo? = null,
    val status: DeviceStatus? = null,
    val lastSync: Long? = null,
    /** Самый поздний `start` среди сессий (курсор `since`). */
    val lastSessionStart: Long = 0,
    val stats: DeviceStats? = null,
    val books: List<Book> = emptyList(),
    val library: LibraryDoc? = null,
    val highlights: List<Highlight> = emptyList(),
    val vocab: List<VocabWord> = emptyList(),
    val screenshots: List<RemoteFile> = emptyList(),
    val sleep: SleepResponse? = null,
    /** Когда `stats` последний раз пришли успешно (свежесть цифр «сегодня»). */
    val statsFetchedAt: Long? = null,
    /** Последняя полная выгрузка сессий (since=0). */
    val lastFullSessionSync: Long? = null,
    /** Последний полный список цитат по BLE (без since). */
    val lastFullHighlightSync: Long? = null,
    /** Чья это читалка: серийный номер, иначе host:port. */
    val deviceKey: String? = null,
    /** Последний GET /api/settings — экран настроек работает (и копит правки) без связи. */
    val deviceSettings: List<DeviceSetting>? = null,
    /** Последний GET /api/inklink/dictionaries (Folio 1.1). */
    val dictionaries: DictionariesResponse? = null,
)

/** Готовые данные для виджетов, пишутся после каждой синхронизации. */
@Serializable
data class WidgetSummary(
    val updated: Long = System.currentTimeMillis(),
    val todayMinutes: Int = 0,
    val goalMinutes: Int = 30,
    val streak: Int = 0,
    val longestStreak: Int = 0,
    val currentBookTitle: String? = null,
    val currentBookAuthor: String? = null,
    val currentBookProgress: Int? = null,
    /** Минуты по дням за последние 70 дней, от старых к новым; последний — сегодня. */
    val recentDays: List<Int> = emptyList(),
    val deviceName: String? = null,
) {
    companion object {
        val placeholder = WidgetSummary(
            todayMinutes = 18, goalMinutes = 30, streak = 6, longestStreak = 21,
            currentBookTitle = "Мастер и Маргарита", currentBookAuthor = "М. Булгаков", currentBookProgress = 42,
            recentDays = List(70) { i -> listOf(0, 12, 25, 40, 8, 0, 55)[i % 7] },
        )
    }
}

/**
 * Изменение для читалки в постоянной очереди отправки. Пишет приложение (все изменения идут
 * через очередь) и «Поделиться» (книги); при подключении очередь отправляется по порядку.
 */
@Serializable(with = OutboxItemSerializer::class)
data class OutboxItem(
    val id: String = UUID.randomUUID().toString(),
    /** Имя файла полезной нагрузки в папке элемента ("" у операций без файла). */
    val fileName: String,
    val title: String,
    /** Подробность для человека (URL статьи, путь, ключи настроек…). */
    val source: String,
    val added: Long = System.currentTimeMillis(),
    val operation: OutboxOperation = OutboxOperation.UploadBook,
    /** Последняя ошибка отправки (для экрана очереди). */
    val lastError: String? = null,
    /** Читалка отклонила элемент: автоматическая отправка пропускает его до «Повторить». */
    val failed: Boolean = false,
    val attempts: Int = 0,
    /**
     * Для какой читалки сделано изменение (`SyncSnapshot.deviceKey`); null — для любой (книги из
     * «Поделиться»). Привязанное изменение никогда не уходит на другую читалку: id выделений,
     * пути, полки и настройки имеют смысл только там, откуда пришли.
     */
    val deviceKey: String? = null,
) {
    fun belongsTo(key: String?): Boolean = deviceKey == null || deviceKey == key

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("fileName", fileName)
        put("title", title)
        put("source", source)
        put("added", added)
        put("operation", operation.toJson())
        lastError?.let { put("lastError", it) }
        put("failed", failed)
        put("attempts", attempts)
        deviceKey?.let { put("deviceKey", it) }
    }

    companion object {
        fun fromJson(o: JsonObject): OutboxItem? {
            val id = o["id"].lenientString()?.takeIf { it.isNotEmpty() } ?: return null
            // Нет operation — старая загрузка книги; есть, но непонятная (записана более новой
            // версией) — хранится дословно как Unsupported: не исполняется и не теряется.
            val opElement = o["operation"]
            val operation = if (opElement == null || opElement == JsonNull) {
                OutboxOperation.UploadBook
            } else {
                OutboxOperation.fromJson(opElement)
            }
            return OutboxItem(
                id = id,
                fileName = o.string("fileName"),
                title = o.string("title"),
                source = o.string("source"),
                added = o["added"].lenientLong() ?: System.currentTimeMillis(),
                operation = operation,
                lastError = o["lastError"].lenientString(),
                failed = o.bool("failed"),
                attempts = o.int("attempts"),
                deviceKey = o["deviceKey"].lenientString(),
            )
        }
    }
}

object OutboxItemSerializer : KSerializer<OutboxItem> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun serialize(encoder: Encoder, value: OutboxItem) {
        (encoder as JsonEncoder).encodeJsonElement(value.toJson())
    }
    override fun deserialize(decoder: Decoder): OutboxItem {
        val o = (decoder as JsonDecoder).decodeJsonElement() as? JsonObject
        return o?.let { OutboxItem.fromJson(it) } ?: error("bad outbox item")
    }
}

/** Что элемент очереди делает на читалке. JSON: {"type":"…", поля…}. */
sealed interface OutboxOperation {
    /** Файл → /Books (папка ищется без учёта регистра; существующий файл — дубликат). */
    data object UploadBook : OutboxOperation

    /** Файл → `dir` (создаётся при отсутствии); `replace` сначала удаляет одноимённый файл. */
    data class UploadFile(
        val dir: String,
        val mimeType: String,
        val replace: Boolean,
        val purpose: UploadPurpose,
    ) : OutboxOperation

    data class DeleteFile(val path: String) : OutboxOperation
    data class DeleteFontFamily(val family: String) : OutboxOperation

    /** Read-modify-write library.json. */
    data class Library(val change: LibraryChange) : OutboxOperation
    data class HighlightNote(val id: String, val note: String) : OutboxOperation
    data class DeleteHighlight(val id: String) : OutboxOperation
    data class DeleteVocab(val word: String) : OutboxOperation

    /** Частичный POST /api/settings. */
    data class Settings(val values: Map<String, JsonElement>) : OutboxOperation

    /** POST /api/inklink/sleep/select ("" — снова случайная). 404 на старой прошивке — без эффекта. */
    data class SelectSleepImage(val name: String) : OutboxOperation

    /** POST /api/inklink/dictionaries/select: `*`, `-` или имя папки. */
    data class SelectDictionary(val name: String) : OutboxOperation

    /** POST /api/inklink/dictionaries/delete: папка словаря целиком. Уже нет (404) — сделано. */
    data class DeleteDictionary(val name: String) : OutboxOperation

    /** Операция, которую эта сборка не знает; JSON хранится дословно и никогда не отправляется. */
    data class Unsupported(val raw: JsonElement) : OutboxOperation

    enum class UploadPurpose(val raw: String) {
        BOOK("book"), SLEEP("sleep"), FONT("font"), DICTIONARY("dictionary"), OTHER("other");

        companion object {
            fun from(raw: String?) = entries.firstOrNull { it.raw == raw } ?: OTHER
        }
    }

    /** Есть ли у элемента файл. У Unsupported — неизвестно, отсутствие файла его не прячет. */
    val hasPayload: Boolean get() = this is UploadBook || this is UploadFile

    val isSupported: Boolean get() = this !is Unsupported

    fun toJson(): JsonElement = when (this) {
        UploadBook -> buildJsonObject { put("type", "uploadBook") }
        is UploadFile -> buildJsonObject {
            put("type", "uploadFile"); put("dir", dir); put("mimeType", mimeType)
            put("replace", replace); put("purpose", purpose.raw)
        }
        is DeleteFile -> buildJsonObject { put("type", "deleteFile"); put("path", path) }
        is DeleteFontFamily -> buildJsonObject { put("type", "deleteFontFamily"); put("family", family) }
        is Library -> buildJsonObject { put("type", "library"); put("change", change.toJson()) }
        is HighlightNote -> buildJsonObject { put("type", "highlightNote"); put("id", id); put("note", note) }
        is DeleteHighlight -> buildJsonObject { put("type", "deleteHighlight"); put("id", id) }
        is DeleteVocab -> buildJsonObject { put("type", "deleteVocab"); put("word", word) }
        is Settings -> buildJsonObject { put("type", "settings"); put("values", JsonObject(values)) }
        is SelectSleepImage -> buildJsonObject { put("type", "selectSleepImage"); put("name", name) }
        is SelectDictionary -> buildJsonObject { put("type", "selectDictionary"); put("name", name) }
        is DeleteDictionary -> buildJsonObject { put("type", "deleteDictionary"); put("name", name) }
        is Unsupported -> raw
    }

    companion object {
        fun fromJson(e: JsonElement): OutboxOperation {
            val o = e as? JsonObject ?: return Unsupported(e)
            val op: OutboxOperation? = when (o["type"].lenientString()) {
                "uploadBook" -> UploadBook
                "uploadFile" -> o["dir"].lenientString()?.let { dir ->
                    UploadFile(dir, o.string("mimeType", "application/octet-stream"), o.bool("replace"),
                        UploadPurpose.from(o["purpose"].lenientString()))
                }
                "deleteFile" -> o["path"].lenientString()?.let { DeleteFile(it) }
                "deleteFontFamily" -> o["family"].lenientString()?.let { DeleteFontFamily(it) }
                "library" -> o.obj("change")?.let { LibraryChange.fromJson(it) }?.let { Library(it) }
                "highlightNote" -> o["id"].lenientString()?.let { HighlightNote(it, o.string("note")) }
                "deleteHighlight" -> o["id"].lenientString()?.let { DeleteHighlight(it) }
                "deleteVocab" -> o["word"].lenientString()?.let { DeleteVocab(it) }
                "settings" -> o.obj("values")?.let { Settings(it) }
                "selectSleepImage" -> SelectSleepImage(o.string("name"))
                "selectDictionary" -> o["name"].lenientString()?.takeIf { it.isNotEmpty() }?.let { SelectDictionary(it) }
                "deleteDictionary" -> o["name"].lenientString()?.takeIf { it.isNotEmpty() }?.let { DeleteDictionary(it) }
                else -> null
            }
            return op ?: Unsupported(e)
        }
    }
}

/** Одна правка library.json. Хранится данными, а не замыканием: переживает перезапуск и повтор. */
sealed interface LibraryChange {
    /** null снимает статус. */
    data class SetStatus(val path: String, val status: String?) : LibraryChange
    data class ShelfMembership(val shelf: String, val path: String, val member: Boolean) : LibraryChange
    data class CreateShelf(val id: String, val name: String) : LibraryChange
    data class RenameShelf(val id: String, val name: String) : LibraryChange
    data class DeleteShelf(val id: String) : LibraryChange
    data class SetGoal(val minutes: Int) : LibraryChange

    /** Идемпотентно: повтор уже применённой правки оставляет документ прежним. */
    fun apply(doc: LibraryDoc): LibraryDoc = when (this) {
        is SetStatus -> doc.copy(status = doc.status.toMutableMap().apply {
            if (status == null) remove(path) else put(path, status)
        })
        is ShelfMembership -> doc.copy(shelves = doc.shelves.map { s ->
            if (s.id != shelf) s else s.copy(books = s.books.filter { it != path } + if (member) listOf(path) else emptyList())
        })
        is CreateShelf -> if (doc.shelves.any { it.id == id }) {
            doc.copy(shelves = doc.shelves.map { if (it.id == id) it.copy(name = name) else it })
        } else {
            doc.copy(shelves = doc.shelves + Shelf(id = id, name = name))
        }
        is RenameShelf -> if (name.isEmpty()) doc else
            doc.copy(shelves = doc.shelves.map { if (it.id == id) it.copy(name = name) else it })
        is DeleteShelf -> doc.copy(shelves = doc.shelves.filter { it.id != id })
        is SetGoal -> doc.copy(dailyMinutes = minutes)
    }

    fun toJson(): JsonObject = when (this) {
        is SetStatus -> buildJsonObject {
            put("type", "setStatus"); put("path", path); put("status", status?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        is ShelfMembership -> buildJsonObject {
            put("type", "shelfMembership"); put("shelf", shelf); put("path", path); put("member", member)
        }
        is CreateShelf -> buildJsonObject { put("type", "createShelf"); put("id", id); put("name", name) }
        is RenameShelf -> buildJsonObject { put("type", "renameShelf"); put("id", id); put("name", name) }
        is DeleteShelf -> buildJsonObject { put("type", "deleteShelf"); put("id", id) }
        is SetGoal -> buildJsonObject { put("type", "setGoal"); put("minutes", minutes) }
    }

    companion object {
        fun fromJson(o: JsonObject): LibraryChange? = when (o["type"].lenientString()) {
            "setStatus" -> o["path"].lenientString()?.let { SetStatus(it, o["status"].lenientString()) }
            "shelfMembership" -> {
                val shelf = o["shelf"].lenientString(); val path = o["path"].lenientString()
                if (shelf != null && path != null) ShelfMembership(shelf, path, o.bool("member")) else null
            }
            "createShelf" -> o["id"].lenientString()?.let { CreateShelf(it, o.string("name")) }
            "renameShelf" -> o["id"].lenientString()?.let { RenameShelf(it, o.string("name")) }
            "deleteShelf" -> o["id"].lenientString()?.let { DeleteShelf(it) }
            "setGoal" -> o["minutes"].lenientInt()?.let { SetGoal(it) }
            else -> null
        }
    }
}
