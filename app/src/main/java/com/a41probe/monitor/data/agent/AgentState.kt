package com.a41probe.monitor.data.agent

import com.a41probe.monitor.data.remote.PrivLevel
import com.a41probe.monitor.data.remote.RemoteDeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一个已连接的监控端。 */
data class MonitorClient(
    val host: String,
    val connectedAt: Long,
)

/**
 * 被监控端运行状态（进程内单例）：AgentService 写入，AgentScreen 观察。
 */
object AgentState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _port = MutableStateFlow(0)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _startedAt = MutableStateFlow(0L)
    val startedAt: StateFlow<Long> = _startedAt.asStateFlow()

    private val _info = MutableStateFlow(RemoteDeviceInfo())
    val info: StateFlow<RemoteDeviceInfo> = _info.asStateFlow()

    private val _clients = MutableStateFlow<List<MonitorClient>>(emptyList())
    val clients: StateFlow<List<MonitorClient>> = _clients.asStateFlow()

    /** 最近一次采集到的权限档（用于 UI 与广播 TXT）。 */
    private val _priv = MutableStateFlow(PrivLevel.FREE)
    val priv: StateFlow<PrivLevel> = _priv.asStateFlow()

    fun setRunning(v: Boolean, port: Int = 0) {
        _running.value = v
        _port.value = port
        if (v) _startedAt.value = System.currentTimeMillis()
    }

    fun setInfo(info: RemoteDeviceInfo) { _info.value = info }
    fun setPriv(p: PrivLevel) { _priv.value = p }

    fun addClient(c: MonitorClient) {
        _clients.value = (_clients.value.filterNot { it.host == c.host }) + c
    }

    fun removeClient(host: String) {
        _clients.value = _clients.value.filterNot { it.host == host }
    }

    fun clearClients() { _clients.value = emptyList() }
}
