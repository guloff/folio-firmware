package com.rustamguloff.foliolink.core.store

import com.rustamguloff.foliolink.core.model.LibraryChange
import com.rustamguloff.foliolink.core.model.OutboxItem
import com.rustamguloff.foliolink.core.model.OutboxOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Очередь в файлах: порядок, нагрузка, старые и повреждённые индексы, неизвестные операции. */
class OutboxStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir get() = File(tmp.root, "outbox")
    private fun index() = File(dir, "outbox.json")
    private fun writeIndex(json: String) { dir.mkdirs(); index().writeText(json) }
    private fun rawOperations(): List<Any?> =
        (Json.parseToJsonElement(index().readText()) as JsonArray).map { (it as JsonObject)["operation"] }

    @Test
    fun persistsEveryKindInOrderAcrossInstances() {
        val outbox = Outbox(dir)
        outbox.enqueue(OutboxOperation.Library(LibraryChange.SetStatus("/Books/Dune.epub", "done")), "status")
        outbox.enqueue(OutboxOperation.HighlightNote("h1", "мысль"), "note")
        outbox.enqueue(OutboxOperation.UploadFile("/sleep", "image/bmp", true, OutboxOperation.UploadPurpose.SLEEP),
            "sleep", payload = byteArrayOf(1, 2, 3), fileName = "card.bmp")
        outbox.enqueue(OutboxOperation.Settings(mapOf("sleepScreen" to JsonPrimitive(8))), "settings")
        outbox.enqueue(OutboxOperation.DeleteFile("/screenshots/a.bmp"), "delete")
        outbox.enqueue(OutboxOperation.DeleteFontFamily("Literata"), "font")
        outbox.enqueue(OutboxOperation.Library(LibraryChange.CreateShelf("sf", "Фантастика")), "shelf")
        outbox.enqueue(OutboxOperation.SelectSleepImage(""), "random")

        val reopened = Outbox(dir).items()
        assertEquals(listOf("status", "note", "sleep", "settings", "delete", "font", "shelf", "random"), reopened.map { it.title })
        assertEquals(OutboxOperation.Library(LibraryChange.SetStatus("/Books/Dune.epub", "done")), reopened[0].operation)
        assertEquals(OutboxOperation.Settings(mapOf("sleepScreen" to JsonPrimitive(8))), reopened[3].operation)
        assertEquals(OutboxOperation.SelectSleepImage(""), reopened[7].operation)
        assertArrayEquals(byteArrayOf(1, 2, 3), Outbox(dir).payload(reopened[2]))
        assertNull(Outbox(dir).payload(reopened[0]))
        assertEquals("", reopened[0].fileName)

        outbox.update(reopened[1].id) { it.copy(lastError = "boom", failed = true, attempts = 2) }
        val again = Outbox(dir).items()[1]
        assertEquals("boom", again.lastError)
        assertTrue(again.failed)
        assertEquals(2, again.attempts)

        outbox.update("missing") { it.copy(failed = true) }
        assertEquals(8, outbox.items().size)
    }

    @Test
    fun payloadLivesNextToTheIndexAndIsRemovedWithTheItem() {
        val outbox = Outbox(dir)
        val item = outbox.enqueueBook("epub".toByteArray(), "a/b:c?.epub", "Book", "share")
        assertEquals("a_b_c_.epub", item.fileName)
        assertTrue(outbox.fileFor(item).exists())
        assertEquals(File(File(dir, item.id), "a_b_c_.epub"), outbox.fileFor(item))
        // Файл пропал — элемент с нагрузкой скрыт.
        outbox.fileFor(item).delete()
        assertTrue(outbox.items().isEmpty())
        val b = outbox.enqueueBook("x".toByteArray(), "x.epub", "X", "")
        outbox.remove(b)
        assertFalse(File(dir, b.id).exists())
        assertTrue(outbox.items().isEmpty())
    }

    @Test
    fun legacyItemWithoutOperationIsABookUpload() {
        val id = "1A2B3C4D-0000-0000-0000-000000000001"
        val file = File(File(dir, id), "x.epub")
        file.parentFile!!.mkdirs()
        file.writeText("epub")
        writeIndex("""[{"id":"$id","fileName":"x.epub","title":"X","source":"share","added":1790380800}]""")
        val items = Outbox(dir).items()
        assertEquals(1, items.size)
        assertEquals(OutboxOperation.UploadBook, items[0].operation)
        assertFalse(items[0].failed)
        writeIndex("""[{"id":"$id","fileName":"x.epub","title":"X","source":"share","added":1790380800,"operation":null}]""")
        assertEquals(listOf(OutboxOperation.UploadBook), Outbox(dir).items().map { it.operation })
    }

    @Test
    fun damagedIndexKeepsReadableItemsAndACopy() {
        val outbox = Outbox(dir)
        writeIndex(
            """[{"id":"good-1","fileName":"","title":"keep","source":"","added":1790380800,
                "operation":{"type":"deleteVocab","word":"w"}},
               {"title":"broken, no id"}, 42]""",
        )
        assertEquals("the readable entry survives", listOf("keep"), outbox.items().map { it.title })
        outbox.enqueue(OutboxOperation.DeleteVocab("new"), "new")
        assertEquals(listOf("keep", "new"), outbox.items().map { it.title })
        assertEquals("the damaged original is kept aside", 1, outbox.unreadableBackups().size)
        assertTrue(outbox.unreadableBackups()[0].readText().contains("broken, no id"))

        index().writeText("{garbage")
        assertTrue(outbox.items().isEmpty())
        outbox.enqueue(OutboxOperation.DeleteVocab("after"), "after")
        assertTrue(outbox.unreadableBackups().isNotEmpty())
        assertEquals(listOf("after"), outbox.items().map { it.title })
    }

    @Test
    fun unknownOperationSurvivesIndexRewriteUnchanged() {
        writeIndex(
            """[{"id":"audio-1","fileName":"clip.mp3","title":"Аудио","source":"x","added":1790380800,
                "operation":{"type":"uploadAudio","dir":"/audio","bitrate":128,"tags":["a",null,1.5]},"deviceKey":"SERIAL-A"}]""",
        )
        val outbox = Outbox(dir)
        val items = outbox.items()
        assertEquals("not filtered out although its payload file is missing", listOf("Аудио"), items.map { it.title })
        assertFalse(items[0].operation.isSupported)
        assertNotEquals("never turned into a book upload", OutboxOperation.UploadBook, items[0].operation)
        assertEquals("SERIAL-A", items[0].deviceKey)

        val before = rawOperations().first()
        outbox.enqueue(OutboxOperation.DeleteVocab("w"), "later")
        outbox.update(items[0].id) { it.copy(attempts = it.attempts) }
        val after = rawOperations()
        assertEquals(2, after.size)
        assertEquals("the raw operation is written back unchanged", before, after[0])
        assertEquals(listOf("Аудио", "later"), Outbox(dir).items().map { it.title })
        assertTrue("a known-shaped entry is not damage", outbox.unreadableBackups().isEmpty())
    }

    @Test
    fun knownTypeWithMissingFieldsIsUnsupportedToo() {
        writeIndex("""[{"id":"x1","fileName":"a.bin","title":"broken","source":"","operation":{"type":"uploadFile"}}]""")
        val items = Outbox(dir).items()
        assertEquals(1, items.size)
        assertFalse(items[0].operation.isSupported)
    }

    @Test
    fun encodingOfKnownOperationsRoundTrips() {
        val item = OutboxItem(fileName = "", title = "t", source = "",
            operation = OutboxOperation.Settings(mapOf("a" to JsonPrimitive(1))), deviceKey = "K")
        val back = OutboxItem.fromJson(item.toJson())
        assertEquals(item, back)
        assertNull(item.toJson()["lastError"])
    }

    @Test
    fun itemsOfOlderBuildsGoToAnyReader() {
        val item = OutboxItem(fileName = "", title = "x", source = "")
        assertTrue(item.belongsTo("SERIAL-A"))
        assertTrue(item.belongsTo(null))
        val bound = OutboxItem(fileName = "", title = "x", source = "", operation = OutboxOperation.DeleteVocab("w"), deviceKey = "A")
        assertTrue(bound.belongsTo("A"))
        assertFalse(bound.belongsTo("B"))
        assertFalse(bound.belongsTo(null))
    }

    @Test
    fun sanitize() {
        assertEquals("a_b_c_.epub", Outbox.sanitize("a/b:c?.epub"))
        assertEquals("hidden", Outbox.sanitize("..hidden"))
        assertEquals("book", Outbox.sanitize(""))
        assertEquals("book", Outbox.sanitize("   "))
        assertEquals("tab_x.txt", Outbox.sanitize("tab\tx.txt"))
        assertEquals("_____.bmp", Outbox.sanitize("*<>|\\.bmp"))
        val long = Outbox.sanitize("я".repeat(200) + ".epub")
        assertEquals(110 + 5, long.length)
        assertTrue(long.endsWith(".epub"))
        assertEquals(110, Outbox.sanitize("x".repeat(130)).length)
        assertEquals("Мастер и Маргарита.epub", Outbox.sanitize("  Мастер и Маргарита.epub "))
    }

    @Test
    fun concurrentEnqueuesKeepEveryItem() {
        val outbox = Outbox(dir)
        val threads = (0 until 8).map { t ->
            Thread { repeat(10) { i -> Outbox(dir).enqueue(OutboxOperation.DeleteVocab("$t-$i"), "$t-$i") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(80, outbox.items().size)
    }
}
