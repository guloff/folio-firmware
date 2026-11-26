package com.rustamguloff.foliolink.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SEC-026 (сторона приложения): откат и чужая плата требуют отдельного подтверждения. */
class FirmwareRiskTest {
    private fun image(version: String) = FirmwareImageInfo("Folio", version)

    @Test
    fun newerImageForThisBoardHasNoRisk() {
        assertTrue(FirmwareRisk.assess(image("1.2.0"), "folio-v1.2.0-x4pro.bin", "1.1.1", "xteink_x4_pro").isEmpty())
        assertTrue("та же версия — переустановка", FirmwareRisk.assess(image("1.1.1"), "x.bin", "1.1.1", "xteink_x4_pro").isEmpty())
    }

    @Test
    fun olderImageIsARollback() {
        assertEquals(
            listOf(FirmwareRisk.Risk.Older("1.0.9", "1.1.1")),
            FirmwareRisk.assess(image("1.0.9"), "my.bin", "1.1.1", "xteink_x4_pro"),
        )
        assertEquals(listOf(FirmwareRisk.Risk.Older("v1.1", "1.1.1-x4pro")).size,
            FirmwareRisk.assess(image("v1.1"), null, "1.1.1-x4pro", null).size)
    }

    @Test
    fun otherBoardByReleaseAssetName() {
        val risks = FirmwareRisk.assess(image("1.2.0"), "folio-v1.2.0-x3.bin", "1.1.1", "xteink_x4_pro")
        assertEquals(listOf(FirmwareRisk.Risk.OtherBoard("x3", "xteink_x4_pro")), risks)
        assertEquals("имя не релизное — плата неизвестна", null, FirmwareRisk.boardFromName("firmware-test.bin"))
    }

    @Test
    fun imageWithoutVersionCannotBeCompared() {
        assertEquals(listOf(FirmwareRisk.Risk.UnknownVersion("1.1.1")), FirmwareRisk.assess(null, "x.bin", "1.1.1", null))
        assertTrue("версия читалки неизвестна — сравнивать не с чем", FirmwareRisk.assess(null, "x.bin", "", null).isEmpty())
    }

    @Test
    fun versionsCompareByComponents() {
        assertEquals(listOf(1, 10, 0), FirmwareRisk.parseVersion("v1.10.0-x4pro"))
        assertTrue(FirmwareRisk.compare(listOf(1, 10), listOf(1, 9, 9)) > 0)
        assertEquals(0, FirmwareRisk.compare(listOf(1, 1), listOf(1, 1, 0)))
    }
}
