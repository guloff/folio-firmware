package com.rustamguloff.foliolink.core.net

import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.BookFiles
import com.rustamguloff.foliolink.core.model.BooksResponse
import com.rustamguloff.foliolink.core.model.DeviceEndpoint
import com.rustamguloff.foliolink.core.model.DeviceInfo
import com.rustamguloff.foliolink.core.model.DeviceSetting
import com.rustamguloff.foliolink.core.model.DeviceStats
import com.rustamguloff.foliolink.core.model.DeviceStatus
import com.rustamguloff.foliolink.core.model.DictionariesResponse
import com.rustamguloff.foliolink.core.model.FileEntry
import com.rustamguloff.foliolink.core.model.FolioJson
import com.rustamguloff.foliolink.core.model.FontsResponse
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.model.HighlightsResponse
import com.rustamguloff.foliolink.core.model.LibraryDoc
import com.rustamguloff.foliolink.core.model.OkResponse
import com.rustamguloff.foliolink.core.model.PairStartResponse
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.model.RemoteFile
import com.rustamguloff.foliolink.core.model.ScreenshotsResponse
import com.rustamguloff.foliolink.core.model.SessionsResponse
import com.rustamguloff.foliolink.core.model.SleepResponse
import com.rustamguloff.foliolink.core.model.TimeRequest
import com.rustamguloff.foliolink.core.model.VocabResponse
import com.rustamguloff.foliolink.core.model.VocabWord
import com.rustamguloff.foliolink.core.model.lenientInt
import com.rustamguloff.foliolink.core.model.lenientString
import com.rustamguloff.foliolink.core.model.okFlag
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer
import java.io.File
import java.util.TimeZone

/**
 * HTTP API читалки (upstream-маршруты и `/api/inklink/…`). Один экземпляр на подключённую читалку.
 * Ошибки — [DeviceError]. Сеть идёт через [HttpTransport] (на IO внутри транспорта).
 */
class DeviceClient(
    val endpoint: DeviceEndpoint,
    token: String? = null,
    private val transport: HttpTransport = HttpTransport.forEndpoint(endpoint),
) {
    /** Токен сопряжения (контракт v2 §1), уходит в [TOKEN_HEADER] на изменяющих запросах. */
    @Volatile
    private var token: String? = token

    private val base: String = endpoint.baseUrl

    fun setToken(token: String?) { this.token = token }
    val hasToken: Boolean get() = token != null

    // region Обвязка

    private fun url(path: String, query: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder(base).append(PercentCoding.encode(path, keep = "/"))
        if (query.isNotEmpty()) {
            sb.append('?')
            sb.append(query.toSortedMap().entries.joinToString("&") {
                PercentCoding.encode(it.key) + "=" + PercentCoding.encode(it.value, keep = "/")
            })
        }
        return sb.toString()
    }

    /** Отправляет запрос (с токеном, где нужен), код ответа не оценивает. */
    private suspend fun performRaw(request: HttpRequest, progress: TransferProgress? = null): HttpResponse {
        val t = token
        val req = if (t != null && needsToken(request.method, request.path)) {
            HttpRequest(request.method, request.url, request.headers + (TOKEN_HEADER to t), request.body, request.timeoutMs)
        } else {
            request
        }
        return transport.execute(req, progress)
    }

    private suspend fun perform(request: HttpRequest, progress: TransferProgress? = null): HttpResponse {
        val r = performRaw(request, progress)
        if (!r.isSuccess) throw mapServerError(r.status, r.body)
        return r
    }

    private suspend fun <T> get(path: String, serializer: KSerializer<T>, query: Map<String, String> = emptyMap()): T {
        val r = perform(HttpRequest("GET", url(path, query)))
        return try {
            FolioJson.decodeFromString(serializer, r.text)
        } catch (e: Exception) {
            throw DeviceError.Decoding(path)
        }
    }

    private suspend inline fun <reified T> get(path: String, query: Map<String, String> = emptyMap()): T =
        get(path, serializer<T>(), query)

    private fun jsonRequest(path: String, body: JsonElement, timeoutMs: Int = 15_000): HttpRequest =
        HttpRequest(
            "POST", url(path),
            headers = mapOf("Content-Type" to "application/json"),
            body = body.toString().toByteArray(Charsets.UTF_8),
            timeoutMs = timeoutMs,
        )

    private fun obj(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) })

    /** POST JSON; ответ `{"ok":false,…}` с кодом 200 — тоже ошибка. */
    private suspend fun postJson(path: String, body: JsonElement, timeoutMs: Int = 15_000): HttpResponse {
        val r = perform(jsonRequest(path, body, timeoutMs))
        val o = strictObject(r.body)
        if (o != null && !o.okFlag()) throw DeviceError.Server(200, o["error"].lenientString().orEmpty())
        return r
    }

    /** POST application/x-www-form-urlencoded (upstream /mkdir, /delete, /rename). */
    private suspend fun postForm(path: String, fields: Map<String, String>): HttpResponse {
        val body = fields.toSortedMap().entries.joinToString("&") {
            "${it.key}=${PercentCoding.encode(it.value)}"
        }
        return perform(
            HttpRequest(
                "POST", url(path),
                headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=utf-8"),
                body = body.toByteArray(Charsets.UTF_8),
            ),
        )
    }

    // endregion

    // region Upstream-маршруты

    suspend fun status(): DeviceStatus = get("/api/status")

    suspend fun files(dir: String): List<FileEntry> = get("/api/files", mapOf("path" to dir))

    suspend fun download(path: String, progress: TransferProgress? = null): ByteArray =
        perform(
            HttpRequest(
                "GET", url("/download", mapOf("path" to path)), timeoutMs = 60_000,
                maxResponseBytes = HttpTransport.MAX_DOWNLOAD_BYTES,
            ),
            progress,
        ).body

    /**
     * Загружает файл в `dir`. Прошивка не перезаписывает файлы; с `replace` одноимённый файл
     * сначала удаляется. Уже существующий файл без `replace` → [DeviceError.AlreadyExists].
     *
     * Список файлов перед заменой запрашивается без отката на «файлов нет»: сбой списка —
     * это сбой загрузки (таймаут остановит очередь как временный, а не превратится в «уже существует»).
     */
    suspend fun upload(
        content: ByteArray,
        fileName: String,
        dir: String,
        mimeType: String = "application/octet-stream",
        replace: Boolean = false,
        progress: TransferProgress? = null,
    ) = upload({ addFile("file", fileName, mimeType, content) }, fileName, dir, replace, progress)

    /** То же для файла на диске: содержимое уходит потоком, в память не читается. */
    suspend fun upload(
        file: File,
        fileName: String,
        dir: String,
        mimeType: String = "application/octet-stream",
        replace: Boolean = false,
        progress: TransferProgress? = null,
    ) = upload({ addFile("file", fileName, mimeType, file) }, fileName, dir, replace, progress)

    private suspend fun upload(
        fill: MultipartBody.() -> Unit,
        fileName: String,
        dir: String,
        replace: Boolean,
        progress: TransferProgress?,
    ) {
        if (replace && files(dir).any { it.name == fileName }) delete(join(dir, fileName))
        val body = MultipartBody().apply(fill)
        perform(
            HttpRequest(
                "POST", url("/upload", mapOf("path" to dir)),
                headers = mapOf("Content-Type" to body.contentType),
                timeoutMs = uploadTimeoutMs(body.length),
                content = body,
            ),
            progress,
        )
    }

    /**
     * Книга в `dir` без потери при совпадении имён. «Файл уже существует» сверяется со списком
     * каталога: тот же размер — это та же книга ([DeviceError.AlreadyExists], очередь снимет
     * элемент как дубликат); другой размер (другая книга, новая версия, обрезок) — файл уходит
     * под свободным именем с суффиксом « (2)», « (3)»… Возвращает имя, под которым книга легла.
     * Не удалось получить список — [DeviceError.RetryLater]: элемент ждёт, копия на телефоне цела.
     */
    suspend fun uploadBook(
        file: File,
        fileName: String,
        dir: String,
        mimeType: String = BookFiles.uploadMime(fileName),
        progress: TransferProgress? = null,
    ): String {
        try {
            upload(file, fileName, dir, mimeType, progress = progress)
            return fileName
        } catch (e: DeviceError.AlreadyExists) {
            val existing = try {
                files(dir)
            } catch (c: CancellationException) {
                throw c
            } catch (listing: Exception) {
                throw DeviceError.RetryLater(listing)
            }
            val free = UploadNames.resolve(existing, fileName, file.length()) ?: throw e
            try {
                upload(file, free, dir, mimeType, progress = progress)
            } catch (again: DeviceError.AlreadyExists) {
                // Имя заняли между списком и загрузкой: не дубликат, а повод повторить позже.
                throw DeviceError.RetryLater(again)
            }
            return free
        }
    }

    suspend fun mkdir(name: String, parent: String) {
        postForm("/mkdir", mapOf("name" to name, "path" to parent))
    }

    /**
     * Создаёт недостающие папки абсолютного пути и возвращает путь в написании карты
     * ("/Books" находит существующую "/books"). Папки с точкой не создаются (их делает прошивка).
     */
    suspend fun ensureDirectory(path: String): String {
        var parent = "/"
        for (comp in path.split('/').filter { it.isNotEmpty() }) {
            if (comp.startsWith(".")) {
                parent = join(parent, comp)
                continue
            }
            val entries = try {
                files(parent)
            } catch (e: DeviceError) {
                // Временный сбой — не «папок нет»: иначе mkdir ответит «exists» и загрузка пойдёт не туда.
                if (e.isTransient) throw e
                emptyList()
            }
            val existing = entries.firstOrNull { it.isDirectory && it.name.equals(comp, ignoreCase = true) }
            if (existing != null) {
                parent = join(parent, existing.name)
                continue
            }
            try {
                mkdir(comp, parent)
            } catch (e: DeviceError.Server) {
                // Гонка или другой регистр — не ошибка.
                val m = e.text.lowercase()
                if (!m.contains("exists") && !m.contains("protected")) throw e
            }
            parent = join(parent, comp)
        }
        return parent
    }

    suspend fun delete(path: String) {
        postForm("/delete", mapOf("path" to path))
    }

    suspend fun rename(path: String, name: String) {
        postForm("/rename", mapOf("path" to path, "name" to name))
    }

    suspend fun settings(): List<DeviceSetting> = get("/api/settings", ListSerializer(DeviceSetting.serializer()))

    /**
     * Возвращает, сколько настроек применила прошивка («Applied N setting(s)»). Ответ без этого
     * числа не считается «применено всё»: настройки перечитываются, и считаются ключи, чьё
     * значение на читалке теперь равно отправленному.
     */
    suspend fun updateSettings(changes: Map<String, JsonElement>): Int {
        val r = postJson("/api/settings", JsonObject(changes))
        appliedCount(r.text)?.let { return it }
        val current = settings().associateBy { it.key }
        return changes.count { (key, value) -> current[key]?.let { sameSettingValue(value, it.value) } == true }
    }

    suspend fun fonts(): FontsResponse = get("/api/fonts")

    suspend fun deleteFontFamily(family: String) {
        postJson("/api/fonts/delete", obj("family" to family))
    }

    /** POST /api/fonts/upload (только .cpfont); `family` — иначе прошивка берёт его из имени файла. */
    suspend fun uploadFont(content: ByteArray, fileName: String, family: String? = null, progress: TransferProgress? = null) {
        val body = MultipartBody().addFile("file", fileName, "application/octet-stream", content)
        perform(
            HttpRequest(
                "POST", url("/api/fonts/upload", family?.let { mapOf("family" to it) } ?: emptyMap()),
                headers = mapOf("Content-Type" to body.contentType),
                timeoutMs = uploadTimeoutMs(body.length),
                content = body,
            ),
            progress,
        )
    }

    // endregion

    // region Маршруты компаньона
    // `/api/inklink/*` — стабильные имена протокола прошивки Folio: историческое «inklink» не переименовывать.

    suspend fun info(): DeviceInfo = get("/api/inklink/info")

    suspend fun setTime(nowMs: Long = System.currentTimeMillis(), timeZone: TimeZone = TimeZone.getDefault()) {
        val body = TimeRequest(epoch = nowMs / 1000, tzOffsetMin = timeZone.getOffset(nowMs) / 60_000)
        postJson("/api/inklink/time", FolioJson.encodeToJsonElement(TimeRequest.serializer(), body))
    }

    suspend fun sessions(since: Long): List<ReadingSession> =
        get<SessionsResponse>("/api/inklink/sessions", mapOf("since" to since.toString())).sessions

    suspend fun stats(): DeviceStats = get("/api/inklink/stats")

    /**
     * Контракт v2 §3: страницы по `pageSize` до `total`. Прошивка без пагинации игнорирует
     * параметры и отдаёт всё без `total` — тогда хватает одного запроса. Пустая или повторная
     * страница (offset проигнорирован, библиотека поменялась) завершает обход.
     */
    suspend fun books(pageSize: Int = 100): List<Book> {
        val all = mutableListOf<Book>()
        val seen = HashSet<String>()
        while (true) {
            val page: BooksResponse = get(
                "/api/inklink/books",
                mapOf("offset" to all.size.toString(), "limit" to pageSize.toString()),
            )
            val total = page.total ?: return page.books
            val fresh = page.books.filter { it.path !in seen }
            if (fresh.isEmpty()) return all
            all += fresh
            fresh.mapTo(seen) { it.path }
            if (all.size >= total) return all
        }
    }

    suspend fun library(): LibraryDoc = get("/api/inklink/library")

    /**
     * Документ для чтения перед записью: POST заменяет library.json целиком, поэтому ответ
     * неожиданной формы — [DeviceError.Decoding], а не «пустые полки» (см. [LibraryDoc.fromJsonStrict]).
     */
    suspend fun libraryStrict(): LibraryDoc {
        val r = perform(HttpRequest("GET", url("/api/inklink/library")))
        return try {
            LibraryDoc.fromJsonStrict(Strict.parseToJsonElement(r.text))
        } catch (e: Exception) {
            throw DeviceError.Decoding("/api/inklink/library")
        }
    }

    suspend fun saveLibrary(doc: LibraryDoc) {
        postJson("/api/inklink/library", doc.toJson())
    }

    suspend fun highlights(book: String? = null): List<Highlight> =
        get<HighlightsResponse>("/api/inklink/highlights", book?.let { mapOf("book" to it) } ?: emptyMap()).highlights

    suspend fun updateHighlight(id: String, note: String) {
        postJson("/api/inklink/highlights/update", obj("id" to id, "note" to note))
    }

    suspend fun deleteHighlight(id: String) {
        postJson("/api/inklink/highlights/delete", obj("id" to id))
    }

    suspend fun vocab(): List<VocabWord> = get<VocabResponse>("/api/inklink/vocab").words

    suspend fun deleteVocab(word: String) {
        postJson("/api/inklink/vocab/delete", obj("word" to word))
    }

    suspend fun screenshots(): List<RemoteFile> = get<ScreenshotsResponse>("/api/inklink/screenshots").screenshots

    suspend fun sleepImages(): SleepResponse = get("/api/inklink/sleep")

    /**
     * «Свой» показывает `name` (имя файла в папке заставок) на каждом сне; "" — снова случайная.
     * Любой 404 — прошивка без маршрута: [DeviceError.NotSupported].
     */
    suspend fun selectSleepImage(name: String) {
        val r = performRaw(jsonRequest("/api/inklink/sleep/select", obj("name" to name)))
        if (r.status == 404) throw DeviceError.NotSupported
        if (!r.isSuccess) throw mapServerError(r.status, r.body)
        val o = strictObject(r.body)
        if (o != null && !o.okFlag()) throw DeviceError.Server(r.status, o["error"].lenientString().orEmpty())
    }

    /** Словари на карте и режим поиска (Folio 1.1, признак `dictionaries`). */
    suspend fun dictionaries(): DictionariesResponse = get("/api/inklink/dictionaries")

    /** `*` — все словари, `-` — выключить, иначе имя папки. Нет папки → 404 «no such dictionary». */
    suspend fun selectDictionary(name: String) {
        postJson("/api/inklink/dictionaries/select", obj("name" to name))
    }

    /** Удаляет папку словаря целиком (с индексами); выбранный → выбор «все». */
    suspend fun deleteDictionary(name: String) {
        postJson("/api/inklink/dictionaries/delete", obj("name" to name))
    }

    // endregion

    // region Сопряжение (контракт v2 §1)

    suspend fun pairStart(name: String): PairStartResponse {
        val r = performRaw(jsonRequest("/api/inklink/pair/start", obj("name" to name)))
        if (r.status == 429) {
            val wait = strictObject(r.body)?.get("retryIn").lenientInt() ?: 30
            throw DeviceError.Pairing(DeviceError.PairingFailure.RateLimited(maxOf(1, wait)))
        }
        if (!r.isSuccess) throw mapServerError(r.status, r.body)
        return runCatching { FolioJson.decodeFromString(PairStartResponse.serializer(), r.text) }
            .getOrDefault(PairStartResponse())
    }

    /** Возвращает токен; неверный/сгоревший/незапрошенный PIN → [DeviceError.Pairing]. */
    suspend fun pairFinish(pin: String, name: String): String {
        val r = performRaw(jsonRequest("/api/inklink/pair/finish", obj("pin" to pin, "name" to name)))
        val o = strictObject(r.body)
        return when (r.status) {
            in 200..299 -> o?.get("token").lenientString()?.takeIf { it.isNotEmpty() }
                ?: throw DeviceError.Decoding("pair/finish")
            403 -> throw DeviceError.Pairing(DeviceError.PairingFailure.BadPin(o?.get("attemptsLeft").lenientInt()))
            409 -> throw DeviceError.Pairing(DeviceError.PairingFailure.NoActivePin)
            410 -> throw DeviceError.Pairing(DeviceError.PairingFailure.Expired)
            else -> throw mapServerError(r.status, r.body)
        }
    }

    suspend fun applyFirmware(path: String): OkResponse {
        val r = postJson("/api/inklink/firmware/apply", obj("path" to path), timeoutMs = 30_000)
        return try {
            FolioJson.decodeFromString(OkResponse.serializer(), r.text)
        } catch (e: Exception) {
            throw DeviceError.Decoding("firmware/apply")
        }
    }

    // endregion

    companion object {
        /** Имя заголовка — часть протокола прошивки: «InkLink» остаётся, хотя приложение — FolioLink. */
        const val TOKEN_HEADER = "X-InkLink-Token"

        private val Strict = Json

        /**
         * Токен нужен каждому изменяющему запросу (контракт v2 §1) и GET /api/inklink/info — чтобы
         * прошивка сообщила `pairing.authorized`. Остальные GET открыты; pair/start и pair/finish — способ его получить.
         */
        fun needsToken(method: String, path: String): Boolean {
            val m = method.uppercase()
            if (m == "GET" || m == "HEAD") return path == "/api/inklink/info"
            return path != "/api/inklink/pair/start" && path != "/api/inklink/pair/finish"
        }

        fun join(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"

        /** ~30 КБ/с в худшем случае по слабой связи, плюс запас. */
        fun uploadTimeoutMs(size: Long): Int = maxOf(30_000L, minOf(size / 30 + 15_000, Int.MAX_VALUE.toLong())).toInt()

        fun uploadTimeoutMs(size: Int): Int = uploadTimeoutMs(size.toLong())

        /** Значения настроек сравниваются как числа (`1` == `1.0` == `true`), прочее — буквально. */
        fun sameSettingValue(a: JsonElement, b: JsonElement): Boolean {
            if (a == b) return true
            val x = settingNumber(a) ?: return false
            return x == settingNumber(b)
        }

        private fun settingNumber(e: JsonElement): Long? {
            val p = e as? JsonPrimitive ?: return null
            if (p.isString) return p.content.trim().toLongOrNull()
            return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.booleanOrNull?.let { if (it) 1L else 0L }
        }

        /** Строгий разбор JSON-объекта (текст upstream вроде «Applied 1 setting(s)» — не объект). */
        internal fun strictObject(body: ByteArray): JsonObject? =
            runCatching { Strict.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()

        fun mapServerError(status: Int, body: ByteArray): DeviceError {
            val o = strictObject(body)
            val message = o?.get("error").lenientString()
                ?: body.toString(Charsets.UTF_8).trim()
            if (status == 401) return DeviceError.PairingRequired
            // Upstream отвечает на неизвестные маршруты «404 Not Found\n\nURI: …».
            if (status == 404 && (message.isEmpty() || message.startsWith("404 Not Found"))) return DeviceError.NotSupported
            if (message.startsWith("File already exists")) {
                return DeviceError.AlreadyExists(message.split(": ").last())
            }
            return DeviceError.Server(status, message.take(200))
        }

        fun appliedCount(text: String): Int? =
            Regex("""Applied (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }
}
