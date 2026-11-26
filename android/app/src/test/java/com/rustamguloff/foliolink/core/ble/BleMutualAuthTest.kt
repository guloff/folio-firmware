package com.rustamguloff.foliolink.core.ble

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SEC-034: взаимная аутентификация BLE и сверка серийника. */
class BleMutualAuthTest {
    private val token = (0x00..0x1f).joinToString("") { "%02x".format(it) }
    private val nonce = (0xa0..0xaf).joinToString("") { "%02x".format(it) }
    private val cnonce = (0xb0..0xbf).joinToString("") { "%02x".format(it) }

    @Test
    fun referenceVector() {
        assertEquals("45fadad226d0d911cb3c1f354a0a3af53f74f6280be71f6363252a0f28b97d6b", BleAuth.proof(token, nonce))
        assertEquals(SPROOF, BleAuth.serverProof(token, cnonce, nonce))
        assertTrue(BleAuth.verifyServerProof(token, cnonce, nonce, SPROOF))
        assertTrue("заглавные hex", BleAuth.verifyServerProof(token, cnonce, nonce, SPROOF.uppercase()))
        assertFalse(BleAuth.verifyServerProof(token, cnonce, nonce, SPROOF.replaceRange(0, 1, "1")))
        assertFalse(BleAuth.verifyServerProof(token, nonce, cnonce, SPROOF))
        assertFalse(BleAuth.verifyServerProof(token, cnonce, nonce, "zz"))
    }

    @Test
    fun clientNonceIs16RandomBytes() {
        val a = BleAuth.newClientNonce()
        assertEquals(32, a.length)
        assertEquals(16, BleAuth.hexToBytes(a)!!.size)
        assertTrue(a != BleAuth.newClientNonce())
    }

    private fun reader(auth: String, info: String = """{"api":1,"features":["files"]}""") = FakeBleTransport { req, _ ->
        when (req.requestJson()["op"].toString().trim('"')) {
            "hello" -> frames("""{"ok":true,"nonce":"$nonce"}""")
            "auth" -> frames(auth)
            "info" -> frames(info)
            else -> frames("""{"ok":true}""")
        }
    }

    private fun plan(require: Boolean = false, serial: String? = null) =
        BleSyncPlan(token = token, cnonce = cnonce, requireServerProof = require, expectedSerial = serial)

    @Test
    fun helloCarriesTheClientNonce() = runBlocking<Unit> {
        val t = reader("""{"ok":true,"api":1,"sproof":"$SPROOF"}""")
        val r = BleSync.run(BleCommandClient(t), plan())
        assertTrue(r.serverVerified)
        assertEquals("\"$cnonce\"", t.written[0].requestJson()["cnonce"].toString())
    }

    @Test
    fun wrongServerProofStopsBeforeAnyData() = runBlocking {
        val t = reader("""{"ok":true,"api":1,"sproof":"${"0".repeat(64)}"}""")
        expectKind(BleServerProofException.Kind.MISMATCH) { BleSync.run(BleCommandClient(t), plan()) }
        assertEquals("после auth ничего не запрошено", listOf("hello", "auth"), t.writtenOps)
    }

    @Test
    fun missingServerProofIsAcceptedOnlyUntilFirstSuccess() = runBlocking<Unit> {
        // Прошивка 1.1.1: sproof нет — пока взаимной проверки не было, синхронизация идёт.
        val old = BleSync.run(BleCommandClient(reader("""{"ok":true,"api":1}""")), plan(require = false))
        assertFalse(old.serverVerified)
        expectKind(BleServerProofException.Kind.MISSING) {
            BleSync.run(BleCommandClient(reader("""{"ok":true,"api":1}""")), plan(require = true))
        }
    }

    @Test
    fun serialFromInfoMustMatchTheCachedReader() = runBlocking<Unit> {
        val info = """{"api":1,"features":["files"],"serial":"SN-B"}"""
        expectKind(BleServerProofException.Kind.WRONG_READER) {
            BleSync.run(BleCommandClient(reader("""{"ok":true,"api":1}""", info)), plan(serial = "SN-A"))
        }
        BleSync.run(BleCommandClient(reader("""{"ok":true,"api":1}""", info)), plan(serial = "SN-B"))
        BleSync.run(BleCommandClient(reader("""{"ok":true,"api":1}""")), plan(serial = "SN-A")) // info без серийника
        assertTrue(BleSync.sameReader(null, "SN-B"))
        assertTrue(BleSync.sameReader("SN-A", "Not found"))
    }

    private suspend fun expectKind(kind: BleServerProofException.Kind, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (e: BleServerProofException) {
            assertEquals(kind, e.kind)
        }
    }

    companion object {
        const val SPROOF = "0c7b4186e06fd6d869e13ea3dd2019f8c2cb30e4d14e91c7cdafd259cfba87d7"
    }
}
