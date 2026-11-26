package com.rustamguloff.foliolink.core.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.rustamguloff.foliolink.core.model.DeviceEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Найденная читалка. `source`: "NSD" (служба компаньона или похожая на читалку HTTP-служба), "mDNS", "UDP". */
data class FoundReader(val endpoint: DeviceEndpoint, val source: String) {
    val id: String get() = endpoint.id
}

/** То, что AppModel и экраны знают о поиске (в тестах — фейк). */
interface ReaderDiscovery {
    val found: StateFlow<List<FoundReader>>
    val isSearching: StateFlow<Boolean>
    fun start()
    fun stop()
}

/**
 * Ищет читалки в локальной сети: NSD (`_inklink._tcp` и `_http._tcp` с «читалочным» именем),
 * известные mDNS-имена (`folio.local`, затем `crosspoint.local`) и UDP `hello` на порт 8134
 * (на Android широковещание разрешено без особых прав — включено всегда).
 *
 * `_inklink._tcp` — стабильное имя протокола прошивки Folio: историческое «inklink» не переименовывать.
 */
class DeviceDiscovery(
    context: Context,
    /** Отладочная сборка: UDP ещё и на loopback/10.0.2.2 (мок и симулятор прошивки на компьютере). */
    private val debug: Boolean = false,
) : ReaderDiscovery {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _found = MutableStateFlow<List<FoundReader>>(emptyList())
    private val _searching = MutableStateFlow(false)
    override val found: StateFlow<List<FoundReader>> = _found.asStateFlow()
    override val isSearching: StateFlow<Boolean> = _searching.asStateFlow()

    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val jobs = mutableListOf<Job>()
    /** NsdManager.resolveService держит одно разрешение за раз — очередь. */
    private val resolveQueue = ArrayDeque<Triple<NsdServiceInfo, String, String>>()
    private var resolving = false
    private val resolved = HashSet<String>()

    override fun start() {
        stop()
        _searching.value = true
        for (type in listOf(COMPANION_SERVICE_TYPE, HTTP_SERVICE_TYPE)) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.w(TAG, "NSD $type failed: $errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                    serviceInfo?.let { info -> scope.launch { resolved.remove(key(type, info.serviceName)) } }
                }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    scope.launch { onFound(serviceInfo, type) }
                }
            }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onSuccess { listeners += listener }
                .onFailure { Log.w(TAG, "NSD $type: ${it.message}") }
        }
        jobs += scope.launch {
            val replies = withContext(Dispatchers.IO) { UdpProbe.broadcastHello(timeoutMs = 2500, debug = debug) }
            replies.forEach { add(DeviceEndpoint(it.host, it.httpPort, it.name), "UDP") }
        }
        for (host in KNOWN_HOST_NAMES) {
            jobs += scope.launch {
                val ep = withContext(Dispatchers.IO) { probeHostName(host) }
                ep?.let { add(it, "mDNS") }
            }
        }
        jobs += scope.launch {
            delay(SEARCH_MS)
            _searching.value = false
        }
    }

    override fun stop() {
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        listeners.clear()
        jobs.forEach { it.cancel() }
        jobs.clear()
        resolveQueue.clear()
        resolved.clear()
        _searching.value = false
    }

    private fun key(type: String, name: String) = "$type/$name"

    private fun onFound(info: NsdServiceInfo, rawType: String) {
        val type = rawType.trimEnd('.')
        val name = info.serviceName ?: return
        // `_http._tcp` рекламирует множество устройств: оставляем только похожие на читалку.
        if (type == HTTP_SERVICE_TYPE && !isReaderServiceName(name)) return
        if (!resolved.add(key(type, name))) return
        resolveQueue.addLast(Triple(info, name, type))
        pumpResolve()
    }

    @Suppress("DEPRECATION") // resolveService: замена (registerServiceInfoCallback) только с API 34.
    private fun pumpResolve() {
        if (resolving) return
        val (info, name, type) = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                scope.launch {
                    resolved.remove(key(type, name))
                    resolving = false
                    pumpResolve()
                }
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = hostOf(serviceInfo)
                scope.launch {
                    if (host != null) add(DeviceEndpoint(host, serviceInfo.port, name), "NSD")
                    resolving = false
                    pumpResolve()
                }
            }
        }
        runCatching { nsd.resolveService(info, listener) }.onFailure {
            resolving = false
            scope.launch { pumpResolve() }
        }
    }

    @Suppress("DEPRECATION")
    private fun hostOf(info: NsdServiceInfo): String? {
        // IPv4: адрес IPv6 link-local без %zone непригоден.
        val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        val a = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull() ?: return null
        return a.hostAddress?.substringBefore('%')
    }

    private fun add(endpoint: DeviceEndpoint, source: String) {
        _found.update { list ->
            val i = list.indexOfFirst { it.endpoint.host == endpoint.host && it.endpoint.port == endpoint.port }
            when {
                i < 0 -> list + FoundReader(endpoint, source)
                // Имя службы компаньона (NSD) предпочтительнее mDNS и UDP.
                source == "NSD" -> list.toMutableList().also { it[i] = FoundReader(endpoint, source) }
                else -> list
            }
        }
    }

    /** Имя mDNS → IPv4, если порт 80 принимает TCP. */
    private fun probeHostName(host: String): DeviceEndpoint? = runCatching {
        val addr = InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } ?: return null
        Socket().use { it.connect(InetSocketAddress(addr, 80), 3000) }
        DeviceEndpoint(addr.hostAddress ?: return null, 80, host)
    }.getOrNull()

    companion object {
        private const val TAG = "Discovery"
        private const val SEARCH_MS = 8_000L

        const val COMPANION_SERVICE_TYPE = "_inklink._tcp"
        const val HTTP_SERVICE_TYPE = "_http._tcp"

        /** mDNS-имена прошивки: новые сборки Folio — `folio.local`, старые (и upstream) — `crosspoint.local`. */
        val KNOWN_HOST_NAMES = listOf("folio.local", "crosspoint.local")

        /** Начала слов, отличающие читалку среди `_http._tcp` ("Folio-X4" — да, "Portfolio" — нет). */
        val READER_NAME_PREFIXES = listOf("folio", "crosspoint", "inklink")

        fun isReaderServiceName(name: String): Boolean {
            val words = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            return words.any { w -> READER_NAME_PREFIXES.any { w.startsWith(it) } }
        }
    }
}

/**
 * UDP `hello` на порт 8134 (широковещательно; в отладке — ещё loopback и 10.0.2.2 эмулятора)
 * и разбор ответов `folio (on <host>);<wsPort>` (старые прошивки: `crosspoint (on …);…`).
 */
object UdpProbe {
    data class Reply(val host: String, val name: String, val httpPort: Int)

    const val PORT = 8134
    val REPLY_PREFIXES = listOf("folio", "crosspoint")

    /** Адреса, за которыми стоит компьютер разработчика (мок/симулятор прошивки на HTTP 8080). */
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "::1", "10.0.2.2")

    fun parse(reply: String, from: String): Reply? {
        val lower = reply.lowercase()
        if (REPLY_PREFIXES.none { lower.startsWith(it) }) return null
        var name = "Folio"
        val open = reply.indexOf("(on ")
        if (open >= 0) {
            val close = reply.indexOf(')', open + 4)
            if (close >= 0) name = reply.substring(open + 4, close)
        }
        // Число после ';' — порт WebSocket (81); HTTP — на 80. На loopback отвечает
        // симулятор/мок на компьютере, у него HTTP на 8080.
        return Reply(host = from, name = name, httpPort = if (from in LOOPBACK_HOSTS) 8080 else 80)
    }

    fun broadcastHello(timeoutMs: Int, debug: Boolean): List<Reply> {
        val replies = LinkedHashSet<Reply>()
        val socket = runCatching { DatagramSocket() }.getOrNull() ?: return emptyList()
        socket.use { s ->
            s.broadcast = true
            s.soTimeout = 300
            val message = "hello".toByteArray()
            val targets = mutableListOf("255.255.255.255")
            if (debug) targets += listOf("127.0.0.1", "10.0.2.2")
            for (t in targets) {
                runCatching { s.send(DatagramPacket(message, message.size, InetAddress.getByName(t), PORT)) }
                    .onFailure { Log.i("Discovery", "UDP hello to $t failed: ${it.message}") }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(512)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                val host = packet.address?.hostAddress?.substringBefore('%') ?: continue
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                parse(text, host)?.let { replies += it }
            }
        }
        return replies.toList()
    }
}
