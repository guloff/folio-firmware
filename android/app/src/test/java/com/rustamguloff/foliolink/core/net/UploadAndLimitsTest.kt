package com.rustamguloff.foliolink.core.net

import com.rustamguloff.foliolink.core.model.FileEntry
import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.rustamguloff.foliolink.core.store.Outbox
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** BUG-033, FBK-038, BUG-038, BUG-040 (очередь вне главного потока). */
class UploadAndLimitsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun book(size: Int): File = tmp.newFile().apply { writeBytes(ByteArray(size) { 7 }) }

    // region BUG-033

    @Test
    fun freeNameGoesBeforeTheWholeBookExtension() {
        assertEquals("Книга (2).epub", UploadNames.freeName("Книга.epub", listOf("Книга.epub")))
        assertEquals("Книга (3).fb2.zip", UploadNames.freeName("Книга.fb2.zip", listOf("книга.fb2.zip", "Книга (2).fb2.zip")))
        assertEquals("notes (2)", UploadNames.freeName("notes", listOf("notes")))
    }

    @Test
    fun sameSizeIsADuplicateOtherSizeGetsAFreeName() {
        val listing = listOf(FileEntry("A.epub", 100), FileEntry("A (2).epub", 5))
        assertEquals(null, UploadNames.resolve(listing, "a.epub", 100))
        assertEquals("A (3).epub", UploadNames.resolve(listing, "A.epub", 101))
    }

    /** Читалка: /upload отвечает «уже существует» на занятое имя; список — по `files`. */
    private class Reader(val files: MutableList<FileEntry>, var listingFails: Boolean = false) {
        val uploaded = mutableListOf<String>()
        val transport = FakeTransport { req ->
            when (req.path) {
                "/api/files" -> if (listingFails) throw DeviceError.Timeout
                else FakeTransport.json(files.joinToString(",", "[", "]") { """{"name":"${it.name}","size":${it.size}}""" })
                "/upload" -> {
                    val name = MultipartBody.fileName(req.body!!)!!
                    if (files.any { it.name.equals(name, ignoreCase = true) }) {
                        FakeTransport.text("File already exists: $name", 400)
                    } else {
                        files += FileEntry(name, MultipartBody.fileContent(req.body!!)!!.size.toLong())
                        uploaded += name
                        FakeTransport.text("File uploaded successfully: $name")
                    }
                }
                else -> FakeTransport.json("""{"ok":true}""")
            }
        }
    }

    @Test
    fun uploadBookComparesSizeBeforeCallingItADuplicate() = runBlocking {
        val r = Reader(mutableListOf(FileEntry("A.epub", 10)))
        val client = r.transport.client("t")
        try {
            client.uploadBook(book(10), "A.epub", "/Books")
            fail()
        } catch (e: DeviceError.AlreadyExists) {
            // тот же размер — та же книга
        }
        assertEquals("A (2).epub", client.uploadBook(book(11), "A.epub", "/Books"))
        assertEquals(listOf("A (2).epub"), r.uploaded)
    }

    @Test
    fun failedListingAfterAlreadyExistsKeepsTheItemQueued() = runBlocking {
        val r = Reader(mutableListOf(FileEntry("A.epub", 10)))
        val outbox = Outbox(tmp.newFolder("Outbox"))
        outbox.enqueueBook(ByteArray(11), "A.epub", "A", "share")
        r.listingFails = true
        val report = OutboxRunner.drain(outbox, includeFailed = false) { item ->
            r.transport.client("t").uploadBook(outbox.payloadFile(item)!!, item.fileName, "/Books")
        }
        assertEquals("временная ошибка останавливает прогон", DeviceError.Timeout, report.stoppedBy)
        assertTrue(report.duplicates.isEmpty())
        assertEquals("копия на телефоне цела", 1, outbox.items().size)
        assertFalse(outbox.items()[0].failed)

        r.listingFails = false
        val second = OutboxRunner.drain(outbox, includeFailed = false) { item ->
            renamedTo = r.transport.client("t").uploadBook(outbox.payloadFile(item)!!, item.fileName, "/Books")
                .takeIf { it != item.fileName }
        }
        assertEquals(1, second.sent.size)
        assertEquals("A (2).epub", second.renamed.single().second)
        assertTrue(outbox.items().isEmpty())
    }

    // endregion

    // region FBK-038

    @Test
    fun replaceDoesNotTreatAFailedListingAsEmpty() = runBlocking {
        var listing = 0
        val t = FakeTransport { req ->
            if (req.path == "/api/files") { listing++; throw DeviceError.Timeout }
            FakeTransport.json("""{"ok":true}""")
        }
        try {
            t.client("t").upload(ByteArray(3), "s.bmp", "/sleep", replace = true)
            fail()
        } catch (e: DeviceError) {
            assertEquals(DeviceError.Timeout, e)
        }
        assertEquals("ни удаления, ни загрузки", listOf("/api/files"), t.requests.map { it.path })
        try {
            t.client("t").ensureDirectory("/fonts/A")
            fail()
        } catch (e: DeviceError) {
            assertEquals(DeviceError.Timeout, e)
        }
        assertFalse(t.requests.any { it.path == "/mkdir" })
    }

    // endregion

    // region BUG-038

    @Test
    fun streamedMultipartEqualsInMemoryBody() {
        val file = book(70_000)
        val streamed = MultipartBody("B").addFile("file", "x.epub", "application/epub+zip", file)
        val memory = MultipartBody("B").addFile("file", "x.epub", "application/epub+zip", file.readBytes())
        assertEquals(memory.length, streamed.length)
        assertArrayEquals(memory.finalized(), streamed.finalized())
        assertEquals(file.length(), MultipartBody.fileContent(streamed.finalized())!!.size.toLong())
    }

    @Test
    fun oversizedResponseIsRefusedWithoutTrustingContentLength() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/big") { ex ->
            ex.sendResponseHeaders(200, 0) // chunked: Content-Length нет
            ex.responseBody.use { out -> repeat(64) { out.write(ByteArray(1024)) } }
        }
        server.createContext("/liar") { ex ->
            ex.responseHeaders.add("Content-Length", "2000000000")
            ex.sendResponseHeaders(200, 2_000_000_000L)
            ex.responseBody.use { it.write(ByteArray(10)) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val t = UrlConnectionTransport(connectTimeoutMs = 2000)
            for (path in listOf("/big", "/liar")) {
                try {
                    t.execute(HttpRequest("GET", base + path, maxResponseBytes = 16 * 1024))
                    fail(path)
                } catch (e: DeviceError.TooLarge) {
                    assertEquals(16L * 1024, e.limit)
                }
            }
            assertEquals(64 * 1024, t.execute(HttpRequest("GET", "$base/big", maxResponseBytes = 64 * 1024)).body.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamedUploadReachesTheServerIntact() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var received = ByteArray(0)
        server.createContext("/upload") { ex ->
            received = ex.requestBody.readBytes()
            ex.sendResponseHeaders(200, 2)
            ex.responseBody.use { it.write("ok".toByteArray()) }
        }
        server.start()
        try {
            val file = book(200_000)
            val body = MultipartBody("B").addFile("file", "x.epub", "application/epub+zip", file)
            val progress = mutableListOf<Long>()
            UrlConnectionTransport(connectTimeoutMs = 2000).execute(
                HttpRequest("POST", "http://127.0.0.1:${server.address.port}/upload", content = body),
            ) { done, _ -> progress += done }
            assertArrayEquals(file.readBytes(), MultipartBody.fileContent(received))
            assertEquals(body.length, progress.last())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun streamedEnqueueHasACeilingAndLeavesNothingBehind() {
        val dir = tmp.newFolder("Outbox")
        val outbox = Outbox(dir)
        try {
            outbox.enqueueBookStream(ByteArrayInputStream(ByteArray(2048)), "big.epub", "big", "share", limit = 1024)
            fail()
        } catch (e: Outbox.TooLargeException) {
            assertEquals(1024L, e.limit)
        }
        assertTrue(outbox.items().isEmpty())
        assertTrue("ни папки, ни временного файла", dir.listFiles()!!.none { it.isDirectory })
        val ok = outbox.enqueueBookStream(ByteArrayInputStream(ByteArray(1000) { 3 }), "ok.epub", "ok", "share", limit = 1024)
        assertEquals(1000L, outbox.payloadFile(ok)!!.length())
    }

    // endregion

    @Test
    fun queueFileOperationsRunOnTheGivenIoContext() = runBlocking {
        val outbox = Outbox(tmp.newFolder("Outbox"))
        outbox.enqueue(OutboxOperation.DeleteVocab("a"), "a")
        val dispatched = AtomicInteger()
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched.incrementAndGet()
                Dispatchers.IO.dispatch(context, block)
            }
        }
        OutboxRunner.drain(outbox, includeFailed = false, io = io) { }
        assertTrue("индекс читается и пишется через io", dispatched.get() >= 2)
    }
}
