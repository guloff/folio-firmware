package com.rustamguloff.foliolink.core.epub

import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Простая статья/книга для упаковки в EPUB. */
data class EpubDocument(
    val title: String,
    val author: String = "",
    val language: String = "ru",
    val sourceUrl: String? = null,
    val blocks: List<Block>,
    val identifier: String = "urn:uuid:" + UUID.randomUUID().toString().lowercase(),
) {
    sealed interface Block {
        val text: String

        data class Heading(override val text: String) : Block
        data class Paragraph(override val text: String) : Block
        data class Quote(override val text: String) : Block
    }
}

/** Собирает минимальный, но валидный EPUB 3 (с NCX из EPUB 2 для старых читалок). */
object EpubBuilder {

    fun build(doc: EpubDocument, modifiedMs: Long = System.currentTimeMillis()): ByteArray {
        val zip = ZipWriter(modifiedMs)
        // mimetype ОБЯЗАН быть первым, несжатым и без extra-полей.
        zip.add("mimetype", "application/epub+zip")
        zip.add("META-INF/container.xml", CONTAINER_XML)
        zip.add("OEBPS/content.opf", opf(doc, modifiedMs))
        zip.add("OEBPS/nav.xhtml", nav(doc))
        zip.add("OEBPS/toc.ncx", ncx(doc))
        zip.add("OEBPS/style.css", CSS)
        zip.add("OEBPS/text.xhtml", chapter(doc))
        return zip.finalize()
    }

    /** Имя файла книги: первые 80 символов названия, очищенные для файловой системы. */
    fun fileName(title: String): String = FileNames.sanitize(title.prefixCodePoints(80)) + ".epub"

    /** XML-экранирование; символы, недопустимые в XML 1.0, выбрасываются. */
    fun esc(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val v = s.codePointAt(i)
            i += Character.charCount(v)
            when (v) {
                '&'.code -> out.append("&amp;")
                '<'.code -> out.append("&lt;")
                '>'.code -> out.append("&gt;")
                '"'.code -> out.append("&quot;")
                '\''.code -> out.append("&#39;")
                else -> if (v == 0x9 || v == 0xA || v == 0xD || v in 0x20..0xD7FF || v in 0xE000..0xFFFD ||
                    v >= 0x10000
                ) {
                    out.appendCodePoint(v)
                }
            }
        }
        return out.toString()
    }

    private const val CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    private val ISO_UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    internal fun opf(d: EpubDocument, modifiedMs: Long): String {
        val modified = ISO_UTC.format(Instant.ofEpochMilli(modifiedMs).truncatedTo(ChronoUnit.SECONDS))
        val creator = if (d.author.isEmpty()) "" else "\n    <dc:creator>${esc(d.author)}</dc:creator>"
        val source = d.sourceUrl?.let { "\n    <dc:source>${esc(it)}</dc:source>" } ?: ""
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="${esc(d.language)}">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">${esc(d.identifier)}</dc:identifier>
    <dc:title>${esc(d.title)}</dc:title>
    <dc:language>${esc(d.language)}</dc:language>$creator$source
    <meta property="dcterms:modified">$modified</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="css" href="style.css" media-type="text/css"/>
    <item id="text" href="text.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="text"/>
  </spine>
</package>"""
    }

    internal fun nav(d: EpubDocument): String = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="${esc(d.language)}">
<head><title>${esc(d.title)}</title></head>
<body>
  <nav epub:type="toc" id="toc"><ol><li><a href="text.xhtml">${esc(d.title)}</a></li></ol></nav>
</body>
</html>"""

    internal fun ncx(d: EpubDocument): String = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="${esc(d.identifier)}"/></head>
  <docTitle><text>${esc(d.title)}</text></docTitle>
  <navMap>
    <navPoint id="p1" playOrder="1"><navLabel><text>${esc(d.title)}</text></navLabel><content src="text.xhtml"/></navPoint>
  </navMap>
</ncx>"""

    private const val CSS = """body { margin: 0 4%; line-height: 1.45; }
h1 { font-size: 1.5em; margin: 0.6em 0 0.2em; }
h2 { font-size: 1.2em; margin: 1em 0 0.3em; }
p { margin: 0 0 0.6em; text-indent: 1.2em; text-align: justify; }
p.meta { text-indent: 0; font-size: 0.85em; font-style: italic; }
blockquote { margin: 0.6em 1.5em; font-style: italic; }"""

    internal fun chapter(d: EpubDocument): String {
        val body = StringBuilder("<h1>${esc(d.title)}</h1>\n")
        val meta = ArrayList<String>()
        if (d.author.isNotEmpty()) meta += esc(d.author)
        d.sourceUrl?.let { src -> runCatching { URI(src).host }.getOrNull()?.let { meta += esc(it) } }
        if (meta.isNotEmpty()) body.append("<p class=\"meta\">${meta.joinToString(" · ")}</p>\n")
        for (block in d.blocks) {
            when (block) {
                is EpubDocument.Block.Heading -> body.append("<h2>${esc(block.text)}</h2>\n")
                is EpubDocument.Block.Paragraph -> body.append("<p>${esc(block.text)}</p>\n")
                is EpubDocument.Block.Quote -> body.append("<blockquote><p>${esc(block.text)}</p></blockquote>\n")
            }
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="${esc(d.language)}">
<head><title>${esc(d.title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
$body</body>
</html>"""
    }
}
