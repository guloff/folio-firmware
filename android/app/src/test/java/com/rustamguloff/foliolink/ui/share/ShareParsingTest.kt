package com.rustamguloff.foliolink.ui.share

import com.rustamguloff.foliolink.ui.share.ShareParsing.Item
import com.rustamguloff.foliolink.ui.share.ShareParsing.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareParsingTest {

    @Test
    fun urlFromPlainLink() {
        assertEquals("https://example.com/a?b=1#c", ShareParsing.extractUrl("  https://example.com/a?b=1#c \n"))
        assertEquals("http://example.com", ShareParsing.extractUrl("http://example.com"))
    }

    @Test
    fun urlFromBrowserShareText() {
        // Chrome: «Заголовок\nссылка»; другие — «Посмотри: ссылка.»
        assertEquals("https://habr.com/ru/articles/1/", ShareParsing.extractUrl("Статья на Хабре\nhttps://habr.com/ru/articles/1/"))
        assertEquals("https://example.com/post", ShareParsing.extractUrl("Посмотри: https://example.com/post."))
        assertEquals("https://example.com/x", ShareParsing.extractUrl("«https://example.com/x»"))
        assertEquals("https://example.com/x", ShareParsing.extractUrl("(see https://example.com/x)"))
        // Скобки, которые часть ссылки, остаются.
        assertEquals("https://en.wikipedia.org/wiki/Dune_(novel)", ShareParsing.extractUrl("https://en.wikipedia.org/wiki/Dune_(novel)"))
        // Первая из нескольких.
        assertEquals("https://a.example/1", ShareParsing.extractUrl("https://a.example/1 via https://b.example/2"))
    }

    @Test
    fun noUrl() {
        assertNull(ShareParsing.extractUrl(null))
        assertNull(ShareParsing.extractUrl("   "))
        assertNull(ShareParsing.extractUrl("просто текст без ссылки"))
        assertNull(ShareParsing.extractUrl("ftp://example.com/file"))
        assertNull(ShareParsing.extractUrl("https://"))
    }

    @Test
    fun normalizeTypedUrl() {
        assertEquals("https://example.com/a", ShareParsing.normalizeUrl(" https://example.com/a "))
        assertEquals("https://example.com/a", ShareParsing.normalizeUrl("example.com/a"))
        assertNull(ShareParsing.normalizeUrl("example"))
        assertNull(ShareParsing.normalizeUrl("two words.com"))
        assertNull(ShareParsing.normalizeUrl("ftp://example.com"))
        assertNull(ShareParsing.normalizeUrl(""))
    }

    @Test
    fun epubDetection() {
        assertTrue(ShareParsing.isEpub("Dune.epub", null))
        assertTrue(ShareParsing.isEpub("Dune.EPUB", "application/octet-stream"))
        assertTrue(ShareParsing.isEpub("document", "application/epub+zip"))
        assertFalse(ShareParsing.isEpub("scan.pdf", "application/pdf"))
        assertFalse(ShareParsing.isEpub("file.bin", "application/octet-stream"))
        assertEquals("document.epub", ShareParsing.epubFileName("document"))
        assertEquals("Dune.EPUB", ShareParsing.epubFileName("Dune.EPUB"))
        assertEquals("Dune", ShareParsing.titleOf("Dune.epub"))
    }

    @Test
    fun planForEpubFiles() {
        val plan = ShareParsing.plan(
            ShareParsing.ACTION_SEND_MULTIPLE,
            listOf(
                Stream(1, "Dune.epub", "application/octet-stream"),
                Stream(2, "scan.pdf", "application/pdf"),
                Stream(3, "content", "application/epub+zip"),
            ),
            text = "https://example.com/ignored",
        )
        assertEquals(listOf(Item.Book(1, "Dune.epub"), Item.Book(3, "content.epub")), plan.items)
        assertEquals(listOf("scan.pdf"), plan.skipped)
    }

    /** FB2 (Folio 1.1): по имени (.fb2 / .fb2.zip, любой MIME) или по MIME FB2 без расширения; .epub не дописывается. */
    @Test
    fun fb2Sharing() {
        assertEquals("Kashtanka.fb2", ShareParsing.bookFileName("Kashtanka.fb2", "application/octet-stream"))
        assertEquals("Kashtanka.fb2", ShareParsing.bookFileName("Kashtanka.fb2", "text/xml"))
        assertEquals("Book.FB2.ZIP", ShareParsing.bookFileName("Book.FB2.ZIP", "application/zip"))
        assertEquals("x.fb2.zip", ShareParsing.bookFileName("x.fb2.zip", "application/x-zip-compressed"))
        assertEquals("book.fb2", ShareParsing.bookFileName("book", "application/x-fictionbook+xml; charset=utf-8"))
        assertEquals("book.fb2", ShareParsing.bookFileName("book", "application/x-fictionbook"))
        assertEquals("doc.epub", ShareParsing.bookFileName("doc", "application/epub+zip"))
        assertNull(ShareParsing.bookFileName("x.zip", "application/zip"))
        assertNull(ShareParsing.bookFileName("notes.xml", "text/xml"))
        assertNull(ShareParsing.bookFileName("notes.txt", "text/plain"))
        assertNull(ShareParsing.bookFileName("file.bin", "application/octet-stream"))
        assertEquals("Book", ShareParsing.titleOf("Book.FB2.ZIP"))
        assertEquals("Чехов. Каштанка", ShareParsing.titleOf("Чехов. Каштанка.fb2"))

        val plan = ShareParsing.plan(
            ShareParsing.ACTION_SEND_MULTIPLE,
            listOf(
                Stream(1, "Kashtanka.fb2", "application/x-fictionbook+xml"),
                Stream(2, "archive.zip", "application/zip"),
                Stream(3, "Voina i mir.fb2.zip", "application/zip"),
            ),
            text = null,
        )
        assertEquals(listOf(Item.Book(1, "Kashtanka.fb2"), Item.Book(3, "Voina i mir.fb2.zip")), plan.items)
        // Поставщик не знает тип — берётся тип интента (один файл): безымянный поток FB2 → ".fb2".
        assertEquals("application/x-fictionbook+xml",
            ShareParsing.streamMime("application/octet-stream", "application/x-fictionbook+xml", 1))
        assertEquals("application/x-fictionbook+xml", ShareParsing.streamMime(null, "application/x-fictionbook+xml", 1))
        assertEquals("application/zip", ShareParsing.streamMime("application/zip", "application/x-fictionbook+xml", 1))
        assertEquals("application/octet-stream", ShareParsing.streamMime("application/octet-stream", "*/*", 1))
        assertNull(ShareParsing.streamMime(null, "application/x-fictionbook+xml", 2))
        assertEquals(listOf("archive.zip"), plan.skipped)
    }

    @Test
    fun planForLink() {
        val plan = ShareParsing.plan<Int>(ShareParsing.ACTION_SEND, emptyList(), "Интересное\nhttps://example.com/post")
        assertEquals(listOf<Item<Int>>(Item.Article("https://example.com/post")), plan.items)
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun planWithNothingUseful() {
        assertTrue(ShareParsing.plan<Int>(ShareParsing.ACTION_SEND, emptyList(), "без ссылки").items.isEmpty())
        val onlyPdf = ShareParsing.plan(ShareParsing.ACTION_SEND, listOf(Stream(1, "a.pdf", "application/pdf")), null)
        assertTrue(onlyPdf.items.isEmpty())
        assertEquals(listOf("a.pdf"), onlyPdf.skipped)
        // Чужое действие не разбирается.
        assertTrue(ShareParsing.plan<Int>("android.intent.action.VIEW", emptyList(), "https://example.com").items.isEmpty())
        assertTrue(ShareParsing.plan<Int>(null, emptyList(), "https://example.com").items.isEmpty())
    }

    /** SEC-035: только content:// чужого поставщика. */
    @Test
    fun onlyForeignContentUrisAreRead() {
        val own = "com.rustamguloff.foliolink"
        assertTrue(ShareParsing.acceptsStream("content", "com.android.providers.downloads.documents", own))
        assertTrue(ShareParsing.acceptsStream("CONTENT", "org.telegram.messenger.provider", own))
        org.junit.Assert.assertFalse(ShareParsing.acceptsStream("file", "", own))
        org.junit.Assert.assertFalse(ShareParsing.acceptsStream("content", "com.rustamguloff.foliolink.files", own))
        org.junit.Assert.assertFalse(ShareParsing.acceptsStream("content", own, own))
        org.junit.Assert.assertFalse(ShareParsing.acceptsStream("content", null, own))
        org.junit.Assert.assertFalse(ShareParsing.acceptsStream("http", "example.com", own))
    }
}
