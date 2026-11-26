package com.rustamguloff.foliolink.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun decodesInfoExampleFromSpec() {
        val info = decodeApi<DeviceInfo>(
            """{"api":1,"firmware":"0.1.0-x4pro","board":"xteink_x4_pro","battery":87,"charging":false,
            "clock":{"available":true,"valid":true,"epoch":1790380800},"sdTotalMB":29000,"sdFreeMB":27000,
            "pairing":{"required":true,"paired":true,"authorized":false},
            "features":["stats","library","highlights","vocab","screenshots","sleep","ota","pairing"]}""",
        )
        assertEquals(87, info.battery)
        assertEquals(1790380800L, info.clock.epoch)
        assertEquals(true, info.pairing?.paired)
        assertTrue(info.supports("ota"))
        assertFalse(info.supports("fonts"))
    }

    @Test
    fun lenientFieldsFallBack() {
        val info = decodeApi<DeviceInfo>("""{"battery":"55","charging":1,"features":null}""")
        assertEquals(55, info.battery)
        assertTrue(info.charging)
        assertTrue(info.features.isEmpty())
        assertNull(info.pairing)
        val book = decodeApi<Book>("""{"path":"/Books/Дюна.epub","status":null}""")
        assertEquals(-1, book.progress)
        assertEquals("Дюна", book.displayTitle)
        assertEquals(-1, book.recent)
    }

    @Test
    fun libraryDocKeepsUnknownKeys() {
        val raw = """{"version":1,"shelves":[{"id":"sf","name":"Фантастика","books":["/Books/Dune.epub"]}],
            "status":{"/Books/Dune.epub":"reading"},"goals":{"dailyMinutes":45,"weekly":3},"future":{"x":1}}"""
        val doc = decodeApi<LibraryDoc>(raw)
        assertEquals(45, doc.dailyMinutes)
        val changed = LibraryChange.SetStatus("/Books/Dune.epub", "done").apply(doc)
        val out = FolioJson.encodeToString(LibraryDoc.serializer(), changed)
        val o = FolioJson.parseToJsonElement(out).jsonObject
        assertEquals(JsonPrimitive(3), o["goals"]!!.jsonObject["weekly"])
        assertEquals(JsonPrimitive(1), o["future"]!!.jsonObject["x"])
        assertEquals("done", decodeApi<LibraryDoc>(out).status["/Books/Dune.epub"])
    }

    @Test
    fun libraryChangesAreIdempotent() {
        var doc = LibraryDoc()
        val changes = listOf(
            LibraryChange.CreateShelf("s1", "Полка"),
            LibraryChange.ShelfMembership("s1", "/a.epub", true),
            LibraryChange.SetGoal(20),
        )
        changes.forEach { doc = it.apply(doc) }
        val once = doc
        changes.forEach { doc = it.apply(doc) }
        assertEquals(once, doc)
        assertEquals(listOf("/a.epub"), doc.shelves.single().books)
    }

    @Test
    fun outboxUnknownOperationIsKeptVerbatim() {
        val raw = """{"id":"x1","fileName":"","title":"t","source":"s","added":1,
            "operation":{"type":"teleport","to":"mars"}}"""
        val item = OutboxItem.fromJson(FolioJson.parseToJsonElement(raw).jsonObject)!!
        assertFalse(item.operation.isSupported)
        val back = item.toJson()["operation"] as JsonObject
        assertEquals(JsonPrimitive("mars"), back["to"])
        val legacy = OutboxItem.fromJson(FolioJson.parseToJsonElement("""{"id":"y","fileName":"a.epub"}""").jsonObject)!!
        assertEquals(OutboxOperation.UploadBook, legacy.operation)
    }

    @Test
    fun outboxOperationsRoundTrip() {
        val ops = listOf(
            OutboxOperation.UploadBook,
            OutboxOperation.UploadFile("/sleep", "image/bmp", true, OutboxOperation.UploadPurpose.SLEEP),
            OutboxOperation.DeleteFile("/a"),
            OutboxOperation.DeleteFontFamily("Lit"),
            OutboxOperation.Library(LibraryChange.SetStatus("/b", null)),
            OutboxOperation.HighlightNote("h1", "n"),
            OutboxOperation.DeleteHighlight("h1"),
            OutboxOperation.DeleteVocab("w"),
            OutboxOperation.Settings(mapOf("k" to JsonPrimitive(1))),
            OutboxOperation.SelectSleepImage(""),
            OutboxOperation.SelectDictionary("*"),
            OutboxOperation.DeleteDictionary("en-ru"),
            OutboxOperation.UploadFile("/dictionaries/en-ru", "application/octet-stream", true, OutboxOperation.UploadPurpose.DICTIONARY),
        )
        ops.forEach { assertEquals(it, OutboxOperation.fromJson(it.toJson())) }
        // Без имени папки операция словаря не понимается (хранится как Unsupported).
        assertTrue(OutboxOperation.fromJson(Json.parseToJsonElement("""{"type":"deleteDictionary"}""")) is OutboxOperation.Unsupported)
    }

    @Test
    fun endpointParsing() {
        assertEquals(DeviceEndpoint("127.0.0.1", 8080), DeviceEndpoint.parse(" http://127.0.0.1:8080/ "))
        assertEquals("http://folio.local", DeviceEndpoint.parse("folio.local")!!.baseUrl)
        assertNull(DeviceEndpoint.parse(""))
    }

    @Test
    fun screenshotCaptureTime() {
        assertEquals(1790380800L, RemoteFile(name = "Dune_p12_1790380800.bmp").capturedAt)
        assertNull(RemoteFile(name = "screenshot-12.bmp").capturedAt)
    }
}
