package com.rustamguloff.foliolink.core.store

import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.rustamguloff.foliolink.core.services.ReviewState
import com.rustamguloff.foliolink.core.services.VocabReviewStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** FBK-034, FBK-036, FBK-039 и атомарная запись. */
class AuditStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // region FBK-034

    @Test
    fun wholeUnreadableIndexIsNotAnEmptyQueue() {
        val dir = tmp.newFolder("Outbox")
        val outbox = Outbox(dir)
        val book = outbox.enqueueBook(ByteArray(5), "Книга.fb2.zip", "Книга", "share")
        outbox.enqueue(OutboxOperation.DeleteVocab("w"), "w")
        val index = File(dir, Outbox.INDEX)
        val garbage = index.readText().dropLast(7) + "\u0000garbage"
        index.writeText(garbage)

        val reopened = Outbox(dir)
        val items = reopened.items()
        assertEquals("книга восстановлена по папке", listOf(book.id), items.map { it.id })
        assertEquals(OutboxOperation.UploadBook, items[0].operation)
        assertEquals(Outbox.RECOVERED, items[0].source)
        assertEquals("две записи в индексе не прочитаны", 2, reopened.unreadableCount())

        // Первая запись: копия делается, сверяется, индекс не затирается молча.
        reopened.enqueue(OutboxOperation.DeleteVocab("x"), "x")
        val backups = reopened.unreadableBackups()
        assertEquals(1, backups.size)
        assertArrayEquals(garbage.toByteArray(), backups[0].readBytes())
        assertEquals(listOf("Книга", "x"), reopened.items().map { it.title })
        assertEquals("число не прочитанного остаётся видно по копии", 2, reopened.unreadableCount())

        reopened.discardUnreadableBackups()
        assertEquals(0, reopened.unreadableCount())
    }

    @Test
    fun atomicWriteReplacesAndCleansUp() {
        val f = File(tmp.root, "a/b.json")
        SharedStore.writeAtomically(f, "1".toByteArray())
        SharedStore.writeAtomically(f, "22".toByteArray())
        assertEquals("22", f.readText())
        assertFalse(File(f.parentFile, ".b.json.tmp").exists())
        try {
            SharedStore.writeAtomically(f) { throw java.io.IOException("disk") }
        } catch (e: java.io.IOException) {
            // ожидаемо
        }
        assertEquals("прежний файл цел", "22", f.readText())
        assertFalse(File(f.parentFile, ".b.json.tmp").exists())
    }

    // endregion

    // region FBK-036

    /** Шифр, который «потерял ключ»: ничего не расшифровывает. */
    private class LostKey : TokenCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(data: ByteArray): ByteArray? = null
    }

    @Test
    fun tokenStoreReportsSaveAndKeepsAnUndecryptableFile() {
        val file = File(tmp.root, "pairing/tokens.bin")
        val plain = FilePairingTokenStore(file)
        assertTrue(plain.save("t1", "SN-1"))
        assertTrue(plain.save("t2", "SN-2"))
        assertEquals("t1", plain.token("SN-1"))

        val broken = FilePairingTokenStore(file, LostKey())
        assertNull(broken.token("SN-1"))
        assertFalse("нечитаемый файл не трогается удалением", broken.remove("SN-1"))
        assertTrue(file.exists())
        // Сохранение откладывает нечитаемый файл, а не затирает его одной записью.
        assertTrue(broken.save("t3", "SN-3"))
        val aside = file.parentFile!!.listFiles()!!.single { it.name.startsWith("tokens.bin.unreadable-") }
        assertEquals(mapOf("SN-1" to "t1", "SN-2" to "t2"), kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(aside.readText()))
    }

    @Test
    fun tokenSaveFailureIsReported() {
        val blocker = tmp.newFile("not-a-dir")
        val store = FilePairingTokenStore(File(blocker, "tokens.bin"))
        assertFalse(store.save("t", "SN"))
        assertNull(store.token("SN"))
    }

    // endregion

    // region FBK-039

    @Test
    fun unreadableProfilesAreSetAsideAndNotOverwritten() {
        val shared = SharedStore(tmp.newFolder("Store"))
        shared.file(SettingsProfileStore.FILE).writeText("""[{"name":"Утро","values":{"secret":""") // обрыв
        val store = SettingsProfileStore(shared)
        assertTrue(store.load().isEmpty())
        assertTrue(store.unreadable)
        val aside = shared.root.listFiles()!!.single { it.name.startsWith("${SettingsProfileStore.FILE}.unreadable-") }
        assertTrue(aside.readText().contains("Утро"))
        val change = store.add(SettingsProfile(name = "Новый", values = emptyMap()))
        assertTrue(change.saved)
        assertEquals(listOf("Новый"), store.load().map { it.name })
    }

    @Test
    fun profileSaveFailureIsReported() {
        val blocker = tmp.newFile("Store")
        val store = SettingsProfileStore(SharedStore(File(blocker, "sub")))
        assertFalse(store.add(SettingsProfile(name = "A", values = emptyMap())).saved)
    }

    @Test
    fun readDistinguishesMissingFromUnreadable() {
        val shared = SharedStore(tmp.newFolder("S"))
        val ser = kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>())
        assertEquals(SharedStore.Loaded.Missing, shared.read(ser, "x.json"))
        shared.file("x.json").writeText("{")
        assertTrue(shared.read(ser, "x.json") is SharedStore.Loaded.Unreadable)
        assertTrue("без quarantine файл на месте", shared.file("x.json").exists())
    }

    @Test
    fun unreadableVocabReviewIsSetAsideAndFlagged() = runBlocking {
        val file = File(tmp.newFolder("Store"), "vocab-review.json")
        file.writeText("{not json")
        val store = VocabReviewStore(file)
        store.load()
        assertTrue(store.storageFailed.value)
        assertTrue(file.parentFile!!.listFiles()!!.any { it.name.startsWith("vocab-review.json.unreadable-") })
        store.review("слово", ReviewState.Grade.GOOD, nowMs = 1_000)
        assertFalse("запись удалась — флаг снят", store.storageFailed.value)
        assertTrue(file.readText().contains("слово"))
    }

    // endregion
}
