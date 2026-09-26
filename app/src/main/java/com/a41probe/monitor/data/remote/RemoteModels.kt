package com.a41probe.monitor.data.remote

import org.json.JSONObject

/** 被监控端采集权限档（与本机 App 三档同口径）。 */
enum class PrivLevel(val tag: String, val label: String) {
    FREE("free", "免提权"),
    SHIZUKU("shizuku", "Shizuku"),
    ROOT("root", "Root");

    companion object {
        fun from(tag: String?): PrivLevel =
            entries.firstOrNull { it.tag == tag } ?: FREE
    }
}

/** 监控端到单台设备的连接状态机状态。 */
enum class ConnState { DISCOVERED, CONNECTING, ONLINE, RETRYING, OFFLINE }

/** 被监控端设备身份（hello 帧），全部真实读取、不写死。 */
data class RemoteDeviceInfo(
    val name: String = "",
    val brand: String = "",
    val model: String = "",
    val device: String = "",
    val manufacturer: String = "",
    val android: String = "",
    val sdk: Int = 0,
    val kernel: String = "",
    val soc: String = "",
    val appVer: String = "",
    val priv: PrivLevel = PrivLevel.FREE,
    /** v0.23.0: SoC 平台类型 tag（qcom/mtk/exynos/hisilicon/tensor/samsung/unknown），旧版忽略 */
    val platform: String = "",
    /** v0.23.0: 商用 SoC 名（如 "Snapdragon 8 Gen 1"），旧版忽略 */
    val socName: String = "",
)

data class RemoteHalTemp(val name: String, val type: Int, val tempC: Double?)
data class RemoteCooling(val name: String, val cur: Int, val max: Int) {
    val active: Boolean get() = cur > 0
}

/** 远端电池（全部为被监控端换算后的标准值；缺权限项为 null，UI 置灰，不伪造）。 */
data class RemoteBattery(
    val capacity: Int? = null,
    val voltage: Double? = null,
    val currentA: Double? = null,
    val powerW: Double? = null,
    val tempC: Double? = null,
    val status: String? = null,
    val healthPercent: Double? = null,
    val cycle: Int? = null,
    val tech: String? = null,
    val chargeMode: String? = null,
    val inputVoltV: Double? = null,
    val inputCurA: Double? = null,
    val inputPowerW: Double? = null,
    val negotVoltV: Double? = null,
    val negotCurA: Double? = null,
    val voltageMaxV: Double? = null,
    val chargeCounterMah: Int? = null,
    val chargeControlLimit: Int? = null,
    val chargeControlLimitMax: Int? = null,
)

data class RemoteMem(
    val totalGB: Double = 0.0,
    val availGB: Double = 0.0,
    val usedPercent: Int = 0,
    val swapUsedGB: Double = 0.0,
    val swapTotalGB: Double = 0.0,
)

/** 一帧远端快照（已换算的标准值）。 */
data class RemoteSnapshot(
    val ts: Long,
    val priv: PrivLevel,
    val cpuTotal: Double?,
    val coreBusy: List<Int?>,
    val freqs: List<Int?>,
    val maxFreqs: List<Int?>,
    val clusters: List<String>,
    val governor: String?,
    val gpuBusy: Int?,
    val gpuFreq: Int?,
    val gpuTemp: Double?,
    val thermalStatus: Int?,
    val hal: List<RemoteHalTemp>,
    val cooling: List<RemoteCooling>,
    val battery: RemoteBattery,
    val mem: RemoteMem,
)

/** 网络消息（decode 结果）。 */
sealed class RemoteMessage {
    data class Hello(val info: RemoteDeviceInfo) : RemoteMessage()
    data class Snapshot(val snap: RemoteSnapshot) : RemoteMessage()
    data object Bye : RemoteMessage()
    data object Unknown : RemoteMessage()

    companion object {
        fun parse(json: String): RemoteMessage {
            val o = JSONObject(json)
            return when (o.optString("type")) {
                "hello" -> Hello(parseInfo(o.optJSONObject("device") ?: JSONObject()))
                "snapshot" -> Snapshot(parseSnapshot(o))
                "bye" -> Bye
                else -> Unknown
            }
        }

        private fun parseInfo(d: JSONObject): RemoteDeviceInfo = RemoteDeviceInfo(
            name = d.optString("name"),
            brand = d.optString("brand"),
            model = d.optString("model"),
            device = d.optString("device"),
            manufacturer = d.optString("manufacturer"),
            android = d.optString("android"),
            sdk = d.optInt("sdk", 0),
            kernel = d.optString("kernel"),
            soc = d.optString("soc"),
            appVer = d.optString("appVer"),
            priv = PrivLevel.from(d.optString("priv")),
            // v0.23.0: 新字段 opt 解码，旧版 App 无此 key 时为空串（向后兼容）
            platform = if (d.isNull("platform")) "" else d.optString("platform"),
            socName = if (d.isNull("socName")) "" else d.optString("socName"),
        )

        private fun parseSnapshot(o: JSONObject): RemoteSnapshot {
            val cpu = o.optJSONObject("cpu")
            val gpu = o.optJSONObject("gpu")
            val th = o.optJSONObject("thermal")
            val bat = o.optJSONObject("battery") ?: JSONObject()
            val mem = o.optJSONObject("mem")

            fun intList(key: String): List<Int?> {
                val arr = cpu?.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).map {
                    if (arr.isNull(it)) null else arr.optInt(it)
                }
            }
            fun strList(key: String): List<String> {
                val arr = cpu?.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).map { arr.optString(it) }
            }
            val halArr = th?.optJSONArray("hal")
            // P2-2: 元素非对象时跳过该传感器，不拖垮整帧
            val hal = if (halArr == null) emptyList() else (0 until halArr.length()).mapNotNull {
                val h = halArr.optJSONObject(it) ?: return@mapNotNull null
                RemoteHalTemp(
                    name = h.optString("name"),
                    type = h.optInt("type", -1),
                    tempC = if (h.isNull("temp")) null else h.optDouble("temp"),
                )
            }
            val coolArr = o.optJSONArray("cooling")
            val cooling = if (coolArr == null) emptyList() else (0 until coolArr.length()).mapNotNull {
                val c = coolArr.optJSONObject(it) ?: return@mapNotNull null
                RemoteCooling(c.optString("name"), c.optInt("cur", 0), c.optInt("max", 0))
            }
            fun d(j: JSONObject?, k: String): Double? =
                if (j == null || j.isNull(k)) null else j.optDouble(k)
            fun i(j: JSONObject?, k: String): Int? =
                if (j == null || j.isNull(k)) null else j.optInt(k)

            val rb = RemoteBattery(
                capacity = i(bat, "capacity"),
                voltage = d(bat, "voltage"),
                currentA = d(bat, "current"),
                powerW = d(bat, "power"),
                tempC = d(bat, "temp"),
                status = if (bat.isNull("status")) null else bat.optString("status"),
                healthPercent = d(bat, "healthPercent"),
                cycle = i(bat, "cycle"),
                tech = if (bat.isNull("tech")) null else bat.optString("tech"),
                chargeMode = if (bat.isNull("chargeMode")) null else bat.optString("chargeMode"),
                inputVoltV = d(bat, "inputVolt"),
                inputCurA = d(bat, "inputCur"),
                inputPowerW = d(bat, "inputPower"),
                negotVoltV = d(bat, "negotVolt"),
                negotCurA = d(bat, "negotCur"),
                voltageMaxV = d(bat, "voltageMax"),
                chargeCounterMah = i(bat, "chargeCounter"),
                chargeControlLimit = i(bat, "chargeControlLimit"),
                chargeControlLimitMax = i(bat, "chargeControlLimitMax"),
            )
            val rm = RemoteMem(
                totalGB = d(mem, "totalGB") ?: 0.0,
                availGB = d(mem, "availGB") ?: 0.0,
                usedPercent = i(mem, "usedPercent") ?: 0,
                swapUsedGB = d(mem, "swapUsedGB") ?: 0.0,
                swapTotalGB = d(mem, "swapTotalGB") ?: 0.0,
            )
            return RemoteSnapshot(
                ts = o.optLong("ts", System.currentTimeMillis()),
                priv = PrivLevel.from(o.optString("priv")),
                cpuTotal = d(cpu, "total"),
                coreBusy = intList("coreBusy"),
                freqs = intList("freqs"),
                maxFreqs = intList("maxFreqs"),
                clusters = strList("clusters"),
                governor = if (cpu == null || cpu.isNull("governor")) null else cpu.optString("governor"),
                gpuBusy = i(gpu, "busy"),
                gpuFreq = i(gpu, "freq"),
                gpuTemp = d(gpu, "temp"),
                thermalStatus = i(th, "status"),
                hal = hal,
                cooling = cooling,
                battery = rb,
                mem = rm,
            )
        }
    }
}
