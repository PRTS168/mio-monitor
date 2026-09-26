package com.a41probe.monitor.data.monitor

import android.content.Context
import com.a41probe.monitor.data.remote.ConnState
import com.a41probe.monitor.data.remote.RemoteDeviceInfo
import com.a41probe.monitor.data.remote.RemoteMessage
import com.a41probe.monitor.data.remote.RemoteSnapshot
import com.a41probe.monitor.data.remote.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

enum class DeviceSource { DISCOVERED, MANUAL }

data class DeviceEntry(
    val key: String,
    val host: String,
    val port: Int,
    val serviceName: String = "",
    val info: RemoteDeviceInfo? = null,
    val snap: RemoteSnapshot? = null,
    val state: ConnState = ConnState.DISCOVERED,
    val latencyMs: Int = -1,
    val source: DeviceSource = DeviceSource.DISCOVERED,
    val log: String = "",
)

/**
 * 监控端统一仓库（进程内单例）：mDNS 发现 + 每设备一个 TCP 连接状态机，
 * 自动连接、断线指数退避重连、手动添加兜底。
 */
object MonitorRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discovery: MonitorNsdDiscovery? = null
    private var appCtx: Context? = null
    private val connJobs = ConcurrentHashMap<String, Job>()
    // P1-4: 持有每设备活动 socket，重入/移除/停止时先关 socket 唤醒阻塞读，避免僵尸协程
    private val connSockets = ConcurrentHashMap<String, Socket>()

    private val _devices = MutableStateFlow<List<DeviceEntry>>(emptyList())
    val devices: StateFlow<List<DeviceEntry>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    @Volatile var autoConnect = true
    @Volatile var autoReconnect = true

    private val RECOVER_STATES = setOf(
        ConnState.DISCOVERED, ConnState.CONNECTING, ConnState.RETRYING, ConnState.OFFLINE,
    )

    fun start(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
        loadPrefs()
        if (discovery == null) {
            discovery = MonitorNsdDiscovery(ctx).apply {
                onResolved = { t -> handleResolved(t) }
                onLost = { name -> handleLost(name) }
            }
        }
        discovery?.start()
        _scanning.value = true
    }

    /** P2-16: 仅停 mDNS 扫描（省电），保活已建立的 TCP 连接；再次进入页面时 start 恢复扫描。 */
    fun stopDiscovery() {
        discovery?.stop()
        _scanning.value = false
    }

    fun stop() {
        discovery?.stop()
        _scanning.value = false
        connSockets.values.forEach { runCatching { it.close() } }
        connSockets.clear()
        connJobs.values.forEach { it.cancel() }
        connJobs.clear()
    }

    private fun loadPrefs() {
        val c = appCtx ?: return
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        autoConnect = p.getBoolean(KEY_AUTO_CONNECT, true)
        autoReconnect = p.getBoolean(KEY_AUTO_RECONNECT, true)
    }

    fun updateAutoConnect(v: Boolean) {
        autoConnect = v
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_AUTO_CONNECT, v)?.apply()
    }

    fun updateAutoReconnect(v: Boolean) {
        autoReconnect = v
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_AUTO_RECONNECT, v)?.apply()
    }

    private fun handleResolved(t: DiscoveredTarget) {
        val key = "${t.host}:${t.port}"
        val existing = _devices.value.firstOrNull { it.key == key }
        if (existing == null) {
            upsert(
                DeviceEntry(
                    key = key, host = t.host, port = t.port, serviceName = t.serviceName,
                    state = ConnState.DISCOVERED,
                )
            )
            if (autoConnect) connect(key)
        } else {
            // 原地刷新 serviceName（upsert 是仅插入语义，存在时不替换，故这里直接 update）
            _devices.update { list ->
                list.map { if (it.key == key) it.copy(serviceName = t.serviceName) else it }
            }
            // P1-5: 设备离开后回归（处于待连/离线态）且无活连接时，自愈重连
            if (existing.state in RECOVER_STATES && autoConnect && connJobs[key] == null) {
                connect(key)
            }
        }
    }

    private fun handleLost(serviceName: String) {
        val e = _devices.value.firstOrNull { it.serviceName == serviceName } ?: return
        if (e.source == DeviceSource.DISCOVERED) {
            connJobs.remove(e.key)?.cancel()
            runCatching { connSockets.remove(e.key)?.close() }
            upsert(e.copy(state = ConnState.OFFLINE, log = "设备已离开局域网"))
        }
    }

    fun manualAdd(host: String, port: Int): String {
        val key = "$host:$port"
        if (_devices.value.any { it.key == key }) {
            connect(key)
            return "已存在，重新连接"
        }
        upsert(
            DeviceEntry(
                key = key, host = host, port = port,
                state = ConnState.CONNECTING, source = DeviceSource.MANUAL,
            )
        )
        connect(key)
        return "正在连接 $key"
    }

    fun remove(key: String) {
        connJobs.remove(key)?.cancel()
        runCatching { connSockets.remove(key)?.close() }
        _devices.update { list -> list.filterNot { it.key == key } }
    }

    fun connect(key: String) {
        connJobs.remove(key)?.cancel()
        // P1-4: 先关闭旧 socket，使阻塞在 readFrame 的旧协程立即抛错退出
        runCatching { connSockets.remove(key)?.close() }
        connJobs[key] = scope.launch {
            var backoff = 1000L
            var terminated = false
            try {
                while (isActive) {
                    val e = _devices.value.firstOrNull { it.key == key } ?: return@launch
                    setState(key, ConnState.CONNECTING, "连接中…")
                    val t0 = System.currentTimeMillis()
                    val socket = Socket()
                    val connected = runCatching {
                        socket.connect(InetSocketAddress(e.host, e.port), 3000)
                    }.isSuccess
                    if (!connected) {
                        runCatching { socket.close() }
                        if (!autoReconnect) {
                            setState(key, ConnState.OFFLINE, "端口无响应")
                            return@launch
                        }
                        setState(key, ConnState.RETRYING, "无响应 · ${backoff / 1000}s 后重试")
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(60_000)
                        continue
                    }
                    val rtt = (System.currentTimeMillis() - t0).toInt()
                    connSockets[key] = socket
                    try {
                        socket.tcpNoDelay = true
                        // P1-6: 读超时。快照周期 1s，15s 收不到任何帧视为断链（对端硬断电无 FIN）
                        socket.soTimeout = 15_000
                        val input = DataInputStream(socket.getInputStream())
                        while (isActive) {
                            val frame = Wire.readFrame(input) ?: break
                            when (val m = RemoteMessage.parse(frame)) {
                                is RemoteMessage.Hello -> {
                                    upsertInfo(key, m.info, rtt)
                                    backoff = 1000L   // P2-17: 收到 Hello 才确认链路可用、重置退避
                                }
                                is RemoteMessage.Snapshot ->
                                    upsertSnap(key, m.snap, rtt)
                                is RemoteMessage.Bye -> {
                                    terminated = true
                                    break
                                }
                                else -> {}
                            }
                            ensureActive()
                        }
                    } catch (t: Throwable) {
                        // soTimeout / 读异常 → terminated 保持 false，落入下方重连
                    } finally {
                        connSockets.remove(key)
                        runCatching { socket.close() }   // P1-4: 正常/异常/取消都关闭
                    }
                    if (terminated || !autoReconnect) {
                        setState(
                            key, ConnState.OFFLINE,
                            if (terminated) "对方已终止被监控模式" else "连接断开",
                        )
                        return@launch
                    }
                    setState(key, ConnState.RETRYING, "连接断开 · 重连中")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(60_000)
                }
            } finally {
                connJobs.remove(key)
            }
        }
    }

    private fun upsert(e: DeviceEntry) =
        _devices.update { list -> if (list.any { it.key == e.key }) list else list + e }

    private fun setState(key: String, st: ConnState, log: String) {
        _devices.update { list ->
            list.map { if (it.key == key) it.copy(state = st, log = log) else it }
        }
    }

    private fun upsertInfo(key: String, info: RemoteDeviceInfo, rtt: Int) {
        _devices.update { list ->
            list.map {
                if (it.key == key)
                    it.copy(info = info, state = ConnState.ONLINE, latencyMs = rtt)
                else it
            }
        }
    }

    private fun upsertSnap(key: String, snap: RemoteSnapshot, rtt: Int) {
        _devices.update { list ->
            list.map {
                if (it.key == key)
                    it.copy(
                        snap = snap, state = ConnState.ONLINE,
                        latencyMs = rtt, log = "",
                    )
                else it
            }
        }
    }

    private const val PREFS = "a41_monitor"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_AUTO_RECONNECT = "auto_reconnect"
}
