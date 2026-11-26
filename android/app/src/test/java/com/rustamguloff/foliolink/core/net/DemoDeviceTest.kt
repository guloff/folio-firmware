package com.rustamguloff.foliolink.core.net

import com.rustamguloff.foliolink.core.store.SettingsProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Демо-читалка отвечает тем же JSON, что прошивка, через обычный DeviceClient. */
class DemoDeviceTest {
    private fun client() = DeviceClient(DemoDevice.ENDPOINT, transport = DemoTransport(DemoReader()))

    @Test
    fun servesTheWholeApi() = runBlocking {
        val client = client()
        val info = client.info()
        assertEquals(false, info.pairing?.required)
        assertTrue("stats" in info.features)
        assertEquals("DEMO-FOLIO", client.status().knownSerial)
        val books = client.books(pageSize = 3)
        assertEquals(9, books.size)
        // FB2 (Folio 1.1) — обычная книга списка: путь .fb2, название из метаданных.
        val fb2 = books.single { it.path.endsWith(".fb2") }
        assertEquals("Каштанка", fb2.displayTitle)
        assertEquals(com.rustamguloff.foliolink.core.model.BookFormat.FB2, fb2.format)
        assertTrue(fb2.shelves.isNotEmpty())
        assertTrue(client.files("/books").any { it.name == "Chekhov - Kashtanka.fb2" && !it.isEpub })
        assertTrue(books.any { it.author == "Jane Austen" })
        assertTrue(books.all { it.shelves.isNotEmpty() })
        val sessions = client.sessions(0)
        assertTrue(sessions.size > 50)
        assertEquals(sessions.sortedBy { it.start }, sessions)
        val stats = client.stats()
        assertEquals(sessions.size, stats.totals.sessions)
        assertEquals(3, stats.totals.books)
        assertTrue(stats.streak.longest >= stats.streak.current)
        assertEquals(5, client.highlights().size)
        assertEquals(3, client.vocab().size)
        assertEquals(2, client.library().shelves.size)
        val shots = client.screenshots()
        assertEquals(2, shots.size)
        assertTrue(shots.all { it.capturedAt != null && it.bookTitle != null })
        val bmp = client.download(shots[0].path)
        assertEquals('B'.code.toByte(), bmp[0])
        assertEquals('M'.code.toByte(), bmp[1])
        assertEquals(54 + 480 * 3 * 800, bmp.size)
        val sleep = client.sleepImages()
        assertEquals(5, sleep.images.size)
        assertEquals(8, sleep.mode)
        assertEquals("", sleep.selected)
        assertEquals(sleep.images[0].size.toInt(), client.download(sleep.images[0].path).size)
        assertTrue(client.settings().any { it.key == "sleepScreen" })
        assertTrue(client.fonts().families.isEmpty())
        client.setTime()
        assertTrue(client.applyFirmware("/firmware/x.bin").confirmOnDevice == true)
        try {
            client.pairStart("Pixel")
            fail()
        } catch (e: DeviceError.Server) {
            assertEquals(409, e.status)
        }
    }

    @Test
    fun sessionsSinceIsInclusive() = runBlocking {
        val client = client()
        val all = client.sessions(0)
        val pivot = all[all.size / 2].start
        val tail = client.sessions(pivot)
        assertEquals(all.filter { it.start >= pivot }, tail)
    }

    @Test
    fun editsChangeTheVirtualReader() = runBlocking {
        val client = client()
        val lib = client.library()
        client.saveLibrary(lib.copy(status = lib.status + ("/books/Frankenstein.epub" to "reading")))
        assertEquals("reading", client.books().first { it.path == "/books/Frankenstein.epub" }.status)
        client.updateHighlight("hdemo1", "мысль")
        assertEquals("мысль", client.highlights().first { it.id == "hdemo1" }.note)
        try {
            client.updateHighlight("nope", "x")
            fail()
        } catch (e: DeviceError) {
            assertEquals(DeviceError.Server(404, "not found"), e)
        }
        client.deleteHighlight("hdemo0")
        assertEquals(4, client.highlights().size)
        client.deleteVocab("leviathan")
        assertFalse(client.vocab().any { it.word == "leviathan" })
        client.upload("epub".toByteArray(), "New.epub", "/books")
        assertTrue(client.files("/books").any { it.name == "New.epub" && it.isEpub })
    }

    @Test
    fun selectsAndForgetsDeletedImage() = runBlocking {
        val client = client()
        val name = "foliolink-test.bmp"
        client.upload(byteArrayOf(0x42, 0x4D, 1, 2, 3), name, "/sleep", "image/bmp", replace = true)
        client.selectSleepImage(name)
        val selected = client.sleepImages()
        assertEquals(name, selected.selected)
        assertTrue(selected.images.any { it.name == name })
        assertArrayEquals("the demo serves what was uploaded", byteArrayOf(0x42, 0x4D, 1, 2, 3), client.download("/sleep/$name"))
        try {
            client.selectSleepImage(".hidden.bmp")
            fail("expected 400")
        } catch (e: DeviceError) {
            assertEquals(DeviceError.Server(400, "invalid image name"), e)
        }
        client.delete("/sleep/$name")
        val after = client.sleepImages()
        assertEquals("a selected file that is gone reads as random", "", after.selected)
        assertFalse(after.images.any { it.name == name })
    }

    @Test
    fun appliesASettingsProfile() = runBlocking {
        val client = client()
        val before = client.settings()
        val values = SettingsProfile.capture(before).toMutableMap()
        val size = SettingsProfile.intValue(values.getValue("fontSize"))!!
        values["fontSize"] = JsonPrimitive(if (size == 2) 3 else 2)
        values["notOnThisReader"] = JsonPrimitive(1)
        val plan = SettingsProfile(name = "t", values = values).plan(before)
        assertEquals(listOf("fontSize"), plan.changes.keys.toList())
        assertEquals(listOf("notOnThisReader"), plan.unknown)
        client.updateSettings(plan.changes)
        val after = client.settings().first { it.key == "fontSize" }
        assertEquals(if (size == 2) 3 else 2, SettingsProfile.intValue(after.value))
        client.updateSettings(mapOf("sleepScreen" to JsonPrimitive(2)))
        assertEquals(2, client.sleepImages().mode)
    }

    @Test
    fun demoDictionaries() = runBlocking {
        val client = client()
        assertTrue("dictionaries" in client.info().features)
        val r = client.dictionaries()
        assertEquals("*", r.selected)
        assertEquals(listOf("en-ru-demo", "ru-en-demo"), r.dictionaries.map { it.name })
        assertEquals(listOf("latin", "cyrillic"), r.dictionaries.map { it.script })
        assertTrue(r.dictionaries.all { it.words > 0 && it.sizeKB > 0 && it.indexed })

        client.selectDictionary("ru-en-demo")
        assertEquals("ru-en-demo", client.dictionaries().selected)
        try {
            client.selectDictionary("nope")
            fail()
        } catch (e: DeviceError.Server) {
            assertEquals(404, e.status)
        }

        // Установка — как в приложении: папка + файлы через /upload.
        val dir = client.ensureDirectory("/dictionaries/de-en")
        val ifo = "StarDict's dict ifo file\nbookname=Deutsch-English\nwordcount=7\nlang=de-en\n".toByteArray()
        client.upload(ifo, "de-en.ifo", dir)
        client.upload(byteArrayOf(1, 2, 3), "de-en.idx", dir)
        assertFalse(client.dictionaries().dictionaries.any { it.name == "de-en" })
        client.upload(byteArrayOf(4, 5, 6), "de-en.dict.dz", dir)
        val added = client.dictionaries().dictionaries.first { it.name == "de-en" }
        assertEquals("Deutsch-English", added.title)
        assertEquals(7, added.words)
        assertFalse(added.indexed)

        client.deleteDictionary("ru-en-demo")
        val after = client.dictionaries()
        assertEquals("*", after.selected)
        assertEquals(listOf("de-en", "en-ru-demo"), after.dictionaries.map { it.name })
        try {
            client.deleteDictionary("ru-en-demo")
            fail()
        } catch (e: DeviceError.Server) {
            assertEquals(404, e.status)
        }
    }

    @Test
    fun seedIsDeterministic() = runBlocking {
        val a = client().sessions(0).map { it.secs }
        val b = client().sessions(0).map { it.secs }
        assertEquals(a, b)
    }
}
