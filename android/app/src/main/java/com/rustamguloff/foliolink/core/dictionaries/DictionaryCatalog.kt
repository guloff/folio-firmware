package com.rustamguloff.foliolink.core.dictionaries

import com.rustamguloff.foliolink.core.model.array
import com.rustamguloff.foliolink.core.model.lenientInt
import com.rustamguloff.foliolink.core.model.lenientLong
import com.rustamguloff.foliolink.core.model.lenientString
import com.rustamguloff.foliolink.core.net.PercentCoding
import com.rustamguloff.foliolink.core.net.TransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

// Каталог словарей FolioLink: manifest.json релиза `dictionaries-1` (файлы плоским списком рядом с ним).
// Чистый Kotlin, кроме загрузки по HTTP (HttpURLConnection, как у релизов прошивки).

/** Файл словаря в каталоге. */
data class CatalogFile(val name: String, val size: Long, val sha256: String)

/** Словарь в каталоге. `id` — имя папки на читалке. */
data class CatalogEntry(
    val id: String,
    /** Название по языкам интерфейса ("en", "ru"). */
    val titles: Map<String, String>,
    val from: String,
    val to: String,
    val headwords: Int,
    val license: String,
    val attribution: String,
    val source: String,
    val files: List<CatalogFile>,
) {
    val totalSize: Long get() = files.sumOf { it.size }

    /** Название на языке `lang`, иначе английское, иначе любое, иначе id. */
    fun title(lang: String): String =
        titles[lang]?.takeIf { it.isNotBlank() }
            ?: titles["en"]?.takeIf { it.isNotBlank() }
            ?: titles.values.firstOrNull { it.isNotBlank() }
            ?: id
}

data class CatalogManifest(val version: Int, val dictionaries: List<CatalogEntry>)

/** Ошибки каталога и скачивания. Текст для человека — в UI. */
sealed class CatalogError(message: String) : Exception(message) {
    /** Ответ сервера не 200 (404 — каталог ещё не опубликован). */
    data class Http(val code: Int) : CatalogError("HTTP $code")
    data object BadManifest : CatalogError("bad manifest")
    /** Скачанный файл не совпал с sha256 из каталога. */
    data class Checksum(val name: String) : CatalogError("sha256 mismatch: $name")
    data class SizeMismatch(val name: String, val expected: Long, val actual: Long) : CatalogError("size $actual != $expected: $name")
    data class TooLarge(val name: String) : CatalogError("too large: $name")
}

object DictionaryCatalog {
    const val DEFAULT_MANIFEST_URL =
        "https://github.com/guloff/folio-firmware/releases/download/dictionaries-1/manifest.json"
    const val SUPPORTED_VERSION = 1
    /** Больше ни один файл каталога не весит (страховка от чужого URL). */
    const val MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024

    private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Разбор manifest.json. Записи с недопустимым id (не имя папки), без файлов или с плохим
     * файлом пропускаются; не JSON, нет списка или версия новее понятной → [CatalogError.BadManifest].
     */
    fun parse(text: String): CatalogManifest {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw CatalogError.BadManifest
        val version = root["version"].lenientInt() ?: SUPPORTED_VERSION
        if (version > SUPPORTED_VERSION) throw CatalogError.BadManifest
        val list = root.array("dictionaries") ?: throw CatalogError.BadManifest
        val entries = list.mapNotNull { (it as? JsonObject)?.let(::entry) }
        return CatalogManifest(version, entries.distinctBy { it.id.lowercase() })
    }

    private fun entry(o: JsonObject): CatalogEntry? {
        val id = o["id"].lenientString()?.trim().orEmpty()
        if (!DictionaryFolder.isValid(id)) return null
        val titles: Map<String, String> = when (val t = o["title"]) {
            is JsonObject -> t.mapNotNull { (k, v) -> v.lenientString()?.let { k to it } }.toMap()
            is JsonPrimitive -> t.lenientString()?.let { mapOf("en" to it) }.orEmpty()
            else -> emptyMap()
        }
        val files = o.array("files").orEmpty().map { f ->
            val fo = f as? JsonObject ?: return null
            val name = fo["name"].lenientString()?.trim().orEmpty()
            val sha = fo["sha256"].lenientString()?.trim().orEmpty()
            val size = fo["size"].lenientLong() ?: -1
            if (!isSafeFileName(name) || StarDictPart.of(name) == null || !SHA256_HEX.matches(sha) || size < 0) return null
            CatalogFile(name, size, sha.lowercase())
        }
        if (files.isEmpty()) return null
        return CatalogEntry(
            id = id,
            titles = titles,
            from = o["from"].lenientString().orEmpty(),
            to = o["to"].lenientString().orEmpty(),
            headwords = o["headwords"].lenientInt() ?: 0,
            license = o["license"].lenientString().orEmpty(),
            attribution = o["attribution"].lenientString().orEmpty(),
            source = o["source"].lenientString().orEmpty(),
            files = files,
        )
    }

    /** Имя файла без пути и без ведущей точки. */
    fun isSafeFileName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith('.') && '/' !in name && '\\' !in name && name.none { it.isISOControl() }

    /** URL файла рядом с манифестом: та же папка + имя файла. */
    fun fileUrl(manifestUrl: String, fileName: String): String =
        URI(manifestUrl.trim()).resolve(PercentCoding.encode(fileName)).toString()

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** Скачанный файл совпадает с каталогом: размер и sha256. Иначе — [CatalogError]. */
    fun verify(file: CatalogFile, data: ByteArray) {
        if (data.size.toLong() != file.size) throw CatalogError.SizeMismatch(file.name, file.size, data.size.toLong())
        if (sha256Hex(data) != file.sha256.lowercase()) throw CatalogError.Checksum(file.name)
    }

    /**
     * GET с переадресациями (GitHub → CDN) и байтовым прогрессом. Не 200 → [CatalogError.Http];
     * больше [limit] → [CatalogError.TooLarge]. Сетевые сбои — исключения java.net.
     */
    suspend fun fetch(
        url: String,
        limit: Long = MAX_DOWNLOAD_BYTES,
        progress: TransferProgress? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "FolioLink-Android")
            val status = conn.responseCode
            if (status != 200) throw CatalogError.Http(status)
            val total = conn.contentLengthLong
            if (total > limit) throw CatalogError.TooLarge(url.substringAfterLast('/'))
            val name = url.substringAfterLast('/')
            conn.inputStream.use { input ->
                progress?.invoke(0, total)
                if (total in 0..limit) {
                    // Размер известен: читаем прямо в массив точного размера, без копии (файлы до ~30 МБ).
                    val data = ByteArray(total.toInt())
                    var done = 0
                    while (done < data.size) {
                        coroutineContext.ensureActive()
                        val n = input.read(data, done, minOf(64 * 1024, data.size - done))
                        if (n < 0) throw java.io.EOFException("$name: $done of $total bytes")
                        done += n
                        progress?.invoke(done.toLong(), total)
                    }
                    if (input.read() >= 0) throw CatalogError.TooLarge(name)
                    data
                } else {
                    val out = ByteArrayOutputStream(64 * 1024)
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > limit) throw CatalogError.TooLarge(name)
                        progress?.invoke(done, total)
                    }
                    out.toByteArray()
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Манифест по URL. */
    suspend fun load(manifestUrl: String): CatalogManifest {
        val data = fetch(manifestUrl, MAX_MANIFEST_BYTES)
        return parse(data.toString(Charsets.UTF_8))
    }
}
