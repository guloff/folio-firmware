package com.rustamguloff.foliolink.ui.device

import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.ble.BleSync
import com.rustamguloff.foliolink.core.ble.BleUiState
import com.rustamguloff.foliolink.core.net.DeviceError
import com.rustamguloff.foliolink.core.net.DeviceError.PairingFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRulesTest {
    @Test
    fun localNetworkPermissionOnlyFromAndroid17() {
        assertEquals(LocalNetworkUi.READY, localNetworkUi(36, granted = false, permanentlyDenied = false))
        assertEquals(LocalNetworkUi.ASK, localNetworkUi(37, granted = false, permanentlyDenied = false))
        assertEquals(LocalNetworkUi.OPEN_SETTINGS, localNetworkUi(37, granted = false, permanentlyDenied = true))
        assertEquals(LocalNetworkUi.READY, localNetworkUi(37, granted = true, permanentlyDenied = true))
    }

    @Test
    fun pinKeepsOnlyDigitsUpToLength() {
        assertEquals("123456", PinRules.sanitize("12 34-56 78", 6))
        assertEquals("1234", PinRules.sanitize("a1b2c3d4", 6))
        assertEquals("", PinRules.sanitize("PIN", 6))
        // Только ASCII-цифры: арабские и другие цифры читалка не примет.
        assertEquals("12", PinRules.sanitize("1٣2", 6))
        assertTrue(PinRules.isComplete("123456", 6))
        assertFalse(PinRules.isComplete("12345", 6))
    }

    @Test
    fun pinDigitsAreClamped() {
        assertEquals(4, PinRules.clampDigits(2))
        assertEquals(6, PinRules.clampDigits(6))
        assertEquals(8, PinRules.clampDigits(12))
    }

    @Test
    fun secondsLeftRoundsUpAndNeverNegative() {
        assertEquals(0, PinRules.secondsLeft(null, 1_000))
        assertEquals(0, PinRules.secondsLeft(1_000, 5_000))
        assertEquals(1, PinRules.secondsLeft(1_001, 1_000))
        assertEquals(120, PinRules.secondsLeft(121_000, 1_000))
        assertEquals(30, PinRules.secondsLeft(30_000, 500))
    }

    @Test
    fun failureEffects() {
        val rate = PinRules.afterFailure(DeviceError.Pairing(PairingFailure.RateLimited(42)))
        assertTrue(rate.clearExpiry)
        assertEquals(42, rate.retryInSeconds)

        val expired = PinRules.afterFailure(DeviceError.Pairing(PairingFailure.Expired))
        assertTrue(expired.clearExpiry)
        assertNull(expired.retryInSeconds)
        assertTrue(PinRules.afterFailure(DeviceError.Pairing(PairingFailure.NoActivePin)).clearExpiry)

        // Неверный PIN: срок прежний, можно вводить снова.
        val bad = PinRules.afterFailure(DeviceError.Pairing(PairingFailure.BadPin(3)))
        assertFalse(bad.clearExpiry)
        assertNull(bad.retryInSeconds)
        assertFalse(PinRules.afterFailure(DeviceError.Unreachable).clearExpiry)
    }

    @Test
    fun everyBleStatusHasText() {
        val statuses = listOf(
            BleUiState.Status.Off, BleUiState.Status.Unsupported, BleUiState.Status.Unauthorized,
            BleUiState.Status.PoweredOff, BleUiState.Status.Searching, BleUiState.Status.Connecting,
            BleUiState.Status.Syncing, BleUiState.Status.Connected,
        )
        statuses.forEach { assertNotNull(it.toString(), BleTexts.statusRes(it)) }
        assertEquals(statuses.size, statuses.mapNotNull { BleTexts.statusRes(it) }.toSet().size)
        assertNull(BleTexts.statusRes(BleUiState.Status.Failed("x")))
        assertEquals(R.string.device_ble_status_connected, BleTexts.statusRes(BleUiState.Status.Connected))
    }

    @Test
    fun bleToneAndBusy() {
        assertEquals(BleTexts.Tone.GOOD, BleTexts.tone(BleUiState.Status.Connected))
        assertEquals(BleTexts.Tone.WARNING, BleTexts.tone(BleUiState.Status.Failed("x")))
        assertEquals(BleTexts.Tone.WARNING, BleTexts.tone(BleUiState.Status.Unauthorized))
        assertEquals(BleTexts.Tone.NEUTRAL, BleTexts.tone(BleUiState.Status.Searching))
        assertTrue(BleTexts.isBusy(BleUiState.Status.Syncing))
        assertTrue(BleTexts.isBusy(BleUiState.Status.Connecting))
        assertFalse(BleTexts.isBusy(BleUiState.Status.Connected))
    }

    @Test
    fun everyBleStepHasHumanName() {
        BleSync.STEPS.forEach { assertNotNull(it, BleTexts.stepRes(it)) }
        assertEquals(BleSync.STEPS.size, BleSync.STEPS.mapNotNull { BleTexts.stepRes(it) }.toSet().size)
        assertNull(BleTexts.stepRes(null))
        assertNull(BleTexts.stepRes("unknown"))
    }

    @Test
    fun readerLine() {
        assertEquals("Folio · MTU 247", BleTexts.readerLine("Folio", 247))
        assertEquals("Folio", BleTexts.readerLine("Folio", null))
    }
}
