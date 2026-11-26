package com.rustamguloff.foliolink.core.export

import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.Highlight
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

class MarkdownExporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun markdownExport() {
        val h = listOf(
            Highlight(id = "h1a", book = "/Books/Dune.epub", title = "Dune", text = "Fear is the mind-killer.",
                note = "Litany", chapter = "Book I", pct = 12, created = 1_790_380_800),
        )
        val md = MarkdownExporter.markdown("Dune", "Frank \"F\" Herbert", "/Books/Dune.epub", h,
            exportedMs = 1_790_380_800_000, zone = ZoneOffset.UTC)
        assertTrue(md.startsWith("---\ntitle: \"Dune\"\n"))
        assertTrue(md.contains("author: \"Frank \\\"F\\\" Herbert\""))
        assertTrue(md.contains("> Fear is the mind-killer."))
        assertTrue(md.contains("> [!note]\n> Litany"))
        assertTrue(md.contains("^h1a"))
        assertTrue(md.contains(">\n> — Book I · 12% · 2026-09-26\n"))
        assertTrue(md.contains("exported: 2026-09-26\n"))
    }

    @Test
    fun orderMultilineAndAnchors() {
        val h = listOf(
            Highlight(id = "b", book = "/x", text = "second\nline", pct = 50),
            Highlight(id = "a:1/2", book = "/x", text = "first", pct = 5),
            Highlight(id = "", book = "/x", text = "no id", pct = -1),
        )
        val md = MarkdownExporter.markdown("X", "", "/x", h)
        assertFalse(md.contains("author:"))
        assertTrue(md.indexOf("> no id") < md.indexOf("> first") && md.indexOf("> first") < md.indexOf("> second"))
        assertTrue(md.contains("> second\n> line\n"))
        assertTrue(md.contains("^a12\n"))
        assertEquals("h", MarkdownExporter.anchor("::"))
        assertEquals("Дюна-1", MarkdownExporter.anchor("Дюна-1!"))
    }

    @Test
    fun markdownNamesAreUnique() {
        val used = HashSet<String>()
        assertEquals("Дюна", MarkdownExporter.uniqueName("Дюна", used))
        assertEquals("Дюна (2)", MarkdownExporter.uniqueName("Дюна", used))
        assertEquals("дюна (3)", MarkdownExporter.uniqueName("дюна", used))
    }

    @Test
    fun exportFilesOnePerBook() = runTest {
        val books = listOf(Book(path = "/a.epub", title = "Дюна", author = "Герберт"), Book(path = "/b.epub", title = "Дюна"))
        val h = listOf(
            Highlight(id = "1", book = "/a.epub", text = "x"),
            Highlight(id = "2", book = "/b.epub", text = "y"),
            Highlight(id = "3", book = "/c/Без:книги.epub", text = "z"),
        )
        val files = MarkdownExporter.exportFiles(h, books, tmp.root)
        assertEquals(listOf("Дюна.md", "Дюна (2).md", "Без_книги.epub.md"), files.map { it.name })
        assertTrue(files[0].readText().contains("author: \"Герберт\""))
    }
}

class ReadwiseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val books = listOf(Book(path = "/books/Dune.epub", title = "Dune", author = "Frank Herbert"))

    /** Подменный транспорт: запоминает запросы, отвечает `reply`. */
    private class FakeTransport(var reply: (ReadwiseRequest) -> ReadwiseResponse) : ReadwiseTransport {
        val requests = ArrayList<ReadwiseRequest>()
        override suspend fun send(request: ReadwiseRequest): ReadwiseResponse {
            requests += request
            return reply(request)
        }
    }

    @Test
    fun csvFieldEscaping() {
        assertEquals("plain text", ReadwiseCsv.field("plain text"))
        assertEquals("\"a, b\"", ReadwiseCsv.field("a, b"))
        assertEquals("\"say \"\"hi\"\"\"", ReadwiseCsv.field("say \"hi\""))
        assertEquals("\"line1\nline2\"", ReadwiseCsv.field("line1\nline2"))
        assertEquals("\"crlf\r\nend\"", ReadwiseCsv.field("crlf\r\nend"))
        assertEquals("Привет; мир", ReadwiseCsv.field("Привет; мир"))
        assertEquals("", ReadwiseCsv.field(""))
    }

    @Test
    fun csvRowsInReadwiseFormat() {
        val highlights = listOf(
            Highlight(id = "2", book = "/books/Dune.epub", title = "Dune", text = "Fear is the mind-killer, \"they\" said",
                note = "Литания\nпротив страха", chapter = "1", pct = 12, created = 1_760_000_000),
            Highlight(id = "1", book = "/books/Dune.epub", title = "Dune", text = "A beginning", pct = 1, created = 0),
            Highlight(id = "3", book = "/books/Без автора.epub", title = "", text = "Одна строка"),
        )
        val csv = ReadwiseCsv.csv(highlights, books, ZoneOffset.UTC)
        val lines = csv.split("\r\n")
        assertEquals("Highlight,Title,Author,URL,Note,Location,Date", lines[0])
        assertEquals("по книге, затем по позиции", "A beginning,Dune,Frank Herbert,,,1,", lines[1])
        assertEquals(
            "\"Fear is the mind-killer, \"\"they\"\" said\",Dune,Frank Herbert,,\"Литания\nпротив страха\",12,2025-10-09 08:53:20",
            lines[2],
        )
        assertEquals("название из имени файла, без автора/позиции/даты", "Одна строка,Без автора,,,,,", lines[3])
        assertEquals("файл кончается CRLF", "", lines.last())
        assertEquals(5, lines.size)
    }

    @Test
    fun highlightMappingForTheApi() {
        val h = Highlight(id = "x", book = "/books/Dune.epub", title = "Dune", text = "Text", note = "Note", pct = 42,
            created = 1_760_000_000)
        val item = ReadwiseHighlight.make(h, books)
        assertEquals("Dune", item.title)
        assertEquals("Frank Herbert", item.author)
        assertEquals("Note", item.note)
        assertEquals(42, item.location)
        assertEquals("order", item.locationType)
        assertEquals("2025-10-09T08:53:20Z", item.highlightedAt)
        assertEquals("foliolink", item.sourceType)
        assertEquals("books", item.category)

        val bare = ReadwiseHighlight.make(Highlight(id = "", book = "/books/x.epub", title = "X", text = "a".repeat(9000)),
            emptyList())
        assertNull(bare.author)
        assertNull(bare.note)
        assertNull(bare.location)
        assertNull(bare.locationType)
        assertNull(bare.highlightedAt)
        assertEquals("лимит Readwise на текст", ReadwiseHighlight.MAX_TEXT, bare.text.length)
    }

    @Test
    fun uploadBatchesOf100WithTokenHeader() = runTest {
        val transport = FakeTransport { ReadwiseResponse(200, body = "[]".toByteArray()) }
        val items = (0 until 250).map { i ->
            ReadwiseHighlight.make(Highlight(id = "$i", book = "/books/Dune.epub", title = "Dune", text = "t$i", pct = i % 100), books)
        }
        val sent = ReadwiseClient("  secret-token \n", transport).upload(items)
        assertEquals(250, sent)
        assertEquals(3, transport.requests.size)
        val counts = ArrayList<Int>()
        for (r in transport.requests) {
            assertEquals("POST", r.method)
            assertEquals("https://readwise.io/api/v2/highlights/", r.url)
            assertEquals("Token secret-token", r.headers["Authorization"])
            assertEquals("application/json", r.headers["Content-Type"])
            val root = Json.parseToJsonElement(String(r.body!!, Charsets.UTF_8)).jsonObject
            val list = root["highlights"]!!.jsonArray
            counts += list.size
            val first = list.first().jsonObject
            assertEquals("foliolink", first["source_type"]!!.jsonPrimitive.content)
            assertEquals("books", first["category"]!!.jsonPrimitive.content)
            assertEquals("Frank Herbert", first["author"]!!.jsonPrimitive.content)
        }
        assertEquals(listOf(100, 100, 50), counts)

        // Пустые поля опускаются, а не пишутся null.
        val bare = ReadwiseHighlight.make(Highlight(id = "", book = "/books/x.epub", title = "X", text = "t"), emptyList())
        transport.requests.clear()
        ReadwiseClient("t", transport).upload(listOf(bare))
        val obj = (Json.parseToJsonElement(String(transport.requests.single().body!!)).jsonObject["highlights"] as JsonArray)
            .first() as JsonObject
        assertEquals(setOf("text", "title", "source_type", "category"), obj.keys)
    }

    @Test
    fun uploadStopsOnErrorAndMapsIt() = runTest {
        val transport = FakeTransport { ReadwiseResponse(401, body = """{"detail":"Invalid token."}""".toByteArray()) }
        val items = listOf(ReadwiseHighlight.make(Highlight(id = "1", book = "/b", title = "B", text = "t"), emptyList()))
        try {
            ReadwiseClient("bad", transport).upload((items + items) + List(200) { items[0] })
            fail("должно бросить")
        } catch (e: ReadwiseException) {
            assertEquals(ReadwiseException.InvalidToken, e)
        }
        assertEquals(1, transport.requests.size)

        transport.reply = { ReadwiseResponse(500, body = "boom".toByteArray()) }
        try {
            ReadwiseClient("t", transport).upload(items)
            fail("должно бросить")
        } catch (e: ReadwiseException) {
            assertEquals(ReadwiseException.Server(500, "boom"), e)
        }

        transport.reply = { ReadwiseResponse(429, mapOf("retry-after" to "30")) }
        try {
            ReadwiseClient("t", transport).upload(items)
            fail("должно бросить")
        } catch (e: ReadwiseException) {
            assertEquals(ReadwiseException.RateLimited(30), e)
        }

        transport.reply = { ReadwiseResponse(502) }
        try {
            ReadwiseClient("t", transport).upload(items)
            fail("должно бросить")
        } catch (e: ReadwiseException) {
            assertEquals(ReadwiseException.Server(502, "bad gateway"), e)
        }
    }

    @Test
    fun checkToken() = runTest {
        val transport = FakeTransport { ReadwiseResponse(204) }
        assertTrue(ReadwiseClient("good", transport).checkToken())
        val r = transport.requests.first()
        assertEquals("GET", r.method)
        assertEquals("https://readwise.io/api/v2/auth/", r.url)
        assertEquals("Token good", r.headers["Authorization"])
        assertNull(r.body)

        transport.reply = { ReadwiseResponse(401) }
        assertFalse(ReadwiseClient("bad", transport).checkToken())

        transport.reply = { ReadwiseResponse(503) }
        try {
            ReadwiseClient("t", transport).checkToken()
            fail("ошибка сервера — не ответ о токене")
        } catch (_: ReadwiseException) {
        }
    }

    @Test
    fun tokenStoreUsesAFileAndRoundTrips() = runTest {
        val file = File(tmp.root, "rw/token.json")
        val store: ReadwiseTokenStore = FileReadwiseTokenStore(file)
        assertNull(store.token())
        store.save("  abc \n")
        assertEquals("abc", store.token())
        assertEquals("abc", FileReadwiseTokenStore(file).token())
        store.remove()
        assertNull(store.token())
        store.save("   ")
        assertNull("пустой токен — как отсутствующий", store.token())
    }

    @Test
    fun exportFileWritesCsv() = runTest {
        val file = ReadwiseCsv.exportFile(listOf(Highlight(id = "1", book = "/books/Dune.epub", text = "t")), books, tmp.root)
        assertEquals("FolioLink-Readwise.csv", file.name)
        assertTrue(file.readText().startsWith("Highlight,Title"))
    }
}
