package com.rustamguloff.foliolink.ui.library

import com.rustamguloff.foliolink.app.AppModel.SubmitResult
import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.BookFiles
import com.rustamguloff.foliolink.core.model.BookStatus
import com.rustamguloff.foliolink.core.model.OutboxItem
import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.model.Shelf
import java.text.Collator
import java.util.Locale

/** Порядок списка книг. [RECENT] — как в iOS (`LibraryView.libraryOrder`). */
enum class LibrarySort { RECENT, TITLE, AUTHOR, PROGRESS }

/** Фильтр библиотеки: статус (null — все), полка (null — все), строка поиска. */
data class LibraryFilter(
    val status: BookStatus? = null,
    val shelf: String? = null,
    val search: String = "",
    val sort: LibrarySort = LibrarySort.RECENT,
)

/** Чистая логика экрана «Библиотека»: фильтрация, поиск, сортировка, итоги загрузки. */
object LibraryLogic {

    /**
     * MIME-типы для системного выбора файлов. octet-stream — EPUB/FB2 у части файловых менеджеров;
     * xml и zip — FB2 и FB2.zip. Выбранное потом фильтруется по имени ([isImportable]):
     * произвольный .zip или .xml не пройдёт.
     */
    val IMPORT_MIME_TYPES = arrayOf(
        "application/epub+zip", "text/plain", "text/markdown", "text/x-markdown", "application/octet-stream",
        "application/x-fictionbook+xml", "application/x-fictionbook", "text/xml", "application/xml",
        "application/zip", "application/x-zip-compressed",
    )

    /** EPUB, FB2, FB2.zip, TXT, MD — по полному имени без учёта регистра (`.fb2.zip`, а не `.zip`). */
    fun isImportable(fileName: String): Boolean = BookFiles.format(fileName) != null

    /**
     * Книги, которые показывает список: фильтр статуса, полки (по документу полок или по полю
     * `shelves` книги), поиск по названию и автору без учёта регистра; скрыты книги, удаление
     * которых ждёт в очереди.
     */
    fun filter(
        books: List<Book>,
        filter: LibraryFilter,
        shelves: List<Shelf>,
        hidden: Set<String> = emptySet(),
        collator: Collator = defaultCollator(),
    ): List<Book> {
        val shelfBooks = filter.shelf?.let { id -> shelves.firstOrNull { it.id == id }?.books?.toSet() ?: emptySet() }
        val q = filter.search.trim()
        return books.filter { b ->
            b.path !in hidden &&
                (filter.status == null || b.bookStatus == filter.status) &&
                (filter.shelf == null || b.path in shelfBooks.orEmpty() || filter.shelf in b.shelves) &&
                (q.isEmpty() || b.displayTitle.contains(q, ignoreCase = true) || b.author.contains(q, ignoreCase = true))
        }.sortedWith(comparator(filter.sort, collator))
    }

    fun comparator(sort: LibrarySort, collator: Collator = defaultCollator()): Comparator<Book> {
        val byTitle = Comparator<Book> { a, b -> collator.compare(a.displayTitle, b.displayTitle) }
        return when (sort) {
            // Сначала недавно читанные; затем «Недавние» самой читалки; затем по алфавиту.
            LibrarySort.RECENT -> compareByDescending<Book> { it.lastRead }
                .thenBy { if (it.recent >= 0) it.recent else Int.MAX_VALUE }
                .then(byTitle)
            LibrarySort.TITLE -> byTitle
            // Без автора — в конце.
            LibrarySort.AUTHOR -> compareBy<Book> { it.author.isBlank() }
                .then { a, b -> collator.compare(a.author, b.author) }
                .then(byTitle)
            // Больший прогресс выше; неначатые (-1) — в конце.
            LibrarySort.PROGRESS -> compareByDescending<Book> { it.progress }.then(byTitle)
        }
    }

    fun defaultCollator(locale: Locale = Locale.getDefault()): Collator =
        Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    /** Пути книг, удаление которых стоит в очереди (не отклонено): их не показываем. */
    fun pendingDeletes(outbox: List<OutboxItem>): Set<String> =
        outbox.filter { !it.failed }.mapNotNull { (it.operation as? OutboxOperation.DeleteFile)?.path }.toSet()

    /** Сводка по книге для карточки: время, сессии, страницы, оценка остатка. */
    data class BookStats(val seconds: Int, val sessions: Int, val pages: Int, val remainingSeconds: Int?)

    fun bookStats(book: Book, sessions: List<ReadingSession>): BookStats {
        val own = sessions.filter { it.book == book.path }
        val secs = maxOf(book.secs, own.sumOf { it.secs })
        val remaining = if (secs > 0 && book.progress in 1..99) secs * (100 - book.progress) / maxOf(book.progress, 1) else null
        return BookStats(secs, own.size, own.sumOf { it.pages }, remaining)
    }

    /** Итог загрузки нескольких файлов: какое сообщение показать (null — хватит баннеров модели). */
    sealed interface UploadSummary {
        data class Unreadable(val names: List<String>) : UploadSummary
        data class Unsupported(val names: List<String>) : UploadSummary
        data class Sent(val count: Int) : UploadSummary
        data class Queued(val count: Int) : UploadSummary
    }

    /**
     * Как `LibraryView.upload` в iOS: нечитаемые файлы важнее всего; несколько отправленных —
     * «Отправлено книг: N»; больше одного в очереди — «В очереди отправки: N».
     * `results`: имя файла → итог (null — файл не прочитан).
     */
    fun uploadSummary(total: Int, results: List<Pair<String, SubmitResult?>>, unsupported: List<String>): UploadSummary? {
        val unreadable = results.filter { it.second == null }.map { it.first }
        val sent = results.count { it.second == SubmitResult.SENT }
        val queued = results.count { it.second == SubmitResult.QUEUED }
        return when {
            unreadable.isNotEmpty() -> UploadSummary.Unreadable(unreadable)
            unsupported.isNotEmpty() -> UploadSummary.Unsupported(unsupported)
            total > 1 && sent > 0 -> UploadSummary.Sent(sent)
            queued > 1 -> UploadSummary.Queued(queued)
            else -> null
        }
    }
}
