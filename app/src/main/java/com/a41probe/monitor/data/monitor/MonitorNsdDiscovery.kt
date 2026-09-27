package com.a41probe.monitor.data.monitor

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/** 解析成功的被监控端：主机地址 + 端口 + 服务名。 */
data class DiscoveredTarget(
    val serviceName: String,
    val host: String,
    val port: Int,
)

/**
 * 监控端 mDNS 发现：扫描 `_mio._tcp`。
 * NsdManager 同一时刻只允许 resolve 一个服务，故用队列串行 resolve。
 * 设备详情（型号/权限档/指标）一律以 TCP hello/snapshot 为准，不依赖 TXT（兼容低版本）。
 */
class MonitorNsdDiscovery(context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private var discovering = false
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile private var resolving = false
    private val seen = HashSet<String>()

    var onResolved: ((DiscoveredTarget) -> Unit)? = null
    var onLost: ((String) -> Unit)? = null

    // P2-18: resolve 看门狗——个别 ROM 回调不触发时，超时复位 resolving 继续消化队列
    private val watchdog = Runnable {
        if (resolving) {
            resolving = false
            pumpResolve()
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onServiceFound(si: NsdServiceInfo) {
            synchronized(seen) {
                if (!seen.add(si.serviceName)) return
            }
            resolveQueue.add(si)
            pumpResolve()
        }
        override fun onServiceLost(si: NsdServiceInfo) {
            synchronized(seen) { seen.remove(si.serviceName) }
            resolveQueue.removeIf { it.serviceName == si.serviceName }
            onLost?.invoke(si.serviceName)
        }
        override fun onStartDiscoveryFailed(type: String, code: Int) {
            discovering = false
            Log.w(TAG, "start discovery failed: $code")
        }
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onServiceResolved(si: NsdServiceInfo) {
            handler.removeCallbacks(watchdog)
            resolving = false
            val host = si.host?.hostAddress
            if (host != null && si.port > 0) {
                onResolved?.invoke(DiscoveredTarget(si.serviceName, host, si.port))
            }
            pumpResolve()
        }
        override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
            handler.removeCallbacks(watchdog)
            resolving = false
            // 短暂失败（同时 busy 等）重试一次
            if (code == NsdManager.FAILURE_INTERNAL_ERROR) resolveQueue.add(si)
            pumpResolve()
        }
    }

    private fun pumpResolve() {
        if (resolving) return
        val next = resolveQueue.poll() ?: return
        resolving = true
        runCatching { nsd.resolveService(next, resolveListener) }
            .onSuccess {
                handler.removeCallbacks(watchdog)
                handler.postDelayed(watchdog, RESOLVE_TIMEOUT_MS)
            }
            .onFailure { resolving = false; pumpResolve() }
    }

    fun start() {
        if (discovering) return
        discovering = true
        runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure { discovering = false }
    }

    fun stop() {
        handler.removeCallbacks(watchdog)
        if (!discovering) return
        discovering = false
        runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        resolveQueue.clear()
        resolving = false
        synchronized(seen) { seen.clear() }
    }

    companion object {
        const val SERVICE_TYPE = "_mio._tcp."
        private const val TAG = "MonDiscovery"
        private const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
