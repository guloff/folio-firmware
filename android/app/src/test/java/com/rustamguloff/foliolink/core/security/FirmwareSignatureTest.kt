package com.rustamguloff.foliolink.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FirmwareSignatureTest {
    /** Фикстуры: образ, подписанный ключом Folio через `openssl pkeyutl -sign -rawin`
     * над "FOLIO-FW-v1\n" + SHA-256(образа). */
    private fun load(name: String): ByteArray =
        javaClass.classLoader!!.getResource(name)!!.readBytes()

    private fun expectFailure(expected: FirmwareSignatureException, block: () -> Unit) {
        try {
            block()
            fail("ожидалась $expected")
        } catch (e: FirmwareSignatureException) {
            assertEquals(expected, e)
        }
    }

    @Test
    fun knownGoodVectorVerifies() {
        val image = load("dummy.bin")
        val sig = load("dummy.bin.sig")
        assertEquals(64, sig.size)
        assertEquals(44, FirmwareSignature.message(image).size)
        FirmwareSignature.verify(image, sig)
        assertTrue(FirmwareSignature.isValid(image, sig))
    }

    @Test
    fun tamperedImageOrSignatureIsRefused() {
        val image = load("dummy.bin")
        val sig = load("dummy.bin.sig")
        val badImage = image.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        expectFailure(FirmwareSignatureException.Invalid) { FirmwareSignature.verify(badImage, sig) }
        val badSig = sig.copyOf().also { it[10] = (it[10].toInt() xor 0x80).toByte() }
        expectFailure(FirmwareSignatureException.Invalid) { FirmwareSignature.verify(image, badSig) }
    }

    @Test
    fun missingOrMalformedSignature() {
        val image = load("dummy.bin")
        expectFailure(FirmwareSignatureException.Missing) { FirmwareSignature.verify(image, null) }
        expectFailure(FirmwareSignatureException.Missing) { FirmwareSignature.verify(image, ByteArray(0)) }
        expectFailure(FirmwareSignatureException.Malformed(63)) { FirmwareSignature.verify(image, ByteArray(63)) }
    }

    @Test
    fun otherKeyIsRefused() {
        val image = load("dummy.bin")
        val sig = load("dummy.bin.sig")
        try {
            FirmwareSignature.verify(image, sig, publicKeyHex = "11".repeat(32))
            fail("чужой ключ должен быть отклонён")
        } catch (_: FirmwareSignatureException) {
        }
        expectFailure(FirmwareSignatureException.Invalid) { FirmwareSignature.verify(image, sig, publicKeyHex = "abc") }
    }

    @Test
    fun embeddedKeyAndHex() {
        assertEquals(32, FirmwareSignature.publicKey().encoded.size)
        assertArrayEquals(byteArrayOf(0x00, 0xFF.toByte(), 0x10, 0xAB.toByte()), Hex.decode("00ff10Ab"))
        assertNull(Hex.decode("abc"))
        assertNull(Hex.decode("zz"))
        assertEquals(FirmwareSignature.PUBLIC_KEY_HEX, Hex.encode(FirmwareSignature.publicKey().encoded))
    }

    @Test
    fun signatureName() {
        assertEquals("folio-v1-x4pro.bin.sig", FirmwareSignature.signatureName("folio-v1-x4pro.bin"))
    }
}

class FirmwareImageInfoTest {
    @Test
    fun readsAppDescriptor() {
        val bytes = ByteArray(0x100)
        bytes[0] = 0xE9.toByte()
        byteArrayOf(0x32, 0x54, 0xCD.toByte(), 0xAB.toByte()).copyInto(bytes, 0x20)
        "0.2.0".toByteArray().copyInto(bytes, 0x30)
        "Folio".toByteArray().copyInto(bytes, 0x50)
        assertEquals(FirmwareImageInfo(project = "Folio", version = "0.2.0"), FirmwareImageInfo.parse(bytes))
        bytes[0x20] = 0
        assertNull("нет magic — нет дескриптора", FirmwareImageInfo.parse(bytes))
        assertNull(FirmwareImageInfo.parse(byteArrayOf(0xE9.toByte(), 1, 2)))
    }

    @Test
    fun fullLengthFieldsWithoutTerminator() {
        val bytes = ByteArray(0x70)
        bytes[0] = 0xE9.toByte()
        byteArrayOf(0x32, 0x54, 0xCD.toByte(), 0xAB.toByte()).copyInto(bytes, 0x20)
        "v".repeat(32).toByteArray().copyInto(bytes, 0x30)
        "p".repeat(32).toByteArray().copyInto(bytes, 0x50)
        val info = FirmwareImageInfo.parse(bytes)!!
        assertEquals("v".repeat(32), info.version)
        assertEquals("p".repeat(32), info.project)
    }
}
