package com.rustamguloff.foliolink.app

import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.ble.BleError
import com.rustamguloff.foliolink.core.model.BookStatus
import com.rustamguloff.foliolink.core.model.DeviceEndpoint
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.model.LibraryChange
import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.rustamguloff.foliolink.core.model.RemoteFile
import com.rustamguloff.foliolink.core.model.SleepResponse
import com.rustamguloff.foliolink.core.net.DemoDevice
import com.rustamguloff.foliolink.core.net.DemoReader
import com.rustamguloff.foliolink.core.net.DemoTransport
import com.rustamguloff.foliolink.core.net.DeviceClient
import com.rustamguloff.foliolink.core.net.DeviceError
import com.rustamguloff.foliolink.core.net.FakeTransport
import com.rustamguloff.foliolink.core.net.HttpRequest
import com.rustamguloff.foliolink.core.net.HttpResponse
import com.rustamguloff.foliolink.core.net.HttpTransport
import com.rustamguloff.foliolink.core.net.MultipartBody
import com.rustamguloff.foliolink.core.net.TransferProgress
import com.rustamguloff.foliolink.core.net.jsonBody
import com.rustamguloff.foliolink.core.store.FilePairingTokenStore
import com.rustamguloff.foliolink.core.store.Outbox
import com.rustamguloff.foliolink.core.store.SettingsProfile
import com.rustamguloff.foliolink.core.store.SettingsProfileStore
import com.rustamguloff.foliolink.core.store.SharedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FakePlatform : Platform {
    val gallery = mutableListOf<Pair<String, Int>>()
    var galleryDenied = false
    var reminders: List<Any>? = null
    var widgetReloads = 0
    override val phoneName = "Pixel Test"
    override fun get(id: Int, vararg args: Any): String = "#$id" + if (args.isEmpty()) "" else args.joinToString("|", "(", ")")
    override suspend fun saveToGallery(image: ByteArray, fileName: String) {
        if (galleryDenied) throw GalleryAccessDenied()
        gallery += fileName to image.size
    }
    override fun rescheduleReminders(enabled: Boolean, minutesOfDay: Int, goalMetToday: Boolean, todayMinutes: Int, goalMinutes: Int) {
        reminders = listOf(enabled, minutesOfDay, goalMetToday, todayMinutes, goalMinutes)
    }
    override fun reloadWidgets() { widgetReloads++ }
}

/** Центр состояния на демо-читалке и фейковом транспорте (без Android). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val platform = FakePlatform()
    private var reader = DemoReader()
    private val tokensFile get() = File(tmp.root, "tokens.json")

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model(transport: ((DeviceEndpoint) -> HttpTransport)? = null): AppModel {
        val store = SharedStore(File(tmp.root, "Store"))
        return AppModel(
            platform = platform,
            store = store,
            outboxStore = Outbox(File(tmp.root, "Outbox")),
            settings = LocalSettings(MemoryKeyValueStore()),
            tokens = FilePairingTokenStore(tokensFile),
            profileStore = SettingsProfileStore(store),
            transportFor = transport ?: { DemoTransport(reader) },
            ioContext = Dispatchers.Unconfined,
            statusRetryDelayMs = 0,
        )
    }

    private fun t(id: Int, vararg args: Any) = platform.get(id, *args)

    @Test
    fun demoConnectSyncsEverything() = runTest {
        val m = model()
        assertTrue(m.connectDemo())
        assertEquals(AppModel.Connection.Connected, m.connection.value)
        val s = m.snapshot.value
        assertEquals("DEMO-FOLIO", s.deviceKey)
        assertEquals(9, s.books.size)
        assertEquals(5, s.highlights.size)
        assertEquals(3, s.vocab.size)
        assertEquals(2, s.screenshots.size)
        assertEquals(5, s.sleep?.images?.size)
        assertNotNull(s.stats)
        assertNotNull(s.lastSync)
        assertNotNull(s.lastFullSessionSync)
        assertTrue(m.sessions.value.size > 50)
        assertEquals(30, m.goalMinutes.value)
        assertEquals(AppModel.PairingState.NOT_REQUIRED, m.pairingState.value)
        assertEquals(AppModel.Banner.Kind.SUCCESS, m.banner.value?.kind)
        assertEquals(t(R.string.core_synced), m.banner.value?.text)
        assertFalse(m.syncing.value)
        assertNull(m.syncStep.value)
        assertNotNull(platform.reminders)
        assertEquals("demo address is not remembered as manual", "", m.settings.manualAddress)
        m.awaitWrites()
        // Виджеты перезагружаются после записи widget.json.
        assertTrue(platform.widgetReloads > 0)
        val reopened = SharedStore(File(tmp.root, "Store"))
        assertEquals(9, reopened.loadSnapshot().books.size)
        assertEquals(m.sessions.value.size, reopened.loadSessions().size)
        assertNotNull(reopened.loadWidgetSummary())

        // Второй синк — инкрементальный курсор (since > 0), без дублей.
        m.sync()
        assertEquals(reopened.loadSessions().size, m.sessions.value.size)
    }

    @Test
    fun offlineEditsAreQueuedShownAndSentOnConnect() = runTest {
        // Кэш от прошлой синхронизации, читалки нет.
        val m1 = model()
        m1.connectDemo()
        m1.disconnect()
        val book = m1.snapshot.value.books.first { it.path == "/books/Frankenstein.epub" }
        assertEquals("want", book.status)

        val r = m1.setStatus(BookStatus.READING, book)
        assertEquals(AppModel.SubmitResult.QUEUED, r)
        assertEquals(t(R.string.core_saved_in_queue), m1.banner.value?.text)
        assertEquals("applied to the cache at once", "reading", m1.book(book.path)?.status)
        assertEquals(1, m1.outbox.value.size)
        assertEquals("DEMO-FOLIO", m1.outbox.value[0].deviceKey)
        val highlight = m1.snapshot.value.highlights.first { it.id == "hdemo1" }
        m1.updateNote(highlight, "офлайн")
        m1.deleteWord(m1.snapshot.value.vocab.first())
        m1.setGoal(45)
        assertEquals("офлайн", m1.snapshot.value.highlights.first { it.id == "hdemo1" }.note)
        assertEquals(2, m1.snapshot.value.vocab.size)
        assertEquals(45, m1.goalMinutes.value)
        assertEquals(4, m1.outbox.value.size)

        // Перезапуск приложения: очередь и кэш с диска; подключение отправляет всё до скачивания.
        m1.awaitWrites()
        val m2 = model()
        assertEquals(4, m2.outbox.value.size)
        assertTrue(m2.connectDemo())
        assertTrue(m2.outbox.value.isEmpty())
        assertEquals("reading", m2.book(book.path)?.status)
        assertEquals("офлайн", m2.snapshot.value.highlights.first { it.id == "hdemo1" }.note)
        assertEquals(2, m2.snapshot.value.vocab.size)
        assertEquals(45, m2.snapshot.value.library?.dailyMinutes)
        assertEquals(t(R.string.core_synced_sent, 4), m2.banner.value?.text)
    }

    @Test
    fun submitWhileConnectedSendsAtOnce() = runTest {
        val m = model()
        m.connectDemo()
        val book = m.snapshot.value.books.first()
        val shelf = m.snapshot.value.library!!.shelves.first { it.id == "adventure" }
        assertEquals(AppModel.SubmitResult.SENT, m.setShelf(shelf, true, book))
        assertTrue(m.outbox.value.isEmpty())
        assertTrue("adventure" in m.book(book.path)!!.shelves)
        assertEquals(AppModel.SubmitResult.SENT, m.createShelf("Новая"))
        assertTrue(m.snapshot.value.library!!.shelves.any { it.name == "Новая" })
        assertTrue(m.deleteRemote(m.snapshot.value.screenshots.first()))
        assertEquals(1, m.snapshot.value.screenshots.size)
    }

    @Test
    fun rejectedChangeStaysInTheQueueWithItsError() = runTest {
        val m = model()
        m.connectDemo()
        val ghost = Highlight(id = "nope", book = "/b", text = "x")
        assertEquals(AppModel.SubmitResult.FAILED, m.updateNote(ghost, "n"))
        val item = m.outbox.value.single()
        assertTrue(item.failed)
        assertEquals(t(R.string.core_error_server, 404, "not found"), item.lastError)
        assertEquals(1, m.failedOutboxCount)
        assertEquals(AppModel.Banner.Kind.ERROR, m.banner.value?.kind)
        // Автоматический прогон его пропускает, «Повторить» — пробует снова.
        assertNull(m.drainOutbox()?.failed?.firstOrNull())
        m.retryOutbox(item)
        assertTrue(m.outbox.value.single().failed)
        assertEquals(2, m.outbox.value.single().attempts)
        m.removeFromOutbox(item)
        assertTrue(m.outbox.value.isEmpty())
        // Выделение без id нельзя менять удалённо.
        assertEquals(AppModel.SubmitResult.FAILED, m.deleteHighlight(Highlight(id = "", text = "legacy")))
        assertTrue(m.outbox.value.isEmpty())
    }

    @Test
    fun retryExplainsUnsupportedAndForeignItems() = runTest {
        val m = model()
        m.retryOutbox()
        assertEquals(t(R.string.core_connect_to_send), m.banner.value?.text)
        m.connectDemo()
        val foreign = m.outboxStore.enqueue(OutboxOperation.DeleteVocab("w"), "w", deviceKey = "OTHER")
        m.refreshOutbox()
        assertTrue(m.isForOtherReader(foreign))
        m.retryOutbox(foreign)
        assertEquals(t(R.string.core_item_for_other_reader), m.banner.value?.text)
        val future = OutboxOperation.Unsupported(JsonPrimitive("x"))
        m.retryOutbox(com.rustamguloff.foliolink.core.model.OutboxItem(fileName = "", title = "f", source = "", operation = future))
        assertEquals(t(R.string.core_item_needs_newer_app), m.banner.value?.text)
    }

    @Test
    fun anotherReaderResetsTheCache() = runTest {
        val m = model()
        m.connectDemo()
        assertTrue(m.sessions.value.isNotEmpty())
        m.awaitWrites()
        val other = FakeTransport { req ->
            when (req.path) {
                "/api/status" -> FakeTransport.json("""{"serial":"OTHER-1"}""")
                "/api/inklink/info" -> FakeTransport.json("""{"api":1,"features":["stats"]}""")
                "/api/inklink/sessions" -> FakeTransport.json("""{"sessions":[]}""")
                "/api/inklink/stats" -> FakeTransport.json("{}")
                else -> FakeTransport.json("""{"ok":true}""")
            }
        }
        val m2 = AppModel(platform, m.store, m.outboxStore, m.settings, m.tokens, m.profileStore, transportFor = { other },
            ioContext = Dispatchers.Unconfined, statusRetryDelayMs = 0)
        assertTrue(m2.connect(DeviceEndpoint("10.0.0.9")))
        assertEquals("OTHER-1", m2.snapshot.value.deviceKey)
        assertTrue("another reader's history must not merge", m2.sessions.value.isEmpty())
        assertTrue(m2.snapshot.value.books.isEmpty())
        assertEquals("10.0.0.9:80", m2.settings.manualAddress)
    }

    /** Читалка с сопряжением: 401 на изменениях без токена, токен после PIN. */
    private class PairingReader {
        var token: String? = null
        val transport = FakeTransport { req -> handle(req) }
        private fun handle(req: HttpRequest): HttpResponse {
            val presented = req.header(DeviceClient.TOKEN_HEADER)
            val authorized = presented != null && presented == token
            return when {
                req.path == "/api/status" -> FakeTransport.json("""{"serial":"SN-PAIR","version":"1.0"}""")
                req.path == "/api/inklink/info" ->
                    FakeTransport.json("""{"api":1,"firmware":"1.0","features":["pairing"],"pairing":{"required":true,"paired":${token != null},"authorized":$authorized}}""")
                req.path == "/api/inklink/pair/start" -> FakeTransport.json("""{"ok":true,"digits":6,"expiresIn":120}""")
                req.path == "/api/inklink/pair/finish" ->
                    if (req.body!!.toString(Charsets.UTF_8).contains("\"123456\"")) {
                        token = "f".repeat(64)
                        FakeTransport.json("""{"ok":true,"token":"$token"}""")
                    } else {
                        FakeTransport.json("""{"ok":false,"error":"bad pin","attemptsLeft":4}""", 403)
                    }
                req.method == "POST" && !authorized -> FakeTransport.json("""{"ok":false,"error":"pairing required"}""", 401)
                else -> FakeTransport.json("""{"ok":true}""")
            }
        }
    }

    @Test
    fun pairingFlow() = runTest {
        val reader = PairingReader()
        val m = model { reader.transport }
        // Офлайн-правка ждёт сопряжения.
        m.submit(OutboxOperation.DeleteVocab("w"), "w")
        assertTrue(m.connect(DeviceEndpoint("10.0.0.5")))
        assertTrue("POST /time answered 401", m.pairingPrompt.value)
        assertEquals(AppModel.PairingState.UNPAIRED, m.pairingState.value)
        assertFalse(m.hasToken.value)
        assertEquals("the queue waits, not rejected", 1, m.outbox.value.size)
        assertFalse(m.outbox.value[0].failed)

        assertEquals(6, m.startPairing().digits)
        assertEquals("Pixel Test", reader.transport.requests.last().jsonBody()["name"]?.let { (it as JsonPrimitive).content })
        try {
            m.finishPairing("000000")
            fail()
        } catch (e: DeviceError) {
            assertEquals(DeviceError.Pairing(DeviceError.PairingFailure.BadPin(4)), e)
            assertEquals(t(R.string.core_error_bad_pin_left, 4), m.errorText(e))
        }
        m.finishPairing("123456")
        assertTrue(m.hasToken.value)
        assertFalse(m.pairingPrompt.value)
        assertEquals(AppModel.PairingState.PAIRED, m.pairingState.value)
        assertEquals("f".repeat(64), m.tokens.token("SN-PAIR"))
        assertTrue("replayed after pairing", m.outbox.value.isEmpty())
        assertEquals("f".repeat(64), m.blePlan().token)

        // Сброс на читалке: токен больше не принимается — приложение его забывает.
        reader.token = "other"
        m.disconnect()
        m.connect(DeviceEndpoint("10.0.0.5"))
        assertNull(m.tokens.token("SN-PAIR"))
        assertFalse(m.hasToken.value)
        try {
            m.blePlan()
            fail()
        } catch (e: BleError) {
            assertEquals(BleError.NoToken, e)
        }
    }

    @Test
    fun forgetReaderDropsTheToken() = runTest {
        val m = model()
        m.connectDemo()
        m.tokens.save("tok", "DEMO-FOLIO")
        m.forgetReader()
        assertNull(m.tokens.token("DEMO-FOLIO"))
        assertNull(m.snapshot.value.device)
        assertEquals(AppModel.Connection.Disconnected, m.connection.value)
        assertEquals(t(R.string.core_reader_forgotten), m.banner.value?.text)
    }

    @Test
    fun unreachableReaderSilentAndLoud() = runTest {
        val down = FakeTransport { throw DeviceError.Unreachable }
        val m = model { down }
        assertFalse(m.connect(DeviceEndpoint("10.0.0.7"), silent = true))
        assertEquals(AppModel.Connection.Disconnected, m.connection.value)
        assertNull(m.banner.value)
        assertFalse(m.connect(DeviceEndpoint("10.0.0.7")))
        assertEquals(AppModel.Connection.Failed(t(R.string.core_error_unreachable)), m.connection.value)
        assertEquals(AppModel.Banner.Kind.ERROR, m.banner.value?.kind)
        assertFalse(m.connect("  "))
        assertEquals(t(R.string.core_error_invalid_address), m.banner.value?.text)
    }

    @Test
    fun sleepChoiceOfflineIsQueuedAndShownAtOnce() = runTest {
        val m = model()
        m.connectDemo()
        m.disconnect()
        assertEquals(8, m.snapshot.value.sleep?.mode)
        val name = m.snapshot.value.sleep!!.images[1].name
        assertTrue(m.chooseSleepImage(SleepChoice.Image(name)))
        assertEquals(name, m.snapshot.value.sleep?.selected)
        assertEquals("a mode without pictures switches to Custom", 2, m.snapshot.value.sleep?.mode)
        assertEquals(
            listOf(OutboxOperation.SelectSleepImage(name), OutboxOperation.Settings(mapOf("sleepScreen" to JsonPrimitive(2)))),
            m.outbox.value.map { it.operation },
        )
        assertEquals(t(R.string.core_title_sleep_show, name), m.outbox.value[0].title)
        assertEquals(t(R.string.core_title_sleep_mode, t(R.string.core_sleep_mode_2)), m.outbox.value[1].title)

        // Подключение отправляет и читалка запоминает выбор.
        m.connectDemo()
        assertTrue(m.outbox.value.isEmpty())
        assertEquals(name, m.snapshot.value.sleep?.selected)
        assertEquals(2, m.snapshot.value.sleep?.mode)

        // Случайная: только выбор; режим с картинками не трогается.
        assertTrue(m.chooseSleepImage(SleepChoice.Random))
        assertEquals("", m.snapshot.value.sleep?.selected)
        assertEquals(t(R.string.core_sleep_chosen, t(R.string.core_sleep_random)), m.banner.value?.text)

        // Удаление выбранной картинки снимает выбор сразу.
        m.chooseSleepImage(SleepChoice.Image(name))
        val file = m.snapshot.value.sleep!!.images.first { it.name == name }
        m.disconnect()
        m.deleteRemote(file)
        assertEquals("", m.snapshot.value.sleep?.selected)
        assertFalse(m.snapshot.value.sleep!!.images.any { it.name == name })
    }

    @Test
    fun oldFirmwareSleepSelectionIsRememberedPerFirmware() = runTest {
        val m = model()
        m.connectDemo()
        assertTrue(m.sleepSelectionSupported)
        m.settings.sleepSelectUnsupported = mapOf("DEMO-FOLIO" to "0.2.0-x4pro")
        assertFalse(m.sleepSelectionSupported)
        // Без поддержки выбора картинка только переключает режим.
        assertEquals(listOf(OutboxOperation.Settings(mapOf("sleepScreen" to JsonPrimitive(2)))),
            SleepChoice.Image("a.bmp").plan(8, selectionSupported = false))
        assertTrue(SleepChoice.Random.plan(8, selectionSupported = false).isEmpty())
        m.settings.sleepSelectUnsupported = mapOf("DEMO-FOLIO" to "0.1.0")
        assertTrue("a firmware update makes the app try again", m.sleepSelectionSupported)
    }

    @Test
    fun uploadsGoThroughTheQueue() = runTest {
        val m = model()
        m.connectDemo()
        assertEquals(AppModel.SubmitResult.SENT, m.uploadBook("epub".toByteArray(), "Мастер/Маргарита.epub"))
        assertEquals(t(R.string.core_book_uploaded, "Мастер_Маргарита.epub"), m.banner.value?.text)
        assertTrue(m.uploadSleepImage(byteArrayOf(0x42, 0x4D, 1), "card.bmp"))
        assertEquals("card.bmp", m.snapshot.value.sleep?.selected)
        assertTrue(m.snapshot.value.sleep!!.images.any { it.name == "card.bmp" })
        val fonts = m.uploadFont("Literata", listOf("Literata-Regular.ttf" to byteArrayOf(1), "Literata-Bold.ttf" to byteArrayOf(2)))
        assertEquals(listOf(AppModel.SubmitResult.SENT, AppModel.SubmitResult.SENT), fonts)
        assertEquals(AppModel.SubmitResult.SENT, m.deleteFontFamily("Literata"))
        assertNull("transfer bar hidden after the queue ran", m.transfer.value)
        assertTrue(m.outbox.value.isEmpty())
    }

    /** FB2 (Folio 1.1): в ту же папку книг, имя как есть (`.fb2.zip` целиком), свой MIME. */
    @Test
    fun fb2BooksUploadUnderTheirOwnNames() = runTest {
        val reader = DemoReader()
        val sent = mutableListOf<HttpRequest>()
        val m = model { endpoint ->
            val demo = DemoTransport(reader)
            object : HttpTransport {
                override suspend fun execute(request: HttpRequest, progress: TransferProgress?): HttpResponse {
                    if (request.decodedPath == "/upload") sent += request
                    return demo.execute(request, progress)
                }
            }
        }
        m.connectDemo()
        val fb2 = "<FictionBook/>".toByteArray()
        val zip = byteArrayOf(0x50, 0x4B, 3, 4, 9)
        assertEquals(AppModel.SubmitResult.SENT, m.uploadBook(fb2, "Чехов. Каштанка.fb2"))
        assertEquals(AppModel.SubmitResult.SENT, m.uploadBook(zip, "Book.FB2.ZIP"))
        assertEquals(listOf("Чехов. Каштанка.fb2", "Book.FB2.ZIP"), sent.map { MultipartBody.fileName(it.body!!) })
        assertTrue(sent.all { it.query["path"] == "/books" })
        val heads = sent.map { String(it.body!!, 0, minOf(400, it.body!!.size), Charsets.ISO_8859_1) }
        assertTrue(heads[0].contains("Content-Type: application/x-fictionbook+xml"))
        assertTrue(heads[1].contains("Content-Type: application/zip"))
        assertArrayEquals(fb2, MultipartBody.fileContent(sent[0].body!!))
        assertArrayEquals(zip, MultipartBody.fileContent(sent[1].body!!))
        // Название в очереди и баннер — без двойного расширения.
        assertEquals(t(R.string.core_book_uploaded, "Book.FB2.ZIP"), m.banner.value?.text)
    }

    @Test
    fun settingsProfilesApplyOnlyDifferences() = runTest {
        val m = model()
        m.connectDemo()
        val current = m.loadDeviceSettings()!!
        val saved = m.saveSettingsProfile("  ", current)!!
        assertEquals(t(R.string.core_profile_default_name, 1), saved.name)
        assertEquals(1, m.profiles.value.size)
        assertNull("identical profile: nothing to send", m.applySettingsProfile(saved, current))
        assertEquals(t(R.string.core_profile_same, saved.name), m.banner.value?.text)

        val night = SettingsProfile(name = "Ночь", values = saved.values + ("fontSize" to JsonPrimitive(3)) + ("frontLight" to JsonPrimitive(1)))
        assertEquals(AppModel.SubmitResult.SENT, m.applySettingsProfile(night, current))
        assertEquals(t(R.string.core_profile_applied, "Ночь", 1) + "\n" + t(R.string.core_profile_skipped, 1), m.banner.value?.text)
        assertEquals(JsonPrimitive(3), m.snapshot.value.deviceSettings!!.first { it.key == "fontSize" }.value)

        m.renameSettingsProfile(saved, "День")
        assertEquals("День", m.profiles.value.single().name)
        m.deleteSettingsProfile(m.profiles.value.single())
        assertTrue(m.profiles.value.isEmpty())
    }

    @Test
    fun screenshotsGoToTheGallery() = runTest {
        val m = model()
        m.connectDemo()
        val shots = m.snapshot.value.screenshots
        val r = m.saveScreenshots(shots)
        assertEquals(2, r.saved)
        assertNull(r.failure)
        assertEquals(shots.map { it.name }, platform.gallery.map { it.first })
        assertTrue(platform.gallery.all { it.second == 54 + 480 * 3 * 800 })
        assertEquals(shots.map { it.path }.toSet(), m.settings.savedScreenshots)
        assertEquals(t(R.string.core_saved_to_gallery, 2), m.banner.value?.text)
        assertNull(m.transfer.value)
    }

    @Test
    fun autoSaveTakesOnlyNewScreenshotsAndTurnsOffWithoutAccess() = runTest {
        val m = model()
        m.connectDemo()
        m.setAutoSaveScreenshots(true)
        assertTrue("existing screenshots are not auto-saved", m.unsavedScreenshots.isEmpty())
        m.settings.savedScreenshots = emptySet()
        platform.galleryDenied = true
        m.sync()
        assertFalse(m.settings.autoSaveScreenshots)
        assertEquals(AppModel.Banner.Kind.ERROR, m.banner.value?.kind)
        assertTrue(m.banner.value!!.text.contains(t(R.string.core_autosave_denied)))
    }

    @Test
    fun autoSaveOutcome() {
        val p = FakePlatform()
        assertNull(AppModel.autoSaveOutcome(0, null, p))
        val ok = AppModel.autoSaveOutcome(3, null, p)!!
        assertFalse(ok.isError)
        assertFalse(ok.disable)
        val denied = AppModel.autoSaveOutcome(0, GalleryAccessDenied(), p)!!
        assertTrue(denied.isError)
        assertTrue(denied.disable)
        val offline = AppModel.autoSaveOutcome(1, DeviceError.Unreachable, p)!!
        assertTrue(offline.isError)
        assertFalse("a lost connection is retried on the next sync", offline.disable)
        assertTrue(offline.text.startsWith(p.get(R.string.core_autosave_partial, 1)))
    }

    @Test
    fun deleteThatFindsNothingCountsAsDone() {
        assertTrue(AppModel.isAlreadyGone(DeviceError.Server(500, "Failed to delete some items: /screenshots/a.bmp (not found); ")))
        assertTrue(AppModel.isAlreadyGone(DeviceError.Server(404, "highlight not found")))
        assertTrue(AppModel.isAlreadyGone(DeviceError.Server(404, "word not found")))
        assertFalse(AppModel.isAlreadyGone(DeviceError.Server(500, "Failed to delete some items: /a (protected file); ")))
        assertFalse(AppModel.isAlreadyGone(DeviceError.Server(400, "bad")))
        assertFalse(AppModel.isAlreadyGone(DeviceError.NotSupported))
        assertFalse(AppModel.isAlreadyGone(DeviceError.Unreachable))
    }

    @Test
    fun replayedDeleteOfMissingFileIsNotARejection() = runTest {
        val gone = FakeTransport { req ->
            when (req.path) {
                "/api/status" -> FakeTransport.json("""{"serial":"SN"}""")
                "/api/inklink/info" -> FakeTransport.json("""{"features":["files"]}""")
                "/delete" -> FakeTransport.text("Failed to delete some items: /screenshots/a.bmp (not found); ", 500)
                else -> FakeTransport.json("""{"ok":true}""")
            }
        }
        val m = model { gone }
        m.connect(DeviceEndpoint("10.0.0.3"))
        assertTrue(m.deleteRemote(RemoteFile(name = "a.bmp", path = "/screenshots/a.bmp")))
        assertTrue(m.outbox.value.isEmpty())
    }

    @Test
    fun firmwareUploadsImageAndSignatureThenApplies() = runTest {
        val m = model()
        assertFalse(m.installFirmware(byteArrayOf(1), byteArrayOf(2), "x").ok)
        m.connectDemo()
        val r = m.installFirmware(ByteArray(1000) { 0xE9.toByte() }, ByteArray(64), "folio v1 x4pro")
        assertTrue(r.ok)
        assertEquals(t(R.string.core_firmware_confirm), r.message)
        assertNull(m.transfer.value)

        val refusing = FakeTransport { req ->
            when (req.path) {
                "/api/status" -> FakeTransport.json("{}")
                "/api/inklink/info" -> FakeTransport.json("""{"features":["files"]}""")
                "/api/inklink/firmware/apply" -> FakeTransport.json("""{"ok":false,"error":"signature missing"}""", 422)
                "/api/files" -> FakeTransport.json("[]")
                else -> FakeTransport.json("""{"ok":true}""")
            }
        }
        val m2 = model { refusing }
        m2.connect(DeviceEndpoint("10.0.0.4"))
        val bad = m2.installFirmware(byteArrayOf(1), byteArrayOf(2), "x.bin")
        assertFalse(bad.ok)
        assertEquals(t(R.string.core_firmware_sig_missing), bad.message)
        val uploads = refusing.requests.filter { it.path == "/upload" }.map { com.rustamguloff.foliolink.core.net.MultipartBody.fileName(it.body!!) }
        assertEquals(listOf("x.bin", "x.bin.sig"), uploads)
    }

    @Test
    fun sleepRulesApplyLocally() {
        val sleep = SleepResponse(
            images = listOf(RemoteFile("a.bmp", "/sleep/a.bmp"), RemoteFile("b.bmp", "/sleep/b.bmp")), mode = 8, selected = "a.bmp",
        )
        assertEquals("b.bmp", SleepRules.apply(OutboxOperation.SelectSleepImage("b.bmp"), sleep)?.selected)
        assertEquals("x.bmp", SleepRules.apply(OutboxOperation.SelectSleepImage("x.bmp"), null)?.selected)
        assertEquals(4, SleepRules.apply(OutboxOperation.Settings(mapOf("sleepScreen" to JsonPrimitive(4))), sleep)?.mode)
        assertEquals(sleep, SleepRules.apply(OutboxOperation.Settings(mapOf("fontSize" to JsonPrimitive(4))), sleep))
        val deleted = SleepRules.apply(OutboxOperation.DeleteFile("/sleep/a.bmp"), sleep)!!
        assertEquals("", deleted.selected)
        assertEquals(listOf("b.bmp"), deleted.images.map { it.name })
        val other = SleepRules.apply(OutboxOperation.DeleteFile("/sleep/b.bmp"), sleep)!!
        assertEquals("a.bmp", other.selected)
        assertNull(SleepRules.apply(OutboxOperation.DeleteFile("/sleep/a.bmp"), null))
        assertTrue(SleepRules.modeShowsImages(2) && SleepRules.modeShowsImages(4) && SleepRules.modeShowsImages(10))
        assertFalse(SleepRules.modeShowsImages(8) || SleepRules.modeShowsImages(null))
        assertEquals(SleepChoice.Random, SleepChoice.current(null))
        assertEquals(SleepChoice.Image("a.bmp"), SleepChoice.current(sleep))
        assertEquals("a.bmp", SleepChoice.current(sleep).file(sleep)?.name)
        assertEquals(listOf(OutboxOperation.SelectSleepImage("a.bmp")), SleepChoice.Image("a.bmp").plan(2))
    }

    @Test
    fun localSettingsPersist() {
        val kv = MemoryKeyValueStore()
        val s = LocalSettings(kv, defaultManualAddress = "10.0.2.2:8080")
        assertEquals("10.0.2.2:8080", s.manualAddress)
        assertEquals(21 * 60, s.reminderMinutes)
        s.setReminderTime(7, 30)
        s.dailyGoalMinutes = 45
        s.savedScreenshots = setOf("/a")
        s.sleepSelectUnsupported = mapOf("SN" to "1.0")
        s.ditherMethod = "atkinson"
        s.manualAddress = "192.168.1.5"
        val again = LocalSettings(kv)
        assertEquals(7 * 60 + 30, again.reminderMinutes)
        assertEquals(7, again.value.reminderHour)
        assertEquals(30, again.value.reminderMinute)
        assertEquals(45, again.dailyGoalMinutes)
        assertEquals(setOf("/a"), again.savedScreenshots)
        assertEquals(mapOf("SN" to "1.0"), again.sleepSelectUnsupported)
        assertEquals("atkinson", again.ditherMethod)
        assertEquals("192.168.1.5", again.state.value.manualAddress)
    }

    @Test
    fun errorTexts() {
        val p = FakePlatform()
        assertEquals(p.get(R.string.core_error_server, 500, "Internal Server Error"), errorText(DeviceError.Server(500, ""), p))
        assertEquals(p.get(R.string.core_error_bad_pin), errorText(DeviceError.Pairing(DeviceError.PairingFailure.BadPin(null)), p))
        assertEquals(p.get(R.string.core_error_rate_limited, 30), errorText(DeviceError.Pairing(DeviceError.PairingFailure.RateLimited(30)), p))
        assertEquals("raw", errorText(DeviceError.Other("raw"), p))
        assertEquals(p.get(R.string.core_ble_no_token), errorText(BleError.NoToken, p))
        assertEquals("boom", errorText(IllegalStateException("boom"), p))
    }

    @Test
    fun applyBleSyncUsesTheSameCache() = runTest {
        val m = model()
        m.connectDemo()
        val before = m.sessions.value.size
        m.applyBleSync(com.rustamguloff.foliolink.core.ble.BleSyncResult())
        assertEquals(before, m.sessions.value.size)
        assertFalse(m.isSyncing)
    }

    @Test
    fun libraryChangeOnTheReaderIsReadModifyWrite() = runTest {
        val m = model()
        m.connectDemo()
        // Читалка изменила полки сама (между синхронизациями) — правка накладывается на её свежий документ.
        val client = DeviceClient(DemoDevice.ENDPOINT, transport = DemoTransport(reader))
        val lib = client.library()
        client.saveLibrary(lib.copy(shelves = lib.shelves + com.rustamguloff.foliolink.core.model.Shelf("device", "С читалки")))
        assertEquals(AppModel.SubmitResult.SENT, m.changeLibrary(LibraryChange.SetGoal(20), "goal"))
        val after = client.library()
        assertTrue(after.shelves.any { it.id == "device" })
        assertEquals(20, after.dailyMinutes)
        assertEquals(20, m.settings.dailyGoalMinutes)
    }
}

