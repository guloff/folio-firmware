package com.rustamguloff.foliolink.core.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Синхронизация по Bluetooth LE (android.bluetooth, роль central).
 *
 * Выключена по умолчанию; включение хранится в SharedPreferences `ble_sync`. Первая читалка
 * ищется сканированием по UUID сервиса; после первого подключения её адрес запоминается, и
 * висит `connectGatt(autoConnect = true)`: система подключается, когда читалка просыпается и
 * начинает рекламу. На каждое подключение: MTU 517 → поиск сервиса → подписка на response →
 * синхронизация (не чаще раза в минуту, если её не запросили вручную).
 *
 * Фоновой службы нет: всё работает, пока жив процесс приложения. Все поля менеджера меняются
 * только на главном потоке (колбэки GATT, скана и рассылок приходят туда же).
 */
@SuppressLint("MissingPermission") // разрешения проверяет hasPermissions(); SecurityException ловится
class BleSyncManager(context: Context, private val scope: CoroutineScope) {

    companion object {
        const val PREFS = "ble_sync"
        /** Синхронизации, начатые переподключением, чаще этого пропускаются. */
        const val MIN_AUTO_INTERVAL_MS = 60_000L
        /** Скан при известной читалке: ускоряет подключение, пока приложение на экране. */
        private const val KNOWN_SCAN_MS = 30_000L
        /** Ожидание подтверждения записи запроса. */
        private const val WRITE_TIMEOUT_MS = 10_000L
        private const val HOST_POLL_MS = 2_000L

        private const val KEY_ENABLED = "enabled"
        private const val KEY_ADDRESS = "address"
        private const val KEY_NAME = "name"
        private const val KEY_LAST_SYNC = "lastSyncMs"

        /** Разрешения, которые UI должен запросить перед включением. */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private enum class Link { NONE, PENDING, CONNECTED }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val btManager: BluetoothManager? = app.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = btManager?.adapter

    /** Центр состояния приложения; задаётся после создания. */
    var host: BleSyncHost? = null
        set(value) {
            field = value
            if (value != null) onMain { if (transport != null) startSync(force = false) }
        }

    private val _state = MutableStateFlow(BleUiState())
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    // Состояние (только главный поток).
    private var enabled = prefs.getBoolean(KEY_ENABLED, false)
    private var status: BleUiState.Status = BleUiState.Status.Off
    private var failure: Exception? = null
    private var lastSyncMs: Long? = prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 }
    private var lastAutoAttemptMs = 0L
    private var step: String? = null
    private var lastWarning: String? = null
    private var mtu: Int? = null
    private var peripheralName: String? = prefs.getString(KEY_NAME, null)

    private var gatt: BluetoothGatt? = null
    private var link = Link.NONE
    private var discovering = false
    private var requestChar: BluetoothGattCharacteristic? = null
    private var transport: GattTransport? = null
    private var syncJob: Job? = null
    private var waitJob: Job? = null
    /** Растёт при каждом запуске синхронизации и обрыве связи: поздний итог брошенной ничего не меняет. */
    @Volatile private var generation = 0
    private var scanning = false
    private var receiverRegistered = false
    private val reconnect = Runnable { evaluate(foreground = false) }
    private val stopKnownScan = Runnable { stopScan() }
    /** Некоторые стеки не присылают onMtuChanged: тогда поиск сервиса начинается без него. */
    private val mtuFallback = Runnable {
        val g = gatt
        if (g != null && link == Link.CONNECTED && !discovering && transport == null) guarded { startDiscovery(g) }
    }

    private val savedAddress: String? get() = prefs.getString(KEY_ADDRESS, null)

    // Публичное API (можно звать с любого потока)

    fun setEnabled(on: Boolean) = onMain {
        if (on == enabled) return@onMain
        enabled = on
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) start() else stop()
    }

    /** «Синхронизировать сейчас»: при связи — сразу, без связи — ищет читалку. */
    fun syncNow() = onMain {
        if (!enabled) return@onMain
        if (transport != null) startSync(force = true) else evaluate(foreground = true)
    }

    /** Забыть читалку: следующее подключение — к первой найденной Folio. */
    fun forgetReader() = onMain {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_NAME).apply()
        peripheralName = null
        if (enabled) {
            stop()
            start()
        } else {
            publish()
        }
    }

    /** Приложение вышло на передний план: синхронизация, если прошлой больше минуты. */
    fun appBecameActive() = onMain {
        if (!enabled) return@onMain
        if (transport != null) startSync(force = false) else evaluate(foreground = true)
    }

    /** UI получил (или потерял) разрешения Bluetooth. */
    fun onPermissionsChanged() = onMain {
        if (enabled) evaluate(foreground = true)
    }

    // Включение и состояние адаптера

    private fun start() {
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                app, adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        evaluate(foreground = true)
    }

    private fun stop() {
        resetLink(BleError.Disconnected)
        stopScan()
        closeGatt()
        main.removeCallbacks(reconnect)
        main.removeCallbacks(mtuFallback)
        if (receiverRegistered) {
            runCatching { app.unregisterReceiver(adapterReceiver) }
            receiverRegistered = false
        }
        failure = null
        setStatus(BleUiState.Status.Off)
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            onMain { if (enabled) evaluate(foreground = false) }
        }
    }

    private val hasBle: Boolean
        get() = app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && adapter != null

    /** Проверяет поддержку, разрешения и адаптер; при порядке — подключается или ищет. */
    private fun evaluate(foreground: Boolean) {
        if (!enabled) return setStatus(BleUiState.Status.Off)
        if (!hasBle) return setStatus(BleUiState.Status.Unsupported)
        if (!hasPermissions(app)) {
            dropEverything()
            return setStatus(BleUiState.Status.Unauthorized)
        }
        val a = adapter ?: return setStatus(BleUiState.Status.Unsupported)
        if (!a.isEnabled) {
            dropEverything()
            return setStatus(BleUiState.Status.PoweredOff)
        }
        guarded { connectKnownOrScan(a, foreground) }
    }

    private fun dropEverything() {
        resetLink(BleError.Disconnected)
        stopScan()
        closeGatt()
    }

    /**
     * Ожидающее подключение к запомненной читалке (autoConnect не истекает: система подключится,
     * когда читалка начнёт рекламу) плюс скан по UUID сервиса: для первой читалки — до находки,
     * для известной — коротко и только при [foreground] (прямое подключение быстрее autoConnect).
     */
    private fun connectKnownOrScan(a: BluetoothAdapter, foreground: Boolean) {
        if (link == Link.CONNECTED) {
            val g = gatt
            if (transport == null && !discovering && g != null) startDiscovery(g)
            return
        }
        val known = savedAddress
        if (known != null && link == Link.NONE && BluetoothAdapter.checkBluetoothAddress(known)) {
            connect(a.getRemoteDevice(known), autoConnect = true)
        }
        if (known == null) {
            startScan(a, limitedMs = null)
        } else if (foreground) {
            startScan(a, limitedMs = KNOWN_SCAN_MS)
        }
        if (status !is BleUiState.Status.Failed) setStatus(BleUiState.Status.Searching) else publish()
    }

    private fun connect(device: BluetoothDevice, autoConnect: Boolean) {
        closeGatt()
        discovering = false
        link = Link.PENDING
        // Вариант с Executor и BluetoothGattConnectionSettings есть только с API 37; minSdk 26.
        @Suppress("DEPRECATION")
        gatt = device.connectGatt(
            app, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, main,
        )
        if (gatt == null) link = Link.NONE
    }

    private fun closeGatt() {
        gatt?.let { g ->
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gatt = null
        link = Link.NONE
        discovering = false
    }

    // Скан

    private fun startScan(a: BluetoothAdapter, limitedMs: Long?) {
        main.removeCallbacks(stopKnownScan)
        if (limitedMs != null) main.postDelayed(stopKnownScan, limitedMs)
        if (scanning) return
        val scanner = a.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleIds.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true
    }

    private fun stopScan() {
        main.removeCallbacks(stopKnownScan)
        if (!scanning) return
        scanning = false
        guarded { adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onMain { found(result.device) }

        override fun onBatchScanResults(results: MutableList<ScanResult>) = onMain {
            results.firstOrNull()?.let { found(it.device) }
        }

        override fun onScanFailed(errorCode: Int) = onMain {
            scanning = false
            // Известная читалка всё равно ждёт autoConnect; без неё — повтор через несколько секунд.
            if (enabled && savedAddress == null) {
                main.removeCallbacks(reconnect)
                main.postDelayed(reconnect, 5_000)
            }
        }
    }

    private fun found(device: BluetoothDevice) {
        if (!enabled || !scanning) return
        val known = savedAddress
        if (known != null && !device.address.equals(known, ignoreCase = true)) return // другая Folio рядом
        stopScan()
        if (link == Link.CONNECTED) return
        guarded {
            connect(device, autoConnect = false)
            setStatus(BleUiState.Status.Connecting)
        }
    }

    // GATT

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) = onMain {
            if (g !== gatt) {
                runCatching { g.close() }
                return@onMain
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && statusCode == BluetoothGatt.GATT_SUCCESS) {
                link = Link.CONNECTED
                stopScan()
                guarded {
                    val name = g.device.name ?: peripheralName ?: BleIds.ADVERTISED_NAME
                    peripheralName = name
                    prefs.edit().putString(KEY_ADDRESS, g.device.address).putString(KEY_NAME, name).apply()
                    setStatus(BleUiState.Status.Connecting)
                    if (g.requestMtu(BleIds.PREFERRED_MTU)) {
                        main.removeCallbacks(mtuFallback)
                        main.postDelayed(mtuFallback, 3_000)
                    } else {
                        startDiscovery(g)
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                linkLost(normal = statusCode == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, statusCode: Int) = onMain {
            if (g !== gatt) return@onMain
            if (statusCode == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            if (link == Link.CONNECTED && !discovering && transport == null) guarded { startDiscovery(g) }
            publish()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) = onMain {
            if (g !== gatt) return@onMain
            guarded { subscribe(g, statusCode) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) = onMain {
            if (g !== gatt) return@onMain
            if (descriptor.uuid != BleIds.CCCD || descriptor.characteristic.uuid != BleIds.RESPONSE) return@onMain
            discovering = false
            val request = requestChar
            if (statusCode != BluetoothGatt.GATT_SUCCESS || request == null) {
                fail(BleError.Gatt(if (statusCode != BluetoothGatt.GATT_SUCCESS) statusCode else BleIds.ATT_NOT_SUBSCRIBED))
                guarded { g.disconnect() }
                return@onMain
            }
            transport = GattTransport(g, request)
            if (mtu == null) mtu = 23
            failure = null
            setStatus(BleUiState.Status.Connected)
            startSync(force = false)
        }

        // API 33+: значение приходит параметром.
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == BleIds.RESPONSE) deliver(g, value.copyOf())
        }

        // До API 33 (на 33+ её вызывает реализация по умолчанию новой — там она переопределена).
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            if (characteristic.uuid == BleIds.RESPONSE) deliver(g, value.copyOf())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            if (characteristic.uuid == BleIds.REQUEST) onMain { if (g === gatt) transport?.didWrite(statusCode) }
        }

        // Читалка перезапустила GATT-сервер (API 31+): найти сервис заново.
        override fun onServiceChanged(g: BluetoothGatt) = onMain {
            if (g !== gatt || link != Link.CONNECTED) return@onMain
            resetLink(BleError.Disconnected)
            guarded { startDiscovery(g) }
        }
    }

    private fun deliver(g: BluetoothGatt, frame: ByteArray) = onMain {
        if (g === gatt) transport?.deliver(frame)
    }

    private fun startDiscovery(g: BluetoothGatt) {
        main.removeCallbacks(mtuFallback)
        discovering = true
        setStatus(BleUiState.Status.Connecting)
        if (!g.discoverServices()) {
            discovering = false
            g.disconnect()
        }
    }

    /** Подписка на response (CCCD) — до первой записи, иначе читалка отвечает ATT 0x81. */
    private fun subscribe(g: BluetoothGatt, statusCode: Int) {
        val service = g.getService(BleIds.SERVICE)
        val request = service?.getCharacteristic(BleIds.REQUEST)
        val response = service?.getCharacteristic(BleIds.RESPONSE)
        val cccd = response?.getDescriptor(BleIds.CCCD)
        if (statusCode != BluetoothGatt.GATT_SUCCESS || request == null || response == null || cccd == null) {
            discovering = false
            fail(if (statusCode != BluetoothGatt.GATT_SUCCESS) BleError.Gatt(statusCode) else BleError.Gatt(BleIds.ATT_NOT_SUBSCRIBED))
            g.disconnect()
            return
        }
        requestChar = request
        g.setCharacteristicNotification(response, true)
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        if (!started) {
            discovering = false
            fail(BleError.Gatt(BleIds.ATT_NOT_SUBSCRIBED))
            g.disconnect()
        }
    }

    /** Соединение потеряно: сессия GATT закрывается, через паузу — снова autoConnect/скан. */
    private fun linkLost(normal: Boolean) {
        resetLink(BleError.Disconnected)
        closeGatt()
        if (!enabled) return
        if (status !is BleUiState.Status.Failed) setStatus(BleUiState.Status.Searching) else publish()
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, if (normal) 500L else 2_000L)
    }

    /** Бросает сессию обмена (адрес читалки остаётся). */
    private fun resetLink(reason: BleError) {
        generation++
        syncJob?.cancel()
        syncJob = null
        waitJob?.cancel()
        waitJob = null
        transport?.close(reason)
        transport = null
        requestChar = null
        mtu = null
        step = null
        publish()
    }

    // Синхронизация

    private fun startSync(force: Boolean) {
        if (syncJob != null) return
        val t = transport ?: return
        val h = host ?: return
        if (h.isSyncing) return waitForHost(force)
        val now = System.currentTimeMillis()
        if (!force) {
            val last = maxOf(lastSyncMs ?: 0L, lastAutoAttemptMs)
            if (now - last < MIN_AUTO_INTERVAL_MS) return
        }
        lastAutoAttemptMs = now
        val plan = try {
            h.blePlan()
        } catch (e: Exception) {
            fail(e)
            return
        }
        generation++
        val gen = generation
        failure = null
        setStatus(BleUiState.Status.Syncing)
        syncJob = scope.launch(Dispatchers.Main) {
            val outcome = try {
                Result.success(withContext(Dispatchers.Default) {
                    BleSync.run(BleCommandClient(t), plan) { name ->
                        onMain {
                            if (gen == generation) {
                                step = name
                                publish()
                            }
                        }
                    }
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            finishSync(outcome, gen)
        }
    }

    /** Идёт синхронизация по Wi-Fi: ждать её конца, пока есть связь. */
    private fun waitForHost(force: Boolean) {
        if (waitJob != null) return
        val t = transport
        waitJob = scope.launch(Dispatchers.Main) {
            while (host?.isSyncing == true && transport === t && t != null) delay(HOST_POLL_MS)
            waitJob = null
            if (transport === t) startSync(force)
        }
    }

    private fun finishSync(outcome: Result<BleSyncResult>, gen: Int) {
        // Брошенная синхронизация (обрыв, выключение), закончившаяся поздно, ничего не меняет.
        if (gen != generation) return
        syncJob = null
        step = null
        outcome.onSuccess { result ->
            host?.applyBleSync(result)
            val now = System.currentTimeMillis()
            lastSyncMs = now
            prefs.edit().putLong(KEY_LAST_SYNC, now).apply()
            lastWarning = result.failures.firstOrNull()
            failure = null
            setStatus(if (transport == null) BleUiState.Status.Searching else BleUiState.Status.Connected)
        }
        outcome.onFailure { e ->
            fail(e as? Exception ?: RuntimeException(e))
            // Молчащая читалка (10 с без кадров) или битые кадры: начать заново с новым соединением.
            if (e is BleError.Timeout || e is BleError.CorruptResponse || e is BleError.Busy) {
                guarded { gatt?.disconnect() }
            }
        }
    }

    private fun fail(e: Exception) {
        failure = e
        setStatus(BleUiState.Status.Failed(e.message ?: e.javaClass.simpleName))
    }

    // Служебное

    private fun setStatus(s: BleUiState.Status) {
        status = s
        publish()
    }

    private fun publish() {
        val canSync = status == BleUiState.Status.Connected ||
            (status is BleUiState.Status.Failed && transport != null && syncJob == null)
        _state.value = BleUiState(
            enabled = enabled,
            status = if (enabled) status else BleUiState.Status.Off,
            lastSyncMs = lastSyncMs,
            step = step,
            lastWarning = lastWarning,
            mtu = mtu,
            peripheralName = peripheralName,
            knowsReader = savedAddress != null,
            canSyncNow = enabled && canSync,
            failure = failure,
        )
    }

    /** Отзыв разрешения посреди работы кидает SecurityException: показываем «нет доступа». */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: SecurityException) {
            dropEverything()
            setStatus(BleUiState.Status.Unauthorized)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // Последним: к этому моменту все колбэки (receiver, scan, gatt) уже созданы.
    init {
        publish()
        if (enabled) onMain { start() }
    }
}

/**
 * [BleTransport] поверх одного подключённого BluetoothGatt. Кадры складываются в канал
 * (колбэки — на главном потоке), запись ждёт onCharacteristicWrite.
 */
@SuppressLint("MissingPermission")
internal class GattTransport(
    private val gatt: BluetoothGatt,
    private val request: BluetoothGattCharacteristic,
) : BleTransport {
    private val frames = Channel<ByteArray>(Channel.UNLIMITED)
    private val lock = Any()
    private var writeWaiter: CompletableDeferred<Unit>? = null
    @Volatile private var closed: BleError? = null

    override suspend fun write(bytes: ByteArray) {
        closed?.let { throw it }
        val waiter = CompletableDeferred<Unit>()
        synchronized(lock) { writeWaiter = waiter }
        val started = try {
            // Длиннее MTU − 3: стек сам делает Long Write (prepare/execute).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(request, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                request.value = bytes
                request.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(request)
            }
        } catch (e: SecurityException) {
            false
        }
        if (!started) {
            synchronized(lock) { if (writeWaiter === waiter) writeWaiter = null }
            closed?.let { throw it }
            // Очередь GATT занята — как ATT 0x80: клиент подождёт и повторит.
            throw BleError.Gatt(BleIds.ATT_BUSY)
        }
        withTimeoutOrNull(WRITE_TIMEOUT_MS) { waiter.await() } ?: run {
            synchronized(lock) { if (writeWaiter === waiter) writeWaiter = null }
            throw BleError.Timeout
        }
    }

    override suspend fun nextFrame(timeoutMs: Long): ByteArray {
        frames.tryReceive().getOrNull()?.let { return it }
        closed?.let { throw it }
        return withTimeoutOrNull(timeoutMs) { frames.receive() } ?: throw BleError.Timeout
    }

    override suspend fun discardPendingFrames() {
        while (frames.tryReceive().isSuccess) { /* выбросить */ }
    }

    fun deliver(frame: ByteArray) {
        frames.trySend(frame)
    }

    fun didWrite(status: Int) {
        val waiter = synchronized(lock) { writeWaiter.also { writeWaiter = null } } ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) waiter.complete(Unit) else waiter.completeExceptionally(BleError.Gatt(status))
    }

    fun close(reason: BleError) {
        closed = reason
        val waiter = synchronized(lock) { writeWaiter.also { writeWaiter = null } }
        waiter?.completeExceptionally(reason)
        frames.close(reason)
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 10_000L
    }
}
