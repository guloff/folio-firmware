package com.rustamguloff.foliolink.core.epub

import com.rustamguloff.foliolink.core.epub.EpubDocument.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class EpubTest {
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    @Test
    fun firstEntryIsUncompressedMimetype() {
        val doc = EpubDocument(
            title = "Тест & <статья>", author = "Автор", sourceUrl = "https://example.com/a?b=1&c=2",
            blocks = listOf(Block.Heading("Глава"), Block.Paragraph("Привет, мир!"), Block.Quote("Цитата")),
        )
        val b = EpubBuilder.build(doc)
        // Сигнатура локального заголовка + метод 0 + имя "mimetype" по смещению 30.
        assertEquals(listOf(0x50, 0x4B, 0x03, 0x04), b.copyOfRange(0, 4).map { it.toInt() and 0xFF })
        assertEquals("stored", 0, u16(b, 8))
        assertEquals("без extra", 0, u16(b, 28))
        assertEquals("mimetype", String(b, 30, 8, Charsets.UTF_8))
        assertEquals("application/epub+zip", String(b, 38, 20, Charsets.UTF_8))

        val entries = ZipReader.entries(b)
        assertEquals("mimetype", entries.first().name)
        assertTrue(entries.all { it.method == 0 })
        val names = entries.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("META-INF/container.xml", "OEBPS/content.opf", "OEBPS/nav.xhtml",
            "OEBPS/text.xhtml", "OEBPS/toc.ncx")))
        val text = String(entries.first { it.name == "OEBPS/text.xhtml" }.data, Charsets.UTF_8)
        assertTrue(text.contains("Тест &amp; &lt;статья&gt;"))
        assertTrue(text.contains("<p>Привет, мир!</p>"))
        assertTrue(text.contains("<p class=\"meta\">Автор · example.com</p>"))
        // Каждая XML-часть должна быть корректной.
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for (e in entries) {
            if (listOf(".xml", ".opf", ".xhtml", ".ncx").none { e.name.endsWith(it) }) continue
            try {
                factory.newDocumentBuilder().parse(ByteArrayInputStream(e.data))
            } catch (ex: Exception) {
                fail("${e.name} некорректен: $ex")
            }
        }
        // Конец центрального каталога на месте и с верным числом записей.
        val eocd = b.size - 22
        assertEquals(listOf(0x50, 0x4B, 0x05, 0x06), b.copyOfRange(eocd, eocd + 4).map { it.toInt() and 0xFF })
        assertEquals(entries.size, u16(b, eocd + 10))
        // Архив читается стандартным ZIP-ридером JDK (CRC и размеры сходятся).
        ZipInputStream(ByteArrayInputStream(b)).use { zin ->
            val read = ArrayList<String>()
            while (true) {
                val e = zin.nextEntry ?: break
                zin.readBytes()
                read += e.name
            }
            assertEquals(entries.map { it.name }, read)
        }
    }

    @Test
    fun crc32KnownValue() {
        assertEquals(0xCBF43926L, Crc32.checksum("123456789".toByteArray()))
    }

    @Test
    fun escapingDropsIllegalXmlCharacters() {
        assertEquals("a&amp;b&#39;c\tx😀", EpubBuilder.esc("a&b'c\t\u0001x￾😀"))
        val opf = EpubBuilder.opf(EpubDocument(title = "T", blocks = emptyList(), identifier = "id"), 0)
        assertTrue(opf.contains("<meta property=\"dcterms:modified\">1970-01-01T00:00:00Z</meta>"))
        assertFalse("без автора нет dc:creator", opf.contains("dc:creator"))
    }

    @Test
    fun articleExtraction() {
        val html = """
        <html lang="en"><head><title>Site | Great Article</title>
        <meta property="og:title" content="Great Article"><script>var x = "<p>no</p>";</script></head>
        <body><nav><p>Menu item that is long enough to be a paragraph maybe</p></nav>
        <article><h1>Great Article</h1><p>First paragraph with &laquo;quotes&raquo; &amp; entities.</p>
        <h2>Section</h2><p>Second <b>bold</b> paragraph.</p><blockquote>Wise words</blockquote></article>
        <footer><p>Copyright</p></footer></body></html>
        """.trimIndent()
        val a = ArticleExtractor.extract(html)
        assertEquals("Great Article", a.title)
        assertEquals("en", a.language)
        assertEquals(Block.Paragraph("First paragraph with «quotes» & entities."), a.blocks.first())
        assertTrue(a.blocks.contains(Block.Heading("Section")))
        assertTrue(a.blocks.contains(Block.Paragraph("Second bold paragraph.")))
        assertTrue(a.blocks.contains(Block.Quote("Wise words")))
        assertFalse(a.blocks.any { it is Block.Paragraph && (it.text.contains("Menu") || it.text.contains("no") && it.text.length < 4) })
    }

    @Test
    fun articleWithoutArticleTagUsesParagraphCluster() {
        val p = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(4)
        val html = "<div class='side'><p>Short</p></div><div class='content'><p>1 $p</p><p>2 $p</p><p>3 $p</p></div>"
        val a = ArticleExtractor.extract(html, fallbackTitle = "x")
        assertEquals(3, a.blocks.size)
        assertEquals("x", a.title)
        assertEquals("ru", a.language)
    }

    @Test
    fun metaContentAnyOrderAndApostrophes() {
        val html = """<meta content="Don't Panic" property="og:title"><meta name='author' content="Douglas Adams">"""
        assertEquals("Don't Panic", ArticleExtractor.metaContent("og:title", html))
        assertEquals("Douglas Adams", ArticleExtractor.metaContent("author", html))
        assertNull(ArticleExtractor.metaContent("description", html))
    }

    @Test
    fun entitiesAndCleanText() {
        assertEquals("A & B — «c» 😀 &unknown; &#xZZ;", ArticleExtractor.decodeEntities("A &amp; B &mdash; &laquo;c&raquo; &#x1F600; &unknown; &#xZZ;"))
        assertEquals("& no semicolon for a long while", ArticleExtractor.decodeEntities("& no semicolon for a long while"))
        assertEquals("a b c", ArticleExtractor.cleanText("  a<br/>b&nbsp;\n <i>c</i> "))
    }

    @Test
    fun decodesWindows1251() {
        val bytes = "<html><head><meta charset=windows-1251></head><p>Привет</p></html>".toByteArray(charset("windows-1251"))
        assertTrue(ArticleExtractor.decodeHtml(bytes, null).contains("Привет"))
        assertTrue(ArticleExtractor.decodeHtml("Привет".toByteArray(charset("windows-1251")), "text/html; charset=Windows-1251")
            .contains("Привет"))
        assertEquals("Привет", ArticleExtractor.decodeHtml("Привет".toByteArray(), "text/html"))
    }

    @Test
    fun makeEpubAndFileName() {
        val article = ArticleExtractor.Article("Дюна: книга/1", "Герберт", "ru", listOf(Block.Paragraph("Текст")))
        val file = ArticleExtractor.makeEpub(article, "https://x.org/a")
        assertEquals("Дюна_ книга_1.epub", file.fileName)
        assertEquals("mimetype", ZipReader.entries(file.data).first().name)
    }

    @Test
    fun sanitize() {
        assertEquals("a_b_c_.epub", FileNames.sanitize("a/b:c?.epub"))
        assertEquals("hidden", FileNames.sanitize("..hidden"))
        assertEquals("book", FileNames.sanitize(""))
        assertEquals("a_b", FileNames.sanitize("a\u0007b"))
        val long = FileNames.sanitize("я".repeat(200) + ".epub")
        assertEquals("я".repeat(110) + ".epub", long)
        assertEquals("ab", "ab😀".prefixCodePoints(2))
        assertEquals("ab😀", "ab😀c".prefixCodePoints(3))
    }
}
