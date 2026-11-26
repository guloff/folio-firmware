package com.rustamguloff.foliolink.core.net

import com.rustamguloff.foliolink.core.model.DeviceEndpoint
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

/** Настоящий HTTP через HttpURLConnection против com.sun.net.httpserver. */
class UrlConnectionTransportTest {
    private lateinit var server: HttpServer
    private val seen = CopyOnWriteArrayList<Triple<String, String, String?>>() // method, rawQuery, token
    private val uploads = CopyOnWriteArrayList<ByteArray>()
    private val big = ByteArray(200_000) { (it % 251).toByte() }

    private val port get() = server.address.port

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seen += Triple(ex.requestMethod, ex.requestURI.rawQuery.orEmpty(), ex.requestHeaders.getFirst(DeviceClient.TOKEN_HEADER))
            when (ex.requestURI.path) {
                "/download" -> reply(ex, 200, big, "application/octet-stream")
                "/upload" -> {
                    uploads += ex.requestBody.readBytes()
                    reply(ex, 200, """{"ok":true}""".toByteArray())
                }
                "/slow" -> {
                    Thread.sleep(1500)
                    reply(ex, 200, "late".toByteArray())
                }
                "/delete" -> reply(ex, 500, "Failed to delete some items: /a (not found); ".toByteArray(), "text/plain")
                "/api/inklink/info" -> reply(ex, 401, """{"ok":false,"error":"pairing required"}""".toByteArray())
                "/api/inklink/sleep/select" -> reply(ex, 404, ByteArray(0))
                else -> reply(ex, 404, "404 Not Found\n\nURI: ${ex.requestURI.path}".toByteArray(), "text/plain")
            }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun reply(ex: HttpExchange, status: Int, body: ByteArray, type: String = "application/json") {
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
    }

    private fun client(token: String? = null) =
        DeviceClient(DeviceEndpoint("127.0.0.1", port), token, UrlConnectionTransport(connectTimeoutMs = 2000))

    @Test
    fun downloadReportsByteProgress() = runBlocking {
        val progress = mutableListOf<Pair<Long, Long>>()
        val data = client().download("/Книги/Дюна.epub") { d, t -> progress += d to t }
        assertArrayEquals(big, data)
        assertEquals(0L to big.size.toLong(), progress.first())
        assertEquals(big.size.toLong() to big.size.toLong(), progress.last())
        assertTrue(progress.size > 3)
        assertEquals("path=/%D0%9A%D0%BD%D0%B8%D0%B3%D0%B8/%D0%94%D1%8E%D0%BD%D0%B0.epub", seen.last().second)
    }

    @Test
    fun uploadReportsSentBytesAndCarriesToken() = runBlocking {
        val content = ByteArray(100_000) { 7 }
        var last = 0L
        var total = 0L
        client(token = "tok").upload(content, "a.epub", "/Books", "application/epub+zip") { d, t -> last = d; total = t }
        val body = uploads.single()
        assertEquals(body.size.toLong(), total)
        assertEquals(total, last)
        assertArrayEquals(content, MultipartBody.fileContent(body))
        assertEquals("tok", seen.last().third)
    }

    @Test
    fun statusCodesMapToDeviceErrors() = runBlocking {
        val c = client()
        try { c.info(); fail() } catch (e: DeviceError) { assertEquals(DeviceError.PairingRequired, e) }
        try { c.stats(); fail() } catch (e: DeviceError) { assertEquals(DeviceError.NotSupported, e) }
        try { c.selectSleepImage("a.bmp"); fail() } catch (e: DeviceError) { assertEquals(DeviceError.NotSupported, e) }
        try { c.delete("/a"); fail() } catch (e: DeviceError) {
            assertEquals(DeviceError.Server(500, "Failed to delete some items: /a (not found);"), e)
        }
    }

    @Test
    fun readTimeoutIsTimeout() = runBlocking {
        val t = UrlConnectionTransport(connectTimeoutMs = 2000)
        try {
            t.execute(HttpRequest("GET", "http://127.0.0.1:$port/slow", timeoutMs = 300))
            fail("expected timeout")
        } catch (e: DeviceError) {
            assertEquals(DeviceError.Timeout, e)
        }
    }

    @Test
    fun refusedConnectionIsUnreachable() = runBlocking {
        val free = ServerSocket(0).use { it.localPort }
        try {
            UrlConnectionTransport(connectTimeoutMs = 2000).execute(HttpRequest("GET", "http://127.0.0.1:$free/api/status"))
            fail("expected unreachable")
        } catch (e: DeviceError) {
            assertEquals(DeviceError.Unreachable, e)
        }
    }

    @Test
    fun exceptionMapping() {
        assertEquals(DeviceError.Timeout, UrlConnectionTransport.map(SocketTimeoutException()))
        assertEquals(DeviceError.Unreachable, UrlConnectionTransport.map(UnknownHostException("folio.local")))
        assertEquals(DeviceError.Unreachable, UrlConnectionTransport.map(java.net.ConnectException()))
        assertEquals(DeviceError.Unreachable, UrlConnectionTransport.map(java.net.SocketException("Connection reset")))
        assertEquals(DeviceError.Unreachable, UrlConnectionTransport.map(IOException("unexpected end of stream on http://x")))
        assertEquals(DeviceError.Other("weird"), UrlConnectionTransport.map(IOException("weird")))
    }

    @Test
    fun demoEndpointNeverUsesTheNetwork() {
        assertTrue(DemoDevice.ENDPOINT.isDemo)
        assertTrue(DemoDevice.ENDPOINT.host.endsWith(".invalid"))
        assertTrue(!DeviceEndpoint("folio.local").isDemo)
        assertTrue(HttpTransport.forEndpoint(DemoDevice.ENDPOINT) is DemoTransport)
        assertTrue(HttpTransport.forEndpoint(DeviceEndpoint("10.0.0.2")) is UrlConnectionTransport)
    }
}
