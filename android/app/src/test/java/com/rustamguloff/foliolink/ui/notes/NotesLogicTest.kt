package com.rustamguloff.foliolink.ui.notes

import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.export.ReadwiseException
import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.model.VocabWord
import com.rustamguloff.foliolink.core.store.AesGcmCipher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Locale
import javax.crypto.KeyGenerator

class NotesLogicTest {
    private val ru = NotesLogic.defaultCollator(Locale.forLanguageTag("ru"))

    private val books = listOf(
        Book(path = "/Books/war.epub", title = "Война и мир", author = "Лев Толстой"),
        Book(path = "/Books/dune.epub", title = "Dune", author = "Frank Herbert"),
    )

    private fun h(book: String, text: String, pct: Int, created: Long, note: String = "", title: String = "", id: String = "") =
        Highlight(id = id, book = book, title = title, text = text, note = note, pct = pct, created = created)

    private val highlights = listOf(
        h("/Books/war.epub", "Все счастливые семьи", 50, 10),
        h("/Books/war.epub", "Начало", 5, 30),
        h("/Books/war.epub", "Тоже начало", 5, 20),
        h("/Books/dune.epub", "Fear is the mind-killer", 12, 1, note = "Litany"),
        h("/Books/gone.epub", "Книги больше нет", 1, 1, title = "Анна Каренина"),
        h("/Books/nameless.epub", "Без названия", 1, 1),
    )

    @Test
    fun groupsByBookSortedByTitleAndPosition() {
        val g = NotesLogic.groups(highlights, books, "", ru)
        // Название: из библиотеки, иначе из цитаты, иначе имя файла.
        assertEquals(listOf("Dune", "nameless.epub", "Анна Каренина", "Война и мир"), g.map { it.title })
        val war = g.last()
        assertEquals("/Books/war.epub", war.key)
        // Внутри книги — по позиции, при равной позиции — по дате.
        assertEquals(listOf("Тоже начало", "Начало", "Все счастливые семьи"), war.items.map { it.text })
    }

    @Test
    fun searchCoversTextNoteAndBookTitle() {
        assertEquals(listOf("Dune"), NotesLogic.groups(highlights, books, "litany", ru).map { it.title })
        assertEquals(listOf("Война и мир"), NotesLogic.groups(highlights, books, "НАЧАЛО", ru).map { it.title })
        assertEquals(2, NotesLogic.groups(highlights, books, "НАЧАЛО", ru).single().items.size)
        assertEquals(listOf("Анна Каренина"), NotesLogic.groups(highlights, books, "каренина", ru).map { it.title })
        assertTrue(NotesLogic.groups(highlights, books, "ничего", ru).isEmpty())
    }

    @Test
    fun wordsFilteredAndNewestFirst() {
        val vocab = listOf(
            VocabWord(word = "serendipity", created = 1),
            VocabWord(word = "ephemeral", created = 3),
            VocabWord(word = "Serene", created = 2),
        )
        assertEquals(listOf("ephemeral", "Serene", "serendipity"), NotesLogic.words(vocab, "").map { it.word })
        assertEquals(listOf("Serene", "serendipity"), NotesLogic.words(vocab, " SER ").map { it.word })
    }

    @Test
    fun shareTextQuotesSourceAndNote() {
        val text = NotesLogic.shareText(h("/b", "  Цитата  ", 1, 1, note = "Мысль"), "Война и мир", "Лев Толстой")
        assertEquals("«Цитата»\n— Война и мир, Лев Толстой\n\nМысль", text)
        assertEquals("«Q»", NotesLogic.shareText(h("/b", "Q", 1, 1), "", ""))
        assertEquals("«Q»\n— Dune", NotesLogic.shareText(h("/b", "Q", 1, 1), "Dune"))
    }

    @Test
    fun wordRangeIgnoresCase() {
        assertEquals(4 until 9, NotesLogic.wordRange("The Ghost walks", "ghost"))
        assertNull(NotesLogic.wordRange("nothing here", "ghost"))
        assertNull(NotesLogic.wordRange("text", ""))
    }

    @Test
    fun readwiseErrorMessages() {
        assertEquals(R.string.notes_readwise_error_token, ReadwiseErrors.message(ReadwiseException.InvalidToken)?.res)
        assertEquals(
            ReadwiseErrors.Message(R.string.notes_readwise_error_rate, listOf(30)),
            ReadwiseErrors.message(ReadwiseException.RateLimited(30)),
        )
        assertEquals(R.string.notes_readwise_error_rate_plain, ReadwiseErrors.message(ReadwiseException.RateLimited(null))?.res)
        assertEquals(
            ReadwiseErrors.Message(R.string.notes_readwise_error_server, listOf(503, "service unavailable")),
            ReadwiseErrors.message(ReadwiseException.Server(503, "service unavailable")),
        )
        assertEquals(R.string.notes_readwise_error_network, ReadwiseErrors.message(IOException("reset"))?.res)
        assertNull(ReadwiseErrors.message(IllegalStateException("x")))
    }
}

class EncryptedReadwiseTokenStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun savesEncryptedAndReadsBack() = runTest {
        val file = tmp.root.resolve("readwise/token.bin")
        val store = EncryptedReadwiseTokenStore(file, AesGcmCipher { key })
        assertNull(store.token())
        store.save("  secret-token-123 \n")
        assertEquals("secret-token-123", store.token())
        // На диске нет токена открытым текстом.
        val raw = file.readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("secret-token"))
        assertTrue(raw.size > AesGcmCipher.IV_SIZE)
        // Другой экземпляр с тем же ключом читает тот же токен.
        assertEquals("secret-token-123", EncryptedReadwiseTokenStore(file, AesGcmCipher { key }).token())
        store.remove()
        assertNull(store.token())
        assertFalse(file.exists())
    }

    @Test
    fun unreadableFileMeansNoToken() = runTest {
        val file = tmp.root.resolve("token.bin")
        EncryptedReadwiseTokenStore(file, AesGcmCipher { key }).save("abc")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(EncryptedReadwiseTokenStore(file, AesGcmCipher { otherKey }).token())
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(EncryptedReadwiseTokenStore(file, AesGcmCipher { key }).token())
    }

    @Test
    fun emptyTokenRemovesFile() = runTest {
        val file = tmp.root.resolve("token.bin")
        val store = EncryptedReadwiseTokenStore(file, AesGcmCipher { key })
        store.save("abc")
        assertTrue(file.exists())
        store.save("   ")
        assertNull(store.token())
        assertArrayEquals(null, if (file.exists()) file.readBytes() else null)
    }
}
