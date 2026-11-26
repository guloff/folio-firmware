package com.rustamguloff.foliolink.core.ble

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.TimeZone

class BleCommandClientTest {
    private inline fun <reified E : Throwable> assertThrows(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("ожидалось ${E::class.simpleName}, получено $e", e)
        }
        fail("ожидалось ${E::class.simpleName}")
        throw IllegalStateException()
    }

    /** Первый ответ теряет кадр: он отбрасывается (до LAST), и команда повторяется. */
    @Test
    fun seqGapRetriesTheCommand() = runBlocking {
        val body = """{"words":[{"word":"мелиоративный","context":"… длинный контекст для нескольких кадров …","book":"/b.epub","created":1790380000,"count":2}]}"""
        val transport = FakeBleTransport { _, index ->
            val f = frames(body, mtu = 23).toMutableList()
            if (index == 0) f.removeAt(2)
            f
        }
        val client = BleCommandClient(transport, frameTimeoutMs = 50)
        assertEquals(listOf("мелиоративный"), client.vocab().map { it.word })
        assertEquals(2, transport.written.size)
    }

    @Test
    fun persistentDamageGivesUp() = runBlocking {
        val transport = FakeBleTransport { _, _ ->
            frames("x".repeat(100), mtu = 23).toMutableList().apply { removeAt(1) }
        }
        val client = BleCommandClient(transport, attempts = 3)
        val e = assertThrows<BleError.CorruptResponse> { client.info() }
        assertEquals(3, e.attempts)
        assertEquals(3, transport.written.size)
    }

    @Test
    fun busyWriteIsRepeated() = runBlocking {
        val transport = FakeBleTransport(writeErrors = listOf(BleIds.ATT_BUSY)) { request, _ ->
            val op = request.requestJson()["op"].toString().trim('"')
            frames(if (op == "hello") BleTestAuth.HELLO else """{"ok":true,"api":1}""")
        }
        val client = BleCommandClient(transport, busyDelayMs = 1)
        assertEquals(1, client.auth("a".repeat(64)))
        assertEquals(listOf("hello", "auth"), transport.writtenOps)
    }

    @Test
    fun busyForeverBecomesBusyError() = runBlocking {
        val transport = FakeBleTransport(writeErrors = List(5) { BleIds.ATT_BUSY }) { _, _ -> frames("{}") }
        val e = assertThrows<BleError.Busy> { BleCommandClient(transport, busyDelayMs = 1).info() }
        assertEquals(3, e.attempts)
    }

    @Test
    fun otherAttErrorIsNotRetried() = runBlocking {
        val transport = FakeBleTransport(writeErrors = listOf(BleIds.ATT_NOT_SUBSCRIBED)) { _, _ -> frames("{}") }
        val e = assertThrows<BleError.Gatt> { BleCommandClient(transport).info() }
        assertEquals(BleIds.ATT_NOT_SUBSCRIBED, e.code)
    }

    @Test
    fun silentReaderTimesOut() = runBlocking {
        val transport = FakeBleTransport { _, _ -> emptyList() }
        assertThrows<BleError.Timeout> { BleCommandClient(transport, frameTimeoutMs = 10).stats() }
        Unit
    }

    @Test
    fun paginationFollowsNextOffset() = runBlocking {
        fun session(i: Int) =
            """{"book":"/books/$i.epub","title":"Книга $i","start":${1790000000 + i * 3600},"secs":600,"pages":5,"pct":10,"day":"2026-09-01"}"""
        fun page(range: IntRange, next: Int?): String {
            val items = range.joinToString(",") { session(it) }
            return if (next != null) """{"sessions":[$items],"more":true,"nextOffset":$next}""" else """{"sessions":[$items]}"""
        }
        val transport = FakeBleTransport { request, _ ->
            val r = request.requestJson()
            assertEquals(1790000000, r.intOrNull("since"))
            when (val offset = r.intOrNull("offset") ?: 0) {
                0 -> frames(page(0 until 25, 25))
                25 -> frames(page(25 until 50, 50))
                50 -> frames(page(50 until 57, null))
                else -> throw UnexpectedRequest("offset $offset")
            }
        }
        val sessions = BleCommandClient(transport).sessions(since = 1790000000)
        assertEquals(57, sessions.size)
        assertEquals(57, sessions.map { it.id }.toSet().size)
        assertEquals(
            listOf(
                """{"op":"sessions","since":1790000000}""",
                """{"offset":25,"op":"sessions","since":1790000000}""",
                """{"offset":50,"op":"sessions","since":1790000000}""",
            ),
            transport.writtenJson,
        )
    }

    /** Читалка, повторяющая nextOffset (или идущая назад), не должна зациклить клиент. */
    @Test
    fun paginationStopsOnNonAdvancingOffset() = runBlocking {
        val transport = FakeBleTransport { _, _ -> frames("""{"words":[{"word":"a"}],"more":true,"nextOffset":0}""") }
        assertEquals(1, BleCommandClient(transport).vocab().size)
        assertEquals(1, transport.written.size)
    }

    @Test
    fun booksPagesByTotalAndNextOffset() = runBlocking {
        fun book(i: Int) = """{"path":"/books/$i.epub","title":"B$i"}"""
        fun list(r: IntRange) = r.joinToString(",") { book(it) }
        val transport = FakeBleTransport { request, _ ->
            val r = request.requestJson()
            assertEquals(100, r.intOrNull("limit"))
            val body = when (r.intOrNull("offset")) {
                0 -> """{"books":[${list(0 until 60)}],"total":250,"more":true,"nextOffset":60}"""
                60 -> """{"books":[${list(60 until 160)}],"total":250}"""
                160 -> """{"books":[${list(160 until 250)}],"total":250}"""
                else -> throw UnexpectedRequest("books $r")
            }
            frames(body, mtu = 517)
        }
        val books = BleCommandClient(transport).books()
        assertEquals(250, books.size)
        assertEquals(250, books.map { it.path }.toSet().size)
    }

    @Test
    fun booksStopOnRepeatedPage() = runBlocking {
        val transport = FakeBleTransport { _, _ -> frames("""{"books":[{"path":"/a.epub"}],"total":5}""") }
        assertEquals(1, BleCommandClient(transport).books().size)
        assertEquals(2, transport.written.size)
    }

    @Test
    fun authFailure() = runBlocking {
        val token = "0".repeat(64)
        val transport = FakeBleTransport { request, _ ->
            val op = request.requestJson()["op"].toString().trim('"')
            if (op == "hello") frames(BleTestAuth.HELLO)
            else frames("""{"ok":false,"error":"invalid proof","status":401,"attemptsLeft":3}""", error = true)
        }
        val client = BleCommandClient(transport)
        assertEquals(BleError.Remote(401, "invalid proof", 3), assertThrows<BleError.Remote> { client.auth(token) })

        // Синхронизация останавливается сразу после отказа auth: больше ничего не спрашивается.
        val e = assertThrows<BleError.Remote> { BleSync.run(client, BleSyncPlan(token = token)) }
        assertEquals(BleError.Remote(401, "invalid proof", 3), e)
        assertEquals(listOf("hello", "auth", "hello", "auth"), transport.writtenOps)
        // Сам токен в эфир не уходит.
        assertFalse(transport.writtenJson.joinToString("").contains(token))
    }

    @Test
    fun malformedTokenIsNoToken() = runBlocking {
        val transport = FakeBleTransport { _, _ -> frames(BleTestAuth.HELLO) }
        assertThrows<BleError.NoToken> { BleSync.run(BleCommandClient(transport), BleSyncPlan(token = "")) }
        assertTrue("без токена ничего не пишется", transport.written.isEmpty())
    }

    @Test
    fun commandBeforeAuthMapsToPairingRequired() = runBlocking {
        val transport = FakeBleTransport { _, _ ->
            frames("""{"ok":false,"error":"pairing required","status":401}""", error = true, mtu = 23)
        }
        assertThrows<BleError.PairingRequired> { BleCommandClient(transport).stats() }
        Unit
    }

    @Test
    fun remoteErrorKeepsStatusAndMessage() = runBlocking {
        val transport = FakeBleTransport { _, _ -> frames("""{"ok":false,"error":"no RTC on this device","status":501}""", error = true) }
        val e = assertThrows<BleError.Remote> { BleCommandClient(transport).setTime(1, 0) }
        assertEquals(501, e.status)
        assertEquals("no RTC on this device", e.error)
    }

    @Test
    fun tooLongRequestIsRefusedLocally() = runBlocking {
        val transport = FakeBleTransport { _, _ -> emptyList() }
        val e = assertThrows<BleError.CommandTooLong> {
            BleCommandClient(transport).highlights(book = "/книга".repeat(100))
        }
        assertTrue(e.size > BleIds.MAX_REQUEST_BYTES)
        assertTrue("ничего не записано", transport.written.isEmpty())
    }

    /** Ошибка одного шага не останавливает остальные. */
    @Test
    fun stepErrorIsRecordedAndSyncContinues() = runBlocking {
        val transport = FakeBleTransport { request, _ ->
            when (val op = request.requestJson()["op"].toString().trim('"')) {
                "hello" -> frames(BleTestAuth.HELLO)
                "auth" -> frames("""{"ok":true,"api":1}""")
                "info" -> frames("""{"api":1,"clock":{"available":true,"valid":true,"epoch":1790390000},"features":["stats","vocab"]}""")
                "stats" -> frames("""{"ok":false,"error":"stats unreadable","status":500}""", error = true)
                "sessions" -> frames("""{"sessions":[]}""")
                "vocab" -> frames("""{"words":[{"word":"x"}]}""")
                else -> throw UnexpectedRequest(op)
            }
        }
        val plan = BleSyncPlan(token = "a".repeat(64), nowMs = 1790390010_000L, timeZone = TimeZone.getTimeZone("UTC"))
        val steps = mutableListOf<String>()
        val result = BleSync.run(BleCommandClient(transport), plan) { steps += it }
        assertNull(result.stats)
        assertEquals(listOf("stats: stats unreadable"), result.failures)
        assertEquals(0, result.sessions?.size)
        assertEquals(1, result.vocab?.size)
        assertFalse("10 с разницы — в пределах 60 с", result.clockSet)
        assertNull("нет функции library — книги не спрашиваются", result.books)
        assertNull(result.highlights)
        assertEquals(listOf("hello", "auth", "info", "stats", "sessions", "vocab"), steps)
    }

    /** Сбой канала посреди синхронизации прерывает её целиком, а не записывается шагом. */
    @Test
    fun linkFailureAbortsSync() = runBlocking {
        val transport = FakeBleTransport { request, _ ->
            when (request.requestJson()["op"].toString().trim('"')) {
                "hello" -> frames(BleTestAuth.HELLO)
                "auth" -> frames("""{"ok":true,"api":1}""")
                "info" -> frames("""{"api":1,"features":["stats","vocab"]}""")
                else -> emptyList() // читалка замолчала
            }
        }
        val client = BleCommandClient(transport, frameTimeoutMs = 10)
        assertThrows<BleError.Timeout> { BleSync.run(client, BleSyncPlan(token = "a".repeat(64))) }
        assertEquals(listOf("hello", "auth", "info", "stats"), transport.writtenOps)
    }

    /** 404 «нет маршрута» от старой прошивки — шаг пропускается без предупреждения. */
    @Test
    fun notSupportedStepIsSkippedSilently() = runBlocking {
        val transport = FakeBleTransport { request, _ ->
            when (request.requestJson()["op"].toString().trim('"')) {
                "hello" -> frames(BleTestAuth.HELLO)
                "auth" -> frames("""{"ok":true,"api":1}""")
                "info" -> frames("""{"api":1,"features":["vocab","screenshots"]}""")
                "vocab" -> frames("""{"ok":false,"error":"","status":404}""", error = true)
                else -> frames("""{"screenshots":[{"name":"a.bmp","path":"/screenshots/a.bmp"}]}""")
            }
        }
        val result = BleSync.run(BleCommandClient(transport), BleSyncPlan(token = "a".repeat(64)))
        assertTrue(result.failures.isEmpty())
        assertNull(result.vocab)
        assertEquals(1, result.screenshots?.size)
    }
}
