package com.a41probe.monitor.data.agent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.a41probe.monitor.data.remote.RemoteDeviceInfo

/**
 * 被监控端 mDNS 广播封装：向局域网注册 `_mio._tcp`，监控端据此自动发现。
 */
class AgentNsd(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Volatile private var registered = false
    @Volatile private var registering = false
    private var serviceName: String = ""
    // P1-2: unregisterService 必须传入与 registerService 完全相同的 listener 引用
    private var regListener: NsdManager.RegistrationListener? = null

    fun register(info: RemoteDeviceInfo, port: Int) {
        if (registered || registering) unregister()
        // 服务名需在同一局域网内唯一：型号 + 端口
        // 修复（关键）：用局部变量 svcName。NsdServiceInfo.apply{} 的隐式 receiver 本身也有
        // serviceName 属性，若在块内直接写 serviceName，会被解析为 receiver 的空初始值
        // （Kotlin 名称遮蔽），导致 registerService 抛 "Service name cannot be empty"，
        // mDNS 广播静默失败、监控端永远无法自动发现。
        val svcName = "${info.model}-$port"
        serviceName = svcName
        val si = NsdServiceInfo().apply {
            serviceName = svcName
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("name", info.name)
            setAttribute("model", info.model)
            setAttribute("brand", info.brand)
            setAttribute("soc", info.soc)
            setAttribute("appVer", info.appVer)
            setAttribute("priv", info.priv.tag)
        }
        val listener = object : NsdManager.RegistrationListener {
            // 回调串扰防护：重启服务时旧 listener 的注销回调可能迟到，若不判断身份会
            // 把新服务已置好的 registered/registering 标志错误清零。旧实例（regListener
            // 已指向新对象或为 null）的回调一律忽略。
            override fun onServiceRegistered(s: NsdServiceInfo) {
                if (regListener !== this) return
                registering = false
                registered = true
                serviceName = s.serviceName
                Log.i(TAG, "registered: ${s.serviceName} @ $port")
            }
            override fun onRegistrationFailed(s: NsdServiceInfo, errorCode: Int) {
                if (regListener !== this) return
                registering = false
                registered = false
                Log.w(TAG, "register failed: $errorCode")
            }
            override fun onServiceUnregistered(s: NsdServiceInfo) {
                // unregister() 已手动清状态并把 regListener 置 null；仅当本实例仍是
                // 当前 listener 时才清，避免旧实例迟到回调覆盖新服务状态。
                if (regListener === this) {
                    registered = false
                    registering = false
                }
            }
            override fun onUnregistrationFailed(s: NsdServiceInfo, errorCode: Int) {
                if (regListener !== this) return
                Log.w(TAG, "unregister failed: $errorCode")
            }
        }
        regListener = listener
        registering = true
        try {
            nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            registering = false
            Log.w(TAG, "registerService threw", t)
        }
    }

    fun unregister() {
        // P1-3: 即使注册回调尚未到达（registering=true、registered=false），也用保存的
        // listener 调 unregisterService，让系统取消进行中的注册，避免回调迟到导致残留。
        val listener = regListener
        if ((registered || registering) && listener != null) {
            runCatching { nsd.unregisterService(listener) }
        }
        registered = false
        registering = false
        regListener = null
    }

    companion object {
        const val SERVICE_TYPE = "_mio._tcp."
        private const val TAG = "AgentNsd"
    }
}
