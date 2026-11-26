package com.rustamguloff.foliolink.app

import android.content.Context
import com.rustamguloff.foliolink.BuildConfig
import com.rustamguloff.foliolink.R
import com.rustamguloff.foliolink.core.ble.BleError
import com.rustamguloff.foliolink.core.ble.BleSyncHost
import com.rustamguloff.foliolink.core.ble.BleSyncPlan
import com.rustamguloff.foliolink.core.ble.BleSyncResult
import com.rustamguloff.foliolink.core.dictionaries.DictionaryRules
import com.rustamguloff.foliolink.core.dictionaries.StarDictFile
import com.rustamguloff.foliolink.core.model.Book
import com.rustamguloff.foliolink.core.model.BookFiles
import com.rustamguloff.foliolink.core.model.BookStatus
import com.rustamguloff.foliolink.core.model.DeviceEndpoint
import com.rustamguloff.foliolink.core.model.DeviceInfo
import com.rustamguloff.foliolink.core.model.DeviceSetting
import com.rustamguloff.foliolink.core.model.Highlight
import com.rustamguloff.foliolink.core.model.LibraryChange
import com.rustamguloff.foliolink.core.model.LibraryDoc
import com.rustamguloff.foliolink.core.model.OutboxItem
import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.rustamguloff.foliolink.core.model.OutboxOperation.UploadPurpose
import com.rustamguloff.foliolink.core.model.PairStartResponse
import com.rustamguloff.foliolink.core.model.ReadingSession
import com.rustamguloff.foliolink.core.model.RemoteFile
import com.rustamguloff.foliolink.core.model.Shelf
import com.rustamguloff.foliolink.core.model.SleepResponse
import com.rustamguloff.foliolink.core.model.SyncRules
import com.rustamguloff.foliolink.core.model.SyncSnapshot
import com.rustamguloff.foliolink.core.model.VocabWord
import com.rustamguloff.foliolink.core.net.ConnectionPolicy
import com.rustamguloff.foliolink.core.net.DemoDevice
import com.rustamguloff.foliolink.core.net.DeviceClient
import com.rustamguloff.foliolink.core.net.DeviceDiscovery
import com.rustamguloff.foliolink.core.net.DeviceError
import com.rustamguloff.foliolink.core.net.FoundReader
import com.rustamguloff.foliolink.core.net.HttpTransport
import com.rustamguloff.foliolink.core.net.OutboxRunner
import com.rustamguloff.foliolink.core.net.ReaderDiscovery
import com.rustamguloff.foliolink.core.net.TransferProgress
import com.rustamguloff.foliolink.core.net.isDemo
import com.rustamguloff.foliolink.core.security.FirmwareImageInfo
import com.rustamguloff.foliolink.core.security.FirmwareRisk
import com.rustamguloff.foliolink.core.security.FirmwareSignature
import com.rustamguloff.foliolink.core.store.FilePairingTokenStore
import com.rustamguloff.foliolink.core.store.Outbox
import com.rustamguloff.foliolink.core.store.PairingTokenStore
import com.rustamguloff.foliolink.core.store.SettingsProfile
import com.rustamguloff.foliolink.core.store.SettingsProfileStore
import com.rustamguloff.foliolink.core.store.SharedStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * Центр состояния приложения: подключение, кэш, синхронизация, очередь отправки. Экраны читают
 * `StateFlow` через `collectAsStateWithLifecycle()` и зовут действия; долгие действия — `suspend`
 * (из `rememberCoroutineScope()`), для «запустил и забыл» есть [launch].
 *
 * Всё состояние меняется на главном потоке ([scope] — Main.immediate); сеть — на IO внутри
 * транспорта, файлы очереди с нагрузкой — на IO.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppModel(
    private val platform: Platform,
    val store: SharedStore,
    val outboxStore: Outbox,
    val settings: LocalSettings,
    val tokens: PairingTokenStore,
    val profileStore: SettingsProfileStore,
    val discovery: ReaderDiscovery? = null,
    private val transportFor: (DeviceEndpoint) -> HttpTransport = { HttpTransport.forEndpoint(it) },
    private val now: () -> Long = System::currentTimeMillis,
    /** Где читаются файлы кэша при создании и идут операции очереди (тесты — Unconfined). */
    private val ioContext: CoroutineContext = Dispatchers.IO,
    /** Пауза между повторами /api/status при подключении. */
    private val statusRetryDelayMs: Long = 400,
) : BleSyncHost {

    sealed interface Connection {
        data object Disconnected : Connection
        data object Connecting : Connection
        data object Connected : Connection
        data class Failed(val message: String) : Connection
    }

    /** Итог изменения, отправленного через очередь. */
    enum class SubmitResult {
        /** Применено на читалке. */
        SENT,
        /** Сохранено в очереди; уйдёт при следующем подключении. */
        QUEUED,
        /** Читалка отклонила; элемент остался в очереди с ошибкой. */
        FAILED,
    }

    /** Идущая загрузка/скачивание с настоящим байтовым прогрессом. */
    data class Transfer(
        val title: String,
        val done: Long = 0,
        val total: Long = -1,
        /** «Файл i из n», когда их несколько подряд. */
        val index: Int = 1,
        val count: Int = 1,
    ) {
        val fraction: Float? get() = if (total > 0) minOf(done.toFloat() / total, 1f) else null
    }

    enum class PairingState {
        /** Прошивка без сопряжения: изменения не требуют токена. */
        NOT_REQUIRED,
        PAIRED,
        UNPAIRED,
    }

    data class Banner(val kind: Kind, val text: String, val id: String = UUID.randomUUID().toString()) {
        enum class Kind { INFO, SUCCESS, ERROR }
    }

    data class FirmwareOutcome(val ok: Boolean, val message: String)

    /** Окно PIN (контракт v2 §1). Живёт в модели: поворот экрана не просит новый PIN. */
    data class PairingUi(
        val digits: Int = 6,
        /** Когда истекает текущий PIN, epoch-мс; null — PIN не выдан. */
        val expiresAt: Long? = null,
        /** 429: когда можно просить новый PIN. */
        val retryAt: Long? = null,
        val starting: Boolean = false,
        val submitting: Boolean = false,
        val error: Throwable? = null,
        /** Растёт после каждой неудачной попытки: окно очищает поле и возвращает фокус. */
        val failures: Int = 0,
    )

    data class ScreenshotSaveResult(val saved: Int, val failure: Throwable?)

    /** Итог автосохранения для баннера синхронизации; `disable` — выключить автосохранение (нет доступа). */
    data class AutoSaveOutcome(val text: String, val isError: Boolean, val disable: Boolean)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Последовательная запись кэша на диск (порядок сохраняется). */
    private val io = Dispatchers.IO.limitedParallelism(1)

    // region Состояние

    private val _connection = MutableStateFlow<Connection>(Connection.Disconnected)
    // Кэш с диска читается на IO после создания модели ([loaded]); до того — пустые значения.
    private val _snapshot = MutableStateFlow(SyncSnapshot())
    private val _sessions = MutableStateFlow<List<ReadingSession>>(emptyList())
    private val _outbox = MutableStateFlow<List<OutboxItem>>(emptyList())
    private val _outboxUnreadable = MutableStateFlow(0)
    private val _loaded = MutableStateFlow(false)
    private val _pairingUi = MutableStateFlow(PairingUi())
    private val _firmwareInstalling = MutableStateFlow(false)
    private val _firmwareResult = MutableStateFlow<FirmwareOutcome?>(null)
    private val _syncing = MutableStateFlow(false)
    private val _syncStep = MutableStateFlow<String?>(null)
    private val _draining = MutableStateFlow(false)
    private val _transfer = MutableStateFlow<Transfer?>(null)
    private val _hasToken = MutableStateFlow(false)
    private val _pairingPrompt = MutableStateFlow(false)
    private val _banner = MutableStateFlow<Banner?>(null)
    private val _profiles = MutableStateFlow<List<SettingsProfile>>(emptyList())

    val connection: StateFlow<Connection> = _connection.asStateFlow()
    val snapshot: StateFlow<SyncSnapshot> = _snapshot.asStateFlow()
    val sessions: StateFlow<List<ReadingSession>> = _sessions.asStateFlow()
    val outbox: StateFlow<List<OutboxItem>> = _outbox.asStateFlow()
    /** Идёт синхронизация по Wi-Fi (`isSyncing` занят контрактом [BleSyncHost]). */
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()
    /** Текущий шаг синхронизации или отправки, для подписи под индикатором. */
    val syncStep: StateFlow<String?> = _syncStep.asStateFlow()
    /** Идёт отправка очереди. */
    val draining: StateFlow<Boolean> = _draining.asStateFlow()
    val transfer: StateFlow<Transfer?> = _transfer.asStateFlow()
    /** Для подключённой читалки сохранён токен. */
    val hasToken: StateFlow<Boolean> = _hasToken.asStateFlow()
    /** Показать окно ввода PIN. */
    val pairingPrompt: StateFlow<Boolean> = _pairingPrompt.asStateFlow()
    val banner: StateFlow<Banner?> = _banner.asStateFlow()
    val profiles: StateFlow<List<SettingsProfile>> = _profiles.asStateFlow()
    /** Кэш, сессии, очередь и профили прочитаны с диска. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    /** Сколько изменений очереди не прочиталось (повреждённый индекс, FBK-034). */
    val outboxUnreadable: StateFlow<Int> = _outboxUnreadable.asStateFlow()
    val pairingUi: StateFlow<PairingUi> = _pairingUi.asStateFlow()
    /** Идёт установка прошивки (в области модели: поворот экрана её не обрывает). */
    val firmwareInstalling: StateFlow<Boolean> = _firmwareInstalling.asStateFlow()
    val firmwareResult: StateFlow<FirmwareOutcome?> = _firmwareResult.asStateFlow()
    val pairingState: StateFlow<PairingState> = combine(_snapshot, _hasToken, ::pairingStateOf)
        .stateIn(scope, SharingStarted.Eagerly, pairingStateOf(_snapshot.value, _hasToken.value))
    /** Цель на день: из library.json читалки, иначе локальная. */
    val goalMinutes: StateFlow<Int> = combine(_snapshot, settings.state) { s, p -> s.library?.dailyMinutes ?: p.dailyGoalMinutes }
        .stateIn(scope, SharingStarted.Eagerly, _snapshot.value.library?.dailyMinutes ?: settings.dailyGoalMinutes)
    /** Читалки, найденные в сети ([startDiscovery]). */
    val discovered: StateFlow<List<FoundReader>> = discovery?.found ?: MutableStateFlow(emptyList())
    val isSearching: StateFlow<Boolean> = discovery?.isSearching ?: MutableStateFlow(false)

    /** Клиент подключённой читалки (null — нет связи). */
    var client: DeviceClient? = null
        private set

    private var snap: SyncSnapshot
        get() = _snapshot.value
        set(v) { _snapshot.value = v }

    private var prefetchJob: Job? = null

    /**
     * Подключение не подтвердило, что хост — читалка из кэша (нет серийника): изменения,
     * привязанные к её ключу, не отправляются (SEC-033).
     */
    private var queueOwned = true

    /** Ключ для отбора очереди: при неподтверждённой читалке — ничей. */
    private val queueReader: String? get() = if (queueOwned) snap.deviceKey else UNCONFIRMED_READER

    private val loadJob: Job = scope.launch {
        val data = withContext(ioContext) {
            LoadedData(store.loadSnapshot(), store.loadSessions(), outboxStore.items(), outboxStore.unreadableCount(), profileStore.load())
        }
        // Изменения, сделанные до конца загрузки (их почти не бывает), не затираются прочитанным.
        if (_snapshot.value == SyncSnapshot()) _snapshot.value = data.snapshot
        if (_sessions.value.isEmpty()) _sessions.value = data.sessions
        _outbox.value = data.outbox
        _outboxUnreadable.value = data.unreadable
        _profiles.value = data.profiles
        _loaded.value = true
    }

    private class LoadedData(
        val snapshot: SyncSnapshot,
        val sessions: List<ReadingSession>,
        val outbox: List<OutboxItem>,
        val unreadable: Int,
        val profiles: List<SettingsProfile>,
    )

    /** Ждёт чтения кэша с диска (подключение и очередь работают только после него). */
    suspend fun awaitLoaded() = loadJob.join()

    /** Пока кэш не прочитан, BLE ждёт так же, как при синхронизации по Wi-Fi. */
    override val isSyncing: Boolean get() = _syncing.value || !_loaded.value
    val isDraining: Boolean get() = _draining.value
    val isConnected: Boolean get() = _connection.value == Connection.Connected
    val endpoint: DeviceEndpoint? get() = client?.endpoint ?: snap.device
    val books: List<Book> get() = snap.books
    fun book(path: String): Book? = snap.books.firstOrNull { it.path == path }

    /** Элементы, отклонённые читалкой (красные в очереди). */
    val failedOutboxCount: Int get() = _outbox.value.count { it.failed }

    val todaySeconds: Int get() = Integration.todaySeconds(snap, _sessions.value, now())
    /** (текущая, лучшая) серия. */
    val streaks: Pair<Int, Int> get() = Integration.streaks(snap, _sessions.value, now())
    val currentlyReading: List<Book> get() = Integration.currentlyReading(snap.books)

    private fun pairingStateOf(s: SyncSnapshot, token: Boolean): PairingState {
        val p = s.info?.pairing
        if (p == null || !p.required) return PairingState.NOT_REQUIRED
        return if (token) PairingState.PAIRED else PairingState.UNPAIRED
    }

    // endregion

    // region Баннеры и тексты

    private fun text(id: Int, vararg args: Any): String = platform.get(id, *args)

    fun errorText(e: Throwable): String = com.rustamguloff.foliolink.app.errorText(e, platform)

    fun show(text: String, kind: Banner.Kind = Banner.Kind.INFO) {
        _banner.value = Banner(kind, text)
    }

    fun show(error: Throwable) {
        if (error is DeviceError.PairingRequired) requestPairing()
        show(errorText(error), Banner.Kind.ERROR)
    }

    fun dismissBanner() { _banner.value = null }

    /** Запустить действие из UI без ожидания. */
    fun launch(block: suspend AppModel.() -> Unit): Job = scope.launch { block() }

    // endregion

    // region Подключение

    fun startDiscovery() = discovery?.start()
    fun stopDiscovery() = discovery?.stop()

    /** Подключение по введённому адресу ("host", "host:port", "http://…"). */
    suspend fun connect(address: String): Boolean {
        val ep = DeviceEndpoint.parse(address) ?: run { show(DeviceError.InvalidAddress); return false }
        return connect(ep)
    }

    /** Демо-режим: виртуальная читалка в памяти. */
    suspend fun connectDemo(): Boolean = connect(DemoDevice.ENDPOINT)

    /**
     * Подключение: /api/inklink/info (нет — /api/status), установка часов, затем [sync].
     * `silent` (автоподключение при запуске): недоступная читалка — нормальное состояние, без баннера.
     */
    suspend fun connect(endpoint: DeviceEndpoint, silent: Boolean = false): Boolean {
        awaitLoaded()
        _connection.value = Connection.Connecting
        try {
            val client = DeviceClient(endpoint, transport = transportFor(endpoint))
            // Ключ, токен и сброс кэша решает ConnectionPolicy: токен — только подтверждённому серийнику.
            val probe = ConnectionPolicy.probe(retryDelayMs = statusRetryDelayMs) { client.status() }
            val status = probe.status
            // Серийника нет — личность по deviceId из info, запрошенного БЕЗ токена.
            var infoUnsupported = false
            val plainInfo = if (probe is ConnectionPolicy.Probe.Confirmed) null else try {
                client.info()
            } catch (e: DeviceError.NotSupported) {
                infoUnsupported = true
                null
            }
            val decision = ConnectionPolicy.decide(probe, plainInfo?.deviceId, endpoint, snap.device, snap.deviceKey) {
                tokens.token(it)
            }
            client.setToken(decision.token)
            val info = when {
                plainInfo != null && decision.token == null -> plainInfo
                infoUnsupported -> null
                else -> try {
                    client.info()
                } catch (e: DeviceError.NotSupported) {
                    null
                }
            } ?: run {
                // Upstream-прошивка без маршрутов FolioLink: файлы и настройки всё равно работают.
                status ?: throw DeviceError.NotSupported
                DeviceInfo(firmware = status.version.orEmpty(), board = status.device ?: "Folio", features = listOf("files"))
            }
            decision.migrateFrom?.let { old -> migrateReaderKey(old, decision.key, decision.token) }
            if (decision.resetCache) {
                // Другая читалка (подтверждено серийником): её история не должна смешаться с кэшем.
                resetCachedData()
            }
            snap = snap.copy(deviceKey = decision.key)
            queueOwned = decision.ownsQueue
            if (ConnectionPolicy.shouldForgetToken(decision, info)) {
                // Сопряжение сброшено на читалке («Сбросить сопряжение») или телефон вытеснен.
                tokens.remove(decision.key)
                client.setToken(null)
            }
            _hasToken.value = client.hasToken
            var clockWarning = false
            var needsPairing = false
            try {
                client.setTime(now())
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceError.Server) {
                if (e.status != 501) clockWarning = info.clock.available // 501: на плате нет RTC
            } catch (e: DeviceError.PairingRequired) {
                needsPairing = true
            } catch (e: Exception) {
                clockWarning = info.clock.available
            }
            this.client = client
            snap = snap.copy(device = endpoint, info = info, status = status ?: snap.status)
            saveSnapshotNow()
            if (!endpoint.isDemo) settings.manualAddress = endpoint.id
            _connection.value = Connection.Connected
            if (needsPairing && !silent) requestPairing()
            // Читалка требует сопряжения, но не называет ни серийник, ни deviceId: токен ей не отправить.
            val unidentified = !decision.confirmed && info.pairing?.required == true
            sync()
            if (unidentified && !silent) show(text(R.string.core_reader_unidentified), Banner.Kind.INFO)
            if (clockWarning) show(text(R.string.core_clock_warning), Banner.Kind.ERROR)
            return true
        } catch (e: CancellationException) {
            _connection.value = Connection.Disconnected
            throw e
        } catch (e: Exception) {
            if (silent) {
                _connection.value = Connection.Disconnected
            } else {
                _connection.value = Connection.Failed(errorText(e))
                show(e)
            }
            return false
        }
    }

    /**
     * Та же читалка, запомненная по адресу, опознана по deviceId: токен, ключ снимка, очередь и
     * отметки переносятся на новый ключ без сброса кэша.
     */
    private suspend fun migrateReaderKey(from: String, to: String, token: String?) {
        withContext(ioContext) {
            if (token != null && tokens.token(to) == null && tokens.save(token, to)) tokens.remove(from)
            outboxStore.rekey(from, to)
        }
        settings.sleepSelectUnsupported[from]?.let { settings.sleepSelectUnsupported = settings.sleepSelectUnsupported - from + (to to it) }
        if (from in settings.bleMutualAuth) settings.bleMutualAuth = settings.bleMutualAuth - from + to
        reloadOutbox()
    }

    private fun resetCachedData() {
        snap = SyncSnapshot(device = snap.device)
        _sessions.value = emptyList()
        saveSessions()
    }

    fun disconnect() {
        prefetchJob?.cancel()
        prefetchJob = null
        client = null
        _connection.value = Connection.Disconnected
    }

    /** Тихо переподключается к последней читалке (запуск приложения). */
    suspend fun reconnectIfPossible() {
        val ep = snap.device ?: return
        if (client != null || _connection.value == Connection.Connecting) return
        connect(ep, silent = true)
    }

    /** Приложение вышло на передний план (как scenePhase .active в iOS). */
    fun onAppForeground() {
        refreshOutbox()
        Integration.bleAppBecameActive()
        scope.launch {
            if (isConnected && _outbox.value.isNotEmpty()) drainOutbox()
            rescheduleReminders()
        }
    }

    // endregion

    // region Сопряжение (контракт v2 §1)

    fun requestPairing() {
        if (client == null) return
        _pairingPrompt.value = true
    }

    fun dismissPairingPrompt() { _pairingPrompt.value = false }

    /** Просит читалку показать PIN. Ошибки — [DeviceError] (429 → RateLimited). */
    suspend fun startPairing(): PairStartResponse {
        val client = client ?: throw DeviceError.Unreachable
        return client.pairStart(platform.phoneName)
    }

    /**
     * Окно PIN открылось: PIN запрашивается, только если действующего нет (поворот экрана и
     * повторное открытие не выдают новый PIN и не делают старый недействительным).
     */
    fun ensurePin() {
        val ui = _pairingUi.value
        if (ui.starting || ui.submitting) return
        val t = now()
        if (ui.expiresAt != null && ui.expiresAt > t) return
        if (ui.retryAt != null && ui.retryAt > t) return
        requestPin()
    }

    /** «Новый PIN» (и первый запрос). Идёт в области модели. */
    fun requestPin() {
        if (_pairingUi.value.starting) return
        _pairingUi.update { it.copy(starting = true, error = null) }
        scope.launch {
            try {
                val r = startPairing()
                _pairingUi.update {
                    it.copy(
                        starting = false, digits = r.digits.coerceIn(4, 8),
                        expiresAt = now() + r.expiresIn * 1000L, retryAt = null,
                    )
                }
            } catch (e: CancellationException) {
                _pairingUi.update { it.copy(starting = false) }
                throw e
            } catch (e: Exception) {
                _pairingUi.update { pinFailure(it.copy(starting = false), e) }
            }
        }
    }

    /** Отправляет PIN; итог — в [pairingUi] и [pairingPrompt]. Идёт в области модели. */
    fun submitPin(pin: String) {
        if (_pairingUi.value.submitting) return
        _pairingUi.update { it.copy(submitting = true, error = null) }
        scope.launch {
            try {
                finishPairing(pin)
                _pairingUi.value = PairingUi()
            } catch (e: CancellationException) {
                _pairingUi.update { it.copy(submitting = false) }
                throw e
            } catch (e: Exception) {
                _pairingUi.update { pinFailure(it.copy(submitting = false), e) }
            }
        }
    }

    private fun pinFailure(ui: PairingUi, e: Exception): PairingUi {
        val f = (e as? DeviceError.Pairing)?.failure
        return when (f) {
            is DeviceError.PairingFailure.RateLimited ->
                ui.copy(error = e, expiresAt = null, retryAt = now() + f.retryIn * 1000L, failures = ui.failures + 1)
            DeviceError.PairingFailure.Expired, DeviceError.PairingFailure.NoActivePin ->
                ui.copy(error = e, expiresAt = null, failures = ui.failures + 1)
            else -> ui.copy(error = e, failures = ui.failures + 1)
        }
    }

    /**
     * Меняет PIN на токен, сохраняет его и отправляет то, что ждало сопряжения. Токен не
     * сохранился — [PairingNotSaved]: «сопряжено» не показывается.
     */
    suspend fun finishPairing(pin: String) {
        val client = client ?: throw DeviceError.Unreachable
        val token = client.pairFinish(pin, platform.phoneName)
        val key = snap.deviceKey ?: client.endpoint.id
        val saved = withContext(ioContext) { tokens.save(token, key) }
        if (!saved) {
            _hasToken.value = false
            throw PairingNotSaved()
        }
        client.setToken(token)
        _hasToken.value = true
        snap.info?.let { info ->
            val pairing = (info.pairing ?: DeviceInfo.Pairing()).copy(paired = true, authorized = true)
            snap = snap.copy(info = info.copy(pairing = pairing))
        }
        saveSnapshotNow()
        _pairingPrompt.value = false
        show(text(R.string.core_paired), Banner.Kind.SUCCESS)
        attempt { client.setTime(now()) }
        drainOutbox()
    }

    /** Читалка выдала токен, а телефон не смог его сохранить (FBK-036). */
    class PairingNotSaved : Exception("pairing token not saved")

    /** Удаляет токен этого телефона для читалки и забывает её (на читалке запись живёт до сброса). */
    fun forgetReader() {
        snap.deviceKey?.let { tokens.remove(it) }
        snap.device?.id?.let { tokens.remove(it) }
        _hasToken.value = false
        disconnect()
        snap = snap.copy(device = null)
        saveSnapshotNow()
        // Bluetooth тоже не должен ждать забытую читалку.
        Integration.bleForgetReader()
        show(text(R.string.core_reader_forgotten), Banner.Kind.INFO)
    }

    // endregion

    // region Синхронизация

    suspend fun sync() {
        val client = client ?: return
        if (_syncing.value) return
        _syncing.value = true
        try {
            runSync(client)
        } finally {
            _syncing.value = false
            _syncStep.value = null
        }
    }

    private suspend fun runSync(client: DeviceClient) {
        val failures = mutableListOf<String>()
        val features = snap.info?.features.orEmpty()
        fun has(f: String) = features.isEmpty() || f in features

        suspend fun <T> step(name: String, op: suspend () -> T): T? {
            _syncStep.value = name
            return try {
                op()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceError.NotSupported) {
                null
            } catch (e: Exception) {
                failures += "$name: ${errorText(e)}"
                if (e is DeviceError.Unreachable) _connection.value = Connection.Failed(errorText(e))
                null
            }
        }

        step(text(R.string.core_step_device)) { client.info() }?.let { snap = snap.copy(info = it) }
        step(text(R.string.core_step_status)) { client.status() }?.let { snap = snap.copy(status = it) }
        if (_connection.value != Connection.Connected) {
            show(text(R.string.core_connection_lost), Banner.Kind.ERROR)
            return
        }
        // Сначала очередь: всё скачанное ниже уже включает её правки.
        val queued = drainOutbox(announce = false, reportErrors = false)
        queued?.stoppedBy?.takeIf { it !is CancellationException }?.let {
            failures += text(R.string.core_sync_queue_error, errorText(it))
        }
        queued?.failed?.takeIf { it.isNotEmpty() }?.let { failures += text(R.string.core_sync_queue_rejected, it.size) }

        if (has("stats")) {
            val since = SyncRules.sessionCursor(_sessions.value, snap.lastSessionStart, snap.lastFullSessionSync, now())
            step(text(R.string.core_step_sessions)) { client.sessions(since) }?.let { new ->
                val merged = SyncRules.mergeSessions(_sessions.value, new)
                _sessions.value = merged
                // Часы, однажды ушедшие вперёд, не должны заморозить курсор в будущем.
                snap = snap.copy(
                    lastSessionStart = minOf(merged.maxOfOrNull { it.start } ?: 0, now() / 1000),
                    lastFullSessionSync = if (since == 0L) now() else snap.lastFullSessionSync,
                )
                saveSessions()
            }
            step(text(R.string.core_step_stats)) { client.stats() }?.let {
                snap = snap.copy(stats = it, statsFetchedAt = now())
            }
        }
        if (has("library")) {
            step(text(R.string.core_step_books)) { client.books() }?.let { snap = snap.copy(books = it) }
            step(text(R.string.core_step_shelves)) { client.library() }?.let { lib ->
                snap = snap.copy(library = lib)
                lib.dailyMinutes?.let { settings.dailyGoalMinutes = it }
            }
        }
        if (has("highlights")) {
            step(text(R.string.core_step_highlights)) { client.highlights() }?.let {
                snap = snap.copy(highlights = it, lastFullHighlightSync = now())
            }
        }
        if (has("vocab")) step(text(R.string.core_step_vocab)) { client.vocab() }?.let { snap = snap.copy(vocab = it) }
        if (has("screenshots")) {
            step(text(R.string.core_step_screenshots)) { client.screenshots() }?.let { snap = snap.copy(screenshots = it) }
        }
        if (has("sleep")) {
            step(text(R.string.core_step_sleep)) { client.sleepImages() }?.let { s ->
                snap = snap.copy(sleep = s)
                if (s.selected.isNotEmpty()) noteSleepSelection(supported = true)
            }
        }
        // Правки, ещё ждущие в очереди, остаются видны поверх свежих данных.
        reapplyPendingChanges()

        snap = snap.copy(lastSync = now())
        persist()
        prefetchScreenshots()
        val photos = autoSaveNewScreenshots()
        if (photos != null && photos.isError) failures += photos.text
        rescheduleReminders()

        val sent = queued?.sent?.size ?: 0
        if (failures.isEmpty()) {
            var t = if (sent > 0) text(R.string.core_synced_sent, sent) else text(R.string.core_synced)
            if (photos != null) t += "\n" + photos.text
            show(t, Banner.Kind.SUCCESS)
        } else {
            show(
                text(R.string.core_synced_with_errors, failures.size) + "\n" + failures.take(2).joinToString("\n"),
                Banner.Kind.ERROR,
            )
        }
    }

    /** Токен и курсоры для синхронизации по BLE: тот же токен и те же правила `since`, что у Wi-Fi. */
    override fun blePlan(): BleSyncPlan {
        val keys = listOfNotNull(snap.deviceKey, snap.device?.id)
        val token = keys.firstNotNullOfOrNull { tokens.token(it) } ?: throw BleError.NoToken
        return BleSyncPlan(
            token = token,
            requireServerProof = keys.any { it in settings.bleMutualAuth },
            expectedSerial = snap.deviceKey?.takeIf { it != snap.device?.id },
            sessionsSince = SyncRules.sessionCursor(_sessions.value, snap.lastSessionStart, snap.lastFullSessionSync, now()),
            highlightsSince = Integration.highlightsCursor(snap.highlights, snap.lastFullHighlightSync, now()),
            nowMs = now(),
        )
    }

    /** Результат BLE — в тот же кэш, виджеты и напоминания, что и Wi-Fi. Вызывать на главном потоке. */
    override fun applyBleSync(result: BleSyncResult) {
        // Читалка однажды доказала себя: впредь ответ без sproof — отказ (SEC-034).
        if (result.serverVerified) {
            snap.deviceKey?.let { if (it !in settings.bleMutualAuth) settings.bleMutualAuth = settings.bleMutualAuth + it }
        }
        // Другая читалка по BLE (серийник в info не тот) — данные не применяются.
        if (!ConnectionPolicy.sameReader(snap.deviceKey?.takeIf { it != snap.device?.id }, result.info?.serial, result.info?.deviceId)) return
        val (s, sessions) = Integration.bleMerge(result, snap, _sessions.value)
        snap = s
        if (result.sessions != null) {
            _sessions.value = sessions
            saveSessions()
        }
        reapplyPendingChanges()
        persist()
        rescheduleReminders()
    }

    /** Сохраняет снимок и сводку виджетов, обновляет виджеты. */
    fun persist() {
        val s = snap
        val summary = Integration.widgetSummary(s, _sessions.value, currentGoal(), now())
        scope.launch(io) {
            store.saveSnapshot(s)
            store.saveWidgetSummary(summary)
            // Виджеты перечитывают widget.json — только после записи.
            platform.reloadWidgets()
        }
    }

    private fun saveSnapshotNow() {
        val s = snap
        scope.launch(io) { store.saveSnapshot(s) }
    }

    private fun saveSessions() {
        val s = _sessions.value
        scope.launch(io) { store.saveSessions(s) }
    }

    /** Ждёт, пока кэш дойдёт до диска (тесты, выход). */
    suspend fun awaitWrites() = withContext(io) {}

    private fun currentGoal(): Int = snap.library?.dailyMinutes ?: settings.dailyGoalMinutes

    fun rescheduleReminders() {
        val today = todaySeconds / 60
        val goal = currentGoal()
        val p = settings.value
        platform.rescheduleReminders(p.reminderEnabled, p.reminderMinutes, today >= goal, today, goal)
    }

    /** Миниатюры новых скриншотов; ESP32 обслуживает один запрос за раз, поэтому ограничено и отменяется. */
    private fun prefetchScreenshots() {
        val client = client ?: return
        val items = snap.screenshots.takeLast(12)
        prefetchJob?.cancel()
        prefetchJob = scope.launch { Integration.prefetchScreenshots(client, items) }
    }

    // endregion

    // region Скриншоты → галерея

    /** Скриншоты, которые автосохранение ещё не обработало. */
    val unsavedScreenshots: List<RemoteFile>
        get() = snap.screenshots.filter { it.path !in settings.savedScreenshots }

    /** Включение автосохранения касается только снимков, сделанных после него. */
    fun setAutoSaveScreenshots(on: Boolean) {
        if (on && !settings.autoSaveScreenshots) {
            settings.savedScreenshots = settings.savedScreenshots + snap.screenshots.map { it.path }
        }
        settings.autoSaveScreenshots = on
    }

    private suspend fun autoSaveNewScreenshots(): AutoSaveOutcome? {
        if (!settings.autoSaveScreenshots || unsavedScreenshots.isEmpty()) return null
        val result = saveScreenshots(unsavedScreenshots, announce = false, reportErrors = false)
        val outcome = autoSaveOutcome(result.saved, result.failure, platform)
        if (outcome?.disable == true) settings.autoSaveScreenshots = false
        return outcome
    }

    /** Скачивает скриншоты и сохраняет их в галерею (`Pictures/FolioLink`). */
    suspend fun saveScreenshots(
        files: List<RemoteFile>,
        announce: Boolean = true,
        reportErrors: Boolean = true,
    ): ScreenshotSaveResult {
        var saved = 0
        try {
            files.forEachIndexed { i, file ->
                val progress = beginTransfer(file.name, file.size, i + 1, files.size)
                try {
                    val client = client ?: throw DeviceError.Unreachable
                    val data = client.download(file.fullPath, progress)
                    platform.saveToGallery(data, file.name)
                    settings.savedScreenshots = settings.savedScreenshots + file.path
                    saved++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (reportErrors) show(e)
                    return ScreenshotSaveResult(saved, e)
                }
            }
        } finally {
            _transfer.value = null
        }
        if (announce) show(text(R.string.core_saved_to_gallery, saved), Banner.Kind.SUCCESS)
        return ScreenshotSaveResult(saved, null)
    }

    // endregion

    // region Очередь отправки: каждое изменение на читалке идёт через постоянную очередь

    /** Перечитывает очередь (на IO; из UI — «запустил и забыл»). */
    fun refreshOutbox() {
        scope.launch { reloadOutbox() }
    }

    private suspend fun reloadOutbox() {
        val (items, lost) = withContext(ioContext) { outboxStore.items() to outboxStore.unreadableCount() }
        _outbox.value = items
        _outboxUnreadable.value = lost
    }

    /** «Удалить повреждённую копию» на экране очереди. */
    fun discardUnreadableOutbox() {
        scope.launch {
            withContext(ioContext) { outboxStore.discardUnreadableBackups() }
            reloadOutbox()
        }
    }

    /**
     * Книга из потока (`content://`) прямо в файл очереди — без копии в памяти (BUG-038).
     * Больше [Outbox.MAX_FILE_BYTES] — ошибка с понятным текстом.
     */
    suspend fun enqueueBookStream(open: () -> java.io.InputStream?, fileName: String, title: String, source: String): OutboxItem =
        withContext(ioContext) {
            val input = open() ?: throw java.io.IOException("cannot open $fileName")
            input.use { outboxStore.enqueueBookStream(it, fileName, title, source) }
        }.also { reloadOutbox() }

    /** «Поделиться» / статья: книга для /Books. */
    suspend fun enqueueBook(data: ByteArray, fileName: String, title: String, source: String) {
        try {
            withContext(ioContext) { outboxStore.enqueueBook(data, fileName, title, source) }
            reloadOutbox()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            show(e)
        }
    }

    fun removeFromOutbox(item: OutboxItem) {
        scope.launch {
            withContext(ioContext) { outboxStore.remove(item) }
            reloadOutbox()
        }
    }

    /**
     * Ставит изменение в очередь, сразу применяет его к кэшу и, если читалка подключена,
     * отправляет очередь (по порядку) до возврата.
     */
    suspend fun submit(
        operation: OutboxOperation,
        title: String,
        detail: String = "",
        payload: ByteArray? = null,
        fileName: String = "",
    ): SubmitResult {
        awaitLoaded()
        val item = try {
            // Книга может уйти на любую читалку; остальное — только на ту, для которой сделано.
            val owner = if (operation == OutboxOperation.UploadBook) null else snap.deviceKey
            withContext(ioContext) {
                outboxStore.enqueue(operation, title, detail, payload, fileName, owner)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            show(e)
            return SubmitResult.FAILED
        }
        applyLocally(operation)
        persist()
        return afterEnqueue(item)
    }

    /** Элемент уже в очереди: офлайн — «сохранено в очереди», иначе отправка по порядку. */
    private suspend fun afterEnqueue(item: OutboxItem): SubmitResult {
        reloadOutbox()
        if (!isConnected) {
            show(text(R.string.core_saved_in_queue), Banner.Kind.INFO)
            return SubmitResult.QUEUED
        }
        while (_draining.value || _syncing.value) delay(100)
        if (_outbox.value.any { it.id == item.id }) drainOutbox(announce = false)
        val left = _outbox.value.firstOrNull { it.id == item.id } ?: return SubmitResult.SENT
        return if (left.failed) SubmitResult.FAILED else SubmitResult.QUEUED
    }

    /**
     * Отправляет очередь по порядку. `includeFailed` — повторить и отклонённые; `only` — только эти id.
     */
    suspend fun drainOutbox(
        includeFailed: Boolean = false,
        only: Set<String>? = null,
        announce: Boolean = true,
        reportErrors: Boolean = true,
    ): OutboxRunner.Report? {
        awaitLoaded()
        reloadOutbox()
        val client = client ?: return null
        if (_outbox.value.isEmpty() || _draining.value) return null
        _draining.value = true
        try {
            val reader = queueReader
            val total = _outbox.value.count {
                it.operation.isSupported && (includeFailed || !it.failed) && (only == null || it.id in only) && it.belongsTo(reader)
            }
            var index = 0
            val touched = mutableSetOf<UploadPurpose>()
            var libraryChanged = false
            val report = OutboxRunner.drain(outboxStore, includeFailed, only, reader, { errorText(it) }, ioContext) { item ->
                index++
                _syncStep.value = text(R.string.core_sending, item.title)
                renamedTo = execute(item, client, index, maxOf(total, index))
                when (val op = item.operation) {
                    OutboxOperation.UploadBook -> touched += UploadPurpose.BOOK
                    is OutboxOperation.UploadFile -> touched += op.purpose
                    is OutboxOperation.Library -> libraryChanged = true
                    is OutboxOperation.SelectSleepImage -> touched += UploadPurpose.SLEEP
                    is OutboxOperation.SelectDictionary, is OutboxOperation.DeleteDictionary -> touched += UploadPurpose.DICTIONARY
                    // Удалён файл книги: список книг на читалке изменился.
                    is OutboxOperation.DeleteFile -> if (op.path.startsWith("/books/", ignoreCase = true)) touched += UploadPurpose.BOOK
                    else -> {}
                }
            }
            reloadOutbox()
            when (report.stoppedBy) {
                DeviceError.PairingRequired -> {
                    // Сохранённый токен отклонён (сброс на читалке или телефон вытеснен): показать как несопряжённую.
                    _hasToken.value = false
                    requestPairing()
                }
                DeviceError.Unreachable -> _connection.value = Connection.Failed(errorText(DeviceError.Unreachable))
                else -> {}
            }
            if (UploadPurpose.BOOK in touched || libraryChanged) attempt { client.books() }?.let { snap = snap.copy(books = it) }
            if (UploadPurpose.SLEEP in touched) attempt { client.sleepImages() }?.let { snap = snap.copy(sleep = it) }
            if (UploadPurpose.DICTIONARY in touched) attempt { client.dictionaries() }?.let { snap = snap.copy(dictionaries = it) }
            reapplyPendingChanges()
            persist()
            if (announce || (reportErrors && (report.failed.isNotEmpty() || report.stoppedBy != null))) announceDrain(report)
            return report
        } finally {
            _draining.value = false
            _transfer.value = null
            if (!_syncing.value) _syncStep.value = null
        }
    }

    private fun announceDrain(report: OutboxRunner.Report) {
        val parts = mutableListOf<String>()
        val books = report.sent.count { it.operation == OutboxOperation.UploadBook }
        val other = report.sent.size - books
        if (books > 0) parts += text(R.string.core_books_sent, books)
        if (other > 0) parts += text(R.string.core_changes_sent, other)
        if (report.duplicates.isNotEmpty()) parts += text(R.string.core_already_on_reader, report.duplicates.size)
        report.renamed.forEach { (item, name) -> parts += text(R.string.core_book_renamed, item.fileName, name) }
        report.failed.firstOrNull()?.let { parts += text(R.string.core_rejected_item, it.title, it.lastError.orEmpty()) }
        report.stoppedBy?.takeIf { it !is CancellationException }?.let { parts += text(R.string.core_rest_waits, errorText(it)) }
        if (parts.isEmpty()) return
        val ok = report.failed.isEmpty() && report.stoppedBy == null
        show(parts.joinToString("\n"), if (ok) Banner.Kind.SUCCESS else Banner.Kind.ERROR)
    }

    /** Показывает передачу и возвращает обработчик байтового прогресса (можно звать с любого потока). */
    fun beginTransfer(title: String, total: Long = -1, index: Int = 1, count: Int = 1): TransferProgress {
        _transfer.value = Transfer(title, total = total, index = index, count = count)
        return { done, t ->
            _transfer.update { cur -> cur?.copy(done = done, total = if (t > 0) t else cur.total) }
        }
    }

    fun endTransfer() { _transfer.value = null }

    /** Элемент сделан для другой читалки: ждёт её. */
    fun isForOtherReader(item: OutboxItem): Boolean = !item.belongsTo(queueReader)

    /** Снимает ошибку и отправляет только этот элемент (null — всю очередь, с отклонёнными). */
    suspend fun retryOutbox(item: OutboxItem? = null) {
        if (!isConnected) {
            show(text(R.string.core_connect_to_send), Banner.Kind.INFO)
            return
        }
        if (item != null && !item.operation.isSupported) {
            show(text(R.string.core_item_needs_newer_app), Banner.Kind.INFO)
            return
        }
        if (item != null && isForOtherReader(item)) {
            show(text(R.string.core_item_for_other_reader), Banner.Kind.INFO)
            return
        }
        drainOutbox(includeFailed = true, only = item?.let { setOf(it.id) })
    }

    /**
     * Исполняет один элемент очереди на читалке. Возвращает имя, под которым легла книга, если
     * оно не своё (имя на читалке занято другим файлом).
     */
    private suspend fun execute(item: OutboxItem, client: DeviceClient, index: Int, count: Int): String? {
        when (val op = item.operation) {
            OutboxOperation.UploadBook, is OutboxOperation.UploadFile -> {
                // Файл очереди уходит потоком: книга или словарь не читаются в память целиком.
                val file = withContext(ioContext) { outboxStore.payloadFile(item) }
                    ?: throw DeviceError.Other(text(R.string.core_outbox_file_unreadable))
                val progress = beginTransfer(item.title, file.length(), index, count)
                try {
                    if (op is OutboxOperation.UploadFile) {
                        val target = client.ensureDirectory(op.dir)
                        client.upload(file, item.fileName, target, op.mimeType, op.replace, progress)
                        Integration.evictImage(DeviceClient.join(target, item.fileName))
                    } else {
                        val booksDir = client.ensureDirectory("/Books")
                        // EPUB, FB2 (.fb2 / .fb2.zip — читалка перекодирует при открытии), TXT, MD.
                        val placed = client.uploadBook(file, item.fileName, booksDir, BookFiles.uploadMime(item.fileName), progress)
                        if (placed != item.fileName) return placed
                    }
                } finally {
                    _transfer.value = null
                }
            }
            is OutboxOperation.DeleteFile -> ignoringAlreadyGone { client.delete(op.path) }
            is OutboxOperation.DeleteFontFamily -> deleteFontFamily(op.family, client)
            is OutboxOperation.Library -> {
                // Без отката на кэш: POST заменяет документ целиком, устаревшая копия стёрла бы полки.
                val doc = op.change.apply(client.libraryStrict())
                client.saveLibrary(doc)
                snap = snap.copy(library = doc)
                doc.dailyMinutes?.let { settings.dailyGoalMinutes = it }
            }
            is OutboxOperation.HighlightNote -> client.updateHighlight(op.id, op.note)
            is OutboxOperation.DeleteHighlight -> ignoringAlreadyGone { client.deleteHighlight(op.id) }
            is OutboxOperation.DeleteVocab -> ignoringAlreadyGone { client.deleteVocab(op.word) }
            is OutboxOperation.Settings -> {
                val applied = client.updateSettings(op.values)
                if (applied < op.values.size) {
                    throw DeviceError.Other(text(R.string.core_settings_partially, applied, op.values.size))
                }
            }
            is OutboxOperation.SelectSleepImage -> noteSleepSelection(selectSleepImage(op.name, client))
            is OutboxOperation.SelectDictionary -> client.selectDictionary(op.name)
            // 404 «no such dictionary» на повторе — уже удалён.
            is OutboxOperation.DeleteDictionary -> try {
                client.deleteDictionary(op.name)
            } catch (e: DeviceError.Server) {
                if (e.status != 404) throw e
            }
            // OutboxRunner такие не отдаёт; отказ надёжнее догадки.
            is OutboxOperation.Unsupported -> throw DeviceError.Other(text(R.string.core_needs_newer_app))
        }
        return null
    }

    private suspend fun ignoringAlreadyGone(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: DeviceError) {
            // Повтор удаления, ответ на которое потерялся: уже удалено.
            if (!isAlreadyGone(e)) throw e
        }
    }

    /** Сначала `/api/fonts/delete`; TTF-семейства, которыми он не управляет, удаляются по файлам. */
    private suspend fun deleteFontFamily(family: String, client: DeviceClient) {
        try {
            client.deleteFontFamily(family)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (OutboxRunner.isTransient(e)) throw e
            // Сбой списка — сбой удаления (временный остановит очередь), а не «файлов нет».
            val entries = client.files("/fonts")
            val folder = entries.firstOrNull { it.isDirectory && it.name == family }
            val file = entries.firstOrNull { !it.isDirectory && it.name.substringBeforeLast('.', it.name) == family }
            when {
                folder != null -> {
                    val dir = "/fonts/" + folder.name
                    for (f in client.files(dir)) client.delete(dir + "/" + f.name)
                    client.delete(dir)
                }
                file != null -> client.delete("/fonts/" + file.name)
                else -> throw e
            }
        }
    }

    /** Отражает изменение из очереди в кэше (офлайн-правки видны сразу). */
    private fun applyLocally(operation: OutboxOperation) {
        when (operation) {
            is OutboxOperation.Library -> {
                val doc = operation.change.apply(snap.library ?: LibraryDoc(dailyMinutes = null))
                snap = snap.copy(library = doc, books = withLibrary(snap.books, doc))
                (operation.change as? LibraryChange.SetGoal)?.let { settings.dailyGoalMinutes = it.minutes }
            }
            is OutboxOperation.HighlightNote -> snap = snap.copy(
                highlights = snap.highlights.map { if (it.id == operation.id) it.copy(note = operation.note) else it },
            )
            is OutboxOperation.DeleteHighlight -> snap = snap.copy(highlights = snap.highlights.filter { it.id != operation.id })
            is OutboxOperation.DeleteVocab -> {
                snap = snap.copy(vocab = snap.vocab.filter { it.word != operation.word })
                Integration.vocabReviewRemove(operation.word)
            }
            is OutboxOperation.DeleteFile -> snap = snap.copy(
                books = snap.books.filter { it.path != operation.path },
                screenshots = snap.screenshots.filter { it.fullPath != operation.path },
                sleep = SleepRules.apply(operation, snap.sleep),
            )
            is OutboxOperation.SelectSleepImage -> snap = snap.copy(sleep = SleepRules.apply(operation, snap.sleep))
            is OutboxOperation.SelectDictionary, is OutboxOperation.DeleteDictionary ->
                snap = snap.copy(dictionaries = DictionaryRules.apply(operation, snap.dictionaries))
            is OutboxOperation.Settings -> {
                val cached = snap.deviceSettings?.map { s -> operation.values[s.key]?.let { s.copy(value = it) } ?: s }
                snap = snap.copy(sleep = SleepRules.apply(operation, snap.sleep), deviceSettings = cached)
            }
            OutboxOperation.UploadBook, is OutboxOperation.UploadFile, is OutboxOperation.DeleteFontFamily,
            is OutboxOperation.Unsupported -> {}
        }
    }

    /** После свежих данных неотправленные правки накладываются снова. */
    private fun reapplyPendingChanges() {
        // Список очереди уже в памяти (_outbox обновляется после каждой операции): без диска на главном потоке.
        for (item in _outbox.value) {
            if (!item.failed && item.belongsTo(snap.deviceKey)) applyLocally(item.operation)
        }
    }

    private fun withLibrary(books: List<Book>, doc: LibraryDoc): List<Book> = books.map { b ->
        b.copy(status = doc.status[b.path].orEmpty(), shelves = doc.shelves.filter { b.path in it.books }.map { it.id })
    }

    // endregion

    // region Библиотека

    suspend fun changeLibrary(change: LibraryChange, title: String, detail: String = ""): SubmitResult =
        submit(OutboxOperation.Library(change), title, detail)

    suspend fun setStatus(status: BookStatus?, book: Book): SubmitResult {
        val what = status?.let { statusTitle(it) } ?: text(R.string.core_status_none)
        return changeLibrary(LibraryChange.SetStatus(book.path, status?.raw), text(R.string.core_title_status, what), book.displayTitle)
    }

    fun statusTitle(status: BookStatus): String = when (status) {
        BookStatus.WANT -> text(R.string.core_status_want)
        BookStatus.READING -> text(R.string.core_status_reading)
        BookStatus.DONE -> text(R.string.core_status_done)
        BookStatus.DROPPED -> text(R.string.core_status_dropped)
    }

    suspend fun setShelf(shelf: Shelf, member: Boolean, book: Book): SubmitResult = changeLibrary(
        LibraryChange.ShelfMembership(shelf.id, book.path, member),
        text(if (member) R.string.core_title_to_shelf else R.string.core_title_from_shelf, shelf.name),
        book.displayTitle,
    )

    suspend fun createShelf(name: String): SubmitResult =
        changeLibrary(LibraryChange.CreateShelf(UUID.randomUUID().toString(), name), text(R.string.core_title_new_shelf, name))

    suspend fun renameShelf(shelf: Shelf, name: String): SubmitResult =
        changeLibrary(LibraryChange.RenameShelf(shelf.id, name), text(R.string.core_title_rename_shelf, name))

    suspend fun deleteShelf(shelf: Shelf): SubmitResult =
        changeLibrary(LibraryChange.DeleteShelf(shelf.id), text(R.string.core_title_delete_shelf, shelf.name))

    suspend fun setGoal(minutes: Int): SubmitResult {
        settings.dailyGoalMinutes = minutes
        return changeLibrary(LibraryChange.SetGoal(minutes), text(R.string.core_title_goal, minutes))
    }

    // endregion

    // region Выделения и словарь

    suspend fun updateNote(highlight: Highlight, note: String): SubmitResult {
        if (!highlight.canModify) { show(errorText(DeviceError.NotSupported), Banner.Kind.ERROR); return SubmitResult.FAILED }
        return submit(OutboxOperation.HighlightNote(highlight.id, note), text(R.string.core_title_note), highlight.text.take(80))
    }

    suspend fun deleteHighlight(highlight: Highlight): SubmitResult {
        if (!highlight.canModify) { show(errorText(DeviceError.NotSupported), Banner.Kind.ERROR); return SubmitResult.FAILED }
        return submit(OutboxOperation.DeleteHighlight(highlight.id), text(R.string.core_title_delete_highlight), highlight.text.take(80))
    }

    suspend fun deleteWord(word: VocabWord): SubmitResult =
        submit(OutboxOperation.DeleteVocab(word.word), text(R.string.core_title_delete_word, word.word))

    // endregion

    // region Файлы, заставки, шрифты, настройки

    suspend fun refreshScreenshots() {
        val client = client ?: return
        try {
            snap = snap.copy(screenshots = client.screenshots())
            reapplyPendingChanges()
            persist()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { show(e) }
    }

    suspend fun refreshSleep() {
        val client = client ?: return
        try {
            snap = snap.copy(sleep = client.sleepImages())
            reapplyPendingChanges()
            persist()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { show(e) }
    }

    /** Удаляет файл на читалке (сейчас или, офлайн, при следующем подключении). */
    suspend fun deleteRemote(file: RemoteFile): Boolean =
        submit(OutboxOperation.DeleteFile(file.fullPath), text(R.string.core_title_delete_file, file.name), file.fullPath) != SubmitResult.FAILED

    /**
     * Книга из `content://` в /Books через очередь: файл копируется из потока прямо в очередь и
     * уходит потоком (BUG-038) — без копии всей книги в памяти.
     */
    suspend fun uploadBookStream(open: () -> java.io.InputStream?, fileName: String, title: String? = null): SubmitResult {
        awaitLoaded()
        val name = Outbox.sanitize(fileName)
        val item = try {
            enqueueBookStream(open, name, title ?: BookFiles.stem(name), name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            show(e)
            return SubmitResult.FAILED
        }
        val result = afterEnqueue(item)
        if (result == SubmitResult.SENT) show(text(R.string.core_book_uploaded, name), Banner.Kind.SUCCESS)
        return result
    }

    /** Книга в /Books (папка создаётся при отсутствии). */
    suspend fun uploadBook(data: ByteArray, fileName: String, title: String? = null): SubmitResult {
        val name = Outbox.sanitize(fileName)
        val result = submit(OutboxOperation.UploadBook, title ?: BookFiles.stem(name), name, data, name)
        if (result == SubmitResult.SENT) show(text(R.string.core_book_uploaded, name), Banner.Kind.SUCCESS)
        return result
    }

    /**
     * Заставка в папку сна (одноимённый файл заменяется; офлайн — в очередь), затем читалке
     * говорится показывать именно её (и режим «Свой», если текущий картинки не показывает).
     */
    suspend fun uploadSleepImage(bmp: ByteArray, name: String): Boolean {
        val dir = snap.sleep?.dir?.ifEmpty { null } ?: "/sleep"
        val result = submit(
            OutboxOperation.UploadFile(dir, "image/bmp", replace = true, purpose = UploadPurpose.SLEEP),
            text(R.string.core_title_sleep_image, name), DeviceClient.join(dir, name), bmp, name,
        )
        if (result == SubmitResult.FAILED) return false
        // Очередь хранит (и загружает) файл под очищенным именем.
        showOnSleep(Outbox.sanitize(name))
        if (result == SubmitResult.SENT) show(text(R.string.core_sleep_uploaded, name), Banner.Kind.SUCCESS)
        return true
    }

    /** Изменения [SleepRules.showPlan] по порядку; итог каждого. */
    suspend fun showOnSleep(name: String): List<SubmitResult> =
        SleepRules.showPlan(name, snap.sleep?.mode, sleepSelectionSupported).map { submit(it, sleepTitle(it)) }

    /**
     * Шрифт: файлы `files` (имя → содержимое) в `/fonts/<folder>` с заменой одноимённых.
     * Офлайн — в очередь. Итоги по файлам.
     */
    suspend fun uploadFont(folder: String, files: List<Pair<String, ByteArray>>): List<SubmitResult> {
        val dir = "/fonts/$folder"
        val results = files.map { (name, data) ->
            val ext = name.substringAfterLast('.', "ttf").lowercase()
            submit(
                OutboxOperation.UploadFile(dir, "font/$ext", replace = true, purpose = UploadPurpose.FONT),
                text(R.string.core_title_font, name), DeviceClient.join(dir, name), data, name,
            )
        }
        if (results.isNotEmpty() && results.all { it == SubmitResult.SENT }) {
            show(text(R.string.core_font_uploaded, folder), Banner.Kind.SUCCESS)
        }
        return results
    }

    suspend fun deleteFontFamily(family: String): SubmitResult {
        val r = submit(OutboxOperation.DeleteFontFamily(family), text(R.string.core_title_delete_font, family))
        if (r == SubmitResult.SENT) show(text(R.string.core_font_deleted, family), Banner.Kind.SUCCESS)
        return r
    }

    // region Словари (Folio 1.1)

    /** Признак `dictionaries` у подключённой читалки; null — сведений о читалке нет. */
    val dictionariesSupported: Boolean? get() = DictionaryRules.supported(snap.info)

    /** Каталог словарей и установки из него (переживают уход с экрана). */
    val dictionaryCatalog: DictionaryCatalogController by lazy { DictionaryCatalogController(this) }

    /** URL манифеста каталога: в отладочной сборке его можно подменить в настройках приложения. */
    val dictionaryCatalogUrl: String
        get() = if (BuildConfig.DEBUG) settings.dictionaryCatalogUrl.trim().ifEmpty { BuildConfig.DICTIONARY_CATALOG_URL }
        else BuildConfig.DICTIONARY_CATALOG_URL

    /** GET /api/inklink/dictionaries в кэш (с наложенными правками из очереди). False — не вышло. */
    suspend fun refreshDictionaries(): Boolean {
        val client = client ?: return false
        return try {
            snap = snap.copy(dictionaries = client.dictionaries())
            reapplyPendingChanges()
            persist()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            show(e)
            false
        }
    }

    /** Режим поиска: `*` — все словари, `-` — выключен, иначе папка. */
    suspend fun selectDictionary(name: String, title: String): SubmitResult {
        val r = submit(OutboxOperation.SelectDictionary(name), text(R.string.core_title_dictionary_mode, title))
        if (r == SubmitResult.SENT) show(text(R.string.core_dictionary_mode_changed, title), Banner.Kind.SUCCESS)
        return r
    }

    suspend fun deleteDictionary(name: String, title: String): SubmitResult {
        val r = submit(OutboxOperation.DeleteDictionary(name), text(R.string.core_title_delete_dictionary, title), name)
        if (r == SubmitResult.SENT) show(text(R.string.core_dictionary_deleted, title), Banner.Kind.SUCCESS)
        return r
    }

    /**
     * Файлы словаря в `/dictionaries/<folder>` через очередь (папка создаётся, одноимённый файл
     * заменяется — повтор после обрыва безопасен). Офлайн — ждут подключения. Итоги по файлам.
     */
    suspend fun uploadDictionary(folder: String, title: String, files: List<StarDictFile>): List<SubmitResult> {
        val dir = DictionaryRules.folderPath(folder)
        val results = mutableListOf<SubmitResult>()
        for (f in files) {
            val r = submit(
                OutboxOperation.UploadFile(dir, "application/octet-stream", replace = true, purpose = UploadPurpose.DICTIONARY),
                text(R.string.core_title_dictionary_file, title, f.name), DeviceClient.join(dir, f.name), f.data, f.name,
            )
            results += r
            // Отклонённый файл: остальные не шлём — неполная папка словарём не станет.
            if (r == SubmitResult.FAILED) break
        }
        if (results.size == files.size && results.all { it == SubmitResult.SENT }) {
            show(text(R.string.core_dictionary_installed, title), Banner.Kind.SUCCESS)
        }
        return results
    }

    // endregion

    fun updateCachedSleepMode(mode: Int) {
        if (snap.sleep?.mode == mode) return
        snap = snap.copy(sleep = (snap.sleep ?: SleepResponse()).copy(mode = mode))
        saveSnapshotNow()
    }

    /** GET /api/settings, кэш для работы без связи (с наложенными правками из очереди). */
    suspend fun loadDeviceSettings(): List<DeviceSetting>? {
        val client = client ?: return snap.deviceSettings
        return try {
            cacheDeviceSettings(client.settings())
            snap.deviceSettings
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            show(e)
            snap.deviceSettings
        }
    }

    fun cacheDeviceSettings(list: List<DeviceSetting>) {
        snap = snap.copy(deviceSettings = list)
        reapplyPendingChanges()
        saveSnapshotNow()
    }

    /** Частичный POST /api/settings через очередь; `title` — для экрана очереди. */
    suspend fun updateDeviceSettings(changes: Map<String, JsonElement>, title: String, detail: String = ""): SubmitResult =
        submit(OutboxOperation.Settings(changes), title, detail)

    suspend fun setSleepMode(index: Int, name: String? = null) {
        val result = submit(
            OutboxOperation.Settings(mapOf("sleepScreen" to JsonPrimitive(index))),
            text(R.string.core_title_sleep_mode, name ?: sleepModeName(index)),
        )
        if (result == SubmitResult.SENT) show(text(R.string.core_sleep_mode_changed), Banner.Kind.SUCCESS)
    }

    /** Название режима экрана сна по номеру (как в прошивке Folio 1.0). */
    fun sleepModeName(index: Int): String = SLEEP_MODE_NAMES.getOrNull(index)?.let { text(it) } ?: index.toString()

    private fun sleepTitle(op: OutboxOperation): String = when (op) {
        is OutboxOperation.SelectSleepImage ->
            if (op.name.isEmpty()) text(R.string.core_title_sleep_random) else text(R.string.core_title_sleep_show, op.name)
        is OutboxOperation.Settings -> text(
            R.string.core_title_sleep_mode,
            sleepModeName(op.values["sleepScreen"]?.let { SettingsProfile.intValue(it) } ?: SleepRules.CUSTOM_MODE),
        )
        else -> ""
    }

    /** False, когда эта читалка (на текущей прошивке) ответила 404 на выбор заставки. */
    val sleepSelectionSupported: Boolean
        get() {
            val key = snap.deviceKey ?: return true
            val firmware = settings.sleepSelectUnsupported[key] ?: return true
            return firmware != snap.info?.firmware.orEmpty()
        }

    private fun noteSleepSelection(supported: Boolean) {
        val key = snap.deviceKey ?: return
        val map = settings.sleepSelectUnsupported
        if (supported) {
            if (key in map) settings.sleepSelectUnsupported = map - key
        } else {
            settings.sleepSelectUnsupported = map + (key to snap.info?.firmware.orEmpty())
        }
    }

    /**
     * «Заставка»: «Свой» показывает эту картинку на каждом сне (режим без картинок переключается
     * на «Свой») или снова случайную. False — читалка отклонила изменение.
     */
    suspend fun chooseSleepImage(choice: SleepChoice): Boolean {
        val results = choice.plan(snap.sleep?.mode, sleepSelectionSupported).map { submit(it, sleepTitle(it)) }
        if (results.isEmpty() || SubmitResult.FAILED in results) return false
        // Офлайн очередь уже сказала своё.
        if (!results.all { it == SubmitResult.SENT }) return true
        if (sleepSelectionSupported) {
            val title = if (choice is SleepChoice.Image) choice.fileName else text(R.string.core_sleep_random)
            show(text(R.string.core_sleep_chosen, title), Banner.Kind.SUCCESS)
        } else {
            // Читалка только что ответила 404: прошивка до Folio 1.0.
            show(text(R.string.core_sleep_update_firmware), Banner.Kind.INFO)
        }
        return true
    }

    // endregion

    // region Профили настроек

    /**
     * Сохраняет текущие переключатели/списки/числа читалки как профиль. «Сохранено» — только если
     * файл записан; null — не записан (баннер с ошибкой).
     */
    fun saveSettingsProfile(name: String, current: List<DeviceSetting>): SettingsProfile? {
        val values = SettingsProfile.capture(current)
        val trimmed = name.trim().ifEmpty { text(R.string.core_profile_default_name, _profiles.value.size + 1) }
        val profile = SettingsProfile(name = trimmed, values = values, created = now(), deviceKey = snap.deviceKey)
        val change = profileStore.add(profile)
        _profiles.value = change.profiles
        if (!change.saved) {
            show(text(R.string.core_profile_save_failed), Banner.Kind.ERROR)
            return null
        }
        if (profileStore.consumeUnreadable()) show(text(R.string.core_profiles_unreadable), Banner.Kind.ERROR)
        else show(text(R.string.core_profile_saved, trimmed, values.size), Banner.Kind.SUCCESS)
        return profile
    }

    fun renameSettingsProfile(profile: SettingsProfile, name: String) = applyProfileChange(profileStore.rename(profile.id, name))

    fun deleteSettingsProfile(profile: SettingsProfile) = applyProfileChange(profileStore.delete(profile.id))

    private fun applyProfileChange(change: SettingsProfileStore.Change) {
        _profiles.value = change.profiles
        if (!change.saved) show(text(R.string.core_profile_save_failed), Banner.Kind.ERROR)
    }

    /**
     * Профиль — одним частичным POST ключей, отличающихся от `current` (схема и значения читалки);
     * ключи, которых у читалки нет, пропускаются и называются. null — отправлять нечего.
     */
    suspend fun applySettingsProfile(profile: SettingsProfile, current: List<DeviceSetting>): SubmitResult? {
        val plan = profile.plan(current)
        val skippedNote = if (plan.skipped > 0) "\n" + text(R.string.core_profile_skipped, plan.skipped) else ""
        if (plan.changes.isEmpty()) {
            show(text(R.string.core_profile_same, profile.name) + skippedNote, Banner.Kind.INFO)
            return null
        }
        val names = plan.changes.keys.mapNotNull { k -> current.firstOrNull { it.key == k }?.name }.sorted()
        val result = submit(OutboxOperation.Settings(plan.changes), text(R.string.core_title_profile, profile.name), names.joinToString(", "))
        if (result == SubmitResult.SENT) {
            show(text(R.string.core_profile_applied, profile.name, plan.changes.size) + skippedNote, Banner.Kind.SUCCESS)
        } else if (result == SubmitResult.QUEUED && plan.skipped > 0) {
            show(text(R.string.core_profile_skipped, plan.skipped), Banner.Kind.INFO)
        }
        return result
    }

    // endregion

    // region Прошивка

    /**
     * Риски установки образа на подключённую читалку (SEC-026): откат версии, другая плата,
     * образ без версии. Непустой список — экран просит отдельное подтверждение.
     */
    fun firmwareRisks(imageName: String?, image: ByteArray): List<FirmwareRisk.Risk> =
        FirmwareRisk.assess(FirmwareImageInfo.parse(image), imageName, snap.info?.firmware, snap.info?.board)

    /**
     * Установка в области модели (BUG-039): поворот экрана и уход с экрана не обрывают загрузку
     * между .bin и .sig. Итог — в [firmwareResult].
     */
    fun startFirmwareInstall(image: ByteArray, signature: ByteArray, name: String) {
        if (_firmwareInstalling.value) return
        _firmwareInstalling.value = true
        _firmwareResult.value = null
        scope.launch {
            try {
                // Ещё раз перед отправкой: на читалку уходит только образ, подписанный ключом Folio.
                val problem = withContext(Dispatchers.Default) {
                    runCatching { FirmwareSignature.verify(image, signature) }.exceptionOrNull()
                }
                _firmwareResult.value = if (problem != null) {
                    FirmwareOutcome(false, firmwareSignatureText(problem))
                } else {
                    installFirmware(image, signature, name)
                }
            } finally {
                _firmwareInstalling.value = false
            }
        }
    }

    fun clearFirmwareResult() { _firmwareResult.value = null }

    private fun firmwareSignatureText(e: Throwable): String = when (e) {
        com.rustamguloff.foliolink.core.security.FirmwareSignatureException.Missing -> text(R.string.firmware_sig_missing)
        is com.rustamguloff.foliolink.core.security.FirmwareSignatureException.Malformed -> text(R.string.firmware_sig_malformed, e.size)
        com.rustamguloff.foliolink.core.security.FirmwareSignatureException.Invalid -> text(R.string.firmware_sig_invalid)
        else -> errorText(e)
    }

    /**
     * Загружает `<name>.bin` и `<name>.bin.sig` в /firmware и просит читалку установить. Подпись
     * проверяет экран ДО вызова (core/security); сюда неподписанное не попадает. В очередь не ставится:
     * установку нужно подтвердить на читалке.
     */
    suspend fun installFirmware(image: ByteArray, signature: ByteArray, name: String): FirmwareOutcome {
        val client = client ?: return FirmwareOutcome(false, errorText(DeviceError.Unreachable))
        return try {
            client.ensureDirectory("/firmware")
            var file = Outbox.sanitize(name)
            if (!file.lowercase().endsWith(".bin")) file += ".bin"
            val progress = beginTransfer(file, image.size.toLong(), 1, 2)
            client.upload(image, file, "/firmware", replace = true, progress = progress)
            val sigProgress = beginTransfer("$file.sig", signature.size.toLong(), 2, 2)
            client.upload(signature, "$file.sig", "/firmware", replace = true, progress = sigProgress)
            endTransfer()
            val r = client.applyFirmware("/firmware/$file")
            if (!r.isOk || r.accepted == false) {
                FirmwareOutcome(false, text(R.string.core_firmware_rejected, r.error ?: "?"))
            } else {
                // Читалка проверяет образ и спрашивает подтверждение на экране; перезагрузится после него.
                FirmwareOutcome(
                    true,
                    if (r.confirmOnDevice != false) text(R.string.core_firmware_confirm) else text(R.string.core_firmware_accepted),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeviceError.Server) {
            endTransfer()
            if (e.status == 422) {
                if (e.text.lowercase().contains("missing")) FirmwareOutcome(false, text(R.string.core_firmware_sig_missing))
                else FirmwareOutcome(false, text(R.string.core_firmware_sig_rejected, e.text))
            } else {
                FirmwareOutcome(false, errorText(e))
            }
        } catch (e: Exception) {
            endTransfer()
            FirmwareOutcome(false, errorText(e))
        }
    }

    // endregion

    /** Как runCatching, но отмену корутины не глотает. */
    private suspend inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /** Ключ, которому не принадлежит ни одно изменение очереди (читалка не подтверждена). */
        private const val UNCONFIRMED_READER = "\u0000unconfirmed"

        private val SLEEP_MODE_NAMES = listOf(
            R.string.core_sleep_mode_0, R.string.core_sleep_mode_1, R.string.core_sleep_mode_2, R.string.core_sleep_mode_3,
            R.string.core_sleep_mode_4, R.string.core_sleep_mode_5, R.string.core_sleep_mode_6, R.string.core_sleep_mode_7,
            R.string.core_sleep_mode_8, R.string.core_sleep_mode_9, R.string.core_sleep_mode_10,
        )

        /** Сборка для приложения: кэш в `filesDir`, очередь в `filesDir/Outbox`, токены — Keystore. */
        fun create(context: Context): AppModel {
            val app = context.applicationContext
            val store = SharedStore(File(app.filesDir, "Store"))
            return AppModel(
                platform = AndroidPlatform(app),
                store = store,
                outboxStore = Outbox(File(app.filesDir, "Outbox")),
                settings = LocalSettings(
                    PrefsKeyValueStore(app.getSharedPreferences("foliolink.settings", Context.MODE_PRIVATE)),
                    defaultManualAddress = if (BuildConfig.DEBUG) "10.0.2.2:8080" else "",
                ),
                tokens = FilePairingTokenStore.standard(app.noBackupFilesDir),
                profileStore = SettingsProfileStore(store),
                discovery = DeviceDiscovery(app, debug = BuildConfig.DEBUG),
            )
        }

        /**
         * Удаление, которому нечего удалять: `/delete` отвечает 500 «… (not found); », маршруты
         * FolioLink — 404 «… not found». Так выглядит повтор удаления, ответ на которое потерялся:
         * изменение сделано, а не отклонено.
         */
        fun isAlreadyGone(error: DeviceError): Boolean {
            val e = error as? DeviceError.Server ?: return false
            val m = e.text.lowercase()
            return (e.status == 404 && "not found" in m) || (e.status == 500 && "(not found)" in m)
        }

        /** Выбор заставки. False — прошивка без маршрута (404): изменение сделано без эффекта. */
        suspend fun selectSleepImage(name: String, client: DeviceClient): Boolean = try {
            client.selectSleepImage(name)
            true
        } catch (e: DeviceError.NotSupported) {
            false
        }

        /** Текст баннера автосохранения. Нет доступа к галерее — автосохранение выключается. */
        fun autoSaveOutcome(saved: Int, error: Throwable?, t: Texts): AutoSaveOutcome? {
            if (error != null) {
                if (error is GalleryAccessDenied) return AutoSaveOutcome(t.get(R.string.core_autosave_denied), isError = true, disable = true)
                val head = if (saved > 0) t.get(R.string.core_autosave_partial, saved) else t.get(R.string.core_autosave_failed)
                return AutoSaveOutcome(head + ": " + errorText(error, t), isError = true, disable = false)
            }
            if (saved <= 0) return null
            return AutoSaveOutcome(t.get(R.string.core_autosave_done, saved), isError = false, disable = false)
        }

        /** Имя файла по умолчанию для новой заставки. */
        fun defaultSleepName(template: String, at: LocalDateTime = LocalDateTime.now()): String =
            "foliolink-$template-${at.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.bmp"
    }
}
