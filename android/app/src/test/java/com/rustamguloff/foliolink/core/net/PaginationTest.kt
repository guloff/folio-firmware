package com.rustamguloff.foliolink.core.net

import com.rustamguloff.foliolink.core.net.FakeTransport.Companion.json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Контракт v2 §3: книги страницами по 100 до `total`. */
class PaginationTest {
    private fun book(i: Int) = """{"path":"/Books/$i.epub","title":"Book $i"}"""
    private fun page(range: IntRange, total: Int?, offset: Int? = null, limit: Int? = null): String {
        val extra = buildString {
            total?.let { append(",\"total\":$it") }
            offset?.let { append(",\"offset\":$it") }
            limit?.let { append(",\"limit\":$it") }
        }
        return """{"books":[${range.joinToString(",") { book(it) }}]$extra}"""
    }

    @Test
    fun loadsAllPagesOf100UntilTotal() = runBlocking {
        val t = FakeTransport { req ->
            val offset = req.query["offset"]!!.toInt()
            val limit = minOf(req.query["limit"]!!.toInt(), 200)
            json(page(offset until minOf(offset + limit, 250), 250, offset, limit))
        }
        val books = t.client().books()
        assertEquals(250, books.size)
        assertEquals((0 until 250).map { "/Books/$it.epub" }, books.map { it.path })
        assertEquals(listOf("0", "100", "200"), t.requests.map { it.query["offset"] })
        assertTrue(t.requests.all { it.query["limit"] == "100" })
    }

    @Test
    fun oldFirmwareWithoutTotalReturnsEverythingInOneRequest() = runBlocking {
        val t = FakeTransport { json(page(0 until 130, null)) }
        assertEquals(130, t.client().books().size)
        assertEquals(1, t.requests.size)
    }

    @Test
    fun firmwareIgnoringOffsetDoesNotLoopOrDuplicate() = runBlocking {
        val t = FakeTransport { json(page(0 until 100, 250)) }
        val books = t.client().books()
        assertEquals(100, books.size)
        assertEquals(100, books.map { it.path }.toSet().size)
        assertEquals(2, t.requests.size)
    }

    @Test
    fun emptyLibrary() = runBlocking {
        val t = FakeTransport { json("""{"books":[],"total":0}""") }
        assertTrue(t.client().books().isEmpty())
        assertEquals(1, t.requests.size)
    }

    @Test
    fun libraryShrinkingDuringWalkEnds() = runBlocking {
        // Вторая страница пустая, хотя total обещал больше: обход заканчивается без цикла.
        val t = FakeTransport { req ->
            if (req.query["offset"] == "0") json(page(0 until 100, 150)) else json(page(IntRange.EMPTY, 150))
        }
        assertEquals(100, t.client().books().size)
        assertEquals(2, t.requests.size)
    }

    @Test
    fun customPageSize() = runBlocking {
        val t = FakeTransport { req ->
            val offset = req.query["offset"]!!.toInt()
            val limit = req.query["limit"]!!.toInt()
            json(page(offset until minOf(offset + limit, 8), 8))
        }
        assertEquals(8, t.client().books(pageSize = 3).size)
        assertEquals(listOf("0", "3", "6"), t.requests.map { it.query["offset"] })
    }
}
