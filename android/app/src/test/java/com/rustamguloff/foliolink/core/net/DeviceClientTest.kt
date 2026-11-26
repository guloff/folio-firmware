package com.rustamguloff.foliolink.core.net

import com.rustamguloff.foliolink.core.model.LibraryDoc
import com.rustamguloff.foliolink.core.net.FakeTransport.Companion.json
import com.rustamguloff.foliolink.core.net.FakeTransport.Companion.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Контракт v2 §1 (сопряжение, токен, коды) и маршруты клиента. */
class DeviceClientTest {
    private fun <T> expect(error: DeviceError, block: suspend () -> T) = runBlocking {
        try {
            block()
            fail("expected $error")
        } catch (e: DeviceError) {
            assertEquals(error, e)
        }
    }

    @Test
    fun tokenHeaderOnEveryMutatingRequestOnly() = runBlocking {
        val t = FakeTransport { req ->
            when (req.path) {
                "/api/files" -> json("""[{"name":"Books","size":0,"isDirectory":true}]""")
                "/api/inklink/library" -> json("""{"version":1,"shelves":[],"status":{}}""")
                "/api/settings" -> if (req.method == "POST") text("Applied 1 setting(s)") else json("[]")
                "/api/inklink/pair/start" -> json("""{"ok":true,"digits":6,"expiresIn":120}""")
                else -> json("""{"ok":true}""")
            }
        }
        val client = t.client(token = "abc123")
        client.files("/")
        client.library()
        runCatching { client.info() }
        client.upload("x".toByteArray(), "a.epub", "/Books")
        client.delete("/Books/a.epub")
        client.mkdir("fonts", "/")
        client.rename("/Books/b.epub", "c.epub")
        client.updateSettings(mapOf("sleepScreen" to JsonPrimitive(8)))
        client.deleteFontFamily("Literata")
        client.saveLibrary(LibraryDoc())
        client.updateHighlight("h1", "n")
        client.setTime()
        client.pairStart("Pixel")

        for (req in t.requests) {
            val header = req.header(DeviceClient.TOKEN_HEADER)
            when {
                req.path == "/api/inklink/info" -> assertEquals("info reports pairing.authorized", "abc123", header)
                req.method == "GET" || req.path.startsWith("/api/inklink/pair/") ->
                    assertNull("${req.method} ${req.path} must not carry the token", header)
                else -> assertEquals("${req.method} ${req.path} needs the token", "abc123", header)
            }
        }
        val posts = t.requests.filter { it.method == "POST" }.map { it.path }
        for (route in listOf("/upload", "/delete", "/mkdir", "/rename", "/api/settings", "/api/fonts/delete",
            "/api/inklink/library", "/api/inklink/highlights/update", "/api/inklink/time")) {
            assertTrue("$route was not exercised", route in posts)
        }
    }

    @Test
    fun noTokenNoHeaderAndSetTokenLater() = runBlocking {
        val t = FakeTransport()
        val client = t.client()
        assertFalse(client.hasToken)
        client.delete("/x")
        assertNull(t.requests.last().header(DeviceClient.TOKEN_HEADER))
        client.setToken("t2")
        client.delete("/x")
        assertEquals("t2", t.requests.last().header(DeviceClient.TOKEN_HEADER))
    }

    @Test
    fun status401MapsToPairingRequiredAndIsTransient() {
        val t = FakeTransport { json("""{"ok":false,"error":"pairing required"}""", 401) }
        expect(DeviceError.PairingRequired) { t.client().delete("/x") }
        assertTrue("a queued item must wait for pairing, not fail", OutboxRunner.isTransient(DeviceError.PairingRequired))
        assertTrue(OutboxRunner.isTransient(DeviceError.Unreachable))
        assertTrue(OutboxRunner.isTransient(DeviceError.Timeout))
        assertFalse(OutboxRunner.isTransient(DeviceError.Server(400, "bad")))
    }

    @Test
    fun pairFinishOutcomes() = runBlocking {
        val cases = listOf(
            Triple(403, """{"ok":false,"error":"bad pin","attemptsLeft":3}""", DeviceError.Pairing(DeviceError.PairingFailure.BadPin(3))),
            Triple(410, """{"ok":false,"error":"pin expired"}""", DeviceError.Pairing(DeviceError.PairingFailure.Expired)),
            Triple(409, """{"ok":false,"error":"no active pin"}""", DeviceError.Pairing(DeviceError.PairingFailure.NoActivePin)),
            Triple(403, """{"ok":false,"error":"bad pin"}""", DeviceError.Pairing(DeviceError.PairingFailure.BadPin(null))),
        )
        for ((status, body, expected) in cases) {
            expect(expected) { FakeTransport { json(body, status) }.client().pairFinish("000000", "Pixel") }
        }
        val token = "ab".repeat(32)
        val t = FakeTransport { json("""{"ok":true,"token":"$token"}""") }
        assertEquals(token, t.client().pairFinish("123456", "Pixel"))
        val sent = t.requests.last()
        assertEquals("/api/inklink/pair/finish", sent.path)
        assertEquals(JsonPrimitive("123456"), sent.jsonBody()["pin"])
        assertEquals(JsonPrimitive("Pixel"), sent.jsonBody()["name"])
        assertEquals("application/json", sent.header("Content-Type"))
        // 200 без токена — не молча пустой токен.
        expect(DeviceError.Decoding("pair/finish")) { FakeTransport { json("""{"ok":true}""") }.client().pairFinish("1", "P") }
    }

    @Test
    fun pairStartRateLimitAndDefaults() = runBlocking {
        expect(DeviceError.Pairing(DeviceError.PairingFailure.RateLimited(30))) {
            FakeTransport { json("""{"ok":false,"error":"too many attempts","retryIn":30}""", 429) }.client().pairStart("P")
        }
        expect(DeviceError.Pairing(DeviceError.PairingFailure.RateLimited(30))) {
            FakeTransport { json("{}", 429) }.client().pairStart("P")
        }
        expect(DeviceError.Pairing(DeviceError.PairingFailure.RateLimited(1))) {
            FakeTransport { json("""{"retryIn":0}""", 429) }.client().pairStart("P")
        }
        val r = FakeTransport { json("""{"ok":true,"digits":6,"expiresIn":90}""") }.client().pairStart("P")
        assertEquals(90, r.expiresIn)
        assertEquals(6, FakeTransport { text("ok") }.client().pairStart("P").digits)
    }

    @Test
    fun serverErrorMapping() {
        assertEquals(DeviceError.AlreadyExists("a.bmp"), DeviceClient.mapServerError(400, "File already exists: a.bmp".toByteArray()))
        assertEquals(DeviceError.Server(400, "BAD_CHIP"), DeviceClient.mapServerError(400, """{"ok":false,"error":"BAD_CHIP"}""".toByteArray()))
        assertEquals(DeviceError.NotSupported, DeviceClient.mapServerError(404, ByteArray(0)))
        assertEquals(DeviceError.NotSupported, DeviceClient.mapServerError(404, "404 Not Found\n\nURI: /api/inklink/info".toByteArray()))
        assertEquals(DeviceError.Server(404, "not found"), DeviceClient.mapServerError(404, """{"ok":false,"error":"not found"}""".toByteArray()))
        assertEquals(DeviceError.PairingRequired, DeviceClient.mapServerError(401, ByteArray(0)))
        assertEquals(DeviceError.Server(500, "Failed to delete"), DeviceClient.mapServerError(500, "  Failed to delete \n".toByteArray()))
        assertEquals(200, (DeviceClient.mapServerError(500, "x".repeat(500).toByteArray()) as DeviceError.Server).text.length)
    }

    @Test
    fun appliedSettingsCount() = runBlocking {
        assertEquals(1, DeviceClient.appliedCount("Applied 1 setting(s)"))
        assertNull(DeviceClient.appliedCount("""{"ok":true}"""))
        val partial = FakeTransport { text("Applied 1 setting(s)") }.client()
        assertEquals(1, partial.updateSettings(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2))))
        // FBK-037: ответ без числа — настройки перечитываются, считаются совпавшие значения.
        val json = FakeTransport { req ->
            if (req.method == "GET") json("""[{"key":"a","type":"value","value":1},{"key":"b","type":"value","value":5}]""")
            else json("""{"ok":true}""")
        }
        assertEquals(1, json.client().updateSettings(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2))))
        assertEquals("перечитаны настройки", listOf("POST", "GET"), json.requests.map { it.method })
        val all = FakeTransport { req ->
            if (req.method == "GET") json("""[{"key":"a","type":"toggle","value":true},{"key":"b","type":"value","value":2.0}]""")
            else json("""{"ok":true}""")
        }.client()
        assertEquals("1 == true, 2 == 2.0", 2, all.updateSettings(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2))))
    }

    @Test
    fun okFalseWith200IsAServerError() {
        expect(DeviceError.Server(200, "BAD_CHIP")) {
            FakeTransport { json("""{"ok":false,"error":"BAD_CHIP"}""") }.client().deleteHighlight("h1")
        }
    }

    @Test
    fun textPlainUpstreamErrors() {
        expect(DeviceError.AlreadyExists("Dune.epub")) {
            FakeTransport { text("File already exists: Dune.epub", 400) }.client().upload(byteArrayOf(1), "Dune.epub", "/Books")
        }
        expect(DeviceError.NotSupported) { FakeTransport { text("404 Not Found\n\nURI: /api/inklink/stats", 404) }.client().stats() }
        expect(DeviceError.Decoding("/api/inklink/stats")) { FakeTransport { text("<html>") }.client().stats() }
    }

    @Test
    fun pathsAndQueriesArePercentEncoded() = runBlocking {
        val t = FakeTransport { json("[]") }
        val client = t.client()
        client.files("/Books/Дюна & Co+")
        val req = t.requests.last()
        assertEquals("/Books/Дюна & Co+", req.query["path"])
        assertTrue(req.url, req.url.startsWith("http://10.0.0.2/api/files?path=/Books/%D0%94"))
        assertFalse("'+' must be escaped", req.url.contains("+"))
        assertFalse(req.url.contains(" "))

        client.download("/screenshots/Дюна/a b.bmp")
        assertEquals("/screenshots/Дюна/a b.bmp", t.requests.last().query["path"])

        // Форма: всё не-ASCII и спецсимволы закодированы, '+' не превращается в пробел.
        client.rename("/Books/a+b.epub", "Мастер & Маргарита.epub")
        val form = t.requests.last()
        assertEquals("application/x-www-form-urlencoded; charset=utf-8", form.header("Content-Type"))
        val fields = HttpRequest.parseQuery(form.body!!.toString(Charsets.UTF_8))
        assertEquals("/Books/a+b.epub", fields["path"])
        assertEquals("Мастер & Маргарита.epub", fields["name"])
        assertEquals("name=", form.body!!.toString(Charsets.UTF_8).take(5))
    }

    @Test
    fun ensureDirectoryIsCaseInsensitiveAndCreatesMissing() = runBlocking {
        val t = FakeTransport { req ->
            when {
                req.path == "/api/files" && req.query["path"] == "/" ->
                    json("""[{"name":"books","isDirectory":true},{"name":"fonts","isDirectory":false}]""")
                req.path == "/api/files" -> json("[]")
                req.path == "/mkdir" && HttpRequest.parseQuery(req.body!!.toString(Charsets.UTF_8))["name"] == "Fonts" ->
                    text("Folder already exists", 400)
                else -> json("""{"ok":true}""")
            }
        }
        val client = t.client()
        assertEquals("/books", client.ensureDirectory("/Books"))
        assertTrue(t.requests.none { it.path == "/mkdir" })

        assertEquals("/books/SF/New", client.ensureDirectory("/Books/SF/New"))
        val made = t.requests.filter { it.path == "/mkdir" }.map { HttpRequest.parseQuery(it.body!!.toString(Charsets.UTF_8)) }
        assertEquals(listOf("SF" to "/books", "New" to "/books/SF"), made.map { it["name"] to it["path"] })

        // «exists» от гонки — не ошибка; файл с тем же именем папкой не считается.
        assertEquals("/Fonts/Lit", client.ensureDirectory("/Fonts/Lit"))
        // Папки с точкой не создаются.
        t.requests.clear()
        assertEquals("/.sleep", client.ensureDirectory("/.sleep"))
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun ensureDirectoryPropagatesRealMkdirErrors() {
        val t = FakeTransport { req -> if (req.path == "/mkdir") json("""{"ok":false,"error":"SD card full"}""", 500) else json("[]") }
        expect(DeviceError.Server(500, "SD card full")) { t.client().ensureDirectory("/sleep") }
    }

    @Test
    fun uploadWithReplaceDeletesExistingFirst() = runBlocking {
        val t = FakeTransport { req ->
            if (req.path == "/api/files") json("""[{"name":"card.bmp","size":3}]""") else json("""{"ok":true}""")
        }
        var lastProgress = 0L
        t.client().upload(byteArrayOf(1, 2, 3), "card.bmp", "/sleep", "image/bmp", replace = true) { d, _ -> lastProgress = d }
        assertEquals(listOf("/api/files", "/delete", "/upload"), t.requests.map { it.path })
        assertEquals("/sleep/card.bmp", HttpRequest.parseQuery(t.requests[1].body!!.toString(Charsets.UTF_8))["path"])
        val upload = t.requests[2]
        assertEquals("/sleep", upload.query["path"])
        assertTrue(upload.header("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        assertEquals(byteArrayOf(1, 2, 3).toList(), MultipartBody.fileContent(upload.body!!)!!.toList())
        assertEquals("card.bmp", MultipartBody.fileName(upload.body!!))
        assertTrue(lastProgress > 0)

        // Без replace — список не запрашивается.
        t.requests.clear()
        t.client().upload(byteArrayOf(1), "b.epub", "/Books")
        assertEquals(listOf("/upload"), t.requests.map { it.path })
        assertTrue(DeviceClient.uploadTimeoutMs(1) >= 30_000)
        assertEquals(15_000 + 6_000_000 / 30, DeviceClient.uploadTimeoutMs(6_000_000))
    }

    @Test
    fun selectSleepImageRequestShapeAndOldFirmware() = runBlocking {
        val t = FakeTransport()
        val client = t.client(token = "tok-1")
        client.selectSleepImage("foliolink-quote-1.bmp")
        client.selectSleepImage("")
        assertEquals(2, t.requests.size)
        val req = t.requests[0]
        assertEquals("/api/inklink/sleep/select", req.path)
        assertEquals("POST", req.method)
        assertEquals("tok-1", req.header(DeviceClient.TOKEN_HEADER))
        assertEquals(JsonPrimitive("foliolink-quote-1.bmp"), req.jsonBody()["name"])
        assertEquals("empty name = random again", JsonPrimitive(""), t.requests[1].jsonBody()["name"])
        assertTrue(DeviceClient.needsToken("POST", "/api/inklink/sleep/select"))

        for (reply in listOf(text("404 Not Found\n\nURI: /api/inklink/sleep/select", 404), json("""{"ok":false,"error":"not found"}""", 404))) {
            expect(DeviceError.NotSupported) { FakeTransport { reply }.client().selectSleepImage("a.bmp") }
        }
        expect(DeviceError.Server(400, "invalid image name")) {
            FakeTransport { json("""{"ok":false,"error":"invalid image name"}""", 400) }.client().selectSleepImage("../x.bmp")
        }
    }

    @Test
    fun needsTokenRules() {
        assertTrue(DeviceClient.needsToken("GET", "/api/inklink/info"))
        assertFalse(DeviceClient.needsToken("GET", "/api/inklink/books"))
        assertFalse(DeviceClient.needsToken("HEAD", "/download"))
        assertFalse(DeviceClient.needsToken("POST", "/api/inklink/pair/start"))
        assertFalse(DeviceClient.needsToken("POST", "/api/inklink/pair/finish"))
        assertTrue(DeviceClient.needsToken("POST", "/upload"))
        assertTrue(DeviceClient.needsToken("post", "/api/inklink/firmware/apply"))
    }

    @Test
    fun setTimeSendsEpochAndOffset() = runBlocking {
        val t = FakeTransport()
        t.client().setTime(1_790_380_800_123, java.util.TimeZone.getTimeZone("GMT+03:00"))
        val body = t.requests.last().jsonBody()
        assertEquals(JsonPrimitive(1_790_380_800L), body["epoch"])
        assertEquals(JsonPrimitive(180), body["tzOffsetMin"])
    }

    @Test
    fun firmwareApplyAndFontRoutes() = runBlocking {
        val t = FakeTransport { req ->
            when (req.path) {
                "/api/inklink/firmware/apply" -> json("""{"ok":true,"accepted":true,"confirmOnDevice":true}""")
                "/api/fonts" -> json("""{"families":[{"name":"Literata","sizes":[12,14]}],"maxFamilies":8}""")
                else -> json("""{"ok":true}""")
            }
        }
        val client = t.client(token = "t")
        val r = client.applyFirmware("/firmware/x.bin")
        assertTrue(r.isOk)
        assertEquals(true, r.confirmOnDevice)
        assertEquals(30_000, t.requests.last().timeoutMs)
        assertEquals(listOf(12, 14), client.fonts().families.first().sizes)
        client.uploadFont(byteArrayOf(9), "Literata_14.cpfont", family = "Literata")
        val up = t.requests.last()
        assertEquals("/api/fonts/upload", up.path)
        assertEquals("Literata", up.query["family"])
        assertEquals("t", up.header(DeviceClient.TOKEN_HEADER))
        expect(DeviceError.Server(422, "signature missing")) {
            FakeTransport { json("""{"error":"signature missing"}""", 422) }.client().applyFirmware("/firmware/x.bin")
        }
    }

    @Test
    fun highlightsForOneBookAndVocab() = runBlocking {
        val t = FakeTransport { req ->
            when (req.path) {
                "/api/inklink/highlights" -> json("""{"highlights":[{"id":"h1","book":"/b.epub","text":"t"}]}""")
                "/api/inklink/vocab" -> json("""{"words":[{"word":"w","count":"2"}]}""")
                "/api/inklink/sessions" -> json("""{"sessions":[{"book":"/b","start":5,"secs":"60"}]}""")
                else -> json("{}")
            }
        }
        val client = t.client()
        assertEquals("h1", client.highlights("/b.epub").single().id)
        assertEquals("/b.epub", t.requests.last().query["book"])
        assertEquals(2, client.vocab().single().count)
        assertEquals(60, client.sessions(since = 42).single().secs)
        assertEquals("42", t.requests.last().query["since"])
    }

    @Test
    fun dictionariesRoutes() = runBlocking {
        val t = FakeTransport { req ->
            when (req.path) {
                "/api/inklink/dictionaries" -> json(
                    """{"selected":"en-ru-wikdict","root":"/dictionaries","dictionaries":[
                    {"name":"en-ru-wikdict","path":"/dictionaries/en-ru-wikdict","stem":"en-ru-wikdict",
                     "title":"English-Russian (WikDict)","words":123456,"script":"latin","sizeKB":34567,
                     "html":true,"synonyms":false,"indexed":true,"future":1},
                    {"name":"crosspoint","path":"/.dictionaries/crosspoint","words":"12","sizeKB":1.5,"indexed":0}]}""",
                )
                "/api/inklink/dictionaries/select" -> if (req.jsonBody()["name"].toString() == "\"gone\"") {
                    json("""{"ok":false,"error":"no such dictionary"}""", 404)
                } else {
                    json("""{"ok":true,"selected":"-"}""")
                }
                else -> json("""{"ok":true}""")
            }
        }
        val client = t.client(token = "tok")
        val r = client.dictionaries()
        assertEquals("en-ru-wikdict", r.selected)
        assertEquals("en-ru-wikdict", r.effectiveSelection)
        assertEquals(2, r.dictionaries.size)
        val d = r.dictionaries[0]
        assertEquals("English-Russian (WikDict)", d.displayTitle)
        assertEquals(123456, d.words)
        assertEquals("latin", d.script)
        assertEquals(34567L, d.sizeKB)
        assertTrue(d.html && d.indexed && !d.synonyms)
        val cp = r.dictionaries[1]
        assertEquals("crosspoint", cp.displayTitle)
        assertEquals(12, cp.words)
        assertEquals(1L, cp.sizeKB)
        assertFalse(cp.indexed)
        assertEquals("*", r.copy(selected = "gone").effectiveSelection)
        assertNull(t.requests.last().header(DeviceClient.TOKEN_HEADER))

        client.selectDictionary("-")
        assertEquals("POST", t.requests.last().method)
        assertEquals("application/json", t.requests.last().header("Content-Type"))
        assertEquals("tok", t.requests.last().header(DeviceClient.TOKEN_HEADER))
        assertEquals("\"-\"", t.requests.last().jsonBody()["name"].toString())
        expect(DeviceError.Server(404, "no such dictionary")) { client.selectDictionary("gone") }

        client.deleteDictionary("en-ru-wikdict")
        assertEquals("/api/inklink/dictionaries/delete", t.requests.last().path)
        assertEquals("\"en-ru-wikdict\"", t.requests.last().jsonBody()["name"].toString())
        assertEquals("tok", t.requests.last().header(DeviceClient.TOKEN_HEADER))

        // Пустой ответ старой прошивки: всё по умолчанию.
        t.handler = { json("{}") }
        val empty = client.dictionaries()
        assertEquals("*", empty.selected)
        assertTrue(empty.dictionaries.isEmpty())
    }

    @Test
    fun multipartBody() {
        val m = MultipartBody("XYZ").addFile("file", "a\"b.bmp", "image/bmp", byteArrayOf(1, 2, 3))
        val s = m.finalized().toString(Charsets.ISO_8859_1)
        assertTrue(s.startsWith("--XYZ\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a_b.bmp\"\r\nContent-Type: image/bmp\r\n\r\n"))
        assertTrue(s.endsWith("\r\n--XYZ--\r\n"))
        assertEquals("multipart/form-data; boundary=XYZ", m.contentType)
        val withField = MultipartBody("B").addField("family", "Lit").finalized().toString(Charsets.UTF_8)
        assertEquals("--B\r\nContent-Disposition: form-data; name=\"family\"\r\n\r\nLit\r\n--B--\r\n", withField)
        assertEquals(listOf<Byte>(1, 2, 3), MultipartBody.fileContent(m.finalized())!!.toList())
    }

    @Test
    fun percentCodingRoundTrip() {
        val s = "/Книги/a b+c&d=e?f#g%h~._-"
        val enc = PercentCoding.encode(s, keep = "/")
        assertTrue(enc.all { it.code < 0x80 })
        assertEquals(s, PercentCoding.decode(enc, plusIsSpace = false))
        assertEquals("a b", PercentCoding.decode("a+b"))
        assertEquals("100%", PercentCoding.decode("100%"))
    }
}
