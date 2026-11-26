package com.rustamguloff.foliolink.core.net

import com.rustamguloff.foliolink.core.model.DeviceEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор адресов, UDP-ответа и имён служб. */
class DiscoveryParsingTest {
    @Test
    fun udpReply() {
        assertEquals(UdpProbe.Reply("192.168.1.9", "crosspoint.local", 80), UdpProbe.parse("crosspoint (on crosspoint.local);81", "192.168.1.9"))
        assertEquals(UdpProbe.Reply("192.168.1.9", "folio.local", 80), UdpProbe.parse("folio (on folio.local);81", "192.168.1.9"))
        // Loopback: симулятор/мок на компьютере, HTTP на 8080 (и через 10.0.2.2 из эмулятора).
        assertEquals(8080, UdpProbe.parse("crosspoint (on mock);8080", "127.0.0.1")?.httpPort)
        assertEquals(8080, UdpProbe.parse("folio (on sim);81", "10.0.2.2")?.httpPort)
        assertEquals(8080, UdpProbe.parse("folio (on sim);81", "::1")?.httpPort)
        assertEquals("Folio", UdpProbe.parse("FOLIO;81", "10.0.0.5")?.name)
        assertEquals("Folio", UdpProbe.parse("folio (on broken;81", "10.0.0.5")?.name)
        assertNull(UdpProbe.parse("hello", "10.0.0.5"))
        assertNull(UdpProbe.parse("printer (on x);81", "10.0.0.5"))
    }

    @Test
    fun readerServiceNames() {
        assertTrue(DeviceDiscovery.isReaderServiceName("Folio-X4"))
        assertTrue(DeviceDiscovery.isReaderServiceName("CrossPoint Reader"))
        assertTrue(DeviceDiscovery.isReaderServiceName("my inklink"))
        assertTrue(DeviceDiscovery.isReaderServiceName("folio"))
        assertFalse(DeviceDiscovery.isReaderServiceName("Portfolio"))
        assertFalse(DeviceDiscovery.isReaderServiceName("HP LaserJet"))
        assertEquals("_inklink._tcp", DeviceDiscovery.COMPANION_SERVICE_TYPE)
        assertEquals(listOf("folio.local", "crosspoint.local"), DeviceDiscovery.KNOWN_HOST_NAMES)
    }

    @Test
    fun endpointParsing() {
        assertEquals(8080, DeviceEndpoint.parse("127.0.0.1:8080")?.port)
        assertEquals(80, DeviceEndpoint.parse("192.168.1.5")?.port)
        assertEquals("crosspoint.local", DeviceEndpoint.parse("http://crosspoint.local/")?.host)
        assertNull(DeviceEndpoint.parse("  "))
        assertEquals("http://10.0.0.2:8080", DeviceEndpoint("10.0.0.2", 8080).baseUrl)
        assertEquals("http://[fe80::1]", DeviceEndpoint("fe80::1").baseUrl)
        assertEquals("10.0.0.2:80", DeviceEndpoint("10.0.0.2").id)
    }
}
