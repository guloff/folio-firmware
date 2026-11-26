package com.rustamguloff.foliolink.core.dictionaries

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

// Набор файлов StarDict для читалки (Folio 1.1): проверка, распаковка .idx.gz, имена на карте.
// Чистый Kotlin, проверяется JVM-тестами.

/** Часть набора StarDict по расширению. Длинные расширения проверяются раньше коротких. */
enum class StarDictPart(val ext: String) {
    IFO(".ifo"),
    IDX_GZ(".idx.gz"),
    IDX(".idx"),
    DICT_DZ(".dict.dz"),
    DICT(".dict"),
    SYN(".syn"),
    ;

    companion object {
        fun of(fileName: String): StarDictPart? {
            val n = fileName.lowercase()
            return entries.firstOrNull { n.endsWith(it.ext) && n.length > it.ext.length }
        }
    }
}

/** Почему набор файлов не годится. Текст для человека — в UI. */
sealed class StarDictError(message: String) : Exception(message) {
    data object NoFiles : StarDictError("no files")
    /** Файл не из набора StarDict (архив, .syn.dz, посторонний файл). */
    data class Unsupported(val name: String) : StarDictError("unsupported file $name")
    /** Файлы разных словарей: у набора должна быть одна основа имени. */
    data class MixedStems(val stems: List<String>) : StarDictError("several stems: $stems")
    /** Одна часть пришла дважды (например, .idx и .idx.gz). */
    data class Duplicate(val part: String) : StarDictError("duplicate $part")
    data object MissingIfo : StarDictError("no .ifo")
    data object MissingIdx : StarDictError("no .idx")
    data object MissingDict : StarDictError("no .dict")
    /** .ifo не начинается с «StarDict's dict ifo file». */
    data object BadIfo : StarDictError("bad .ifo")
    /** Сжатый файл не распаковывается (или .dict.dz — не gzip). */
    data class BadArchive(val name: String) : StarDictError("bad gzip $name")
    /** Распакованный .idx не совпадает с `idxfilesize` из .ifo — файл обрезан или чужой. */
    data class IndexSizeMismatch(val expected: Long, val actual: Long) : StarDictError("idx $actual != $expected")
    data class Empty(val name: String) : StarDictError("empty $name")
    data class TooLarge(val name: String) : StarDictError("too large $name")
}

/** Сведения из .ifo. */
data class StarDictIfo(
    val bookname: String?,
    val wordcount: Int?,
    val idxFileSize: Long?,
    val sameTypeSequence: String?,
    val lang: String?,
    val values: Map<String, String>,
)

/** Файл, готовый к загрузке: имя на карте и содержимое. */
class StarDictFile(val part: StarDictPart, val name: String, val data: ByteArray) {
    val size: Int get() = data.size
}

/** Проверенный набор: основа исходных имён, название и число слов из .ifo, файлы для читалки. */
class StarDictSet(
    val stem: String,
    val title: String,
    val words: Int,
    /** Файлы с исходными расширениями, но уже распакованные (.idx.gz → .idx). Порядок загрузки. */
    private val parts: List<Pair<StarDictPart, ByteArray>>,
) {
    val totalBytes: Long get() = parts.sumOf { it.second.size.toLong() }

    /** Файлы для папки `folder`: основа имени = имя папки (ASCII, как ждёт прошивка). */
    fun files(folder: String): List<StarDictFile> = parts.map { (part, data) -> StarDictFile(part, folder + part.ext, data) }
}

object StarDict {
    const val IFO_MAGIC = "StarDict's dict ifo file"
    /** Предел одного файла после распаковки (всё держится в памяти до загрузки). */
    const val MAX_FILE_BYTES = 256L * 1024 * 1024

    /** Основа имени без расширения части: "en-ru.idx.gz" → "en-ru". */
    fun stem(fileName: String): String {
        val base = fileName.substringAfterLast('/')
        val part = StarDictPart.of(base) ?: return base
        return base.dropLast(part.ext.length)
    }

    fun isGzip(data: ByteArray): Boolean =
        data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()

    /** Разбирает .ifo; null — это не .ifo StarDict. */
    fun parseIfo(data: ByteArray): StarDictIfo? {
        var text = data.toString(Charsets.UTF_8)
        if (text.startsWith('\uFEFF')) text = text.substring(1)
        val lines = text.split('\n').map { it.trimEnd('\r') }
        if (lines.firstOrNull()?.trim() != IFO_MAGIC) return null
        val values = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            values.putIfAbsent(line.substring(0, i).trim(), line.substring(i + 1).trim())
        }
        return StarDictIfo(
            bookname = values["bookname"]?.takeIf { it.isNotEmpty() },
            wordcount = values["wordcount"]?.toIntOrNull(),
            idxFileSize = values["idxfilesize"]?.toLongOrNull(),
            sameTypeSequence = values["sametypesequence"],
            lang = values["lang"] ?: values["sourceLang"],
            values = values,
        )
    }

    /**
     * Распаковывает gzip (в том числе склеенные члены) с пределом размера.
     * Не gzip или битый поток → [StarDictError.BadArchive]; больше [limit] → [StarDictError.TooLarge].
     */
    fun gunzip(data: ByteArray, name: String = "", limit: Long = MAX_FILE_BYTES): ByteArray {
        if (!isGzip(data)) throw StarDictError.BadArchive(name)
        return try {
            GZIPInputStream(ByteArrayInputStream(data), 64 * 1024).use { input ->
                val out = ByteArrayOutputStream(maxOf(data.size * 3, 1024))
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw StarDictError.TooLarge(name)
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            throw StarDictError.BadArchive(name)
        }
    }

    /**
     * Проверяет выбранные файлы и готовит набор: одна основа, есть .ifo, .idx (или .idx.gz),
     * .dict (или .dict.dz), необязательный .syn. .idx.gz распаковывается; размер .idx сверяется
     * с `idxfilesize`. Ошибка — [StarDictError].
     */
    fun prepare(files: List<Pair<String, ByteArray>>): StarDictSet {
        // Служебные файлы macOS при копировании с карты/из архива — не часть словаря.
        val picked = files.filter { (name, _) ->
            val base = name.substringAfterLast('/')
            !base.startsWith("._") && base != ".DS_Store"
        }
        if (picked.isEmpty()) throw StarDictError.NoFiles
        val byPart = LinkedHashMap<StarDictPart, Pair<String, ByteArray>>()
        for ((name, data) in picked) {
            val part = StarDictPart.of(name) ?: throw StarDictError.Unsupported(name.substringAfterLast('/'))
            val slot = when (part) {
                StarDictPart.IDX_GZ -> StarDictPart.IDX
                StarDictPart.DICT_DZ -> StarDictPart.DICT
                else -> part
            }
            if (byPart.containsKey(slot)) throw StarDictError.Duplicate(slot.ext)
            byPart[slot] = name to data
        }
        val stems = picked.map { stem(it.first) }.distinctBy { it.lowercase() }
        if (stems.size > 1) throw StarDictError.MixedStems(stems.sorted())

        val (ifoName, ifoData) = byPart[StarDictPart.IFO] ?: throw StarDictError.MissingIfo
        val (idxName, idxRaw) = byPart[StarDictPart.IDX] ?: throw StarDictError.MissingIdx
        val (dictName, dictData) = byPart[StarDictPart.DICT] ?: throw StarDictError.MissingDict
        val ifo = parseIfo(ifoData) ?: throw StarDictError.BadIfo

        // Прошивке нужен несжатый .idx: .idx.gz (или .idx, который на деле gzip) распаковывается.
        val idx = if (StarDictPart.of(idxName) == StarDictPart.IDX_GZ || isGzip(idxRaw)) gunzip(idxRaw, idxName) else idxRaw
        if (idx.isEmpty()) throw StarDictError.Empty(idxName)
        ifo.idxFileSize?.let { if (it != idx.size.toLong()) throw StarDictError.IndexSizeMismatch(it, idx.size.toLong()) }

        val dictPart = StarDictPart.of(dictName)!!
        if (dictData.isEmpty()) throw StarDictError.Empty(dictName)
        if (dictPart == StarDictPart.DICT_DZ && !isGzip(dictData)) throw StarDictError.BadArchive(dictName)

        val parts = mutableListOf(StarDictPart.IFO to ifoData, StarDictPart.IDX to idx)
        byPart[StarDictPart.SYN]?.let { (synName, syn) ->
            if (syn.isEmpty()) throw StarDictError.Empty(synName)
            parts += StarDictPart.SYN to syn
        }
        parts += dictPart to dictData

        val stem = stems.first()
        return StarDictSet(
            stem = stem,
            title = ifo.bookname ?: stem,
            words = ifo.wordcount ?: 0,
            parts = parts,
        )
    }
}

/** Имя папки словаря на карте: ASCII `[A-Za-z0-9._-]`, без ведущей точки, не длиннее 31 символа. */
object DictionaryFolder {
    const val MAX_LENGTH = 31
    const val FALLBACK = "dictionary"
    private const val ALLOWED = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-"

    /** Годится ли имя как есть (то же правило, что у прошивки; `*` и `-` — режимы, не папки). */
    fun isValid(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_LENGTH && !name.startsWith('.') && name != "-" &&
            name.all { it in ALLOWED }

    /**
     * Безопасное имя из произвольной строки: кириллица — латиницей, прочее — «-», повторы
     * схлопываются, края без точек и дефисов, не длиннее 31; пусто — "dictionary".
     */
    fun sanitize(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw.trim()) {
            when {
                ch in ALLOWED -> sb.append(ch)
                ch.isWhitespace() -> sb.append('-')
                else -> {
                    val t = translit(ch)
                    if (t != null) sb.append(t) else sb.append('-')
                }
            }
        }
        var s = sb.toString()
        s = s.replace(Regex("[-]{2,}"), "-").replace(Regex("[.]{2,}"), ".")
        s = trimEdges(s)
        if (s.length > MAX_LENGTH) s = trimEdges(s.take(MAX_LENGTH))
        return if (isValid(s)) s else FALLBACK
    }

    /** [base], а если такая папка уже есть (без учёта регистра — FAT) — base-2, base-3… в пределах 31 символа. */
    fun unique(base: String, existing: Collection<String>): String {
        val taken = existing.map { it.lowercase() }.toSet()
        val clean = if (isValid(base)) base else sanitize(base)
        if (clean.lowercase() !in taken) return clean
        var n = 2
        while (true) {
            val suffix = "-$n"
            val candidate = trimEdges(clean.take(MAX_LENGTH - suffix.length)) + suffix
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }

    private fun trimEdges(s: String): String = s.trimStart('.', '-', '_').trimEnd('.', '-', '_')

    private fun translit(ch: Char): String? {
        val lower = ch.lowercaseChar()
        val t = CYRILLIC[lower] ?: return null
        if (t.isEmpty()) return ""
        return if (ch != lower) t.replaceFirstChar { it.uppercaseChar() } else t
    }

    private val CYRILLIC: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh",
        'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu",
        'я' to "ya", 'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g", 'ў' to "u", 'ғ' to "gh", 'ӣ' to "i",
        'қ' to "q", 'ӯ' to "u", 'ҳ' to "h", 'ҷ' to "j",
    )
}
