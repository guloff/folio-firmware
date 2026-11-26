package com.rustamguloff.foliolink.ui.screenshots

import com.rustamguloff.foliolink.core.model.RemoteFile

/** Порядок и группировка скриншотов для сетки (чистая логика, проверяется JVM-тестами). */
object ScreenshotGroups {
    /** Группа: книга (подпапка /screenshots/<название>/) или null — снимки вне книг. */
    data class Group(val book: String?, val files: List<RemoteFile>) {
        val key: String get() = book ?: ""
    }

    /** Сначала новые; без времени — в конце, по имени (как RemoteFile.newestFirst в iOS). */
    val newestFirst: Comparator<RemoteFile> = Comparator { a, b ->
        val x = a.capturedAt
        val y = b.capturedAt
        when {
            x != null && y != null -> y.compareTo(x)
            x != null -> -1
            y != null -> 1
            else -> b.name.compareTo(a.name)
        }
    }

    fun sorted(files: List<RemoteFile>): List<RemoteFile> = files.sortedWith(newestFirst)

    /**
     * По книгам: группы идут по самому свежему снимку в них, внутри — сначала новые.
     * Снимки без книги — своей группой, на общих правах.
     */
    fun group(files: List<RemoteFile>): List<Group> {
        val byBook = LinkedHashMap<String?, MutableList<RemoteFile>>()
        for (f in sorted(files)) byBook.getOrPut(f.bookTitle) { mutableListOf() } += f
        return byBook.map { (book, list) -> Group(book, list) }
    }

    /** Заголовки нужны, когда есть хотя бы одна книга (одна безымянная группа — просто сетка). */
    fun showsHeaders(groups: List<Group>): Boolean = groups.any { it.book != null }
}
