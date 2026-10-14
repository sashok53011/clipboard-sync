package com.clipboardsync.core

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityManager
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Движок синхронизации: TCP-сервер + mDNS-обнаружение (Wi-Fi),
 * RFCOMM SPP (Bluetooth со спаренными устройствами), чтение/запись буфера
 * обмена и рассылка изменений всем подключённым устройствам.
 */
object SyncEngine {

    private const val TAG = "ClipSync"
    const val PORT = 47821

    interface Listener {
        fun onStateChanged() {}
        fun onPeersChanged() {}
        fun onEvent(text: String) {}
        fun onClipboardReceived(text: String, from: String) {}
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var platform = "phone"
    @Volatile private var identity: DeviceIdentity? = null
    @Volatile private var enabled = false
    @Volatile private var running = false
    @Volatile private var accessibilityService: AccessibilityService? = null

    @Volatile var onClipboardReceived: ((String, String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = CopyOnWriteArrayList<Connection>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val discovered = ConcurrentHashMap<String, DiscoveredPeer>()
    private val manualTargets = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val events = mutableListOf<String>()

    private var serverSocket: ServerSocket? = null
    private var nsd: NsdDiscovery? = null
    private var bt: BtSpp? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var lastClipboardHash: String? = null
    @Volatile private var lastClipboardText: String? = null

    data class DiscoveredPeer(val host: String, val port: Int, val name: String)
    data class PeerUi(
        val name: String,
        val platform: String,
        val transport: String,
        val connected: Boolean,
        val address: String
    )

    // ---------------------------------------------------------------- lifecycle

    fun init(context: Context, plat: String) {
        if (appContext == null) {
            val ctx = context.applicationContext
            appContext = ctx
            platform = plat
            identity = DeviceIdentity(ctx, plat)
            enabled = Prefs.enabled(ctx)
            manualTargets.addAll(Prefs.manualTargets(ctx))
            log(str(R.string.log_initialized, identity?.name ?: "?", plat))
        }
    }

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun isEnabled(): Boolean = enabled

    fun isRunning(): Boolean = running

    fun identity(): DeviceIdentity? = identity

    fun setEnabled(v: Boolean) {
        val ctx = appContext ?: return
        enabled = v
        Prefs.setEnabled(ctx, v)
        if (v) start() else stop()
        listeners.forEach { it.onStateChanged() }
    }

    @Synchronized
    fun start() {
        if (running || !enabled) return
        val ctx = appContext ?: return
        running = true
        // Запоминаем текущий буфер обмена: при подключении сверим его с памятью
        // собеседника (начальная синхронизация).
        ClipboardStore.readText(ctx)?.let { t ->
            if (t.isNotBlank()) {
                lastClipboardText = t
                lastClipboardHash = sha1(t)
            }
        }
        registerClipboardListener(ctx)
        acquireMulticastLock(ctx)
        startTcpServer()
        startConnector()
        startNsd()
        startBluetooth()
        startPolling()
        startKeepalive()
        registerNetworkCallback(ctx)
        log(str(R.string.log_started))
        listeners.forEach { it.onStateChanged(); it.onPeersChanged() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        unregisterClipboardListener()
        connections.toList().forEach { c ->
            runCatching { c.send(Msg.Bye) }
            c.close()
        }
        connections.clear()
        connecting.clear()
        discovered.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        nsd?.stop()
        nsd = null
        bt?.stop()
        bt = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        unregisterNetworkCallback()
        log(str(R.string.log_stopped))
        listeners.forEach { it.onStateChanged(); it.onPeersChanged() }
    }

    fun onAccessibilityConnected(service: AccessibilityService) {
        accessibilityService = service
        log(str(R.string.log_acc_connected))
        if (enabled && !running) start()
        listeners.forEach { it.onStateChanged() }
    }

    fun onAccessibilityDisconnected() {
        accessibilityService = null
        listeners.forEach { it.onStateChanged() }
    }

    // ---------------------------------------------------------------- clipboard

    private fun registerClipboardListener(ctx: Context) {
        if (clipListener != null) return
        try {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            val l = ClipboardManager.OnPrimaryClipChangedListener { onLocalClipChanged() }
            cm?.addPrimaryClipChangedListener(l)
            clipListener = l
        } catch (e: Exception) {
            log(str(R.string.log_clipboard_error, e.message ?: "?"))
        }
    }

    private fun unregisterClipboardListener() {
        val l = clipListener ?: return
        try {
            appContext?.getSystemService(ClipboardManager::class.java)
                ?.removePrimaryClipChangedListener(l)
        } catch (e: Exception) {
        }
        clipListener = null
    }

    private fun onLocalClipChanged() {
        val ctx = appContext ?: return
        val text = ClipboardStore.readText(ctx) ?: return
        if (text.isBlank()) return
        val h = sha1(text)
        if (h == lastClipboardHash) return
        lastClipboardHash = h
        lastClipboardText = text
        val n = broadcast(Msg.Clipboard(text, identity?.deviceId ?: "?", System.currentTimeMillis()))
        if (n > 0) log(str(R.string.log_copied, n))
    }

    private fun applyRemote(text: String, senderId: String) {
        val ctx = appContext ?: return
        if (text.isEmpty()) return
        val h = sha1(text)
        if (h == lastClipboardHash) return // эхо
        lastClipboardHash = h
        lastClipboardText = text
        ClipboardStore.writeText(ctx, text)
        val fromName = connections.firstOrNull { it.deviceId == senderId }?.name
            ?: senderId.take(8)
        log(str(R.string.log_received, fromName, text.take(40)))
        onClipboardReceived?.invoke(text, fromName)
        listeners.forEach { it.onClipboardReceived(text, fromName) }
    }

    /** Тестовое сообщение: записать в свой буфер и разослать. */
    fun sendTest(text: String) {
        val ctx = appContext ?: return
        lastClipboardHash = sha1(text)
        lastClipboardText = text
        ClipboardStore.writeText(ctx, text)
        val n = broadcast(Msg.Clipboard(text, identity?.deviceId ?: "?", System.currentTimeMillis()))
        log(str(R.string.log_test_sent, n))
    }

    // ---------------------------------------------------------------- connections

    private fun broadcast(msg: Msg): Int {
        var n = 0
        for (c in connections.toList()) {
            try {
                c.send(msg)
                n++
            } catch (e: Exception) {
                dropConnection(c, "ошибка отправки")
            }
        }
        return n
    }

    fun handleSocket(
        input: InputStream,
        output: OutputStream,
        transport: String,
        address: String,
        dialedByMe: Boolean,
        keyOverride: String? = null
    ) {
        val key = keyOverride ?: "$transport:$address"
        connecting.add(key)
        scope.launch {
            var conn: Connection? = null
            try {
                val writer = OutputStreamWriter(output, Charsets.UTF_8)
                val c = Connection(input, output, transport, address, dialedByMe, writer)
                conn = c
                c.send(Msg.Hello(identity?.deviceId ?: "?", identity?.name ?: "device", platform))
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                while (running) {
                    val line = reader.readLine() ?: break
                    val msg = Msg.fromJson(line) ?: continue
                    when (msg) {
                        is Msg.Hello -> {
                            if (msg.deviceId == identity?.deviceId) {
                                log(str(R.string.log_self_node))
                                break
                            }
                            // При одновременном взаимном подключении возникают два сокета.
                            // Обе стороны должны выбрать ОДИН и тот же: побеждает сокет,
                            // инициированный устройством с меньшим deviceId.
                            val selfId = identity?.deviceId ?: ""
                            val myInitiator = if (c.dialedByMe) selfId else msg.deviceId
                            val conflict = connections.firstOrNull {
                                it !== c && it.deviceId == msg.deviceId && it.transport == transport
                            }
                            if (conflict != null) {
                                val otherInitiator = if (conflict.dialedByMe) selfId else (conflict.deviceId ?: "")
                                if (myInitiator <= otherInitiator) {
                                    log(str(R.string.log_duplicate))
                                    break
                                }
                                dropConnection(conflict, str(R.string.log_duplicate))
                            }
                            if (c.deviceId == null) {
                                c.deviceId = msg.deviceId
                                c.name = msg.name
                                c.platform = msg.platform
                                connections.add(c)
                                log(str(R.string.log_connected, msg.name, transportLabel(transport)))
                                lastClipboardText?.let { t ->
                                    if (t.isNotEmpty()) {
                                        runCatching {
                                            c.send(Msg.Clipboard(t, identity?.deviceId ?: "?", System.currentTimeMillis()))
                                        }
                                    }
                                }
                                listeners.forEach { it.onPeersChanged(); it.onStateChanged() }
                            }
                        }

                        is Msg.Clipboard -> {
                            c.lastSeen = System.currentTimeMillis()
                            if (msg.sender != identity?.deviceId) applyRemote(msg.text, msg.sender)
                        }

                        Msg.Ping -> c.lastSeen = System.currentTimeMillis()
                        Msg.Bye -> break
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "connection ended ($transport/$address): ${e.message}")
            } finally {
                conn?.let { dropConnection(it, null) }
                connecting.remove(key)
            }
        }
    }

    private fun dropConnection(c: Connection, reason: String?) {
        val removed = connections.remove(c)
        c.close()
        if (removed) {
            val label = c.name.ifEmpty { c.address }
            log(
                if (reason != null) str(R.string.log_disconnected_reason, label, reason)
                else str(R.string.log_disconnected, label)
            )
            listeners.forEach { it.onPeersChanged(); it.onStateChanged() }
        }
    }

    // ---------------------------------------------------------------- networking

    private fun startTcpServer() {
        scope.launch {
            while (running) {
                var ss: ServerSocket? = null
                try {
                    ss = ServerSocket(PORT)
                    serverSocket = ss
                    while (running) {
                        val s = ss.accept()
                        s.tcpNoDelay = true
                        val addr = s.inetAddress?.hostAddress ?: "wifi"
                        handleSocket(s.inputStream, s.outputStream, "wifi", addr, dialedByMe = false)
                    }
                } catch (e: Exception) {
                    if (running) {
                        log(str(R.string.log_tcp_server_error, e.message ?: "?"))
                        delay(5000)
                    }
                } finally {
                    runCatching { ss?.close() }
                    if (serverSocket === ss) serverSocket = null
                }
            }
        }
    }

    private fun startConnector() {
        scope.launch {
            while (running) {
                try {
                    val targets = linkedMapOf<String, Pair<String, Int>>()
                    discovered.values.forEach { targets["wifi:${it.host}"] = it.host to it.port }
                    manualTargets.forEach { t ->
                        val host = t.substringBefore(":").trim()
                        val port = t.substringAfter(":", PORT.toString()).trim().toIntOrNull() ?: PORT
                        if (host.isNotEmpty()) targets["manual:$host"] = host to port
                    }
                    for ((key, hp) in targets) {
                        if (!running) break
                        if (connecting.any { it.endsWith(":${hp.first}") }) continue
                        if (connections.any { it.address == hp.first }) continue
                        tryConnect(hp.first, hp.second, key)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "connector", e)
                }
                delay(15_000)
            }
        }
    }

    private fun tryConnect(host: String, port: Int, key: String) {
        scope.launch {
            if (!running || connecting.contains(key)) return@launch
            connecting.add(key)
            var ok = false
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                ok = true
                handleSocket(
                    s.inputStream, s.outputStream,
                    if (key.startsWith("manual:")) "manual" else "wifi",
                    host, dialedByMe = true, keyOverride = key
                )
            } catch (e: Exception) {
                // Устройство недоступно — попробуем позже.
            } finally {
                if (!ok) connecting.remove(key)
            }
        }
    }

    private fun startNsd() {
        val ctx = appContext ?: return
        val selfName = "ClipboardSync-" + (identity?.deviceId?.take(4) ?: "dev")
        nsd = NsdDiscovery(
            ctx,
            selfName,
            { host, port, name ->
                if (name.startsWith(selfName)) return@NsdDiscovery // собственные регистрации
                discovered["wifi:$host:$port"] = DiscoveredPeer(host, port, name)
                log(str(R.string.log_found_wifi, name, host))
                if (!connections.any { it.address == host } && connecting.none { it.endsWith(":$host") }) {
                    tryConnect(host, port, "wifi:$host")
                }
                listeners.forEach { it.onPeersChanged() }
            },
            { msg -> log(msg) }
        )
        nsd?.start(PORT)
    }

    private fun restartNsd() {
        if (!running) return
        nsd?.stop()
        nsd = null
        scope.launch {
            delay(1500)
            if (running) startNsd()
        }
    }

    private fun startBluetooth() {
        val ctx = appContext ?: return
        val s = BtSpp(
            ctx,
            { input, output, addr -> handleSocket(input, output, "bt", addr, dialedByMe = false) },
            { msg -> log(msg) }
        )
        bt = s
        s.startServer()
        scope.launch {
            while (running) {
                try {
                    if (s.hasPermission() && connections.none { it.transport == "bt" }) {
                        for (addr in s.bondedAddresses()) {
                            if (!running) break
                            if (connections.any { it.address == addr }) continue
                            if (connecting.any { it.endsWith(":$addr") }) continue
                            s.connectTo(addr)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "bt loop", e)
                }
                delay(30_000)
            }
        }
    }

    private fun startPolling() {
        // Страховка: слушатель буфера может не сработать в фоне.
        scope.launch {
            while (running) {
                delay(7000)
                try {
                    onLocalClipChanged()
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun startKeepalive() {
        scope.launch {
            while (running) {
                delay(60_000)
                val now = System.currentTimeMillis()
                connections.toList().forEach { c ->
                    try {
                        c.send(Msg.Ping)
                    } catch (e: Exception) {
                        dropConnection(c, "ping")
                        return@forEach
                    }
                    if (now - c.lastSeen > 300_000) dropConnection(c, "таймаут")
                }
            }
        }
    }

    private fun acquireMulticastLock(ctx: Context) {
        try {
            val wm = ctx.getSystemService(WifiManager::class.java)
            multicastLock = wm?.createMulticastLock("ClipSync")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
        }
    }

    private fun registerNetworkCallback(ctx: Context) {
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    restartNsd()
                }
            }
            cm?.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback ?: return
        try {
            appContext?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
        }
        netCallback = null
    }

    fun addManualTarget(value: String) {
        val ctx = appContext ?: return
        val host = value.trim().substringBefore(":")
        if (host.isEmpty()) return
        if (manualTargets.none { it.substringBefore(":") == host }) {
            manualTargets.add(value.trim())
            Prefs.addManualTarget(ctx, value.trim())
            log(str(R.string.log_manual_added, value.trim()))
            listeners.forEach { it.onPeersChanged() }
        }
    }

    // ---------------------------------------------------------------- UI helpers

    fun peers(): List<PeerUi> {
        val result = mutableListOf<PeerUi>()
        val connectedAddrs = mutableSetOf<String>()
        for (c in connections) {
            if (c.deviceId == null) continue
            connectedAddrs.add(c.address)
            result.add(PeerUi(c.name, c.platform, transportLabel(c.transport), true, c.address))
        }
        for (d in discovered.values) {
            if (d.host in connectedAddrs) continue
            if (result.any { it.address == d.host }) continue
            result.add(PeerUi(d.name, "?", "Wi-Fi", false, d.host))
        }
        for (t in manualTargets) {
            val host = t.substringBefore(":")
            if (host in connectedAddrs) continue
            if (result.any { it.address == host }) continue
            result.add(PeerUi(t, "?", "вручную", false, host))
        }
        return result
    }

    fun statusText(ctx: Context): String {
        val am = ctx.getSystemService(AccessibilityManager::class.java)
        val accOn = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.id.contains(ctx.packageName) } == true

        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val wifiOn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val btOn = bt?.hasBluetooth() == true &&
            (bt?.hasPermission() != false)

        val state = when {
            !enabled -> ctx.getString(R.string.status_disabled)
            running -> ctx.getString(R.string.status_running)
            else -> ctx.getString(R.string.status_waiting)
        }
        return ctx.getString(
            R.string.status_summary,
            state,
            ctx.getString(if (accOn) R.string.status_acc_ok else R.string.status_acc_missing),
            ctx.getString(if (wifiOn) R.string.status_wifi_on else R.string.status_none),
            ctx.getString(if (btOn) R.string.status_bt_ready else R.string.status_none),
            ctx.getString(R.string.status_connections, connections.size)
        )
    }

    fun events(): List<String> = synchronized(events) { events.toList() }

    fun transportLabel(transport: String): String = when (transport) {
        "wifi" -> str(R.string.transport_wifi)
        "bt" -> str(R.string.transport_bt)
        "manual" -> str(R.string.transport_manual)
        else -> transport
    }

    private fun log(text: String) {
        val line = "${timeFmt.format(Date())}  $text"
        synchronized(events) {
            events.add(0, line)
            while (events.size > 60) events.removeAt(events.size - 1)
        }
        Log.i(TAG, text)
        listeners.forEach { it.onEvent(line) }
    }

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** Локализованная строка из ресурсов core. */
    private fun str(id: Int, vararg args: Any): String = try {
        appContext?.getString(id, *args) ?: ""
    } catch (e: Exception) {
        ""
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
}
