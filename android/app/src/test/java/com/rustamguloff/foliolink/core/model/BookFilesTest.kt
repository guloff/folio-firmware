package com.rustamguloff.foliolink.core.model

import com.rustamguloff.foliolink.core.store.Outbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Форматы книг по имени: EPUB, FB2, FB2.zip (двойное расширение), TXT, MD. */
class BookFilesTest {
    @Test
    fun formatByFullLowercasedName() {
        assertEquals(BookFormat.EPUB, BookFiles.format("Dune.EPUB"))
        assertEquals(BookFormat.FB2, BookFiles.format("Каштанка.fb2"))
        assertEquals(BookFormat.FB2_ZIP, BookFiles.format("Book.FB2.ZIP"))
        assertEquals(BookFormat.FB2_ZIP, BookFiles.format("/books/x.fb2.zip"))
        assertEquals(BookFormat.TXT, BookFiles.format("a.b.txt"))
        assertEquals(BookFormat.MD, BookFiles.format("notes.md"))
        assertNull(BookFiles.format("x.zip"))
        assertNull(BookFiles.format("x.fb2.bak"))
        assertNull(BookFiles.format("scan.pdf"))
        assertNull(BookFiles.format(".fb2"))
        assertNull(BookFiles.format("fb2"))
        assertTrue(BookFiles.isFb2("x.fb2.zip"))
        assertTrue(BookFormat.FB2_ZIP.isEpubLike)
        assertFalse(BookFormat.TXT.isEpubLike)
    }

    @Test
    fun stemsAndMimes() {
        assertEquals("Book", BookFiles.stem("Book.FB2.ZIP"))
        assertEquals("Чехов. Каштанка", BookFiles.stem("Чехов. Каштанка.fb2"))
        assertEquals("a.b", BookFiles.stem("a.b.epub"))
        assertEquals("archive", BookFiles.stem("archive.zip"))
        assertEquals("application/x-fictionbook+xml", BookFiles.uploadMime("x.fb2"))
        assertEquals("application/zip", BookFiles.uploadMime("x.Fb2.Zip"))
        assertEquals("application/epub+zip", BookFiles.uploadMime("x.epub"))
        assertEquals("text/plain", BookFiles.uploadMime("x.txt"))
        assertTrue(BookFiles.isFb2Mime("Application/X-FictionBook+XML; charset=windows-1251"))
        assertFalse(BookFiles.isFb2Mime("text/xml"))
        assertFalse(BookFiles.isFb2Mime(null))
    }

    @Test
    fun fb2BooksFromTheApiShowLikeEpubs() {
        assertEquals("Book", Book(path = "/books/Book.fb2.zip").displayTitle)
        assertEquals("Kashtanka", Book(path = "/books/Kashtanka.fb2").displayTitle)
        assertEquals("Каштанка", Book(path = "/books/Kashtanka.fb2", title = "Каштанка").displayTitle)
        assertEquals("Dune", Book(path = "/books/Dune.epub").displayTitle)
        assertEquals(BookFormat.FB2_ZIP, Book(path = "/books/Book.fb2.zip").format)
        // Список, где название — имя файла без одного `.zip`, показывает его без `.fb2`.
        assertEquals("FB2_test_cp1251", Book(path = "/Books/FB2_test_cp1251.fb2.zip", title = "FB2_test_cp1251.fb2").displayTitle)
        assertEquals("Война и мир", Book(path = "/Books/voina.fb2.zip", title = "Война и мир").displayTitle)
        val decoded = FolioJson.decodeFromString(
            BooksResponse.serializer(),
            """{"books":[{"path":"/books/Voina.fb2.zip","title":"","author":"","progress":-1},{"path":"/books/a.fb2","title":"A"}]}""",
        )
        assertEquals(listOf("Voina", "A"), decoded.books.map { it.displayTitle })
    }

    @Test
    fun sanitizeKeepsTheDoubleExtension() {
        assertEquals("Book.fb2.zip", Outbox.sanitize("Book.fb2.zip"))
        val long = Outbox.sanitize("x".repeat(200) + ".fb2.zip")
        assertTrue(long.endsWith(".fb2.zip"))
        assertTrue(long.length <= 120)
        assertTrue(Outbox.sanitize("y".repeat(200) + ".epub").endsWith("y.epub"))
    }
}
