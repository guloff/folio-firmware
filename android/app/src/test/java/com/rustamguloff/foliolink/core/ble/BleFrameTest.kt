package com.rustamguloff.foliolink.core.ble

import com.rustamguloff.foliolink.core.ble.BleFrameAssembler.Problem
import com.rustamguloff.foliolink.core.ble.BleFrameAssembler.Result
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFrameTest {
    /** ~9 КБ вперемешку ASCII и кириллицы (2 байта в UTF-8). */
    private val text: String = buildString {
        append("""{"sessions":[""")
        append((0 until 300).joinToString(",") { i -> """{"title":"Дюна №$i","book":"/books/Library/${i}_дюна.epub"}""" })
        append("]}")
    }

    @Test
    fun roundTripAtMtu23_185_517() {
        val payload = text.toByteArray(Charsets.UTF_8)
        for (mtu in listOf(23, 185, 517)) {
            val frames = BleFrame.encode(payload, isError = false, mtu = mtu)
            val chunk = mtu - 6
            assertEquals("mtu $mtu", (payload.size + chunk - 1) / chunk, frames.size)
            assertTrue("кадр больше MTU − 3 при $mtu", frames.all { it.size <= mtu - 3 })
            val a = BleFrameAssembler()
            frames.forEachIndexed { i, f ->
                assertEquals(i, BleFrame.seq(f))
                val r = a.push(f)
                if (i < frames.size - 1) {
                    assertEquals(Result.More, r)
                } else {
                    assertEquals(Result.Done(payload, false), r)
                    assertEquals(text, (r as Result.Done).payload.toString(Charsets.UTF_8))
                }
            }
            assertTrue("после LAST сборщик сброшен", a.isIdle)
        }
    }

    @Test
    fun boundarySizes() {
        for (mtu in listOf(23, 185, 517)) {
            val chunk = mtu - 6
            for (n in listOf(0, 1, chunk - 1, chunk, chunk + 1, 2 * chunk, 2 * chunk + 1)) {
                val payload = ByteArray(n) { (it * 31 + 7).toByte() }
                val frames = BleFrame.encode(payload, isError = false, mtu = mtu)
                assertEquals("mtu $mtu n $n", if (n == 0) 1 else (n + chunk - 1) / chunk, frames.size)
                assertArrayEquals(payload, BleFixture.payload(frames))
            }
        }
    }

    /** Двухбайтовый символ, разрезанный между кадрами, декодируется только после склейки. */
    @Test
    fun utf8SplitInsideMultibyteCharacter() {
        // MTU 23 → 17 байт payload на кадр. `{"t":"` + 10 × "a" = 16 байт, и "ж" (D0 B6)
        // ложится на кадры 0 и 1.
        val s = """{"t":"""" + "a".repeat(10) + "жж\"}"
        val data = s.toByteArray(Charsets.UTF_8)
        assertEquals(0xD0.toByte(), data[16])
        val frames = BleFrame.encode(data, isError = false, mtu = 23)
        assertFalse("кадр 0 кончается посреди символа", isValidUtf8(frames[0].copyOfRange(3, frames[0].size)))
        assertFalse("кадр 1 начинается посреди символа", isValidUtf8(frames[1].copyOfRange(3, frames[1].size)))
        assertEquals(s, BleFixture.payload(frames).toString(Charsets.UTF_8))
    }

    @Test
    fun seqGapReplayAndRuntAreRejected() {
        val frames = BleFrame.encode(text.toByteArray(Charsets.UTF_8), isError = false, mtu = 23)
        val a = BleFrameAssembler()
        assertEquals(Result.More, a.push(frames[0]))
        assertEquals(Result.Bad(Problem.SeqGap(expected = 1, got = 2)), a.push(frames[2]))
        assertTrue(a.isIdle)

        val b = BleFrameAssembler()
        assertEquals(Result.More, b.push(frames[0]))
        assertEquals(Result.Bad(Problem.SeqGap(expected = 1, got = 0)), b.push(frames[0]))

        val c = BleFrameAssembler()
        assertEquals(Result.Bad(Problem.Runt), c.push(frames[0].copyOf(2)))

        // Ответ обязан начинаться с seq 0.
        val d = BleFrameAssembler()
        assertEquals(Result.Bad(Problem.SeqGap(expected = 0, got = 1)), d.push(frames[1]))
    }

    @Test
    fun errorFlag() {
        val body = """{"ok":false,"error":"invalid token","status":401,"attemptsLeft":3}""".toByteArray()
        val errFrames = BleFrame.encode(body, isError = true, mtu = 23)
        assertTrue("ERROR на каждом кадре", errFrames.all { ((BleFrame.flags(it) ?: 0) and BleFrame.FLAG_ERROR) != 0 })
        val a = BleFrameAssembler()
        var last: Result = Result.More
        for (f in errFrames) last = a.push(f)
        assertEquals(Result.Done(body, true), last)

        // ERROR, появившийся посреди ответа, — повреждённый ответ.
        val ok = BleFrame.encode(body, isError = false, mtu = 23).toMutableList()
        ok[1] = errFrames[1]
        val b = BleFrameAssembler()
        assertEquals(Result.More, b.push(ok[0]))
        assertEquals(Result.Bad(Problem.FlagMismatch), b.push(ok[1]))
    }

    @Test
    fun tooLargeResponseIsRejected() {
        val frames = BleFrame.encode(ByteArray(BleFrame.MAX_RESPONSE_BYTES + 600), isError = false, mtu = 517)
        val a = BleFrameAssembler()
        var last: Result = Result.More
        for (f in frames) {
            last = a.push(f)
            if (last !is Result.More) break
        }
        assertEquals(Result.Bad(Problem.TooLarge), last)
    }

    /** Дамп MTU 23 из api.md / qa/session2/ble-loopback.txt (раздел 9). */
    @Test
    fun realFramesFromLoopbackDump() {
        val dump = listOf(
            "0000027b226f6b223a66616c73652c226572726f",
            "01000272223a2270616972696e67207265717569",
            "020002726564222c22737461747573223a343031",
            "0300037d",
        )
        val a = BleFrameAssembler()
        var result: Result = Result.More
        for (h in dump) result = a.push(hex(h))
        assertEquals(Result.Done("""{"ok":false,"error":"pairing required","status":401}""".toByteArray(), true), result)

        val b = BleFrameAssembler()
        assertEquals(Result.More, b.push(hex("0000007b226f6b223a747275652c22617069223a")))
        assertEquals(Result.Done("""{"ok":true,"api":1}""".toByteArray(), false), b.push(hex("010001317d")))
    }

    @Test
    fun requestEncoding() {
        assertEquals("""{"limit":100,"offset":17,"op":"books"}""", BleRequest(op = "books", offset = 17, limit = 100).toJson())
        assertEquals("""{"op":"info"}""", BleRequest(op = "info").toJson())
        assertEquals(
            """{"book":"/books/Дюна.epub","op":"highlights","since":1790000000}""",
            BleRequest(op = "highlights", since = 1790000000, book = "/books/Дюна.epub").toJson(),
        )
        assertEquals(
            """{"epoch":1790390000,"op":"time","tzOffsetMin":300}""",
            BleRequest(op = "time", epoch = 1790390000, tzOffsetMin = 300).toJson(),
        )
    }
}

class BleAuthTest {
    /** Тот же вектор, что в прошивке и iOS (сверено с модулем hmac в Python). */
    @Test
    fun proofMatchesReferenceVector() {
        val token = "ab".repeat(32)
        val nonce = "000102030405060708090a0b0c0d0e0f"
        assertEquals(EXPECTED, BleAuth.proof(token, nonce))
        assertEquals("заглавные hex тоже принимаются", EXPECTED, BleAuth.proof(token.uppercase(), nonce.uppercase()))
    }

    @Test
    fun proofRejectsMalformedInput() {
        assertNull(BleAuth.proof("abc", "000102030405060708090a0b0c0d0e0f"))
        assertNull(BleAuth.proof("ab".repeat(32), "00"))
        assertNull(BleAuth.proof("zz".repeat(32), "000102030405060708090a0b0c0d0e0f"))
    }

    private companion object {
        const val EXPECTED = "2fe98ce7a1de2bc37eba2371aa001ae149d16d595808d6934e4b1066eab94072"
    }
}
