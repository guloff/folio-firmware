package com.rustamguloff.foliolink.app

import com.rustamguloff.foliolink.core.model.DeviceEndpoint
import com.rustamguloff.foliolink.core.model.LibraryChange
import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.rustamguloff.foliolink.core.model.SyncSnapshot
import com.rustamguloff.foliolink.core.net.DeviceClient
import com.rustamguloff.foliolink.core.net.DeviceError
import com.rustamguloff.foliolink.core.net.FakeTransport
import com.rustamguloff.foliolink.core.net.HttpRequest
import com.rustamguloff.foliolink.core.net.HttpResponse
import com.rustamguloff.foliolink.core.store.FilePairingTokenStore
import com.rustamguloff.foliolink.core.store.Outbox
import com.rustamguloff.foliolink.core.store.PairingTokenStore
import com.rustamguloff.foliolink.core.store.SettingsProfileStore
import com.rustamguloff.foliolink.core.store.SharedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Склейка AppModel по находкам аудита 2026-11-10: подключение (BUG-037, SEC-033, deviceId),
 * сопряжение (BUG-039, FBK-036), library перед записью (FBK-035), загрузка кэша (BUG-040).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuditFlowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val platform = FakePlatform()
    private val ep = DeviceEndpoint("10.0.0.7")
    private val token = "a".repeat(64)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Читалка с настраиваемыми status/info; помнит, с каким токеном что пришло. */
    private class Reader(
        var status: () -> HttpResponse = { FakeTransport.json("""{"serial":"SN-A","version":"1.1.1"}""") },
        var deviceId: String? = null,
        var acceptedToken: String? = "a".repeat(64),
        var library: String = """{"version":1,"shelves":[],"status":{},"goals":{"dailyMinutes":30}}""",
    ) {
        val transport = FakeTransport { req -> handle(req) }
        val tokensSeen = mutableListOf<Pair<String, String?>>()

        private fun handle(req: HttpRequest): HttpResponse {
            val presented = req.header(DeviceClient.TOKEN_HEADER)
            tokensSeen += req.path to presented
            val authorized = presented != null && presented == acceptedToken
            return when (req.path) {
                "/api/status" -> status()
                "/api/inklink/info" -> FakeTransport.json(
                    """{"api":1,"firmware":"1.1.1","features":["pairing","library"],""" +
                        (deviceId?.let { """"deviceId":"$it",""" } ?: "") +
                        """"pairing":{"required":true,"paired":true,"authorized":$authorized}}""",
                )
                "/api/inklink/library" -> if (req.method == "GET") FakeTransport.json(library) else FakeTransport.json("""{"ok":true}""")
                "/api/inklink/pair/start" -> FakeTransport.json("""{"ok":true,"digits":6,"expiresIn":120}""")
                "/api/inklink/pair/finish" -> FakeTransport.json("""{"ok":true,"token":"${"b".repeat(64)}"}""")
                "/api/inklink/books" -> FakeTransport.json("""{"books":[],"total":0}""")
                else -> FakeTransport.json("""{"ok":true}""")
            }
        }
    }

    private fun model(reader: Reader, tokens: PairingTokenStore = FilePairingTokenStore(File(tmp.root, "tokens.json"))): AppModel {
        val store = SharedStore(File(tmp.root, "Store"))
        return AppModel(
            platform = platform,
            store = store,
            outboxStore = Outbox(File(tmp.root, "Outbox")),
            settings = LocalSettings(MemoryKeyValueStore()),
            tokens = tokens,
            profileStore = SettingsProfileStore(store),
            transportFor = { reader.transport },
            ioContext = Dispatchers.Unconfined,
            statusRetryDelayMs = 0,
        )
    }

    private fun Reader.tokenOn(path: String) = tokensSeen.filter { it.first == path }.map { it.second }

    // region BUG-037 / SEC-033

    @Test
    fun statusFailureIsRetriedAndTheSerialKeyKept() = runTest {
        var calls = 0
        val r = Reader(status = {
            calls++
            if (calls <= 2) throw DeviceError.Timeout
            FakeTransport.json("""{"serial":"SN-A"}""")
        })
        val m = model(r)
        m.tokens.save(token, "SN-A")
        assertTrue(m.connect(ep))
        assertEquals("3 попытки при подключении + шаг status в sync", 4, calls)
        assertEquals("SN-A", m.snapshot.value.deviceKey)
        assertEquals(token, r.tokenOn("/api/inklink/info").last())
        assertTrue(m.hasToken.value)
    }

    @Test
    fun hostWithoutSerialGetsNoTokenAndCannotEraseIt() = runTest {
        // Чужой хост на сохранённом адресе: серийника нет, на любой токен — authorized:false.
        val r = Reader(status = { FakeTransport.json("""{"serial":"Not found"}""") }, acceptedToken = "other")
        val m = model(r)
        m.store.saveSnapshot(SyncSnapshot(device = ep, deviceKey = "SN-A"))
        val reopened = model(r)
        reopened.tokens.save(token, "SN-A")
        reopened.submit(OutboxOperation.DeleteVocab("w"), "w")
        assertTrue(reopened.connect(ep))
        assertTrue("ни одного запроса с токеном", r.tokensSeen.all { it.second == null })
        assertEquals("токен не стёрт", token, reopened.tokens.token("SN-A"))
        assertEquals("ключ и кэш не сменились", "SN-A", reopened.snapshot.value.deviceKey)
        assertEquals("правка для SN-A ждёт свою читалку", 1, reopened.outbox.value.size)
        assertFalse(r.transport.requests.any { it.path == "/api/inklink/vocab/delete" })
    }

    @Test
    fun discoveredReaderWithOtherSerialDoesNotGetOurToken() = runTest {
        val r = Reader(status = { FakeTransport.json("""{"serial":"SN-B"}""") })
        val m = model(r)
        m.store.saveSnapshot(SyncSnapshot(device = ep, deviceKey = "SN-A"))
        val reopened = model(r)
        reopened.tokens.save(token, "SN-A")
        assertTrue(reopened.connect(DeviceEndpoint("10.0.0.8")))
        assertTrue(r.tokensSeen.all { it.second == null })
        assertEquals("SN-B", reopened.snapshot.value.deviceKey)
        assertEquals(token, reopened.tokens.token("SN-A"))
    }

    @Test
    fun confirmedReaderThatRejectsTheTokenForgetsIt() = runTest {
        val r = Reader(acceptedToken = "other")
        val m = model(r)
        m.tokens.save(token, "SN-A")
        assertTrue(m.connect(ep))
        assertNull(m.tokens.token("SN-A"))
        assertFalse(m.hasToken.value)
    }

    @Test
    fun deviceIdIsAskedWithoutTokenThenTokenFollows() = runTest {
        val id = "0123456789abcdef0123456789abcdef"
        val r = Reader(status = { FakeTransport.json("""{"serial":"Not found"}""") }, deviceId = id)
        val m = model(r)
        m.tokens.save(token, "id:$id")
        assertTrue(m.connect(ep))
        assertEquals("сначала без токена, потом с ним", listOf(null, token), r.tokenOn("/api/inklink/info").take(2))
        assertEquals("id:$id", m.snapshot.value.deviceKey)
        assertTrue(m.hasToken.value)
    }

    @Test
    fun addressKeyMigratesToDeviceIdWithoutCacheReset() = runTest {
        val id = "0123456789abcdef0123456789abcdef"
        val r = Reader(status = { FakeTransport.json("""{"serial":"Not found"}""") }, deviceId = id)
        val first = model(r)
        first.store.saveSnapshot(SyncSnapshot(device = ep, deviceKey = ep.id, lastSessionStart = 42))
        first.tokens.save(token, ep.id)
        val m = model(r)
        m.submit(OutboxOperation.DeleteVocab("w"), "w") // офлайн, для ключа host:port
        assertEquals(ep.id, m.outbox.value.single().deviceKey)
        r.acceptedToken = token
        assertTrue(m.connect(ep))
        val key = "id:$id"
        assertEquals(key, m.snapshot.value.deviceKey)
        assertEquals("кэш не сброшен", 42L, m.snapshot.value.lastSessionStart)
        assertEquals(token, m.tokens.token(key))
        assertNull(m.tokens.token(ep.id))
        assertTrue("правка ушла под новым ключом", m.outbox.value.isEmpty())
        assertTrue(r.transport.requests.any { it.path == "/api/inklink/vocab/delete" })
    }

    @Test
    fun readerWithNeitherSerialNorDeviceIdConnectsWithoutTokenAndSaysSo() = runTest {
        val r = Reader(status = { FakeTransport.json("""{"serial":"Not found"}""") })
        val m = model(r)
        m.tokens.save(token, ep.id)
        assertTrue(m.connect(ep))
        assertTrue(r.tokensSeen.all { it.second == null })
        assertFalse(m.hasToken.value)
        assertTrue(platform.get(com.rustamguloff.foliolink.R.string.core_reader_unidentified) in m.banner.value!!.text)
    }

    // endregion

    // region BUG-039 / FBK-036

    @Test
    fun pinIsRequestedOnceAcrossRecreatedSheets() = runTest {
        val r = Reader()
        val m = model(r)
        m.tokens.save(token, "SN-A")
        m.connect(ep)
        m.ensurePin()
        m.ensurePin() // поворот экрана: окно создано заново
        assertEquals(1, r.transport.requests.count { it.path == "/api/inklink/pair/start" })
        assertEquals(6, m.pairingUi.value.digits)
        m.requestPin() // «Новый PIN» — явно
        assertEquals(2, r.transport.requests.count { it.path == "/api/inklink/pair/start" })
    }

    @Test
    fun pairingThatCannotBeSavedIsNotShownAsPaired() = runTest {
        val r = Reader(acceptedToken = "b".repeat(64))
        val failing = object : PairingTokenStore {
            override fun token(device: String): String? = null
            override fun save(token: String, device: String) = false
            override fun remove(device: String) = true
        }
        val m = model(r, failing)
        m.connect(ep)
        m.submitPin("123456")
        assertFalse(m.hasToken.value)
        assertTrue(m.pairingUi.value.error is AppModel.PairingNotSaved)
        assertEquals(platform.get(com.rustamguloff.foliolink.R.string.core_pairing_save_failed), m.errorText(m.pairingUi.value.error!!))
    }

    // endregion

    // region FBK-035

    @Test
    fun unexpectedLibraryShapeIsNotOverwritten() = runTest {
        val r = Reader(library = """{"version":1,"shelves":{"oops":1},"status":{}}""")
        val m = model(r)
        m.tokens.save(token, "SN-A")
        m.connect(ep)
        m.changeLibrary(LibraryChange.CreateShelf("s1", "Полка"), "new shelf")
        assertFalse("POST не выполнен", r.transport.requests.any { it.path == "/api/inklink/library" && it.method == "POST" })
        assertTrue("элемент остался", m.outbox.value.single().failed)

        r.library = """{"version":1,"shelves":[{"id":"x","name":"A","books":["/a.epub"]}],"status":{"/a.epub":"done"},"goals":{}}"""
        m.retryOutbox(m.outbox.value.single())
        assertTrue(r.transport.requests.any { it.path == "/api/inklink/library" && it.method == "POST" })
        assertTrue(m.outbox.value.isEmpty())
    }

    // endregion

    // region BUG-040

    @Test
    fun cacheIsLoadedAndBleWaitsUntilThen() = runTest {
        val store = SharedStore(File(tmp.root, "Store"))
        store.saveSnapshot(SyncSnapshot(deviceKey = "SN-A"))
        val m = model(Reader())
        assertTrue(m.loaded.value)
        assertEquals("SN-A", m.snapshot.value.deviceKey)
        assertFalse(m.isSyncing)
    }

    // endregion
}
