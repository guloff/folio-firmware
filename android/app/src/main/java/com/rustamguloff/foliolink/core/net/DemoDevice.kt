package com.rustamguloff.foliolink.core.net

import com.rustamguloff.foliolink.core.dictionaries.DictionaryFolder
import com.rustamguloff.foliolink.core.dictionaries.StarDict
import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.DeviceEndpoint
import com.rustamguloff.foliolink.core.model.FileEntry
import com.rustamguloff.foliolink.core.model.FolioJson
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.model.RemoteFile
import com.rustamguloff.foliolink.core.model.VocabWord
import com.rustamguloff.foliolink.core.model.array
import com.rustamguloff.foliolink.core.model.lenientInt
import com.rustamguloff.foliolink.core.model.lenientString
import com.rustamguloff.foliolink.core.model.obj
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Демо-режим: виртуальная читалка Folio внутри приложения — попробовать FolioLink без железа.
// DeviceClient для `DemoDevice.ENDPOINT` ходит через DemoTransport, который отвечает тем же JSON,
// что прошивка, поэтому все экраны идут своим настоящим путём. Состояние живёт в памяти.

object DemoDevice {
    /** Зарезервированный хост (.invalid никогда не резолвится): демо-запрос не уйдёт в сеть. */
    const val HOST = "folio-demo.invalid"
    val ENDPOINT = DeviceEndpoint(host = HOST, port = 80, name = "Folio Demo")
}

val DeviceEndpoint.isDemo: Boolean get() = host == DemoDevice.HOST

/** Транспорт демо-читалки: разбирает запрос и отдаёт его [DemoReader]. */
class DemoTransport(private val reader: DemoReader = DemoReader.shared) : HttpTransport {
    override suspend fun execute(request: HttpRequest, progress: TransferProgress?): HttpResponse {
        val body = request.body ?: ByteArray(0)
        request.body?.let { progress?.invoke(it.size.toLong(), it.size.toLong()) }
        val reply = reader.handle(request.method, request.decodedPath, request.query, body)
        if (request.body == null) progress?.invoke(reply.body.size.toLong(), reply.body.size.toLong())
        return reply
    }
}

/** Состояние и API виртуальной читалки. Потокобезопасно. */
class DemoReader(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val lock = Any()
    private val books = mutableListOf<Book>()
    private var sessions = listOf<ReadingSession>()
    private val highlights = mutableListOf<Highlight>()
    private val vocab = mutableListOf<VocabWord>()
    private var library: JsonObject = JsonObject(emptyMap())
    private val settings = mutableListOf<JsonObject>()
    private val files = mutableMapOf<String, MutableList<FileEntry>>()
    private var screenshotEntries = listOf<RemoteFile>()
    /** Содержимое загруженных файлов по пути (заставки показываются миниатюрами). */
    private val uploads = mutableMapOf<String, ByteArray>()
    /** Нарисованные по требованию картинки (скриншоты и заставки из набора). */
    private val drawn = mutableMapOf<String, () -> ByteArray>()
    private var sleepSelected = ""
    /** Режим словаря (Folio 1.1): `*`, `-` или папка. */
    private var dictionarySelected = "*"
    /** Папки словарей, у которых уже построен индекс .qidx (у загруженных — нет). */
    private val indexedDictionaries = mutableSetOf<String>()
    private var tzOffsetMin = zone.rules.getOffset(Instant.now()).totalSeconds / 60

    init { seed() }

    private data class Sample(val file: String, val title: String, val author: String, val progress: Int, val status: String)

    // region Пример данных (книги и цитаты в общественном достоянии)

    private fun seed() {
        val now = Instant.ofEpochMilli(nowMs())
        val nowSec = now.epochSecond
        val today = now.atZone(zone).toLocalDate()
        val paths = SAMPLE_BOOKS.map { "/books/" + it.file }

        // ~4 месяца чтения, детерминированно: почти каждый вечер, иногда пропуски.
        val rng = SplitMix(42u)
        val lastRead = mutableMapOf<String, Long>()
        val secsPerBook = mutableMapOf<String, Int>()
        val reading = listOf(0, 2, 4, 1, 3, 5)
        val list = mutableListOf<ReadingSession>()
        for (back in 120 downTo 0) {
            val day = today.minusDays(back.toLong())
            if (back > 0 && rng.next() % 7u == 0uL) continue
            val sessionsToday = if (back == 0) 1 else 1 + (rng.next() % 2u).toInt()
            for (i in 0 until sessionsToday) {
                val bookIndex = reading[(back / 20 + i) % reading.size]
                val hour = if (back == 0) 8 else 19 + i * 2
                val start = day.atStartOfDay(zone).toEpochSecond() + hour * 3600 + (rng.next() % 1800u).toLong()
                if (start > nowSec) continue
                val secs = 900 + (rng.next() % 2400u).toInt()
                val sample = SAMPLE_BOOKS[bookIndex]
                val pct = (if (sample.progress > 0) sample.progress - back / 3 else 5).coerceIn(1, 100)
                val path = paths[bookIndex]
                list += ReadingSession(
                    book = path, title = sample.title, start = start, secs = secs, pages = secs / 75, pct = pct,
                    day = DAY.format(Instant.ofEpochSecond(start).atZone(zone)),
                )
                lastRead[path] = maxOf(lastRead[path] ?: 0, start)
                secsPerBook[path] = (secsPerBook[path] ?: 0) + secs
            }
        }
        sessions = list
        val recentOrder = lastRead.entries.sortedByDescending { it.value }.map { it.key }
        SAMPLE_BOOKS.forEachIndexed { i, b ->
            val path = paths[i]
            books += Book(
                path = path, title = b.title, author = b.author, progress = b.progress,
                lastRead = lastRead[path] ?: 0, secs = secsPerBook[path] ?: 0, status = b.status,
                shelves = if (i in listOf(2, 4, 6)) listOf("adventure") else listOf("classics"),
                recent = recentOrder.indexOf(path),
            )
        }
        library = buildJsonObject {
            put("version", 1)
            put("shelves", buildJsonArray {
                add(shelf("classics", "Classics", listOf(paths[0], paths[1], paths[3], paths[5], paths[7], paths[8])))
                add(shelf("adventure", "Adventure", listOf(paths[2], paths[4], paths[6])))
            })
            put("status", JsonObject(paths.indices.associate { paths[it] to JsonPrimitive(SAMPLE_BOOKS[it].status) }))
            put("goals", buildJsonObject { put("dailyMinutes", 30) })
        }

        val quotes = listOf(
            Quote(1, "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.", "Chapter 1", 1),
            Quote(4, "Call me Ishmael.", "Chapter 1. Loomings", 1),
            Quote(5, "The only way to get rid of a temptation is to yield to it.", "Chapter 2", 9),
            Quote(2, "You see, but you do not observe.", "A Scandal in Bohemia", 3),
            Quote(3, "Why, sometimes I've believed as many as six impossible things before breakfast.", "Chapter 5", 41),
        )
        quotes.forEachIndexed { n, q ->
            highlights += Highlight(
                id = "hdemo$n", book = paths[q.book], title = SAMPLE_BOOKS[q.book].title, text = q.text,
                note = if (n == 0) "Opening line" else "", chapter = q.chapter, pct = q.pct,
                created = nowSec - (n + 1) * 86_400L * 3,
            )
        }
        vocab += VocabWord("blacksmith", "…Joe Gargery, who married the blacksmith…", paths[0], nowSec - 86_400L * 5, 2)
        vocab += VocabWord("leviathan", "…the great Leviathan is that one creature in the world…", paths[4], nowSec - 86_400L * 9, 1)
        vocab += VocabWord("entailed", "…Mr. Bennet's property… was entailed…", paths[1], nowSec - 86_400L * 20, 1)

        settings += setting("sleepScreen", "Sleep Screen", "Display", "enum", 8,
            listOf("Dark", "Light", "Custom", "Cover", "Cover + Custom", "None", "Quick Resume", "Transparent",
                "Dashboard", "Quote", "Rotate"))
        settings += setting("folioHome", "Folio home screen", "Display", "toggle", 1)
        settings += setting("showHighlights", "Show highlights in text", "Reader", "toggle", 1)
        settings += setting("refreshFrequency", "Refresh Frequency", "Display", "enum", 3,
            listOf("1 page", "5 pages", "10 pages", "15 pages", "30 pages"))
        settings += setting("fontFamily", "Reader Font Family", "Reader", "enum", 0, listOf("Bookerly", "Noto Sans", "Open Dyslexic"))
        settings += setting("fontSize", "Font Size", "Reader", "enum", 1, listOf("Small", "Medium", "Large", "X Large"))
        settings += setting("lineSpacing", "Reader Line Spacing", "Reader", "enum", 1, listOf("Tight", "Normal", "Wide"))
        settings += buildJsonObject {
            put("key", "screenMargin"); put("name", "Reader Screen Margin"); put("category", "Reader")
            put("type", "value"); put("value", 10); put("min", 5); put("max", 40); put("step", 5)
        }
        settings += setting("paragraphAlignment", "Reader Paragraph Alignment", "Reader", "enum", 0,
            listOf("Justify", "Left", "Center", "Right", "Book's Style"))
        settings += setting("hyphenationEnabled", "Hyphenation", "Reader", "toggle", 1)
        settings += buildJsonObject {
            put("key", "deviceName"); put("name", "Device Name"); put("category", "System")
            put("type", "string"); put("value", "Folio Demo")
        }
        settings += setting("bluetoothSync", "Bluetooth sync", "System", "toggle", 0)

        // Два «скриншота» читалки, рисуются по требованию.
        val shots = listOf(
            Triple("Great Expectations", "Great Expectations_ch1_p1_1pct", "My father's family name being Pirrip, and my Christian name Philip, my infant tongue could make of both names nothing longer or more explicit than Pip. So, I called myself Pip, and came to be called Pip."),
            Triple("Moby-Dick", "Moby-Dick_ch1_p1_1pct", "Call me Ishmael. Some years ago — never mind how long precisely — having little or no money in my purse, and nothing particular to interest me on shore, I thought I would sail about a little and see the watery part of the world."),
        )
        screenshotEntries = shots.mapIndexed { i, (title, stem, text) ->
            val epoch = nowSec - (i + 1) * 86_400L
            val name = "${stem}_$epoch.bmp"
            val path = "/screenshots/$title/$name"
            drawn[path] = { GrayBmp.page(text, seed = i) }
            RemoteFile(name = name, path = path, size = GrayBmp.FULL_SIZE.toLong(), book = title)
        }
        files["/"] = mutableListOf(dir("books"), dir("sleep"), dir("screenshots"), dir("fonts"), dir("dictionaries"))
        // Два словаря StarDict (Folio 1.1): файлы — только записи списка, .ifo — настоящие.
        files["/dictionaries"] = mutableListOf()
        for (d in DEMO_DICTIONARIES) {
            val folder = "/dictionaries/${d.folder}"
            files.getValue("/dictionaries") += dir(d.folder)
            val ifo = "StarDict's dict ifo file\nversion=2.4.2\nbookname=${d.title}\nwordcount=${d.words}\n" +
                "idxfilesize=${d.idxSize}\nsametypesequence=h\nlang=${d.lang}\n"
            uploads["$folder/${d.folder}.ifo"] = ifo.toByteArray()
            files[folder] = mutableListOf(
                FileEntry("${d.folder}.ifo", ifo.length.toLong(), isDirectory = false, isEpub = false),
                FileEntry("${d.folder}.idx", d.idxSize, isDirectory = false, isEpub = false),
                FileEntry("${d.folder}.dict.dz", d.dictSize, isDirectory = false, isEpub = false),
                FileEntry("${d.folder}.idx.qidx", d.idxSize / 8, isDirectory = false, isEpub = false),
            )
            indexedDictionaries += d.folder
        }
        files["/books"] = SAMPLE_BOOKS.map {
            FileEntry(it.file, 350_000, isDirectory = false, isEpub = it.file.lowercase().endsWith(".epub"))
        }.toMutableList()
        files["/sleep"] = mutableListOf()
        files["/fonts"] = mutableListOf()
        files["/screenshots"] = shots.map { dir(it.first) }.toMutableList()
        // Несколько заставок Folio уже на читалке, чтобы в «Заставке» было из чего выбрать.
        SLEEP_PICTURES.forEachIndexed { i, name ->
            files.getValue("/sleep") += FileEntry(name, GrayBmp.FULL_SIZE.toLong(), isDirectory = false, isEpub = false)
            drawn["/sleep/$name"] = { GrayBmp.pattern(i) }
        }
    }

    private data class DemoDictionary(
        val folder: String, val title: String, val words: Int, val lang: String, val idxSize: Long, val dictSize: Long,
    )

    private data class Quote(val book: Int, val text: String, val chapter: String, val pct: Int)

    private fun shelf(id: String, name: String, books: List<String>) = buildJsonObject {
        put("id", id); put("name", name); put("books", JsonArray(books.map { JsonPrimitive(it) }))
    }

    private fun setting(key: String, name: String, category: String, type: String, value: Int, options: List<String>? = null) =
        buildJsonObject {
            put("key", key); put("name", name); put("category", category); put("type", type); put("value", value)
            options?.let { put("options", JsonArray(it.map { o -> JsonPrimitive(o) })) }
        }

    private fun dir(name: String) = FileEntry(name, 0, isDirectory = true, isEpub = false)

    // endregion

    // region API

    fun handle(method: String, path: String, query: Map<String, String>, body: ByteArray): HttpResponse = synchronized(lock) {
        val json = runCatching { Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
        val ok = buildJsonObject { put("ok", true) }
        when (method.uppercase() to path) {
            "GET" to "/api/status" -> reply(buildJsonObject {
                put("version", "0.2.0-x4pro"); put("ip", "demo"); put("mode", "STA"); put("rssi", -48)
                put("freeHeap", 180_000); put("uptime", 3600); put("device", "xteink_x4_pro"); put("serial", "DEMO-FOLIO")
            })
            "GET" to "/api/inklink/info" -> reply(buildJsonObject {
                put("api", 1); put("firmware", "0.2.0-x4pro"); put("board", "X4 Pro"); put("battery", 86)
                put("charging", false); put("sdTotalMB", 29_000); put("sdFreeMB", 27_400)
                put("clock", buildJsonObject { put("available", true); put("valid", true); put("epoch", nowMs() / 1000) })
                put("pairing", buildJsonObject { put("required", false); put("paired", false); put("authorized", true) })
                put("features", JsonArray(listOf("stats", "library", "highlights", "vocab", "screenshots", "sleep", "dictionaries")
                    .map { JsonPrimitive(it) }))
            })
            "POST" to "/api/inklink/time" -> {
                tzOffsetMin = json["tzOffsetMin"].lenientInt() ?: tzOffsetMin
                reply(ok)
            }
            "GET" to "/api/inklink/sessions" -> {
                val since = query["since"]?.toLongOrNull() ?: 0
                reply(buildJsonObject { put("sessions", encode(ReadingSession.serializer(), sessions.filter { it.start >= since })) })
            }
            "GET" to "/api/inklink/stats" -> reply(stats())
            "GET" to "/api/inklink/books" -> {
                val all = books.map(::withLibraryState)
                val offset = maxOf(0, query["offset"]?.toIntOrNull() ?: 0)
                val limit = minOf(200, query["limit"]?.toIntOrNull() ?: all.size)
                val page = if (offset < all.size) all.subList(offset, minOf(all.size, offset + limit)) else emptyList()
                reply(buildJsonObject {
                    put("books", encode(Book.serializer(), page)); put("total", all.size)
                    put("offset", offset); put("limit", limit)
                })
            }
            "GET" to "/api/inklink/library" -> reply(library)
            "POST" to "/api/inklink/library" -> {
                if (json.isNotEmpty()) library = json
                reply(ok)
            }
            "GET" to "/api/inklink/highlights" -> {
                val book = query["book"]
                reply(buildJsonObject {
                    put("highlights", encode(Highlight.serializer(), highlights.filter { book == null || it.book == book }))
                })
            }
            "POST" to "/api/inklink/highlights/update" -> {
                val id = json["id"].lenientString()
                val i = highlights.indexOfFirst { it.id == id }
                if (id != null && i >= 0) {
                    highlights[i] = highlights[i].copy(note = json["note"].lenientString().orEmpty())
                    reply(ok)
                } else {
                    error(404, "not found")
                }
            }
            "POST" to "/api/inklink/highlights/delete" -> {
                val id = json["id"].lenientString()
                highlights.removeAll { it.id == id }
                reply(ok)
            }
            "GET" to "/api/inklink/vocab" -> reply(buildJsonObject { put("words", encode(VocabWord.serializer(), vocab)) })
            "POST" to "/api/inklink/vocab/delete" -> {
                val word = json["word"].lenientString()
                vocab.removeAll { it.word == word }
                reply(ok)
            }
            "GET" to "/api/inklink/screenshots" ->
                reply(buildJsonObject { put("screenshots", encode(RemoteFile.serializer(), screenshotEntries)) })
            "GET" to "/api/inklink/sleep" -> {
                val images = files["/sleep"].orEmpty().map { RemoteFile(name = it.name, path = "/sleep/${it.name}", size = it.size) }
                val mode = settings.firstOrNull { it["key"].lenientString() == "sleepScreen" }?.get("value") ?: JsonPrimitive(8)
                // Как прошивка: выбранный файл, которого больше нет, читается как "".
                val selected = if (images.any { it.name == sleepSelected }) sleepSelected else ""
                reply(buildJsonObject {
                    put("dir", "/sleep"); put("images", encode(RemoteFile.serializer(), images))
                    put("mode", mode); put("selected", selected)
                })
            }
            "POST" to "/api/inklink/sleep/select" -> {
                val name = json["name"].lenientString()
                when {
                    name == null -> error(400, "missing name")
                    name.contains('/') || name.contains('\\') || name.startsWith(".") -> error(400, "invalid image name")
                    else -> { sleepSelected = name; reply(ok) }
                }
            }
            "GET" to "/api/inklink/dictionaries" -> {
                val list = dictionaries()
                val selected = if (dictionarySelected == "*" || dictionarySelected == "-" ||
                    list.any { it["name"].lenientString() == dictionarySelected }) dictionarySelected else "*"
                reply(buildJsonObject {
                    put("selected", selected); put("root", "/dictionaries"); put("dictionaries", JsonArray(list))
                })
            }
            "POST" to "/api/inklink/dictionaries/select" -> {
                val name = json["name"].lenientString().orEmpty()
                when {
                    name == "*" || name == "-" -> { dictionarySelected = name; reply(buildJsonObject { put("ok", true); put("selected", name) }) }
                    !DictionaryFolder.isValid(name) -> error(400, "invalid dictionary name")
                    dictionaries().none { it["name"].lenientString() == name } -> error(404, "no such dictionary")
                    else -> { dictionarySelected = name; reply(buildJsonObject { put("ok", true); put("selected", name) }) }
                }
            }
            "POST" to "/api/inklink/dictionaries/delete" -> {
                val name = json["name"].lenientString().orEmpty()
                val folder = "/dictionaries/$name"
                when {
                    !DictionaryFolder.isValid(name) -> error(400, "invalid dictionary name")
                    files.remove(folder) == null -> error(404, "no such dictionary")
                    else -> {
                        files["/dictionaries"]?.removeAll { it.name == name }
                        uploads.keys.removeAll { it.startsWith("$folder/") }
                        indexedDictionaries -= name
                        if (dictionarySelected == name) dictionarySelected = "*"
                        reply(ok)
                    }
                }
            }
            "GET" to "/api/settings" -> reply(JsonArray(settings.toList()))
            "POST" to "/api/settings" -> {
                for ((key, value) in json) {
                    val i = settings.indexOfFirst { it["key"].lenientString() == key }
                    if (i >= 0) settings[i] = JsonObject(settings[i] + ("value" to value))
                }
                reply(ok)
            }
            "GET" to "/api/fonts" -> reply(buildJsonObject { put("families", JsonArray(emptyList())); put("maxFamilies", 128) })
            "POST" to "/api/fonts/delete", "POST" to "/api/fonts/upload" -> reply(ok)
            "GET" to "/api/files" -> reply(encode(FileEntry.serializer(), files[query["path"] ?: "/"].orEmpty()))
            "GET" to "/download" -> {
                val p = query["path"].orEmpty()
                val data = uploads[p] ?: drawn[p]?.invoke()
                if (data != null) {
                    HttpResponse(200, data, if (p.endsWith(".bmp")) "image/bmp" else "application/octet-stream")
                } else {
                    HttpResponse(200, "FolioLink demo file".toByteArray(), "application/octet-stream")
                }
            }
            "POST" to "/upload" -> {
                val dirPath = query["path"] ?: "/"
                val name = MultipartBody.fileName(body) ?: "file-${nowMs() / 1000}"
                val content = MultipartBody.fileContent(body)
                val list = files.getOrPut(dirPath) { mutableListOf() }
                list.removeAll { it.name == name }
                list += FileEntry(name, maxOf(content?.size ?: body.size, 1).toLong(), isDirectory = false,
                    isEpub = name.lowercase().endsWith(".epub"))
                if (dirPath == "/sleep" && content != null) {
                    uploads["/sleep/$name"] = content
                    drawn.remove("/sleep/$name")
                }
                // .ifo словаря хранится целиком: из него список берёт название и число слов.
                if (dirPath.startsWith("/dictionaries/") && name.lowercase().endsWith(".ifo") && content != null) {
                    uploads["$dirPath/$name"] = content
                }
                reply(ok)
            }
            "POST" to "/delete" -> {
                // Заставки действительно исчезают (отметка «выбрана» следом); прочее остаётся в наборе.
                val p = HttpRequest.parseQuery(body.toString(Charsets.UTF_8))["path"]
                if (p != null && p.startsWith("/sleep/")) {
                    val name = p.removePrefix("/sleep/")
                    files["/sleep"]?.removeAll { it.name == name }
                    uploads.remove(p)
                    drawn.remove(p)
                }
                reply(ok)
            }
            "POST" to "/mkdir" -> {
                // Папка появляется в списке родителя (ensureDirectory ищет её там).
                val form = HttpRequest.parseQuery(body.toString(Charsets.UTF_8))
                val name = form["name"].orEmpty()
                val parent = form["path"] ?: "/"
                if (name.isNotEmpty()) {
                    val list = files.getOrPut(parent) { mutableListOf() }
                    if (list.none { it.isDirectory && it.name.equals(name, ignoreCase = true) }) list += dir(name)
                    files.getOrPut(DeviceClient.join(parent, name)) { mutableListOf() }
                }
                reply(ok)
            }
            "POST" to "/rename", "POST" to "/move" -> reply(ok)
            "POST" to "/api/inklink/firmware/apply" -> reply(buildJsonObject {
                put("ok", true); put("accepted", true); put("confirmOnDevice", true)
            })
            "POST" to "/api/inklink/pair/start", "POST" to "/api/inklink/pair/finish" -> error(409, "demo reader needs no pairing")
            else -> error(404, "not found")
        }
    }

    /** Словари как у прошивки: папки в /dictionaries с одной основой .idx и .dict(.dz) рядом. */
    private fun dictionaries(): List<JsonObject> = files.keys
        .filter { it.startsWith("/dictionaries/") && it.count { c -> c == '/' } == 2 }
        .sortedBy { it.lowercase() }
        .mapNotNull { folder ->
            val name = folder.removePrefix("/dictionaries/")
            val entries = files[folder].orEmpty().filter { !it.isDirectory }
            val stems = entries.filter { it.name.endsWith(".idx") }.map { it.name.removeSuffix(".idx") }.distinct()
            val stem = stems.singleOrNull() ?: return@mapNotNull null
            if (entries.none { it.name == "$stem.dict" || it.name == "$stem.dict.dz" }) return@mapNotNull null
            val ifo = uploads["$folder/$stem.ifo"]?.let { StarDict.parseIfo(it) }
            val lang = ifo?.lang.orEmpty().take(2).lowercase()
            val script = when (lang) {
                "ru", "uk", "be", "bg", "tg", "kk" -> "cyrillic"
                "en", "de", "fr", "es", "it", "pt" -> "latin"
                else -> "other"
            }
            buildJsonObject {
                put("name", name); put("path", folder); put("stem", stem)
                put("title", ifo?.bookname ?: name); put("words", ifo?.wordcount ?: 0); put("script", script)
                put("sizeKB", (entries.sumOf { it.size } + 1023) / 1024)
                put("html", ifo?.sameTypeSequence == "h")
                put("synonyms", entries.any { it.name == "$stem.syn" })
                put("indexed", name in indexedDictionaries)
            }
        }

    private fun withLibraryState(book: Book): Book {
        val status = library.obj("status")?.get(book.path).lenientString()
        val shelves = library.array("shelves").orEmpty().mapNotNull { it as? JsonObject }
            .filter { s -> s.array("books").orEmpty().any { it.lenientString() == book.path } }
            .mapNotNull { it["id"].lenientString() }
        return book.copy(status = status ?: book.status, shelves = shelves)
    }

    private fun stats(): JsonObject {
        val byDay = sortedMapOf<String, IntArray>()
        for (s in sessions) {
            val a = byDay.getOrPut(s.day) { IntArray(2) }
            a[0] += s.secs; a[1] += s.pages
        }
        val today = Instant.ofEpochMilli(nowMs()).atZone(zone).toLocalDate()
        var cursor = if (byDay.containsKey(DAY.format(today))) today else today.minusDays(1)
        var current = 0
        while (byDay.containsKey(DAY.format(cursor))) { current++; cursor = cursor.minusDays(1) }
        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        for (key in byDay.keys) {
            val d = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            longest = maxOf(longest, run)
            prev = d
        }
        val goal = library.obj("goals")?.get("dailyMinutes").lenientInt() ?: 30
        val t = byDay[DAY.format(today)]
        return buildJsonObject {
            put("today", buildJsonObject { put("secs", t?.get(0) ?: 0); put("pages", t?.get(1) ?: 0) })
            put("goalMinutes", goal)
            put("streak", buildJsonObject { put("current", current); put("longest", longest) })
            put("totals", buildJsonObject {
                put("secs", sessions.sumOf { it.secs }); put("pages", sessions.sumOf { it.pages })
                put("sessions", sessions.size); put("books", books.count { it.status == "done" })
            })
            put("days", JsonArray(byDay.filterKeys { it.isNotEmpty() }.map { (d, a) ->
                buildJsonObject { put("d", d); put("s", a[0]); put("p", a[1]) }
            }))
        }
    }

    private fun <T> encode(serializer: kotlinx.serialization.KSerializer<T>, list: List<T>): JsonElement =
        FolioJson.encodeToJsonElement(ListSerializer(serializer), list)

    private fun reply(e: JsonElement) = HttpResponse(200, e.toString().toByteArray(Charsets.UTF_8), "application/json")

    private fun error(code: Int, message: String) = HttpResponse(
        code, buildJsonObject { put("ok", false); put("error", message) }.toString().toByteArray(Charsets.UTF_8),
        "application/json",
    )

    // endregion

    companion object {
        /** Одна читалка на сеанс приложения. */
        val shared: DemoReader by lazy { DemoReader() }

        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        private val SAMPLE_BOOKS = listOf(
            Sample("Great Expectations.epub", "Great Expectations", "Charles Dickens", 64, "reading"),
            Sample("Pride and Prejudice.epub", "Pride and Prejudice", "Jane Austen", 100, "done"),
            Sample("The Adventures of Sherlock Holmes.epub", "The Adventures of Sherlock Holmes", "Arthur Conan Doyle", 23, "reading"),
            Sample("Alice's Adventures in Wonderland.epub", "Alice's Adventures in Wonderland", "Lewis Carroll", 100, "done"),
            Sample("Moby-Dick.epub", "Moby-Dick", "Herman Melville", 12, "reading"),
            Sample("The Picture of Dorian Gray.epub", "The Picture of Dorian Gray", "Oscar Wilde", 100, "done"),
            Sample("Adventures of Huckleberry Finn.epub", "Adventures of Huckleberry Finn", "Mark Twain", -1, "want"),
            Sample("Frankenstein.epub", "Frankenstein", "Mary Shelley", -1, "want"),
            // FB2 (Folio 1.1): читалка перекодирует в EPUB при первом открытии; в списке — как обычная книга.
            Sample("Chekhov - Kashtanka.fb2", "Каштанка", "Антон Чехов", -1, "want"),
        )

        private val DEMO_DICTIONARIES = listOf(
            DemoDictionary("en-ru-demo", "English–Russian (demo)", 48_213, "en-ru", 1_046_000, 6_812_000),
            DemoDictionary("ru-en-demo", "Русско-английский (demo)", 31_870, "ru-en", 702_000, 4_390_000),
        )

        private val SLEEP_PICTURES = listOf(
            "folio-01-night-reading.bmp", "folio-02-lighthouse.bmp", "folio-03-sleeping-cat.bmp",
            "folio-05-moon-mountains.bmp", "folio-09-botanical.bmp",
        )
    }
}

/** Маленький детерминированный ГПСЧ для истории чтения (SplitMix64, как в iOS). */
internal class SplitMix(private var state: ULong) {
    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }
}

/**
 * Серые BMP 480×800 (24 бит, снизу вверх) для картинок демо-читалки: страница с «текстом»
 * полосами и простые узоры заставок. Свой крошечный кодер, чтобы не зависеть от core/imaging.
 */
internal object GrayBmp {
    const val W = 480
    const val H = 800
    const val FULL_SIZE = 54 + W * 3 * H

    fun encode(width: Int, height: Int, gray: (x: Int, y: Int) -> Int): ByteArray {
        val rowSize = (width * 3 + 3) / 4 * 4
        val size = 54 + rowSize * height
        val b = ByteArray(size)
        fun le32(o: Int, v: Int) { for (k in 0..3) b[o + k] = (v shr (8 * k)).toByte() }
        fun le16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        b[0] = 'B'.code.toByte(); b[1] = 'M'.code.toByte()
        le32(2, size); le32(10, 54); le32(14, 40); le32(18, width); le32(22, height)
        le16(26, 1); le16(28, 24); le32(30, 0); le32(34, rowSize * height)
        le32(38, 2835); le32(42, 2835)
        for (y in 0 until height) {
            val row = 54 + (height - 1 - y) * rowSize
            for (x in 0 until width) {
                val g = gray(x, y).coerceIn(0, 255).toByte()
                val o = row + x * 3
                b[o] = g; b[o + 1] = g; b[o + 2] = g
            }
        }
        return b
    }

    /** «Страница»: строки текста тёмными полосами по длине слов, внизу — колонтитул. */
    fun page(text: String, seed: Int): ByteArray {
        val words = text.split(' ').filter { it.isNotEmpty() }
        // Раскладка слов по строкам шириной 424 px (≈ 13 px на символ).
        val lines = mutableListOf<List<IntRange>>()
        var line = mutableListOf<IntRange>()
        var x = 28
        for (w in words) {
            val len = w.length * 13
            if (x + len > 452 && line.isNotEmpty()) { lines += line; line = mutableListOf(); x = 28 }
            line += x until x + len
            x += len + 10
        }
        if (line.isNotEmpty()) lines += line
        return encode(W, H) { px, py ->
            val li = (py - 40) / 44
            val inText = py >= 40 && (py - 40) % 44 in 8..30 && li in lines.indices && lines[li].any { px in it }
            when {
                inText -> 40
                py in 760..776 && px in 28..(200 + seed * 40) -> 110
                else -> 255
            }
        }
    }

    /** Узор заставки: градиенты и круги, свой для каждого номера. */
    fun pattern(index: Int): ByteArray = encode(W, H) { x, y ->
        when (index % 5) {
            0 -> if ((x - 240) * (x - 240) + (y - 260) * (y - 260) < 90 * 90) 230 else 30 + y * 120 / H
            1 -> if (x in 210..270 && y > 300) 200 else 255 - y * 200 / H
            2 -> if ((x / 40 + y / 40) % 2 == 0) 220 else 170
            3 -> if (y > 520 - (x % 160 - 80).let { if (it < 0) -it else it } * 2) 60 else 200
            else -> 255 - ((x * x + y * y) / 3000 % 4) * 60
        }
    }
}
