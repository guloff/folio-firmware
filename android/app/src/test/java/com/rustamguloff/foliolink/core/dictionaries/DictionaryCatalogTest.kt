package com.rustamguloff.foliolink.core.dictionaries

import com.rustamguloff.foliolink.core.model.DeviceInfo
import com.rustamguloff.foliolink.core.model.DictionariesResponse
import com.rustamguloff.foliolink.core.model.DictionaryInfo
import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/** Манифест каталога, sha256, загрузка с локального сервера, правила экрана. */
class DictionaryCatalogTest {
    private val sha = "a".repeat(64)

    private val manifest = """
        {"version":1,"dictionaries":[
          {"id":"en-ru-wikdict","title":{"en":"English–Russian (WikDict)","ru":"Англо-русский (WikDict)"},
           "from":"en","to":"ru","headwords":123456,"license":"CC BY-SA 4.0",
           "attribution":"© WikDict, Wiktionary contributors","source":"https://www.wikdict.com/",
           "files":[{"name":"en-ru-wikdict.ifo","size":312,"sha256":"$sha"},
                    {"name":"en-ru-wikdict.idx","size":2048,"sha256":"${sha.uppercase()}"},
                    {"name":"en-ru-wikdict.dict.dz","size":"4096","sha256":"$sha"}],
           "extra":{"ignored":true}},
          {"id":"ru-en","title":"Russian–English","from":"ru","to":"en","headwords":10,
           "files":[{"name":"ru-en.ifo","size":1,"sha256":"$sha"}]},
          {"id":".hidden","title":{"en":"bad id"},"files":[{"name":"x.ifo","size":1,"sha256":"$sha"}]},
          {"id":"no-files","title":{"en":"No files"},"files":[]},
          {"id":"bad-sha","files":[{"name":"x.ifo","size":1,"sha256":"xyz"}]},
          {"id":"bad-path","files":[{"name":"../x.ifo","size":1,"sha256":"$sha"}]},
          {"id":"EN-RU-WIKDICT","files":[{"name":"dup.ifo","size":1,"sha256":"$sha"}]}
        ]}
    """.trimIndent()

    @Test
    fun parsesManifest() {
        val m = DictionaryCatalog.parse(manifest)
        assertEquals(1, m.version)
        assertEquals(listOf("en-ru-wikdict", "ru-en"), m.dictionaries.map { it.id })
        val e = m.dictionaries[0]
        assertEquals("Англо-русский (WikDict)", e.title("ru"))
        assertEquals("English–Russian (WikDict)", e.title("en"))
        assertEquals("English–Russian (WikDict)", e.title("de"))
        assertEquals("en", e.from)
        assertEquals("ru", e.to)
        assertEquals(123456, e.headwords)
        assertEquals("CC BY-SA 4.0", e.license)
        assertEquals("© WikDict, Wiktionary contributors", e.attribution)
        assertEquals("https://www.wikdict.com/", e.source)
        assertEquals(listOf("en-ru-wikdict.ifo", "en-ru-wikdict.idx", "en-ru-wikdict.dict.dz"), e.files.map { it.name })
        assertEquals(312L + 2048 + 4096, e.totalSize)
        assertTrue(e.files.all { it.sha256 == sha })
        // Название строкой — английское; нет полей — пустые.
        val r = m.dictionaries[1]
        assertEquals("Russian–English", r.title("ru"))
        assertEquals("", r.license)
    }

    /** Настоящий манифест релиза dictionaries-1 (копия в test/resources): лишнее поле `inputs` не мешает. */
    @Test
    fun parsesTheRealReleaseManifest() {
        val text = javaClass.classLoader!!.getResource("dictionaries-1-manifest.json")!!.readText()
        val m = DictionaryCatalog.parse(text)
        assertEquals(
            listOf("en-ru-wiktionary", "ru-en-wiktionary", "en-en-wiktionary", "ru-ru-wiktionary", "tg-en-wiktionary", "tg-ru-wiktionary"),
            m.dictionaries.map { it.id },
        )
        for (e in m.dictionaries) {
            assertEquals(e.id, 4, e.files.size)
            assertEquals(e.id, listOf(".ifo", ".idx", ".dict.dz", ".syn").map { e.id + it }, e.files.map { it.name })
            assertEquals("CC BY-SA 4.0", e.license)
            assertTrue(e.id, e.headwords > 0 && e.attribution.isNotEmpty() && e.source.startsWith("https://"))
            assertTrue(e.titles["ru"]!!.isNotEmpty() && e.titles["en"]!!.isNotEmpty())
        }
        val enEn = m.dictionaries.first { it.id == "en-en-wiktionary" }
        assertEquals("English explanatory (Wiktionary)", enEn.title("en"))
        assertEquals("Толковый английский (Викисловарь)", enEn.title("ru"))
        assertTrue(enEn.totalSize > 40_000_000)
    }

    @Test
    fun rejectsBadManifests() {
        for (bad in listOf("", "[]", "not json", """{"version":1}""", """{"version":2,"dictionaries":[]}""")) {
            try {
                DictionaryCatalog.parse(bad)
                fail("accepted: $bad")
            } catch (e: CatalogError) {
                assertEquals(CatalogError.BadManifest, e)
            }
        }
        assertEquals(0, DictionaryCatalog.parse("""{"dictionaries":[]}""").dictionaries.size)
    }

    @Test
    fun fileUrlsSitNextToTheManifest() {
        assertEquals(
            "https://github.com/guloff/folio-firmware/releases/download/dictionaries-1/en-ru.dict.dz",
            DictionaryCatalog.fileUrl(DictionaryCatalog.DEFAULT_MANIFEST_URL, "en-ru.dict.dz"),
        )
        assertEquals("http://10.0.2.2:8000/sub/a%20b.ifo", DictionaryCatalog.fileUrl("http://10.0.2.2:8000/sub/manifest.json", "a b.ifo"))
    }

    @Test
    fun verifiesSha256AndSize() {
        // Эталон FIPS 180-2.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DictionaryCatalog.sha256Hex("abc".toByteArray()))
        val data = "abc".toByteArray()
        val good = CatalogFile("a.ifo", 3, "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD")
        DictionaryCatalog.verify(good, data)
        try {
            DictionaryCatalog.verify(good, "abd".toByteArray())
            fail()
        } catch (e: CatalogError.Checksum) {
            assertEquals("a.ifo", e.name)
        }
        try {
            DictionaryCatalog.verify(good, "abcd".toByteArray())
            fail()
        } catch (e: CatalogError.SizeMismatch) {
            assertEquals(4L, e.actual)
        }
    }

    // region Загрузка по HTTP

    private lateinit var server: HttpServer
    private val payload = ByteArray(200_000) { (it % 251).toByte() }

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            when (path) {
                "/cat/manifest.json" -> {
                    val body = manifest.toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
                }
                "/cat/file.dict.dz" -> {
                    ex.sendResponseHeaders(200, payload.size.toLong()); ex.responseBody.use { it.write(payload) }
                }
                "/moved/manifest.json" -> {
                    ex.responseHeaders.add("Location", "/cat/manifest.json")
                    ex.sendResponseHeaders(302, -1); ex.close()
                }
                else -> { ex.sendResponseHeaders(404, -1); ex.close() }
            }
        }
        server.start()
    }

    @After
    fun stopServer() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Test
    fun fetchesWithProgressAndFollowsRedirects() = runBlocking {
        var last = 0L
        val data = DictionaryCatalog.fetch(DictionaryCatalog.fileUrl("$base/cat/manifest.json", "file.dict.dz")) { done, total ->
            assertTrue(done >= last); last = done
            assertEquals(payload.size.toLong(), total)
        }
        assertArrayEquals(payload, data)
        assertEquals(payload.size.toLong(), last)
        assertEquals(2, DictionaryCatalog.load("$base/moved/manifest.json").dictionaries.size)
    }

    @Test
    fun missingCatalogIsHttp404AndLimitHolds() = runBlocking {
        try {
            DictionaryCatalog.load("$base/nope/manifest.json")
            fail()
        } catch (e: CatalogError.Http) {
            assertEquals(404, e.code)
        }
        try {
            DictionaryCatalog.fetch("$base/cat/file.dict.dz", limit = 1000)
            fail()
        } catch (e: CatalogError.TooLarge) {
            assertEquals("file.dict.dz", e.name)
        }
    }

    // endregion

    // region Правила экрана

    private val list = DictionariesResponse(
        selected = "en-ru",
        dictionaries = listOf(DictionaryInfo(name = "en-ru", title = "EN-RU"), DictionaryInfo(name = "ru-en", title = "")),
    )

    @Test
    fun appliesQueuedChangesToTheCache() {
        assertEquals("-", DictionaryRules.apply(OutboxOperation.SelectDictionary("-"), list)!!.selected)
        val deleted = DictionaryRules.apply(OutboxOperation.DeleteDictionary("en-ru"), list)!!
        assertEquals(listOf("ru-en"), deleted.dictionaries.map { it.name })
        assertEquals("*", deleted.selected)
        val other = DictionaryRules.apply(OutboxOperation.DeleteDictionary("ru-en"), list)!!
        assertEquals("en-ru", other.selected)
        assertNull(DictionaryRules.apply(OutboxOperation.SelectDictionary("*"), null))
        assertEquals(list, DictionaryRules.apply(OutboxOperation.DeleteFile("/x"), list))
    }

    @Test
    fun modeInstalledAndSupport() {
        assertEquals("en-ru", DictionaryRules.mode(list))
        assertEquals("*", DictionaryRules.mode(list.copy(selected = "gone")))
        assertEquals("*", DictionaryRules.mode(null))
        assertTrue(DictionaryRules.isInUse(list.dictionaries[0], list))
        assertFalse(DictionaryRules.isInUse(list.dictionaries[1], list))
        assertTrue(DictionaryRules.isInUse(list.dictionaries[1], list.copy(selected = "*")))
        assertFalse(DictionaryRules.isInUse(list.dictionaries[0], list.copy(selected = "-")))
        assertEquals("ru-en", list.dictionaries[1].displayTitle)

        val entry = DictionaryCatalog.parse(manifest).dictionaries[0]
        assertTrue(DictionaryRules.isInstalled(entry, listOf(DictionaryInfo(name = "EN-RU-WikDict"))))
        assertFalse(DictionaryRules.isInstalled(entry, list.dictionaries))

        assertNull(DictionaryRules.supported(null))
        assertEquals(false, DictionaryRules.supported(DeviceInfo(features = listOf("stats"))))
        assertEquals(true, DictionaryRules.supported(DeviceInfo(features = listOf("stats", "dictionaries"))))
    }

    // endregion
}
