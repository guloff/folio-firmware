package com.rustamguloff.foliolink.ui.library

import com.rustamguloff.foliolink.app.AppModel.SubmitResult
import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.BookStatus
import com.rustamguloff.foliolink.core.model.OutboxItem
import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.model.Shelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LibraryLogicTest {
    private val ru = LibraryLogic.defaultCollator(Locale.forLanguageTag("ru"))

    private val dune = Book(path = "/Books/Dune.epub", title = "Dune", author = "Frank Herbert", progress = 40,
        lastRead = 300, status = "reading", recent = 0)
    private val war = Book(path = "/Books/war.epub", title = "Война и мир", author = "Лев Толстой", progress = 100,
        lastRead = 100, status = "done", shelves = listOf("classics"))
    private val anna = Book(path = "/Books/anna.epub", title = "Анна Каренина", author = "Лев Толстой", progress = -1,
        status = "want")
    private val noTitle = Book(path = "/Books/zettel.txt", progress = 5)
    private val all = listOf(anna, war, noTitle, dune)
    private val shelves = listOf(Shelf(id = "fav", name = "Избранное", books = listOf(anna.path, dune.path)))

    private fun paths(list: List<Book>) = list.map { it.path }

    @Test
    fun recentOrderMatchesIos() {
        // lastRead по убыванию; при равенстве — место в «Недавних»; затем по алфавиту.
        val a = Book(path = "/a", title = "Б", lastRead = 0, recent = 1)
        val b = Book(path = "/b", title = "А", lastRead = 0, recent = -1)
        val c = Book(path = "/c", title = "В", lastRead = 0, recent = 0)
        val d = Book(path = "/d", title = "Г", lastRead = 10)
        val e = Book(path = "/e", title = "Аа", lastRead = 0, recent = -1)
        val sorted = LibraryLogic.filter(listOf(a, b, c, d, e), LibraryFilter(), emptyList(), collator = ru)
        assertEquals(listOf("/d", "/c", "/a", "/b", "/e"), paths(sorted))
    }

    @Test
    fun statusFilter() {
        val f = LibraryLogic.filter(all, LibraryFilter(status = BookStatus.DONE), shelves, collator = ru)
        assertEquals(listOf(war.path), paths(f))
        assertEquals(4, LibraryLogic.filter(all, LibraryFilter(), shelves, collator = ru).size)
    }

    @Test
    fun shelfFilterUsesDocumentAndBookField() {
        val fav = LibraryLogic.filter(all, LibraryFilter(shelf = "fav", sort = LibrarySort.TITLE), shelves, collator = ru)
        assertEquals(setOf(anna.path, dune.path), paths(fav).toSet())
        // Полки нет в документе, но книга знает о ней сама.
        val classics = LibraryLogic.filter(all, LibraryFilter(shelf = "classics"), shelves, collator = ru)
        assertEquals(listOf(war.path), paths(classics))
        assertTrue(LibraryLogic.filter(all, LibraryFilter(shelf = "missing"), shelves, collator = ru).isEmpty())
    }

    @Test
    fun searchByTitleOrAuthorIgnoringCase() {
        assertEquals(setOf(war.path, anna.path),
            paths(LibraryLogic.filter(all, LibraryFilter(search = "толстой"), shelves, collator = ru)).toSet())
        assertEquals(listOf(dune.path), paths(LibraryLogic.filter(all, LibraryFilter(search = "  DUNE "), shelves, collator = ru)))
        // Книга без названия ищется по имени файла.
        assertEquals(listOf(noTitle.path), paths(LibraryLogic.filter(all, LibraryFilter(search = "zett"), shelves, collator = ru)))
        assertTrue(LibraryLogic.filter(all, LibraryFilter(search = "нет такой"), shelves, collator = ru).isEmpty())
    }

    @Test
    fun filtersCombine() {
        val f = LibraryFilter(status = BookStatus.WANT, shelf = "fav", search = "анна")
        assertEquals(listOf(anna.path), paths(LibraryLogic.filter(all, f, shelves, collator = ru)))
        assertTrue(LibraryLogic.filter(all, f.copy(status = BookStatus.READING), shelves, collator = ru).isEmpty())
    }

    @Test
    fun hiddenBooksAreSkipped() {
        val f = LibraryLogic.filter(all, LibraryFilter(), shelves, hidden = setOf(dune.path), collator = ru)
        assertFalse(dune.path in paths(f))
        assertEquals(3, f.size)
    }

    @Test
    fun sortByTitleAuthorProgress() {
        assertEquals(listOf(dune.path, noTitle.path, anna.path, war.path),
            paths(LibraryLogic.filter(all, LibraryFilter(sort = LibrarySort.TITLE), shelves, collator = ru)))
        // Без автора — в конце; одинаковый автор — по названию.
        assertEquals(listOf(dune.path, anna.path, war.path, noTitle.path),
            paths(LibraryLogic.filter(all, LibraryFilter(sort = LibrarySort.AUTHOR), shelves, collator = ru)))
        assertEquals(listOf(war.path, dune.path, noTitle.path, anna.path),
            paths(LibraryLogic.filter(all, LibraryFilter(sort = LibrarySort.PROGRESS), shelves, collator = ru)))
    }

    @Test
    fun pendingDeletesIgnoreRejectedItems() {
        val outbox = listOf(
            OutboxItem(fileName = "", title = "d", source = "", operation = OutboxOperation.DeleteFile("/Books/Dune.epub")),
            OutboxItem(fileName = "", title = "d", source = "", operation = OutboxOperation.DeleteFile("/Books/war.epub"), failed = true),
            OutboxItem(fileName = "x.epub", title = "x", source = ""),
        )
        assertEquals(setOf("/Books/Dune.epub"), LibraryLogic.pendingDeletes(outbox))
    }

    @Test
    fun bookStatsAndRemainingTime() {
        val sessions = listOf(
            ReadingSession(book = dune.path, start = 1, secs = 1200, pages = 10),
            ReadingSession(book = dune.path, start = 2, secs = 600, pages = 5),
            ReadingSession(book = war.path, start = 3, secs = 999, pages = 1),
        )
        val s = LibraryLogic.bookStats(dune.copy(secs = 100), sessions)
        assertEquals(1800, s.seconds)
        assertEquals(2, s.sessions)
        assertEquals(15, s.pages)
        // 1800 с на 40 % → ещё 2700 с на 60 %.
        assertEquals(2700, s.remainingSeconds)
        assertNull(LibraryLogic.bookStats(war, sessions).remainingSeconds)
        assertNull(LibraryLogic.bookStats(anna, sessions).remainingSeconds)
        // Время из library.json больше суммы сессий — берётся оно.
        assertEquals(5000, LibraryLogic.bookStats(dune.copy(secs = 5000), sessions).seconds)
    }

    @Test
    fun importableExtensions() {
        assertTrue(LibraryLogic.isImportable("Book.EPUB"))
        assertTrue(LibraryLogic.isImportable("notes.md"))
        assertTrue(LibraryLogic.isImportable("a.b.txt"))
        assertFalse(LibraryLogic.isImportable("scan.pdf"))
        assertFalse(LibraryLogic.isImportable("epub"))
        // FB2 (Folio 1.1): .fb2 и .fb2.zip по полному имени; просто .zip — нет.
        assertTrue(LibraryLogic.isImportable("Kashtanka.fb2"))
        assertTrue(LibraryLogic.isImportable("Book.FB2.ZIP"))
        assertTrue(LibraryLogic.isImportable("x.fb2.zip"))
        assertFalse(LibraryLogic.isImportable("x.zip"))
        assertFalse(LibraryLogic.isImportable("x.xml"))
        assertFalse(LibraryLogic.isImportable(".fb2"))
        assertTrue("application/x-fictionbook+xml" in LibraryLogic.IMPORT_MIME_TYPES)
        assertTrue("application/zip" in LibraryLogic.IMPORT_MIME_TYPES)
    }

    @Test
    fun uploadSummaryLikeIos() {
        assertEquals(LibraryLogic.UploadSummary.Unreadable(listOf("b.epub")), LibraryLogic.uploadSummary(
            2, listOf("a.epub" to SubmitResult.SENT, "b.epub" to null), emptyList()))
        assertEquals(LibraryLogic.UploadSummary.Unsupported(listOf("x.pdf")), LibraryLogic.uploadSummary(
            2, listOf("a.epub" to SubmitResult.SENT), listOf("x.pdf")))
        assertEquals(LibraryLogic.UploadSummary.Sent(2), LibraryLogic.uploadSummary(
            3, listOf("a" to SubmitResult.SENT, "b" to SubmitResult.SENT, "c" to SubmitResult.FAILED), emptyList()))
        assertEquals(LibraryLogic.UploadSummary.Queued(2), LibraryLogic.uploadSummary(
            2, listOf("a" to SubmitResult.QUEUED, "b" to SubmitResult.QUEUED), emptyList()))
        // Один файл: хватает баннера модели.
        assertNull(LibraryLogic.uploadSummary(1, listOf("a" to SubmitResult.SENT), emptyList()))
        assertNull(LibraryLogic.uploadSummary(1, listOf("a" to SubmitResult.QUEUED), emptyList()))
    }
}
