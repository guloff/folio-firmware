package com.rustamguloff.foliolink.core.export

import com.rustamguloff.foliolink.core.epub.FileNames
import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.Highlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Экспорт выделений в Markdown для Obsidian: файл на книгу с YAML front matter, цитатами,
 * заметками-выносками `> [!note]` и якорями блоков `^id`.
 */
object MarkdownExporter {

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.US)

    /** Строка YAML в двойных кавычках. */
    fun yaml(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun markdown(
        bookTitle: String,
        author: String,
        path: String,
        highlights: List<Highlight>,
        exportedMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        fun day(epochMs: Long) = DAY.format(Instant.ofEpochMilli(epochMs).atZone(zone))
        val out = StringBuilder("---\n")
        out.append("title: ${yaml(bookTitle)}\n")
        if (author.isNotEmpty()) out.append("author: ${yaml(author)}\n")
        out.append("source: ${yaml(path)}\n")
        out.append("type: book-highlights\n")
        out.append("highlights: ${highlights.size}\n")
        out.append("exported: ${day(exportedMs)}\n")
        out.append("tags:\n  - foliolink\n  - highlights\n")
        out.append("---\n\n")
        out.append("# $bookTitle\n\n")
        if (author.isNotEmpty()) out.append("*$author*\n\n")
        for (h in highlights.sortedWith(compareBy({ it.pct }, { it.created }))) {
            out.append(h.text.split("\n").joinToString("\n") { "> $it" }).append('\n')
            val meta = ArrayList<String>()
            if (h.chapter.isNotEmpty()) meta += h.chapter
            if (h.pct >= 0) meta += "${h.pct}%"
            if (h.created > 0) meta += day(h.created * 1000)
            if (meta.isNotEmpty()) out.append(">\n> — ").append(meta.joinToString(" · ")).append('\n')
            out.append('^').append(anchor(h.key)).append('\n')
            if (h.note.isNotEmpty()) {
                out.append("\n> [!note]\n").append(h.note.split("\n").joinToString("\n") { "> $it" }).append('\n')
            }
            out.append('\n')
        }
        return out.toString()
    }

    /** Id блока Obsidian: только буквы, цифры и дефисы. */
    fun anchor(id: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < id.length) {
            val cp = id.codePointAt(i)
            if (Character.isLetterOrDigit(cp) || cp == '-'.code) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return if (sb.isEmpty()) "h" else sb.toString()
    }

    /** "Название", затем "Название (2)", "Название (3)"… для книг с одинаковым названием. */
    fun uniqueName(base: String, used: MutableSet<String>): String {
        var name = base
        var n = 2
        while (name.lowercase() in used) {
            name = "$base ($n)"
            n++
        }
        used += name.lowercase()
        return name
    }

    /**
     * Пишет по .md на книгу в новую папку `FolioLinkExport-<uuid>` внутри `parentDir`
     * (например, cacheDir для «Поделиться») и возвращает файлы.
     */
    suspend fun exportFiles(
        highlights: List<Highlight>,
        books: List<Book>,
        parentDir: File,
        exportedMs: Long = System.currentTimeMillis(),
    ): List<File> = withContext(Dispatchers.IO) {
        val dir = File(parentDir, "FolioLinkExport-${UUID.randomUUID()}")
        if (!dir.mkdirs() && !dir.isDirectory) throw java.io.IOException("cannot create $dir")
        val used = HashSet<String>()
        highlights.groupBy { it.book }.toSortedMap().map { (path, items) ->
            val book = books.firstOrNull { it.path == path }
            val title = book?.displayTitle ?: items.firstOrNull()?.title?.takeIf { it.isNotEmpty() }
                ?: path.substringAfterLast('/')
            val md = markdown(title, book?.author ?: "", path, items, exportedMs)
            val file = File(dir, uniqueName(FileNames.sanitize(title), used) + ".md")
            file.writeText(md, Charsets.UTF_8)
            file
        }
    }
}
