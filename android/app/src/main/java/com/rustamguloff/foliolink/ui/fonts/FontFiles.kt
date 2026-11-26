package com.rustamguloff.foliolink.ui.fonts

import com.rustamguloff.foliolink.core.model.FileEntry
import com.rustamguloff.foliolink.core.model.FontFamily
import java.nio.charset.Charset

// Разбор файлов шрифтов (TTF/OTF/TTC) и правила имён — чистый Kotlin, проверяется JVM-тестами.

/** Начертание файла в папке семейства: `<Семейство>-<suffix>.<ext>` (так их ищет прошивка). */
enum class FontStyle(val suffix: String) {
    REGULAR("Regular"), BOLD("Bold"), ITALIC("Italic"), BOLD_ITALIC("BoldItalic");

    val isBold: Boolean get() = this == BOLD || this == BOLD_ITALIC
    val isItalic: Boolean get() = this == ITALIC || this == BOLD_ITALIC

    companion object {
        fun of(bold: Boolean, italic: Boolean): FontStyle = when {
            bold && italic -> BOLD_ITALIC
            bold -> BOLD
            italic -> ITALIC
            else -> REGULAR
        }
    }
}

/** Откуда взято начертание (для тестов и отладки). */
enum class StyleSource { OS2, HEAD, NAME_TABLE, FILE_NAME, DEFAULT }

data class FontFileInfo(
    /** Семейство из таблицы name (ID 16, иначе 1) или из имени файла. */
    val family: String,
    val style: FontStyle,
    val styleSource: StyleSource,
)

object FontInspector {
    val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
    const val CPFONT = "cpfont"

    fun extension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    fun isCpfont(fileName: String): Boolean = extension(fileName) == CPFONT

    /**
     * Семейство и начертание. Приоритет начертания: OS/2.fsSelection (+ usWeightClass ≥ 700),
     * head.macStyle, подсемейство из таблицы name, имя файла. null — данные не похожи на шрифт sfnt.
     */
    fun inspect(data: ByteArray, fileName: String): FontFileInfo? {
        val sfnt = Sfnt.open(data) ?: return null
        val os2 = sfnt.table("OS/2")?.takeIf { it.length >= 64 }
        val head = sfnt.table("head")?.takeIf { it.length >= 46 }
        val names = sfnt.table("name")?.let { sfnt.names(it) }.orEmpty()

        val family = (names[16] ?: names[1])?.trim()?.takeIf { it.isNotEmpty() } ?: familyFromFileName(fileName)

        val (style, source) = when {
            os2 != null -> {
                val fsSelection = sfnt.u16(os2.offset + 62)
                val weight = sfnt.u16(os2.offset + 4)
                val italic = fsSelection and 0x0001 != 0 || fsSelection and 0x0200 != 0
                val bold = fsSelection and 0x0020 != 0 || weight >= 700
                FontStyle.of(bold, italic) to StyleSource.OS2
            }
            head != null -> {
                val mac = sfnt.u16(head.offset + 44)
                FontStyle.of(mac and 1 != 0, mac and 2 != 0) to StyleSource.HEAD
            }
            else -> {
                val sub = names[17] ?: names[2]
                sub?.let { styleFromName(it) }?.let { it to StyleSource.NAME_TABLE }
                    ?: styleFromName(fileName.substringBeforeLast('.'))?.let { it to StyleSource.FILE_NAME }
                    ?: (FontStyle.REGULAR to StyleSource.DEFAULT)
            }
        }
        return FontFileInfo(family, style, source)
    }

    /** Начертание по имени ("Literata-BoldItalic", "Bold Oblique"); null — не понятно. */
    fun styleFromName(name: String): FontStyle? {
        val n = name.lowercase().filter { it.isLetter() }
        val bold = "bold" in n || "black" in n || "heavy" in n
        val italic = "italic" in n || "oblique" in n
        return when {
            bold || italic -> FontStyle.of(bold, italic)
            "regular" in n || "book" in n || "roman" in n || "normal" in n -> FontStyle.REGULAR
            else -> null
        }
    }

    /** "Literata-BoldItalic.ttf" → "Literata"; "PT Serif.ttf" → "PT Serif". */
    fun familyFromFileName(fileName: String): String {
        val stem = fileName.substringAfterLast('/').substringBeforeLast('.')
        val cut = stem.lastIndexOfAny(charArrayOf('-', '_'))
        if (cut > 0 && styleFromName(stem.substring(cut + 1)) != null) return stem.substring(0, cut)
        return stem.ifEmpty { "Font" }
    }

    /** Имя папки семейства: ASCII-буквы, цифры, «_» вместо прочего; пусто — "Font". */
    fun safeFamily(family: String): String {
        val mapped = family.map { c -> if (c.code < 128 && c.isLetterOrDigit()) c else '_' }.joinToString("")
        var s = mapped
        while ("__" in s) s = s.replace("__", "_")
        s = s.trim('_')
        return s.ifEmpty { "Font" }
    }

    /** Имя файла на читалке. .cpfont — как есть (прошивка читает имя), остальное — `<папка>-<стиль>.<ext>`. */
    fun targetName(folder: String, originalName: String, style: FontStyle): String {
        if (isCpfont(originalName)) return safeFileName(originalName)
        val ext = extension(originalName).ifEmpty { "ttf" }
        return "$folder-${style.suffix}.$ext"
    }

    private fun safeFileName(name: String): String =
        name.substringAfterLast('/').map { c -> if (c.code < 128 && (c.isLetterOrDigit() || c in "._-")) c else '_' }.joinToString("")

    /** Имена, которые встречаются больше одного раза (файлы затрут друг друга). */
    fun duplicates(names: List<String>): Set<String> =
        names.groupBy { it.lowercase() }.filterValues { it.size > 1 }.values.map { it.first() }.toSet()

    /**
     * Семейства на карте: из `/api/fonts` и из папки `/fonts` (TTF-семейства, которыми
     * `/api/fonts` не управляет). Совпадения без учёта регистра не дублируются.
     */
    fun allFamilies(families: List<FontFamily>, entries: List<FileEntry>): List<String> {
        val names = families.map { it.name }.toMutableList()
        for (f in fontEntries(entries)) {
            val name = if (f.isDirectory) f.name else f.name.substringBeforeLast('.', f.name)
            if (names.none { it.equals(name, ignoreCase = true) || it.equals(f.name, ignoreCase = true) }) names += name
        }
        return names.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /** Папки и файлы шрифтов в `/fonts`. */
    fun fontEntries(entries: List<FileEntry>): List<FileEntry> =
        entries.filter { it.isDirectory || extension(it.name) in FONT_EXTENSIONS + CPFONT }
}

/** Минимальное чтение sfnt: каталог таблиц и строки таблицы name. */
internal class Sfnt private constructor(private val data: ByteArray, private val tables: Map<String, Table>) {
    data class Table(val offset: Int, val length: Int)

    fun table(tag: String): Table? = tables[tag]

    fun u16(at: Int): Int =
        if (at < 0 || at + 2 > data.size) 0 else ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    /** nameID → строка; предпочтение: Windows/английский, затем Unicode, затем Mac. */
    fun names(t: Table): Map<Int, String> {
        val base = t.offset
        val count = u16(base + 2)
        val strings = base + u16(base + 4)
        data class Candidate(val rank: Int, val text: String)
        val best = HashMap<Int, Candidate>()
        for (i in 0 until count) {
            val rec = base + 6 + i * 12
            if (rec + 12 > data.size) break
            val platform = u16(rec)
            val encoding = u16(rec + 2)
            val language = u16(rec + 4)
            val nameId = u16(rec + 6)
            val length = u16(rec + 8)
            val offset = strings + u16(rec + 10)
            if (offset < 0 || offset + length > data.size || length == 0) continue
            val (rank, charset) = when {
                platform == 3 && (encoding == 1 || encoding == 10 || encoding == 0) ->
                    (if (language == 0x0409) 0 else 1) to Charsets.UTF_16BE
                platform == 0 -> 2 to Charsets.UTF_16BE
                platform == 1 && encoding == 0 -> 3 to MAC_ROMAN
                else -> continue
            }
            val text = String(data, offset, length, charset).trim { it.isWhitespace() || it == '\u0000' }
            if (text.isEmpty()) continue
            val cur = best[nameId]
            if (cur == null || rank < cur.rank) best[nameId] = Candidate(rank, text)
        }
        return best.mapValues { it.value.text }
    }

    companion object {
        private val MAC_ROMAN: Charset = runCatching { Charset.forName("x-MacRoman") }.getOrDefault(Charsets.ISO_8859_1)

        fun open(data: ByteArray): Sfnt? {
            if (data.size < 12) return null
            fun u32(at: Int): Long = if (at < 0 || at + 4 > data.size) -1 else
                ((data[at].toLong() and 0xFF) shl 24) or ((data[at + 1].toLong() and 0xFF) shl 16) or
                    ((data[at + 2].toLong() and 0xFF) shl 8) or (data[at + 3].toLong() and 0xFF)
            fun u16(at: Int): Int = if (at + 2 > data.size) 0 else
                ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

            var start = 0
            // Коллекция TTC: берётся первый шрифт.
            if (String(data, 0, 4, Charsets.ISO_8859_1) == "ttcf") {
                val first = u32(12)
                if (first < 0 || first + 12 > data.size) return null
                start = first.toInt()
            }
            val version = u32(start)
            val tag = String(data, start, 4, Charsets.ISO_8859_1)
            if (version != 0x00010000L && tag != "OTTO" && tag != "true" && tag != "typ1") return null
            val numTables = u16(start + 4)
            if (numTables == 0 || start + 12 + numTables * 16 > data.size) return null
            val tables = HashMap<String, Table>()
            for (i in 0 until numTables) {
                val rec = start + 12 + i * 16
                val name = String(data, rec, 4, Charsets.ISO_8859_1)
                val offset = u32(rec + 8)
                val length = u32(rec + 12)
                if (offset < 0 || length < 0 || offset + length > data.size) continue
                tables[name] = Table(offset.toInt(), length.toInt())
            }
            return Sfnt(data, tables)
        }
    }
}
