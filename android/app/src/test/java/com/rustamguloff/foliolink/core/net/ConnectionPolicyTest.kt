package com.rustamguloff.foliolink.core.net

import com.rustamguloff.foliolink.core.model.DeviceEndpoint
import com.rustamguloff.foliolink.core.model.DeviceInfo
import com.rustamguloff.foliolink.core.model.DeviceStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** ARCH-022 / BUG-037 / SEC-033: решение о подключении без AppModel. */
class ConnectionPolicyTest {
    private val ep = DeviceEndpoint("192.168.1.20")
    private val tokens = mapOf("SN-A" to "tok-a", "SN-B" to "tok-b", "192.168.1.20" to "tok-addr")

    @Test
    fun statusIsRetriedAfterTransientFailures() = runBlocking {
        var calls = 0
        val probe = ConnectionPolicy.probe(retryDelayMs = 0) {
            calls++
            if (calls < 3) throw DeviceError.Timeout
            DeviceStatus(serial = "SN-A")
        }
        assertEquals(3, calls)
        assertEquals(ConnectionPolicy.Probe.Confirmed(DeviceStatus(serial = "SN-A"), "SN-A"), probe)
    }

    @Test
    fun statusThatKeepsFailingIsAnErrorNotAnotherKey() = runBlocking {
        var calls = 0
        try {
            ConnectionPolicy.probe(retryDelayMs = 0) { calls++; throw DeviceError.Unreachable }
            fail()
        } catch (e: DeviceError) {
            assertEquals(DeviceError.Unreachable, e)
        }
        assertEquals("1 + 2 повтора", 3, calls)
    }

    @Test
    fun upstreamWithoutSerialOrRouteIsNoSerial() = runBlocking {
        val notFound = ConnectionPolicy.probe(retryDelayMs = 0) { DeviceStatus(serial = "Not found", version = "1.0") }
        assertTrue(notFound is ConnectionPolicy.Probe.NoSerial)
        assertEquals("1.0", notFound.status?.version)
        val noRoute = ConnectionPolicy.probe(retryDelayMs = 0) { throw DeviceError.NotSupported }
        assertEquals(ConnectionPolicy.Probe.NoSerial(null), noRoute)
        var calls = 0
        ConnectionPolicy.probe(retryDelayMs = 0) { calls++; throw DeviceError.Decoding("/api/status") }
        assertEquals("неразборчивый ответ не повторяется", 1, calls)
    }

    @Test
    fun confirmedSameSerialGetsItsToken() {
        val d = ConnectionPolicy.decide(confirmed("SN-A"), null, ep, ep, "SN-A", tokens::get)
        assertEquals("SN-A", d.key)
        assertEquals("tok-a", d.token)
        assertFalse(d.resetCache)
        assertTrue(d.ownsQueue)
    }

    @Test
    fun confirmedOtherSerialResetsCacheAndGetsOnlyItsOwnToken() {
        // Хост из списка обнаружения — другая читалка: токен SN-A ему не уходит.
        val d = ConnectionPolicy.decide(confirmed("SN-B"), null, ep, ep, "SN-A", tokens::get)
        assertEquals("SN-B", d.key)
        assertEquals("tok-b", d.token)
        assertTrue(d.resetCache)
        val unknown = ConnectionPolicy.decide(confirmed("SN-C"), null, ep, ep, "SN-A", tokens::get)
        assertNull("у новой читалки токена нет", unknown.token)
    }

    @Test
    fun noSerialSendsNoTokenAndKeepsKeyAndCache() {
        val d = ConnectionPolicy.decide(ConnectionPolicy.Probe.NoSerial(DeviceStatus()), null, ep, ep, "SN-A", tokens::get)
        assertEquals("ключ не меняется без подтверждённого другого серийника", "SN-A", d.key)
        assertNull("и токен по адресу не уходит", d.token)
        assertFalse(d.resetCache)
        assertFalse("изменения для SN-A ждут подтверждения", d.ownsQueue)

        val fresh = ConnectionPolicy.decide(ConnectionPolicy.Probe.NoSerial(null), null, ep, null, null, tokens::get)
        assertEquals(ep.id, fresh.key)
        assertNull(fresh.token)
        assertTrue(fresh.ownsQueue)
    }

    @Test
    fun tokenIsForgottenOnlyByAConfirmedReaderThatRejectsIt() {
        val rejected = DeviceInfo(pairing = DeviceInfo.Pairing(required = true, paired = true, authorized = false))
        val confirmed = ConnectionPolicy.decide(confirmed("SN-A"), null, ep, ep, "SN-A", tokens::get)
        assertTrue(ConnectionPolicy.shouldForgetToken(confirmed, rejected))
        val accepted = rejected.copy(pairing = DeviceInfo.Pairing(required = true, paired = true, authorized = true))
        assertFalse(ConnectionPolicy.shouldForgetToken(confirmed, accepted))
        val stranger = ConnectionPolicy.decide(ConnectionPolicy.Probe.NoSerial(DeviceStatus()), null, ep, ep, "SN-A", tokens::get)
        assertFalse("ответ хоста без серийника токен не стирает", ConnectionPolicy.shouldForgetToken(stranger, rejected))
    }

    // region deviceId (серийник "Not found")

    private val id = "0123456789abcdef0123456789ABCDEF"
    private val idKey = "id:0123456789abcdef0123456789abcdef"
    private val noSerial = ConnectionPolicy.Probe.NoSerial(DeviceStatus(serial = "Not found"))

    @Test
    fun deviceIdConfirmsAReaderWithoutSerial() {
        assertEquals(idKey, ConnectionPolicy.confirmedKey(noSerial, id))
        assertEquals("серийник важнее deviceId", "SN-A", ConnectionPolicy.confirmedKey(confirmed("SN-A"), id))
        assertNull("не 32 hex — не идентификатор", ConnectionPolicy.confirmedKey(noSerial, "1234"))
        assertNull(ConnectionPolicy.confirmedKey(noSerial, null))

        val d = ConnectionPolicy.decide(noSerial, id, ep, null, null, mapOf(idKey to "tok-id")::get)
        assertEquals(idKey, d.key)
        assertTrue(d.confirmed)
        assertEquals("tok-id", d.token)
        assertFalse(d.resetCache)
    }

    @Test
    fun addressKeyOfTheSameAddressMigratesToDeviceId() {
        val d = ConnectionPolicy.decide(noSerial, id, ep, ep, ep.id, mapOf(ep.id to "tok-addr")::get)
        assertEquals(idKey, d.key)
        assertEquals("перенос со старого адресного ключа", ep.id, d.migrateFrom)
        assertEquals("токен из адресного ключа", "tok-addr", d.token)
        assertFalse("кэш не сбрасывается", d.resetCache)
        assertTrue(d.ownsQueue)
    }

    @Test
    fun addressKeyOfAnotherAddressIsNotMigrated() {
        val other = DeviceEndpoint("192.168.1.99")
        val d = ConnectionPolicy.decide(noSerial, id, ep, other, other.id, tokens::get)
        assertNull(d.migrateFrom)
        assertNull("чужой адресный токен не уходит", d.token)
        assertTrue(d.resetCache)
    }

    @Test
    fun otherDeviceIdIsAnotherReader() {
        val d = ConnectionPolicy.decide(noSerial, "f".repeat(32), ep, ep, idKey, mapOf(idKey to "tok-id")::get)
        assertEquals("id:" + "f".repeat(32), d.key)
        assertNull(d.token)
        assertTrue(d.resetCache)
    }

    @Test
    fun neitherSerialNorDeviceIdKeepsTheOldRule() {
        val d = ConnectionPolicy.decide(noSerial, null, ep, ep, ep.id, tokens::get)
        assertFalse(d.confirmed)
        assertNull("1.1.1 без серийника — без токена", d.token)
        assertNull(d.migrateFrom)
    }

    @Test
    fun sameReaderUsesTheRightIdentifier() {
        assertTrue(ConnectionPolicy.sameReader(idKey, null, id))
        assertFalse(ConnectionPolicy.sameReader(idKey, null, "f".repeat(32)))
        assertTrue("неизвестный не спорит", ConnectionPolicy.sameReader(idKey, "SN-X", null))
        assertFalse(ConnectionPolicy.sameReader("SN-A", "SN-B", id))
        assertTrue(ConnectionPolicy.sameReader("SN-A", "Not found", id))
    }

    // endregion

    private fun confirmed(serial: String) = ConnectionPolicy.Probe.Confirmed(DeviceStatus(serial = serial), serial)
}
