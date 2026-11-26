package com.rustamguloff.foliolink.core.net

import com.rustamguloff.foliolink.core.model.LibraryChange
import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.rustamguloff.foliolink.core.store.Outbox
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Отправка очереди: порядок, отказы, временные сбои, дубликаты, привязка к читалке, неизвестные операции. */
class OutboxRunnerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun outbox() = Outbox(File(tmp.root, "outbox"))

    @Test
    fun replayInOrderKeepsRejectedAndStopsOnTransient() = runBlocking {
        val outbox = outbox()
        for (t in listOf("a", "b", "c", "d")) outbox.enqueue(OutboxOperation.DeleteVocab(t), t)
        val calls = mutableListOf<String>()
        val report = OutboxRunner.drain(outbox, includeFailed = false) { item ->
            calls += item.title
            if (item.title == "b") throw DeviceError.Server(400, "bad")
            if (item.title == "c") throw DeviceError.Unreachable
        }
        assertEquals("rejection continues, transient error stops", listOf("a", "b", "c"), calls)
        assertEquals(listOf("a"), report.sent.map { it.title })
        assertEquals(listOf("b"), report.failed.map { it.title })
        assertEquals(DeviceError.Unreachable, report.stoppedBy)
        val left = outbox.items()
        assertEquals(listOf("b", "c", "d"), left.map { it.title })
        assertTrue(left[0].failed)
        assertNotNull(left[0].lastError)
        assertFalse("transient: retried automatically next time", left[1].failed)
        assertEquals(1, left[1].attempts)
        assertNotNull(left[1].lastError)

        // Следующее подключение: отклонённое пропускается до «Повторить».
        calls.clear()
        val second = OutboxRunner.drain(outbox, includeFailed = false) { calls += it.title }
        assertEquals(listOf("c", "d"), calls)
        assertEquals(2, second.sent.size)
        assertEquals(listOf("b"), outbox.items().map { it.title })

        // «Повторить»: только этот элемент, с отклонёнными.
        calls.clear()
        val retry = OutboxRunner.drain(outbox, includeFailed = true, only = setOf(left[0].id)) { calls += it.title }
        assertEquals(listOf("b"), calls)
        assertEquals(1, retry.sent.size)
        assertTrue(outbox.items().isEmpty())
        assertTrue(retry.isEmpty.not())
    }

    @Test
    fun pairingRequiredStopsTheRun() = runBlocking {
        val outbox = outbox()
        outbox.enqueue(OutboxOperation.DeleteVocab("a"), "a")
        outbox.enqueue(OutboxOperation.DeleteVocab("b"), "b")
        val report = OutboxRunner.drain(outbox, includeFailed = false, errorText = { "localized" }) {
            throw DeviceError.PairingRequired
        }
        assertEquals(DeviceError.PairingRequired, report.stoppedBy)
        val left = outbox.items()
        assertEquals(2, left.size)
        assertFalse(left[0].failed)
        assertEquals("localized", left[0].lastError)
        assertEquals(0, left[1].attempts)
    }

    @Test
    fun duplicateBookIsDroppedAndNewItemsArePickedUpDuringReplay() = runBlocking {
        val outbox = outbox()
        outbox.enqueueBook("1".toByteArray(), "a.epub", "a", "share")
        val calls = mutableListOf<String>()
        val report = OutboxRunner.drain(outbox, includeFailed = false) { item ->
            calls += item.title
            if (item.title == "a") {
                outbox.enqueue(OutboxOperation.DeleteVocab("late"), "late")
                throw DeviceError.AlreadyExists("a.epub")
            }
        }
        assertEquals(listOf("a", "late"), calls)
        assertEquals(listOf("a"), report.duplicates.map { it.title })
        assertTrue(outbox.items().isEmpty())
    }

    @Test
    fun alreadyExistsForNonBookIsARejection() = runBlocking {
        val outbox = outbox()
        outbox.enqueue(OutboxOperation.UploadFile("/sleep", "image/bmp", false, OutboxOperation.UploadPurpose.SLEEP),
            "s", payload = byteArrayOf(1), fileName = "s.bmp")
        val report = OutboxRunner.drain(outbox, includeFailed = false) { throw DeviceError.AlreadyExists("s.bmp") }
        assertEquals(1, report.failed.size)
        assertTrue(outbox.items().single().failed)
    }

    @Test
    fun changesForAnotherReaderAreHeldBack() = runBlocking {
        val outbox = outbox()
        outbox.enqueue(OutboxOperation.DeleteHighlight("7"), "a-delete", deviceKey = "SERIAL-A")
        outbox.enqueueBook("1".toByteArray(), "any.epub", "book", "share")
        outbox.enqueue(OutboxOperation.Settings(mapOf("sleepScreen" to JsonPrimitive(2))), "b-settings", deviceKey = "SERIAL-B")
        assertEquals("persisted", listOf("SERIAL-A", null, "SERIAL-B"), Outbox(outbox.dir).items().map { it.deviceKey })

        val calls = mutableListOf<String>()
        val report = OutboxRunner.drain(outbox, includeFailed = true, deviceKey = "SERIAL-B") { calls += it.title }
        assertEquals("A's highlight id means nothing on B", listOf("book", "b-settings"), calls)
        assertEquals(2, report.sent.size)
        assertEquals("kept for reader A", listOf("a-delete"), outbox.items().map { it.title })

        calls.clear()
        OutboxRunner.drain(outbox, includeFailed = false, deviceKey = "SERIAL-A") { calls += it.title }
        assertEquals(listOf("a-delete"), calls)
        assertTrue(outbox.items().isEmpty())
    }

    @Test
    fun runnerSkipsUnsupportedItems() = runBlocking {
        val dir = File(tmp.root, "outbox")
        dir.mkdirs()
        File(dir, "outbox.json").writeText(
            """[{"id":"future-1","fileName":"","title":"future","source":"","added":1790380800000,"operation":{"type":"teleport"}}]""",
        )
        val outbox = Outbox(dir)
        outbox.enqueue(OutboxOperation.DeleteVocab("w"), "known")
        val calls = mutableListOf<String>()
        val report = OutboxRunner.drain(outbox, includeFailed = true) { calls += it.title }
        assertEquals(listOf("known"), calls)
        assertEquals(listOf("known"), report.sent.map { it.title })
        assertTrue(report.failed.isEmpty())
        val left = outbox.items()
        assertEquals(listOf("future"), left.map { it.title })
        assertFalse(left[0].failed)
        assertEquals(0, left[0].attempts)

        calls.clear()
        OutboxRunner.drain(outbox, includeFailed = true, only = setOf(left[0].id)) { calls += it.title }
        assertTrue("not even an explicit retry runs it", calls.isEmpty())
        outbox.remove(left[0])
        assertTrue("delete works", outbox.items().isEmpty())
    }

    @Test
    fun libraryChangesAreIdempotentOnReplay() {
        var doc = com.rustamguloff.foliolink.core.model.LibraryDoc(
            shelves = listOf(com.rustamguloff.foliolink.core.model.Shelf("sf", "SF", listOf("/a"))),
            status = mapOf("/a" to "reading"), dailyMinutes = 30,
        )
        val changes = listOf(
            LibraryChange.SetStatus("/a", "done"), LibraryChange.ShelfMembership("sf", "/b", true),
            LibraryChange.CreateShelf("new", "Новая"), LibraryChange.RenameShelf("sf", "Фантастика"), LibraryChange.SetGoal(45),
        )
        changes.forEach { doc = it.apply(doc) }
        val once = doc
        changes.forEach { doc = it.apply(doc) }
        assertEquals(once, doc)
        assertEquals("done", doc.status["/a"])
        assertEquals(listOf("/a", "/b"), doc.shelves.first { it.id == "sf" }.books)
        assertEquals("Фантастика", doc.shelves.first { it.id == "sf" }.name)
        assertEquals(1, doc.shelves.count { it.id == "new" })
        assertEquals(45, doc.dailyMinutes)
        doc = LibraryChange.SetStatus("/a", null).apply(doc)
        doc = LibraryChange.ShelfMembership("sf", "/a", false).apply(doc)
        doc = LibraryChange.DeleteShelf("new").apply(doc)
        assertNull(doc.status["/a"])
        assertEquals(listOf("sf"), doc.shelves.map { it.id })
        assertEquals(listOf("/b"), doc.shelves[0].books)
    }

    @Test
    fun oldFirmwareDrainsSleepSelectionAsDone() = runBlocking {
        val outbox = outbox()
        outbox.enqueue(OutboxOperation.SelectSleepImage("card.bmp"), "select")
        val client = FakeTransport { FakeTransport.text("404 Not Found\n\nURI: /api/inklink/sleep/select", 404) }.client("tok")
        val supported = mutableListOf<Boolean>()
        val report = OutboxRunner.drain(outbox, includeFailed = false) { item ->
            val op = item.operation as OutboxOperation.SelectSleepImage
            supported += com.rustamguloff.foliolink.app.AppModel.selectSleepImage(op.name, client)
        }
        assertEquals(1, report.sent.size)
        assertTrue(report.failed.isEmpty())
        assertNull(report.stoppedBy)
        assertEquals(listOf(false), supported)
        assertTrue(outbox.items().isEmpty())
    }
}
