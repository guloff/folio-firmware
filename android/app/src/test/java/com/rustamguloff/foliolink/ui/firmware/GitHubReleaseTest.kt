package com.rustamguloff.foliolink.ui.firmware

import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.model.DeviceInfo
import com.rustamguloff.foliolink.core.security.FirmwareSignatureException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GitHubReleaseTest {
    private inline fun <reified E : Throwable> assertThrows(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("ожидалось ${E::class.simpleName}, а брошено $e")
        }
        fail("ожидалось ${E::class.simpleName}")
        throw IllegalStateException()
    }

    // Перенос iOS AuditRegressionTests.testAssetSelectionByBoard
    @Test
    fun assetSelectionByBoard() {
        val names = listOf("folio-0.2.0-x4.bin", "folio-0.2.0-x4pro.bin", "notes.txt")
        assertEquals("folio-0.2.0-x4pro.bin", GitHubRelease.pickAsset(names, "xteink_x4_pro"))
        assertEquals("folio-0.2.0-x4pro.bin", GitHubRelease.pickAsset(names, "X4 Pro"))
        assertEquals("folio-0.2.0-x4.bin", GitHubRelease.pickAsset(names, "X4"))
        assertThrows<GitHubRelease.ReleaseError.NoAsset> { GitHubRelease.pickAsset(names, "X3") }
        assertThrows<GitHubRelease.ReleaseError.Ambiguous> { GitHubRelease.pickAsset(listOf("a.bin", "b.bin"), "") }
        assertEquals("only.bin", GitHubRelease.pickAsset(listOf("only.bin"), ""))
        assertThrows<GitHubRelease.ReleaseError.NoAsset> { GitHubRelease.pickAsset(listOf("notes.txt"), "X4") }
    }

    // Перенос iOS ContractV2Tests.testSignatureNameAndReleaseAssetPick
    @Test
    fun signatureFilesAreNotPicked() {
        val names = listOf("folio-v1-x4pro.bin", "folio-v1-x4pro.bin.sig", "folio-v1-x4.bin", "folio-v1-x4.bin.sig")
        assertEquals("folio-v1-x4pro.bin", GitHubRelease.pickAsset(names, "X4 Pro"))
        assertEquals("folio-v1.0.0-x4pro.bin", GitHubRelease.pickAsset(listOf("folio-v1.0.0-x4pro.bin", "folio-v1.0.0-x4pro.bin.sig"), "xteink_x4_pro"))
    }

    @Test
    fun boardSlug() {
        assertEquals("x4pro", GitHubRelease.boardSlug("xteink_x4_pro"))
        assertEquals("x4pro", GitHubRelease.boardSlug("X4 Pro"))
        assertEquals("", GitHubRelease.boardSlug(""))
    }

    // Перенос iOS AuditRegressionTests.testDownloadValidation
    @Test
    fun downloadValidation() {
        val image = ByteArray(100 * 1024).also { it[0] = 0xE9.toByte() }
        GitHubRelease.validateDownload(200, image)
        assertEquals(404, assertThrows<GitHubRelease.ReleaseError.Http> { GitHubRelease.validateDownload(404, image) }.code)
        assertThrows<GitHubRelease.ReleaseError.NotFirmware> { GitHubRelease.validateDownload(200, "<html>".toByteArray()) }
        assertThrows<GitHubRelease.ReleaseError.NotFirmware> { GitHubRelease.validateDownload(200, ByteArray(100 * 1024)) }
    }

    @Test
    fun apiUrl() {
        val latest = "https://api.github.com/repos/guloff/folio-firmware/releases/latest"
        assertEquals(latest, GitHubRelease.apiUrl("guloff/folio-firmware"))
        assertEquals(latest, GitHubRelease.apiUrl(" https://github.com/guloff/folio-firmware/releases "))
        assertEquals("https://api.github.com/repos/a/b/releases/tags/v1", GitHubRelease.apiUrl("https://api.github.com/repos/a/b/releases/tags/v1"))
        assertNull(GitHubRelease.apiUrl("folio"))
        assertNull(GitHubRelease.apiUrl(""))
    }

    @Test
    fun parseReleaseWithSignatureAsset() {
        val body = """
            {"tag_name":"v1.0.0","name":"Folio 1.0","assets":[
              {"name":"folio-v1.0.0-x4pro.bin","browser_download_url":"https://dl/x4pro.bin","size":1},
              {"name":"folio-v1.0.0-x4pro.bin.sig","browser_download_url":"https://dl/x4pro.sig"},
              {"name":"folio-v1.0.0-x4.bin","browser_download_url":"https://dl/x4.bin"}
            ]}
        """.trimIndent()
        assertEquals(
            GitHubRelease("v1.0.0", "folio-v1.0.0-x4pro.bin", "https://dl/x4pro.bin", "https://dl/x4pro.sig"),
            GitHubRelease.parse(body, "xteink_x4_pro"),
        )
        // Подписи нет среди ассетов — по контракту она лежит рядом как "<URL образа>.sig".
        val noSig = """{"tag_name":"v2","assets":[{"name":"folio-v2-x4pro.bin","browser_download_url":"https://dl/a.bin"}]}"""
        assertEquals("https://dl/a.bin.sig", GitHubRelease.parse(noSig, "X4 Pro").signatureUrl)
        assertThrows<GitHubRelease.ReleaseError.NoAsset> { GitHubRelease.parse("""{"tag_name":"v3","assets":[]}""", "X4") }
    }

    @Test
    fun errorTexts() {
        assertEquals(R.string.firmware_sig_missing to null, FirmwareTexts.errorRes(FirmwareSignatureException.Missing))
        assertEquals(R.string.firmware_sig_malformed to 12, FirmwareTexts.errorRes(FirmwareSignatureException.Malformed(12)))
        assertEquals(R.string.firmware_sig_invalid to null, FirmwareTexts.errorRes(FirmwareSignatureException.Invalid))
        assertEquals(R.string.firmware_err_http to 503, FirmwareTexts.errorRes(GitHubRelease.ReleaseError.Http(503)))
        assertEquals(R.string.firmware_err_no_signature to null, FirmwareTexts.errorRes(GitHubRelease.ReleaseError.NoSignature))
        assertEquals(R.string.firmware_err_network to null, FirmwareTexts.errorRes(java.net.UnknownHostException("api.github.com")))
        assertNull(FirmwareTexts.errorRes(IllegalStateException("x")))
    }

    @Test
    fun lowBattery() {
        assertTrue(FirmwareTexts.lowBattery(DeviceInfo(battery = 20)))
        assertFalse(FirmwareTexts.lowBattery(DeviceInfo(battery = 20, charging = true)))
        assertFalse(FirmwareTexts.lowBattery(DeviceInfo(battery = 30)))
        // −1 — заряд неизвестен: не пугать.
        assertFalse(FirmwareTexts.lowBattery(DeviceInfo(battery = -1)))
        assertFalse(FirmwareTexts.lowBattery(null))
    }

    @Test
    fun fileSelection() {
        val empty = FirmwareTexts.Selection(null, null, null)
        val img = byteArrayOf(1)
        val sig = byteArrayOf(2)
        val both = FirmwareTexts.select(empty, listOf("a.bin" to img, "a.bin.sig" to sig))
        assertEquals("a.bin", both.imageName)
        assertSame(img, both.image)
        assertSame(sig, both.signature)
        // Новый образ без подписи сбрасывает старую.
        val img2 = byteArrayOf(3)
        val onlyImage = FirmwareTexts.select(both, listOf("b.bin" to img2))
        assertSame(img2, onlyImage.image)
        assertNull(onlyImage.signature)
        // Одна подпись подписывает выбранный образ.
        val signed = FirmwareTexts.select(onlyImage, listOf("b.bin.SIG" to sig))
        assertSame(img2, signed.image)
        assertSame(sig, signed.signature)
        assertSame(signed, FirmwareTexts.select(signed, emptyList()))
    }
}
