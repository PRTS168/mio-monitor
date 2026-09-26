package com.a41probe.monitor.data.remote

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/**
 * 被监控端编码：把本机 [com.a41probe.monitor.data.Snapshot] 与真实设备信息编码为协议 JSON。
 * 换算在被监控端完成（输出标准值），监控端只负责展示，保证两端口径一致。
 */
object SnapshotEncoder {

    /** 真实读取设备身份（不写死）。priv 由采集结果决定后再补。 */
    fun buildDeviceInfo(ctx: Context): RemoteDeviceInfo {
        val name = runCatching {
            Settings.Global.getString(ctx.contentResolver, "device_name")
        }.getOrNull().orEmpty().ifBlank { "${Build.BRAND} ${Build.MODEL}" }
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.orEmpty()
        } else ""
        val appVer = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        // v0.23.0: SoC 平台识别（元信息层，不影响运行时采集路径）
        val pInfo = com.a41probe.monitor.data.PlatformDetector.get()
        return RemoteDeviceInfo(
            name = name,
            brand = Build.BRAND ?: "",
            model = Build.MODEL ?: "",
            device = Build.DEVICE ?: "",
            manufacturer = Build.MANUFACTURER ?: "",
            android = Build.VERSION.RELEASE ?: "",
            sdk = Build.VERSION.SDK_INT,
            kernel = System.getProperty("os.version") ?: "",
            soc = soc,
            appVer = appVer,
            platform = pInfo.platform.tag,
            socName = pInfo.socName ?: "",
        )
    }

    fun privOf(s: com.a41probe.monitor.data.Snapshot): PrivLevel = when {
        s.rootAvailable -> PrivLevel.ROOT
        s.shizukuActive -> PrivLevel.SHIZUKU
        else -> PrivLevel.FREE
    }

    fun encodeHello(info: RemoteDeviceInfo): String {
        val o = JSONObject()
        o.put("type", "hello")
        o.put("device", deviceJson(info))
        return o.toString()
    }

    fun encodeBye(): String = JSONObject().put("type", "bye").toString()

    fun encodeSnapshot(s: com.a41probe.monitor.data.Snapshot): String {
        val priv = privOf(s)
        val root = JSONObject()
        root.put("type", "snapshot")
        root.put("ts", s.ts)
        root.put("priv", priv.tag)

        // CPU
        val cpu = JSONObject()
        putD(cpu, "total", s.cpuTotalPercent)
        val cores = s.cores
        val busy = s.corePercent
        cpu.put("coreBusy", buildList {
            for (i in cores.indices) add(busy.getOrNull(i))
        }.fold(JSONArrayCompat()) { arr, v -> arr.put(v ?: JSONObject.NULL) })
        cpu.put("freqs", cores.fold(JSONArrayCompat()) { arr, c ->
            arr.put(c.freqMHz ?: JSONObject.NULL) })
        cpu.put("maxFreqs", cores.fold(JSONArrayCompat()) { arr, c ->
            arr.put(c.maxMHz ?: JSONObject.NULL) })
        cpu.put("clusters", cores.fold(JSONArrayCompat()) { arr, c ->
            arr.put(c.cluster) })
        putS(cpu, "governor", s.governor)
        root.put("cpu", cpu)

        // GPU
        val gpu = JSONObject()
        putI(gpu, "busy", s.gpu.busyPercent)
        putI(gpu, "freq", s.gpu.freqMHz)
        putD(gpu, "temp", s.gpu.temp0C)
        root.put("gpu", gpu)

        // Thermal HAL
        val th = JSONObject()
        putI(th, "status", s.thermalStatus)
        th.put("hal", s.halTemps.fold(JSONArrayCompat()) { arr, h ->
            val ho = JSONObject()
            ho.put("name", h.name)
            ho.put("type", h.type)
            putD(ho, "temp", h.tempC)
            arr.put(ho)
        })
        root.put("thermal", th)

        // Cooling
        root.put("cooling", s.cooling.fold(JSONArrayCompat()) { arr, c ->
            val co = JSONObject()
            co.put("name", c.name)
            co.put("cur", c.cur ?: 0)
            co.put("max", c.max ?: 0)
            arr.put(co)
        })

        // Battery（全部标准值）
        val b = s.battery
        val bat = JSONObject()
        putI(bat, "capacity", b.capacity)
        putD(bat, "voltage", b.voltage)
        putD(bat, "current", b.currentDisplay)
        putD(bat, "power", b.powerShown)
        putD(bat, "temp", b.tempC)
        putS(bat, "status", b.status)
        putD(bat, "healthPercent", b.healthPercent)
        putI(bat, "cycle", b.cycleCount)
        putS(bat, "tech", b.tech)
        putS(bat, "chargeMode", b.chargeMode)
        putD(bat, "inputVolt", b.inputVoltV)
        putD(bat, "inputCur", b.inputCurA)
        putD(bat, "inputPower", b.inputPowerW)
        putD(bat, "negotVolt", b.maxChargingVoltageUv?.let { it / 1_000_000.0 })
        putD(bat, "negotCur", b.maxChargingCurrentUa?.let { it / 1_000_000.0 })
        putD(bat, "voltageMax", b.voltageMaxV)
        putI(bat, "chargeCounter", b.chargeCounterUah?.let { (it / 1000).toInt() })
        putI(bat, "chargeControlLimit", b.chargeControlLimit)
        putI(bat, "chargeControlLimitMax", b.chargeControlLimitMax)
        root.put("battery", bat)

        // Memory
        val m = s.mem
        val mem = JSONObject()
        if (m != null) {
            putD(mem, "totalGB", m.totalGB)
            putD(mem, "availGB", m.availGB)
            mem.put("usedPercent", m.usedPercent)
            putD(mem, "swapUsedGB", m.swapUsedGB)
            putD(mem, "swapTotalGB", m.swapTotalGB)
        }
        root.put("mem", mem)

        return root.toString()
    }

    private fun deviceJson(info: RemoteDeviceInfo): JSONObject {
        val d = JSONObject()
        d.put("name", info.name)
        d.put("brand", info.brand)
        d.put("model", info.model)
        d.put("device", info.device)
        d.put("manufacturer", info.manufacturer)
        d.put("android", info.android)
        d.put("sdk", info.sdk)
        d.put("kernel", info.kernel)
        d.put("soc", info.soc)
        d.put("appVer", info.appVer)
        d.put("priv", info.priv.tag)
        // v0.23.0: 平台识别新字段（旧版监控端忽略未知 key，向后兼容）
        if (info.platform.isNotBlank()) d.put("platform", info.platform)
        if (info.socName.isNotBlank()) d.put("socName", info.socName)
        return d
    }

    private fun putD(o: JSONObject, k: String, v: Double?) {
        if (v == null) o.put(k, JSONObject.NULL) else o.put(k, v)
    }
    private fun putI(o: JSONObject, k: String, v: Int?) {
        if (v == null) o.put(k, JSONObject.NULL) else o.put(k, v)
    }
    private fun putS(o: JSONObject, k: String, v: String?) {
        if (v == null) o.put(k, JSONObject.NULL) else o.put(k, v)
    }
}

/** 轻量别名，避免在泛型 fold 里写全限定 org.json.JSONArray。 */
private typealias JSONArrayCompat = org.json.JSONArray
