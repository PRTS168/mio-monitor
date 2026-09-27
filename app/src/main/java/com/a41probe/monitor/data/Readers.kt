package com.a41probe.monitor.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import com.a41probe.monitor.BuildConfig
import com.a41probe.monitor.ui.theme.DataState
import com.a41probe.monitor.ui.theme.Priv
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "MioBridge"

// M18: 顶层正则，避免热路径反复编译
private val RE_DIGITS = Regex("\\d+")
private val RE_WS = Regex("\\s+")
private val RE_MEMTOTAL = Regex("""MemTotal:\s+(\d+) kB""")

/** 低层 sysfs / proc 读取：App 域直读，失败返回 null */
object Sysfs {
    fun read(path: String): String? = try {
        File(path).readText().trim()
    } catch (t: Throwable) {
        null
    }

    fun readInt(path: String): Int? = read(path)?.toIntOrNull()
    fun readLong(path: String): Long? = read(path)?.toLongOrNull()
    fun readDouble(path: String): Double? = read(path)?.toDoubleOrNull()
}

/** Shizuku 桥：ping → 授权 → shell 执行（12.2.0 公开 API；超时 + stderr 排空 + 进程销毁） */
object ShizukuBridge {

    @Volatile var active: Boolean = false; private set
    @Volatile var authorized: Boolean = false; private set
    // v0.27.1: 探测失败连续计数——瞬时 ping 失败（Doze 唤醒/系统抖动）不立即翻转 active，
    // 连续 2 次失败才判定掉线，避免"切前台闪需要授权"
    @Volatile private var failStreak = 0
    // 收紧超时：shell 慢则快速降级置灰，避免 UI "滞后 3s+" 的卡顿感知
    private const val EXEC_TIMEOUT_MS = 2500L
    // M4: 有界守护线程池，防止无界增长拖住进程退出
    private val EXEC_POOL = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "a41-exec").apply { isDaemon = true }
    }
    // S2: 存活进程表，超时路径直接 destroy（关闭管道 → 阻塞 read 立即 EOF）
    private val liveProcs = java.util.concurrent.ConcurrentHashMap.newKeySet<ShizukuRemoteProcess>()

    fun refresh(): Boolean {
        val ping = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (ping) {
            active = true
            authorized = runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            failStreak = 0
        } else {
            // 瞬时探测失败：保留旧状态（active/authorized 不动），连续 2 次失败才翻转
            if (active) failStreak++
            else failStreak = 0
            if (failStreak >= 2) active = false
        }
        return active
    }

    /** 发起授权请求（在主线程调用）；用户拒绝后状态不变 */
    fun requestPermission() {
        if (!active || authorized) return
        try {
            Shizuku.requestPermission(7001)
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "requestPermission failed", t)
        }
    }

    /** 执行一条 shell 命令（shell 域），带超时；返回输出；失败/超时返回 null */
    fun exec(cmd: String): String? {
        if (!active || !authorized) return null
        // v20.12(S1-6): per-call 持有自身进程，超时只销毁自己，不误杀并发 exec
        val holder = java.util.concurrent.atomic.AtomicReference<ShizukuRemoteProcess?>()
        val future = EXEC_POOL.submit(java.util.concurrent.Callable<String?> { runExec(cmd, holder) })
        return try {
            future.get(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            future.cancel(true)
            // S2: 只销毁当前超时的进程（关闭管道 → 阻塞 read 立即 EOF）
            holder.get()?.let { runCatching { it.destroy() } }
            if (BuildConfig.DEBUG) Log.w(TAG, "exec timeout: $cmd")
            null
        }
    }

    private fun runExec(cmd: String, holder: java.util.concurrent.atomic.AtomicReference<ShizukuRemoteProcess?>): String? {
        if (!active || !authorized) return null
        var proc: ShizukuRemoteProcess? = null
        return try {
            proc = Shizuku.newProcess(arrayOf("/system/bin/sh", "-c", cmd), null, null)
                ?: return null
            holder.set(proc)
            liveProcs.add(proc)
            // S2: 并行排空 stderr，防止管道写满导致 readLine 永久阻塞
            val err = proc.errorStream
            Thread { runCatching { err.bufferedReader().forEachLine { } } }
                .apply { isDaemon = true }.start()
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                var l: String?
                while (r.readLine().also { l = it } != null) sb.append(l).append('\n')
            }
            sb.toString().trim()
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "exec failed: $cmd", t)
            null
        } finally {
            if (proc != null) {
                liveProcs.remove(proc)
                runCatching { proc.destroy() }
                    .onFailure { if (BuildConfig.DEBUG) Log.w(TAG, "destroy failed", it) }
            }
        }
    }
}

/** Root 桥（v20.11）：真实探测 su 可用性 + root shell 执行通道。
 *  探测：`su -c id -u` 判 uid==0（纯只读，不做任何系统修改）。
 *  执行：su -c cmd，超时 + stderr 排空 + 进程销毁，与 ShizukuBridge 同构。
 *  只读监控原则：root 仅用于读取受保护节点（GPU 频率 / cpu0 max / 背光等），不写任何系统状态。 */
object RootBridge {

    @Volatile var available: Boolean = false; private set
    // v20.12(S0-1): 运行期失权检测——bulk 连续失败达阈值即标记不可用并异步重探测
    @Volatile private var failStreak = 0
    private const val FAIL_LIMIT = 5
    private const val EXEC_TIMEOUT_MS = 2500L

    /** bulk 读取成功 → 通道健康，清零失败计数 */
    fun noteExecOk() { failStreak = 0 }

    /** bulk 读取失败 → 累计；达阈值判定失权：置不可用 + 异步重探测（不阻塞采样循环） */
    fun noteExecFail() {
        failStreak++
        if (failStreak >= FAIL_LIMIT) {
            failStreak = 0
            available = false
            Thread { refresh() }.apply { isDaemon = true }.start()
        }
    }
    private val EXEC_POOL = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "a41-root").apply { isDaemon = true }
    }
    // 存活进程表，超时路径直接 destroy（关闭管道 → 阻塞 read 立即 EOF）
    private val liveProcs = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()

    fun refresh() {
        var p: Process? = null
        available = try {
            p = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            // v20.12(S1-1): 先 waitFor 再读行——su 授权弹窗期间 readLine 会无界阻塞，
            // waitFor 超时销毁管道后 readLine 立即 EOF，探测线程不再被卡死
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                false
            } else {
                p.inputStream.bufferedReader().use { it.readLine() }?.trim() == "0"
            }
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { p?.destroy() }
        }
    }

    /** 执行一条 root shell 命令（su -c），带超时；返回输出；失败/超时返回 null */
    fun exec(cmd: String): String? {
        if (!available) return null
        // v20.12(S1-6): per-call 持有自身进程，超时只销毁自己，不误杀并发 exec
        val holder = java.util.concurrent.atomic.AtomicReference<Process?>()
        val future = EXEC_POOL.submit(java.util.concurrent.Callable<String?> { runExec(cmd, holder) })
        return try {
            future.get(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            future.cancel(true)
            holder.get()?.let { runCatching { it.destroyForcibly() } }
            if (BuildConfig.DEBUG) Log.w(TAG, "root exec timeout: $cmd")
            null
        }
    }

    private fun runExec(cmd: String, holder: java.util.concurrent.atomic.AtomicReference<Process?>): String? {
        if (!available) return null
        var proc: Process? = null
        return try {
            // v20.12(S2-4): stderr 与 stdout 合并（命令均 2>/dev/null），省一次排空线程
            proc = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            holder.set(proc)
            liveProcs.add(proc)
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                var l: String?
                while (r.readLine().also { l = it } != null) sb.append(l).append('\n')
            }
            sb.toString().trim()
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "root exec failed: $cmd", t)
            null
        } finally {
            if (proc != null) {
                liveProcs.remove(proc)
                runCatching { proc.destroy() }
            }
        }
    }
}

/** 电池：sysfs 全套 + BatteryManager + 系统广播双口径；特征值低频刷新（M20） */
object BatteryReader {

    // M20: charge_full/design/cycle/health/tech 是天级变化，启动读一次后 5 分钟刷新
    @Volatile private var featureAt = 0L
    @Volatile private var full: Int? = null
    @Volatile private var design: Int? = null
    @Volatile private var fullRaw: Long? = null
    @Volatile private var designRaw: Long? = null
    @Volatile private var cycle: Int? = null
    @Volatile private var health: String? = null
    @Volatile private var tech: String? = null

    private fun refreshFeatures() {
        val now = System.currentTimeMillis()
        if (now - featureAt < 300_000 && full != null) return
        featureAt = now
        // charge_full / design 单位 μAh，换算 mAh（健康度比率不受影响）；v20.21 同时留 raw µAh
        fullRaw = Sysfs.readLong("/sys/class/power_supply/battery/charge_full")
        designRaw = Sysfs.readLong("/sys/class/power_supply/battery/charge_full_design")
        full = fullRaw?.div(1000)?.toInt()
        design = designRaw?.div(1000)?.toInt()
        cycle = Sysfs.readInt("/sys/class/power_supply/battery/cycle_count")
        health = Sysfs.read("/sys/class/power_supply/battery/health")
        tech = Sysfs.read("/sys/class/power_supply/battery/technology")
    }

    fun read(context: Context, priv: ((String) -> String?)?, rootOn: Boolean, psuOverride: List<PsuLine>? = null): BatteryData {
        val cap = Sysfs.readInt("/sys/class/power_supply/battery/capacity")
        val status = Sysfs.read("/sys/class/power_supply/battery/status")
        val voltUv = Sysfs.readLong("/sys/class/power_supply/battery/voltage_now")
        val curUa = Sysfs.readLong("/sys/class/power_supply/battery/current_now")
        val powerUw = Sysfs.readLong("/sys/class/power_supply/battery/power_now")
        val tempRaw = Sysfs.readInt("/sys/class/power_supply/battery/temp")
        val chargeType = Sysfs.read("/sys/class/power_supply/battery/charge_type")
        // v20.24: uevent 一次性自洽快照（V/I 同时刻），优先于逐个 cat（有毫秒级时间差）
        val ue = readUevent()
        // P2-3: V/I 必须同时刻——仅当 uevent 两字段都命中才整体采用，否则整体回退逐节点 cat
        val ueComplete = ue.voltUv != null && ue.curUa != null
        val mainVoltUv = if (ueComplete) ue.voltUv else voltUv
        val mainCurUa = if (ueComplete) ue.curUa else curUa
        refreshFeatures()
        // 适配器输入侧功率（充电器实际输出）：usb / ucsi-source / adapter 多路径回退，
        // v20.12(S1-4): Root 通道 su 补读（App 域 EACCES、Shizuku 同样 EACCES，仅 Root 可读）
        val input = readAdapterInput(priv, psuOverride)

        // BatteryManager 双口径
        var tempBm: Double? = null
        var tempBmRaw: Int? = null
        var bmStatus: String? = null
        var maxChargingCurUa: Long? = null
        var maxChargingVolUv: Long? = null
        // v0.28.1: 标准广播完整兜底——vivo/OriginOS 等 SELinux 严格机型即使 sysfs 电池目录
        // 全被挡，电量/电压/温度/健康/技术仍可从 ACTION_BATTERY_CHANGED 读出（免提权）
        var bmLevel: Int? = null
        var bmVoltMv: Int? = null
        var bmHealth: String? = null
        var bmTech: String? = null
        runCatching {
            val bm = context.getSystemService(BatteryManager::class.java)
            if (bm != null) {
                val bi = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (bi != null) {
                    // v0.26.7: 免提权状态兜底（sysfs status 不可读时仍能正确识别充满等状态）
                    bmStatus = when (bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                        BatteryManager.BATTERY_STATUS_FULL -> "Full"
                        else -> null
                    }
                    val t = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                    if (t != -1) {
                        tempBmRaw = t
                        tempBm = t / 10.0
                    }
                    // 电量百分比：EXTRA_LEVEL / EXTRA_SCALE（scale 通常 100）
                    val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (lvl >= 0 && scale > 0) {
                        bmLevel = (lvl * 100 / scale)
                    }
                    // 电压：ACTION_BATTERY_CHANGED 的 "voltage" extra，单位 mV
                    (bi.extras?.get("voltage") as? Number)?.toInt()
                        ?.let { if (it > 0) bmVoltMv = it }
                    // 技术
                    bi.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
                        ?.takeIf { it.isNotBlank() }?.let { bmTech = it }
                    // 健康：int 常量 → 与 sysfs health 同口径字符串
                    bmHealth = when (bi.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Unspecified failure"
                        BatteryManager.BATTERY_HEALTH_UNKNOWN -> "Unknown"
                        else -> null
                    }
                    // v20.24: 协商充电上限（µA/µV），免提权可读
                    // 修复：固件可能以 int 或 long 写入。getLongExtra 遇到 int 存储会刷
                    // "W Bundle Attempt to cast..." 且静默返回默认值（协商值读不到）。
                    // 直接从 extras Bundle 取原始 Number 再 toLong()，int/long 皆兼容、无噪音。
                    (bi.extras?.get("max_charging_current") as? Number)?.toLong()
                        ?.let { if (it > 0) maxChargingCurUa = it }
                    (bi.extras?.get("max_charging_voltage") as? Number)?.toLong()
                        ?.let { if (it > 0) maxChargingVolUv = it }
                }
            }
        }

        val finalCap = cap ?: bmLevel
        val state = if (finalCap == null) DataState.UNAVAILABLE else DataState.OK
        return BatteryData(
            capacity = finalCap,
            // v0.28.2: 构造时物理范围钳制（脏节点超界→null，不污染 UI/曲线；RAW 字段仍存原值）
            voltage = Units.safeVoltage(mainVoltUv?.div(1_000_000.0) ?: bmVoltMv?.div(1000.0)),
            currentA = Units.safeCurrent(mainCurUa?.div(1_000_000.0)),
            powerW = powerUw?.div(1_000_000.0)?.takeIf { kotlin.math.abs(it) <= 150.0 },
            tempC = Units.safeTemp(tempRaw?.div(10.0) ?: tempBm),
            tempBmC = tempBm,
            tempBmRaw = tempBmRaw,
            chargeFull = full,
            chargeDesign = design,
            cycleCount = cycle,
            health = health ?: bmHealth,
            status = status ?: bmStatus,
            tech = tech ?: bmTech,
            chargeType = chargeType,
            inputVoltV = input?.voltV,
            inputCurA = input?.curA,
            inputVoltUv = input?.voltUv,
            inputCurUa = input?.curUa,
            voltageNowUv = mainVoltUv,
            currentNowUa = mainCurUa,
            powerNowUw = powerUw,
            tempRaw = tempRaw,
            chargeFullUah = fullRaw,
            chargeDesignUah = designRaw,
            voltageOcvV = ue.ocvUv?.div(1_000_000.0),
            voltageMaxV = ue.vmaxUv?.div(1_000_000.0),
            chargeCounterUah = ue.chargeCounterUah,
            chargeControlLimit = ue.ctrlLimit,
            chargeControlLimitMax = ue.ctrlLimitMax,
            maxChargingCurrentUa = maxChargingCurUa,
            maxChargingVoltageUv = maxChargingVolUv,
            ueventUsed = ue.voltUv != null && ue.curUa != null,
            state = state,
        )
    }

    /** v20.24: uevent 快照字段 */
    private data class Uevent(
        val voltUv: Long?, val curUa: Long?, val ocvUv: Long?, val vmaxUv: Long?,
        val chargeCounterUah: Long?, val ctrlLimit: Int?, val ctrlLimitMax: Int?,
    )

    /** 读 battery/uevent 一次，解析同时刻 V/I 及 OCV/VMAX/counter/限流档 */
    private fun readUevent(): Uevent {
        val raw = Sysfs.read("/sys/class/power_supply/battery/uevent")
        fun k(name: String): String? {
            val key = "POWER_SUPPLY_$name="
            return raw?.lineSequence()?.firstOrNull { it.startsWith(key) }?.removePrefix(key)?.trim()
        }
        return Uevent(
            voltUv = k("VOLTAGE_NOW")?.toLongOrNull(),
            curUa = k("CURRENT_NOW")?.toLongOrNull(),
            ocvUv = k("VOLTAGE_OCV")?.toLongOrNull(),
            vmaxUv = k("VOLTAGE_MAX")?.toLongOrNull(),
            chargeCounterUah = k("CHARGE_COUNTER")?.toLongOrNull(),
            ctrlLimit = k("CHARGE_CONTROL_LIMIT")?.toIntOrNull(),
            ctrlLimitMax = k("CHARGE_CONTROL_LIMIT_MAX")?.toIntOrNull(),
        )
    }

    /** v20.21: 输入侧读数（换算值 + 原始 µV/µA 一并返回，RAW 区留证） */
    private data class AdapterInput(val voltV: Double, val curA: Double, val voltUv: Long, val curUa: Long)

    /** 读取充电器输入侧 V×A（μV/μA→V/A），多路径回退；rootOn 时 su 补读；
     *  读不到=未提权或机型无此节点 */
    private fun readAdapterInput(priv: ((String) -> String?)?, psuOverride: List<PsuLine>?): AdapterInput? {
        // v0.27.0: 优先用 bulk 一次取回的 PsuLine（零额外 transact）；无 bulk（App 域/旧路径）再本地枚举
        val entries: List<PsuLine> = psuOverride ?: run {
            File("/sys/class/power_supply").listFiles()?.map { d ->
                fun rl(n: String) = priv?.invoke("cat ${d.path}/$n 2>/dev/null")?.trim()?.toLongOrNull()
                    ?: Sysfs.readLong("${d.path}/$n")
                PsuLine(d.name, Sysfs.read("${d.path}/type") ?: "",
                    rl("voltage_now"), rl("current_now"),
                    rl("input_voltage_now"), rl("input_current_now"))
            } ?: return null
        }
        for (e in entries) {
            val type = e.type
            if (type.equals("Battery", ignoreCase = true)) continue
            val isInput = type.contains("USB", true) || type.contains("DCP", true) ||
                type.contains("CDP", true) || type.contains("Wireless", true) || type.contains("Mains", true)
            if (!isInput) continue
            val vUv = e.vNow ?: e.ivNow
            val cUa = e.cNow ?: e.icNow
            if (vUv != null && cUa != null && vUv > 0 && cUa > 0) {
                // v0.23.0: 输入侧单位阈值归一化（电压 >100_000→µV÷1e6 否则 mV÷1e3；
                // 电流 >10_000→µA÷1e6 否则 mA÷1e3）。RAW 字段 voltUv/curUa 仍存原始值。
                val v = Units.voltageV(vUv)
                val c = Units.currentA(cUa)
                // v0.26.4: 物理范围校验——手机充电输入 V∈(0.5,25]、I∈(0,15]，
                // 厂商脏节点（实测 30V·5.2A→155.6W）跳过并回退下一输入节点
                if (v != null && c != null && v in 0.5..25.0 && c in 0.0..15.0)
                    return AdapterInput(v, c, vUv, cUa)
            }
        }
        return null
    }
}

/** CPU：核数按 possible 位图（M15）、频率 / 上限 / governor；簇 label 与颜色角色由 CpuTopologyDetector 动态探测 */
object CpuReader {

    // M15: 从 possible 位图解析核数，不写死 8
    private val possibleCores: List<Int> by lazy {
        val s = Sysfs.read("/sys/devices/system/cpu/possible") ?: "0-7"
        val parsed = s.split(",").flatMap { part ->
            val r = part.split("-")
            if (r.size == 2) {
                val a = r[0].toIntOrNull() ?: return@flatMap emptyList()
                val b = r[1].toIntOrNull() ?: return@flatMap emptyList()
                (a..b).toList()
            } else listOfNotNull(part.toIntOrNull())
        }
        // v0.28.2: 手机极限核数保护——异常固件 possible 位图 >16 核时截断到 16，
        // 避免对不存在的 cpuN 做空读（16 核已远超当前手机上限）
        if (parsed.size > 16) {
            if (BuildConfig.DEBUG) Log.w(TAG, "possibleCores=${parsed.size} exceeds phone limit 16, truncating")
            parsed.take(16)
        } else parsed
    }

    // M19: scaling_max_freq 接近 boot 恒定，60s 缓存一次
    private val maxCache = ConcurrentHashMap<Int, Int?>()
    private val maxRawCache = ConcurrentHashMap<Int, Int?>()
    private val maxCacheAt = ConcurrentHashMap<Int, Long>()

    fun read(priv: ((String) -> String?)?, rootOn: Boolean, curOverride: Map<Int, Int>? = null): List<CpuCore> = possibleCores.map { i ->
        val base = "/sys/devices/system/cpu/cpu$i/cpufreq"
        val now = System.currentTimeMillis()
        val max = if (now - (maxCacheAt[i] ?: 0L) > 60_000) {
            // v20.15: 读取链 ① scaling_max_freq（A710/X2 App 域可读）→
            // ② cpuinfo_max_freq（探针 444 权限 App 域全可读，静态硬件上限，
            //    覆盖 A510 scaling EACCES 的场景）→ ③ Root su 补读；
            // 每 60s 缓存一次，省 su fork
            var v = Sysfs.readInt("$base/scaling_max_freq")
            if (v == null) v = Sysfs.readInt("$base/cpuinfo_max_freq")
            if (v == null && priv != null) {
                v = priv("cat $base/scaling_max_freq 2>/dev/null")?.trim()?.toIntOrNull()
            }
            run {
                // v0.23.0: 单位阈值归一化（>100_000 视为 kHz÷1000，否则视为已 MHz）；
                // maxRawCache 仍存原始值，CSV RAW 区原样输出
                maxCache[i] = Units.cpuFreqMHz(v)
                maxRawCache[i] = v
                maxCacheAt[i] = now
                maxCache[i]   // 块返回 Int?（赋值语句返回 Unit，不写会让 max 推断成 Any?）
            }
        } else maxCache[i]
        var curKHz = curOverride?.get(i) ?: Sysfs.readInt("$base/scaling_cur_freq")
        // v0.24: App 域读不到当前频率时用提权补读（MTK/其他平台 shell 可读）
        if (curKHz == null && priv != null) {
            curKHz = priv("cat $base/scaling_cur_freq 2>/dev/null")?.trim()?.toIntOrNull()
        }
        CpuCore(
            index = i,
            cluster = CpuTopologyDetector.clusterLabelForCore(i),
            colorRole = CpuTopologyDetector.colorRoleForCore(i),
            // v0.23.0: 单位阈值归一化（>100_000 视为 kHz÷1000，否则视为已 MHz）；
            // freqKHz RAW 字段仍存原始值
            freqMHz = Units.cpuFreqMHz(curKHz),
            maxMHz = max,
            freqKHz = curKHz,
            maxKHz = maxRawCache[i],
        )
    }

    fun governor(): String? = Sysfs.read("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
}

/** GPU：占用（免提权 gpubusy 差分）、频率（仅 Root）、温度（Shizuku 批量读 gpuss zone） */
object GpuReader {

    // gpubusy 是"开机至今累计 busy/idle 周期"，直接取比率是累计平均，需两次采样差分
    // O4: 基线有意跨采样循环保留，进程内仅单循环使用
    private var lastBusy: Long? = null
    private var lastIdle: Long? = null
    private val gpuLock = Any()

    fun read(priv: ((String) -> String?)?, rootOn: Boolean): GpuData {
        // 主通道：gpu_busy_percentage（A41 App 域实测可读；输出可能为 "2" 或 "2 %"，提取首个整数）
        // M16: 强制 clamp 0..100，防驱动瞬态异常值污染曲线
        var busyPercent: Int? = Sysfs.read("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
            ?.let { RE_DIGITS.find(it)?.value?.toIntOrNull()?.coerceIn(0, 100) }
        // 备选通道：gpubusy 开机累计计数差分（无 gpu_busy_percentage 的机型使用）
        if (busyPercent == null) {
            val raw = Sysfs.read("/sys/class/kgsl/kgsl-3d0/gpubusy")
                ?.split(RE_WS)?.filter { it.isNotEmpty() }
            if (raw != null && raw.size >= 2) {
                val b = raw[0].toLongOrNull()
                val i = raw[1].toLongOrNull()
                if (b != null && i != null) {
                    synchronized(gpuLock) {
                        val pb = lastBusy
                        val pi = lastIdle
                        lastBusy = b
                        lastIdle = i
                        if (pb != null && pi != null && b >= pb && i >= pi) {
                            val dB = b - pb
                            val dI = i - pi
                            // M8(UX): 周期内无计数（GPU 深度休眠）返回 null → 曲线断点，
                            // 不再画贴底 0% 直线让用户误以为"GPU 真空闲/采样停了"
                            busyPercent = if (dB + dI > 0) (dB * 100.0 / (dB + dI)).toInt() else null
                        }
                        // 首次采样无基线 → 保持 null，由 UI 置灰
                    }
                }
            }
        }
        // 备选③：Mali（MTK/Exynos）/sys/kernel/gpu/gpu_busy 直接百分比整数
        if (busyPercent == null) {
            busyPercent = (Sysfs.read("/sys/kernel/gpu/gpu_busy")
                ?: priv?.invoke("cat /sys/kernel/gpu/gpu_busy 2>/dev/null"))
                ?.let { RE_DIGITS.find(it)?.value?.toIntOrNull()?.coerceIn(0, 100) }
        }
        // 备选④：MTK ged 模块 gpu_loading（百分比）
        if (busyPercent == null) {
            busyPercent = (Sysfs.read("/sys/module/ged/parameters/gpu_loading")
                ?: priv?.invoke("cat /sys/module/ged/parameters/gpu_loading 2>/dev/null"))
                ?.let { RE_DIGITS.find(it)?.value?.toIntOrNull()?.coerceIn(0, 100) }
        }
        // 备选⑤：老 MTK GED 模块 gpu_idle（空闲百分比 0-100），占用 = 100 - idle（老款无 gpu_loading）
        if (busyPercent == null) {
            val idle = (Sysfs.read("/sys/module/ged/parameters/gpu_idle")
                ?: priv?.invoke("cat /sys/module/ged/parameters/gpu_idle 2>/dev/null"))
                ?.let { RE_DIGITS.find(it)?.value?.toIntOrNull() }
            if (idle != null && idle in 0..100) busyPercent = (100 - idle).coerceIn(0, 100)
        }
        // 备选⑥：devfreq load 通道（Exynos/通用）——/sys/class/devfreq/*/load。
        // 兼容两种格式：单列=直接百分比；双列 "busy total"=busy/total 比率。
        if (busyPercent == null) {
            val gpuDev = File("/sys/class/devfreq").listFiles()
                ?.firstOrNull { gpuDevfreqName(it.name) }
            if (gpuDev != null) {
                val loadRaw = Sysfs.read("${gpuDev.path}/load")
                    ?: priv?.invoke("cat ${gpuDev.path}/load 2>/dev/null")
                val parts = loadRaw?.split(RE_WS)?.filter { it.isNotEmpty() }
                if (!parts.isNullOrEmpty()) {
                    busyPercent = when {
                        parts.size >= 2 -> {
                            val b = parts[0].toLongOrNull()
                            val t = parts[1].toLongOrNull()
                            if (b != null && t != null && t > 0)
                                (b * 100 / t).toInt().coerceIn(0, 100) else null
                        }
                        else -> parts[0].toIntOrNull()?.coerceIn(0, 100)
                    }
                }
            }
        }
        // 都缺则 busyPercent 保持 null，UI 置「–」
        // GPU 频率：节点命名随内核而异（gpuclk/gpu_clk/gpu_clock，单位多为 Hz 亦可能 kHz），
        // 多节点回退 + devfreq 枚举；全部 App 域可试读，读不到返回 null 由 UI 置灰
        // v20.21: gpuFreq 返回 GpuFreqResult（mhz + raw + 命中节点），raw/node 存入 GpuData 供 RAW 区
        val freqResult = gpuFreq(priv, rootOn)
        val freq = freqResult?.mhz
        // v0.23.0: 状态语义按平台修正——QCOM 平台但 kgsl 目录不存在=本平台无此节点(UNAVAILABLE)，
        // 即使未提权也不报 NEED_PRIV；QCOM 有 kgsl 但 App 域读不到=权限不够(NEED_PRIV)。
        val freqState = when {
            freq != null -> DataState.OK
            priv != null -> DataState.UNAVAILABLE
            PlatformDetector.get().platform == PlatformDetector.Platform.QCOM &&
                !File("/sys/class/kgsl").exists() -> DataState.UNAVAILABLE
            else -> DataState.NEED_PRIV
        }
        return GpuData(
            busyPercent = busyPercent,
            freqMHz = freq,
            freqState = freqState,
            freqRaw = freqResult?.raw,
            freqNode = freqResult?.node,
        )
    }

    /** GPU 频率回退链结果：换算后 MHz + 原始 raw + 命中节点路径 */
    private data class GpuFreqResult(val mhz: Int, val raw: Long, val node: String)

    /** GPU 频率回退链：kgsl 直读节点（Hz/kHz 兼容换算）→ devfreq 枚举（Hz）。
     *  v20.11: rootOn 时走 su 通道——该节点 App 域 EACCES、Shizuku shell 域同样 EACCES，仅 Root 可读。
     *  v20.21: 返回 GpuFreqResult（含 raw + 命中节点），供 RAW 区留证 */
    private fun gpuFreq(priv: ((String) -> String?)?, rootOn: Boolean): GpuFreqResult? {
        // ① Mali/MTK/Exynos：/sys/kernel/gpu/gpu_clock 单位就是 MHz（直接用，不÷1e6）
        val maliClock = "/sys/kernel/gpu/gpu_clock"
        run {
            val raw = (priv?.invoke("cat $maliClock 2>/dev/null")?.trim()?.toLongOrNull())
                ?: Sysfs.readLong(maliClock)
            if (raw != null && raw in 1..9999) return GpuFreqResult(raw.toInt(), raw, maliClock)
        }
        // ② 高通 kgsl 直读节点（Hz/kHz 自动换算）
        val nodes = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/gpu_clk",
        )
        for (p in nodes) {
            val raw = if (rootOn)
                RootBridge.exec("cat $p 2>/dev/null")?.trim()?.toLongOrNull()
            else Sysfs.readLong(p)
            if (raw != null) {
                val mhz = freqFromRaw(raw)
                if (mhz != null) return GpuFreqResult(mhz, raw, p)
            }
        }
        // ③ 老 MTK(4.9 内核) PowerVR 私有节点：均为 kHz，÷1000=MHz。
        //    GM9446(Helio P90/Reno Z) / GE8320(Helio P35/Y3s) 存在，Mali 机型读不到自然回退
        run {
            // /sys/kernel/ged/hal/current_freqency（驱动拼写 freqency 非 frequency），单位 kHz
            val gedClock = "/sys/kernel/ged/hal/current_freqency"
            val rawGed = (priv?.invoke("cat $gedClock 2>/dev/null")?.trim()?.toLongOrNull())
                ?: Sysfs.readLong(gedClock)
            if (rawGed != null) {
                val mhz = (rawGed / 1000).toInt()
                if (mhz in 1..9999) return GpuFreqResult(mhz, rawGed, gedClock)
            }
            // /proc/gpufreq/gpufreq_var_dump 多行文本：优先 cur/current 行，否则首个数字行（kHz）
            val varDump = "/proc/gpufreq/gpufreq_var_dump"
            val varText = priv?.invoke("cat $varDump 2>/dev/null") ?: Sysfs.read(varDump)
            val mhzVar = varText?.let { parseMtkGpufreqVar(it) }
            if (mhzVar != null) return GpuFreqResult(mhzVar, mhzVar.toLong() * 1000, varDump)
            // /proc/gpufreq/gpufreq_opp_dump OPP 表：取首个数字档（kHz）
            val oppDump = "/proc/gpufreq/gpufreq_opp_dump"
            val oppText = priv?.invoke("cat $oppDump 2>/dev/null") ?: Sysfs.read(oppDump)
            val mhzOpp = oppText?.let { parseMtkGpufreqOpp(it) }
            if (mhzOpp != null) return GpuFreqResult(mhzOpp, mhzOpp.toLong() * 1000, oppDump)
        }
        // devfreq：高通典型路径 3d00000.qcom,kgsl-3d0/cur_freq（Hz）
        if (priv != null) {
            val found = priv("ls /sys/class/devfreq 2>/dev/null")?.lines()?.asSequence()
                ?.filter { gpuDevfreqName(it) }
                ?.mapNotNull { d ->
                    val p = "/sys/class/devfreq/$d/cur_freq"
                    priv("cat $p 2>/dev/null")
                        ?.trim()?.toLongOrNull()?.let { raw ->
                            freqFromRaw(raw)?.let { GpuFreqResult(it, raw, p) }
                        }
                }?.firstOrNull()
            if (found != null) return found
            // P2-C2: shell 列目录失败不直接放弃，落 App 域 listFiles 兜底
        }
        val dir = File("/sys/class/devfreq")
        return dir.listFiles()?.asSequence()
            ?.filter { gpuDevfreqName(it.name) }
            ?.mapNotNull { d ->
                val p = "${d.path}/cur_freq"
                Sysfs.readLong(p)?.let { raw ->
                    freqFromRaw(raw)?.let { GpuFreqResult(it, raw, p) }
                }
            }
            ?.firstOrNull()
    }

    /** raw 量级归一化：≥1e7 视为 Hz（如 585_000_000→585）；≥1e3 视为 kHz（585_000→585；
     *  1_200_000→1200，修复 ≥1GHz kHz 值被误读为 1–2MHz）；<1e3 视为已 MHz。
     *  GPU 频率真实范围 ~200MHz–3GHz：Hz 表示 ≥2e8，kHz 表示 ≤3e6，1e7 阈值稳分。 */
    private fun freqFromRaw(raw: Long): Int? = when {
        raw >= 10_000_000 -> (raw / 1_000_000).toInt()
        raw >= 1_000 -> (raw / 1_000).toInt()
        else -> raw.toInt()
    }.takeIf { it in 1..9_999 }

    /** 老 MTK gpufreq_var_dump 多行文本：优先含 cur/current 的行，否则首个数字行，提取首个整数(kHz)→MHz */
    private fun parseMtkGpufreqVar(text: String): Int? {
        val curLine = text.lines().firstOrNull {
            it.contains("cur_freq", ignoreCase = true) || it.contains("current_freq", ignoreCase = true)
        }
        val src = curLine ?: text.lines().firstOrNull { RE_DIGITS.containsMatchIn(it) }
        return src?.let { RE_DIGITS.find(it)?.value?.toLongOrNull() }
            ?.let { (it / 1000).toInt() }?.takeIf { it in 1..9_999 }
    }

    /** 老 MTK gpufreq_opp_dump OPP 表：取首个含数字的行(档位频率 kHz)→MHz */
    private fun parseMtkGpufreqOpp(text: String): Int? =
        text.lines().firstOrNull { RE_DIGITS.containsMatchIn(it) }
            ?.let { RE_DIGITS.find(it)?.value?.toLongOrNull() }
            ?.let { (it / 1000).toInt() }?.takeIf { it in 1..9_999 }

    /** devfreq 目录名匹配 GPU 节点：高通 kgsl / Mali / PowerVR(PVR)/IMG / 通用 gpu/3d */
    private fun gpuDevfreqName(name: String): Boolean =
        name.contains("kgsl") || name.contains("mali") || name.contains("gpu") ||
            name.contains("3d") || name.contains("pvr") || name.contains("img") ||
            name.contains("powervr")
}

/** 热区：84 zone。App 域 EACCES → NEED_PRIV；Shizuku 激活时整表读取 */
object ThermalReader {

    /** 免提权通道：尝试直读所有 zone（A41 上 App 域全拒，用于判定状态） */
    fun readAppDomain(): List<ThermalZone> {
        val root = thermalRoot()
        val zones = zoneNames(root)
        return zones.map { z ->
            val v = Sysfs.readInt("$root/thermal_zone${z.first}/temp")
            // v0.28.2: 物理范围钳制 -40..150°C，超界 → tempC=null（占位 NEED_PRIV），raw m°C 仍留证
            val tc = Units.safeTemp(v?.div(1000.0))
            ThermalZone(z.first, z.second, tc,
                if (tc != null) DataState.OK else DataState.NEED_PRIV, Priv.SHIZUKU, tempMC = v)
        }
    }

    /** 热区根目录：优先 /sys/class/thermal；华为 EMUI 该路径 EACCES/空时回退 /sys/devices/virtual/thermal（结构相同） */
    private fun thermalRoot(): String {
        val primary = "/sys/class/thermal"
        val hasZones = File(primary).listFiles()?.any { it.name.startsWith("thermal_zone") } == true
        if (hasZones) return primary
        return "/sys/devices/virtual/thermal"
    }

    private fun zoneNames(root: String): List<Pair<Int, String>> {
        val dir = File(root)
        val zones = dir.listFiles()?.filter { it.name.startsWith("thermal_zone") } ?: emptyList()
        return zones.mapNotNull { z ->
            val id = z.name.removePrefix("thermal_zone").toIntOrNull() ?: return@mapNotNull null
            val name = Sysfs.read("${z.path}/type") ?: ""
            id to name
        }.sortedBy { it.first }
    }

    fun topN(zones: List<ThermalZone>, n: Int = 8): List<ThermalZone> =
        zones.filter { it.tempC != null && it.tempC > 0 }   // 负值=未使能占位，不参与排序
            .sortedByDescending { it.tempC }.take(n)
}

/** v0.27.0: power_supply 输入侧一行（bulk 一次取回，供 BatteryReader 解析，替代逐节点 priv cat） */
data class PsuLine(
    val dir: String,
    val type: String,
    val vNow: Long?,
    val cNow: Long?,
    val ivNow: Long?,
    val icNow: Long?,
)

/** Shizuku 批量采集（S6）：一次 shell 调用拿 热区 + loadavg + /proc/stat，每秒 fork 数 4→1。
 *  zone type 恒定只首读一次缓存，每秒命令只读 temp（cat 次数 ~170→~88），显著降低单次采样耗时。 */
object BulkReader {

    data class Bulk(
        val zones: List<ThermalZone>,
        val load: String?,
        val stat: String?,
        val coreStats: List<String> = emptyList(),
        val cooling: List<CoolingDev> = emptyList(),
        // v0.27.0: 8 核当前频率 kHz（一次 bulk，替代每核一次 transact）
        val cpuCurKHz: Map<Int, Int> = emptyMap(),
        // v0.27.0: power_supply 输入侧 V/I（一次 bulk，替代逐目录 priv cat）
        val psu: List<PsuLine> = emptyList(),
    )

    @Volatile private var zoneTypes: Map<Int, String>? = null
    @Volatile private var coolingMeta: Map<Int, Pair<String, Int>>? = null

    // v0.27.2: grep 多文件一次 fork（原循环 ~170 fork，首读 2s+）
    private val ZONE_TYPE_CMD =
        "grep -H \"\" /sys/class/thermal/thermal_zone*/type 2>/dev/null"
    private val COOLING_SCAN_CMD =
        "echo \"==TYPES==\"; grep -H \"\" /sys/class/thermal/cooling_device*/type 2>/dev/null; " +
        "echo \"==MAXS==\"; grep -H \"\" /sys/class/thermal/cooling_device*/max_state 2>/dev/null"

    // 关键热缓解部件白名单：模式匹配（多厂商 cooling_device type），只对这些每秒读 cur_state
    // （其余大量 modem/PA 255 级不每秒读）。高频项保留快速匹配，其余按 contains 模式。
    private val COOLING_QUICK = setOf(
        "gpu", "ufs", "battery", "cdsp", "cdsp_hw", "wlan",
    )

    private fun isCoolingRelevant(type: String): Boolean =
        type in COOLING_QUICK ||
            type.contains("thermal-cpufreq") || type.contains("thermal-devfreq") ||
            type.contains("mali") || type.contains("ddr") || type.contains("gpu") ||
            type.contains("display") || type.contains("panel") || type.contains("battery") ||
            type.contains("cdsp") || type.contains("ufs") || type.contains("wlan") ||
            type.contains("kgsl") ||
            type.contains("mtk-cpufreq") || type.contains("mtk-")

    /** PSU uevent 多行累积器：一次 cat uevent 拿全部键，替代 6 次逐文件 cat */
    private class MPsu(val dir: String, val type: String) {
        var vNow: Long? = null; var cNow: Long? = null
        var ivNow: Long? = null; var icNow: Long? = null
        fun consume(line: String) {
            val eq = line.indexOf('=')
            if (eq <= 0) return
            val key = line.substring(0, eq)
            val v = line.substring(eq + 1).trim().toLongOrNull() ?: return
            when (key) {
                "POWER_SUPPLY_VOLTAGE_NOW" -> vNow = v
                "POWER_SUPPLY_CURRENT_NOW" -> cNow = v
                "POWER_SUPPLY_INPUT_VOLTAGE_NOW" -> ivNow = v
                "POWER_SUPPLY_INPUT_CURRENT_NOW" -> icNow = v
            }
        }
        fun build() = PsuLine(dir, type, vNow, cNow, ivNow, icNow)
    }

    fun readShizuku(): Bulk? = readWith { ShizukuBridge.exec(it) }
    fun readRoot(): Bulk? = readWith { RootBridge.exec(it) }

    private fun readWith(exec: (String) -> String?): Bulk? {
        // 首读：zone type（恒定，缓存）
        if (zoneTypes == null) {
            val out = exec(ZONE_TYPE_CMD)
            val parsed = out?.lines()?.mapNotNull { l ->
                // /sys/class/thermal/thermal_zone12/type:cpu-thermal
                val id = l.substringAfter("thermal_zone").substringBefore("/").toIntOrNull()
                    ?: return@mapNotNull null
                val v = l.substringAfterLast(":").trim()
                if (v.isEmpty()) null else id to v
            }?.toMap()
            if (parsed.isNullOrEmpty()) return null
            zoneTypes = parsed
        }
        if (coolingMeta == null) {
            val out = exec(COOLING_SCAN_CMD)
            val tmap = HashMap<Int, String>()
            val mmax = HashMap<Int, Int>()
            var cm = 0
            out?.lines()?.forEach { l ->
                when {
                    l == "==TYPES==" -> cm = 1
                    l == "==MAXS==" -> cm = 2
                    cm == 1 -> {
                        val id = l.substringAfter("cooling_device").substringBefore("/")
                            .toIntOrNull() ?: return@forEach
                        val v = l.substringAfterLast(":").trim()
                        if (v.isNotEmpty()) tmap[id] = v
                    }
                    cm == 2 -> {
                        val id = l.substringAfter("cooling_device").substringBefore("/")
                            .toIntOrNull() ?: return@forEach
                        l.substringAfterLast(":").trim().toIntOrNull()?.let { mmax[id] = it }
                    }
                }
            }
            if (tmap.isNotEmpty()) {
                coolingMeta = tmap.mapValues { (id, t) -> t to (mmax[id] ?: -1) }
            }
        }
        val types = zoneTypes ?: return null
        val meta = coolingMeta ?: emptyMap()
        val coolIds = meta.filter { isCoolingRelevant(it.value.first) }.keys.sorted()

        // 每秒命令：zone temp + load + stat + 白名单 cooling cur（同一 shell 调用，不额外 fork）
        // v0.27.2: grep 合并多文件，fork 250+ → ~20，bulk 2.0s → 0.45s
        val sb = StringBuilder("{")
        sb.append(" grep -H \"\" /sys/class/thermal/thermal_zone*/temp 2>/dev/null;")
        sb.append(" echo \"==LOAD==\"; cat /proc/loadavg;")
        sb.append(" echo \"==STAT==\"; grep -E \"^cpu[0-9]* \" /proc/stat;")
        if (coolIds.isNotEmpty()) {
            sb.append(" echo \"==COOL==\";")
            for (cid in coolIds) {
                sb.append(" echo \"$cid|\$(cat /sys/class/thermal/cooling_device$cid/cur_state 2>/dev/null)\";")
            }
        }
        sb.append(" echo \"==CPUFREQ==\"; grep -H \"\" /sys/devices/system/cpu/cpu[0-9]/cpufreq/scaling_cur_freq /sys/devices/system/cpu/cpu[1-9][0-9]/cpufreq/scaling_cur_freq 2>/dev/null;")
        sb.append(" echo \"==PSU==\"; for d in /sys/class/power_supply/*; do echo \"\${d##*/}|\$(cat \$d/type 2>/dev/null)|\$(cat \$d/uevent 2>/dev/null)\"; done;")
        sb.append(" }")
        val out = exec(sb.toString()) ?: return null
        return parseBulk(out, types, meta, coolIds)
    }

    private fun parseBulk(
        out: String, types: Map<Int, String>,
        meta: Map<Int, Pair<String, Int>>, coolIds: List<Int>,
    ): Bulk? {
        val zones = mutableListOf<ThermalZone>()
        var load: String? = null
        var stat: String? = null
        val coreStats = mutableListOf<String>()
        val cooling = mutableListOf<CoolingDev>()
        val cpuCur = HashMap<Int, Int>()
        val psu = mutableListOf<PsuLine>()
        var mode = 1
        var curPsu: MPsu? = null
        out.lines().forEach { l ->
            when {
                l == "==LOAD==" -> mode = 2
                l == "==STAT==" -> mode = 3
                l == "==COOL==" -> mode = 4
                l == "==CPUFREQ==" -> mode = 5
                l == "==PSU==" -> mode = 6
                mode == 1 -> {
                    // /sys/class/thermal/thermal_zone12/temp:44500
                    val id = l.substringAfter("thermal_zone").substringBefore("/").toIntOrNull()
                    val v = l.substringAfterLast(":").trim().toIntOrNull()
                    val name = types[id]
                    if (id != null && name != null) {
                        // v0.28.2: 物理范围钳制 -40..150°C，超界 → tempC=null（UNAVAILABLE），raw m°C 仍留证
                        val tc = Units.safeTemp(v?.div(1000.0))
                        zones.add(ThermalZone(id, name, tc,
                            if (tc != null) DataState.OK else DataState.UNAVAILABLE,
                            Priv.SHIZUKU, tempMC = v))
                    }
                }
                mode == 2 -> if (l.isNotBlank()) load = l.trim()
                mode == 3 -> if (l.isNotBlank()) {
                    if (l.startsWith("cpu ")) stat = l.trim()
                    else if (l.startsWith("cpu")) coreStats.add(l.trim())
                }
                mode == 4 -> {
                    val p = l.split("|")
                    if (p.size >= 2) {
                        val id = p[0].toIntOrNull()
                        val cur = p[1].toIntOrNull()
                        val m = meta[id]
                        if (id != null && m != null) {
                            val mx = if (m.second >= 0) m.second else null
                            cooling.add(CoolingDev(id, m.first, cur, mx))
                        }
                    }
                }
                mode == 5 -> {
                    // /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq:1689600
                    val id = l.substringAfter("/cpu").substringBefore("/").toIntOrNull()
                    val v = l.substringAfterLast(":").trim().toIntOrNull()
                    if (id != null && v != null) cpuCur[id] = v
                }
                mode == 6 -> {
                    if (l.startsWith("POWER_SUPPLY_")) {
                        curPsu?.consume(l)
                    } else {
                        curPsu?.let { psu.add(it.build()) }
                        val p = l.split("|")
                        if (p.size >= 2) {
                            curPsu = MPsu(p[0], p[1])
                            p.drop(2).forEach { curPsu?.consume(it) }
                        }
                    }
                }
            }
        }
        curPsu?.let { psu.add(it.build()) }
        if (zones.isEmpty() && load == null && stat == null) return null
        return Bulk(zones.sortedBy { it.id }, load, stat, coreStats, cooling, cpuCur, psu)
    }
}

/** Thermal HAL 低频读取（dumpsys thermalservice，节流 5s）：官方热状态 / HAL 分类温度 / 阈值红线。
 *  不并入 1Hz 批量（dumpsys 较慢，会拖慢主采样）；纯字符串解析，无正则。 */
object ThermalHalReader {

    data class HalData(
        val status: Int?,
        val temps: List<HalTemp>,
        val thresholds: List<ThermalThreshold>,
    )

    @Volatile private var lastAt = 0L
    @Volatile private var cached: HalData? = null

    /** v0.27.0: 只读缓存（主采样热路径调用，不触发 dumpsys）；刷新由 ViewModel 独立协程负责 */
    fun cachedOnly(): HalData? = cached

    fun get(exec: (String) -> String?, force: Boolean = false): HalData? {
        val now = System.currentTimeMillis()
        if (!force && cached != null && now - lastAt < 5000) return cached
        // P1-1: out 为空/blank（dumpsys 偶发空、格式漂移）时不推进节流、不清空好缓存，下拍立即重试
        val out = exec("dumpsys thermalservice")
        if (out.isNullOrBlank()) return cached

        val status = out.lineSequence().mapNotNull { l ->
            val t = l.trim()
            if (t.startsWith("Thermal Status:"))
                t.removePrefix("Thermal Status:").trim().toIntOrNull() else null
        }.firstOrNull()

        // 仅解析 "Current temperatures from HAL:" 段（跳过 Cached 历史峰值，避免误读为当前）
        val temps = mutableListOf<HalTemp>()
        val curIdx = out.indexOf("Current temperatures from HAL:")
        val coolIdx = out.indexOf("Current cooling devices from HAL:")
        if (curIdx >= 0 && coolIdx > curIdx) {
            out.substring(curIdx, coolIdx).lineSequence().forEach { l ->
                if (l.contains("Temperature{mValue=")) parseTempLine(l)?.let { temps.add(it) }
            }
        }

        // 阈值（恒定）：hotThrottlingThresholds[3]=降频, [6]=关机；NaN→null
        val thresholds = mutableListOf<ThermalThreshold>()
        val thIdx = out.indexOf("Temperature static thresholds")
        if (thIdx >= 0) {
            out.substring(thIdx).lineSequence().forEach { l ->
                if (l.contains(".hotThrottlingThresholds ="))
                    parseThreshLine(l)?.let { thresholds.add(it) }
            }
        }

        // P1-1: 解析不出任何温度（格式漂移）且已有好缓存 → 保留旧值，不覆盖为空、不推进节流
        if (temps.isEmpty() && cached != null) return cached
        // 阈值/状态恒定：本次偶发解析丢失时沿用旧缓存，避免红线卡/徽章闪烁消失
        val finalThresholds = thresholds.ifEmpty { cached?.thresholds ?: thresholds }
        val finalStatus = status ?: cached?.status
        lastAt = now
        cached = HalData(finalStatus, temps, finalThresholds)
        return cached
    }

    private fun extract(s: String, key: String, end: String): String? {
        val i = s.indexOf(key)
        if (i < 0) return null
        val from = i + key.length
        val j = s.indexOf(end, from)
        return if (j < 0) null else s.substring(from, j)
    }

    // Temperature{mValue=34.9, mType=0, mName=CPU0, mStatus=0}
    private fun parseTempLine(line: String): HalTemp? {
        val mv = extract(line, "mValue=", ",") ?: return null
        val mt = extract(line, "mType=", ",") ?: return null
        val mn = extract(line, "mName=", ",") ?: return null
        val mvRaw = mv.trim()
        // P2-1: "NaN".toDoubleOrNull() 会返回 Double.NaN（合法解析），需显式转 null 防 UI 显示 NaN
        val temp = if (mvRaw == "NaN") null else mvRaw.toDoubleOrNull()
        return HalTemp(mn.trim(), mt.trim().toIntOrNull() ?: return null, temp)
    }

    // {.type = CPU, .name = CPU0, .hotThrottlingThresholds = [NaN,...,95.0,...,115.0], ...}
    private fun parseThreshLine(line: String): ThermalThreshold? {
        val type = extract(line, ".type = ", ",") ?: return null
        val name = extract(line, ".name = ", ",") ?: return null
        val arr = extract(line, ".hotThrottlingThresholds = [", "]") ?: return null
        val parts = arr.split(",").map { it.trim() }
        fun at(i: Int) = parts.getOrNull(i)?.let {
            if (it == "NaN" || it.isBlank()) null else it.toDoubleOrNull() }
        val th = at(3); val sh = at(6)
        if (th == null && sh == null) return null
        return ThermalThreshold(name.trim(), type.trim(), th, sh)
    }
}

/** /proc 摘要：loadavg（App 域可读）、meminfo（缓存，M21） */
object SysReader {

    // M21: MemTotal 运行期恒定，5 分钟缓存
    @Volatile private var memCache: Int? = null
    @Volatile private var memAt = 0L
    private val RE_MEM = Regex("^(MemTotal|MemFree|MemAvailable|Cached|SwapTotal|SwapFree):\\s+(\\d+)")

    fun memTotalMB(): Int? {
        val now = System.currentTimeMillis()
        if (memCache == null || now - memAt > 300_000) {
            memCache = Sysfs.read("/proc/meminfo")?.lineSequence()?.firstOrNull()?.let {
                RE_MEMTOTAL.find(it)?.groupValues?.get(1)?.toIntOrNull()?.div(1024)
            }
            memAt = now
        }
        return memCache
    }

    /** 内存摘要：实时读 /proc/meminfo 全字段（文件 ~2KB，1Hz 开销可接受）；缺 MemAvailable 用 Free 兜底 */
    fun memInfo(): MemInfo? {
        val raw = Sysfs.read("/proc/meminfo") ?: return null
        val m = HashMap<String, Long>()
        raw.lineSequence().forEach { l ->
            val mm = RE_MEM.find(l) ?: return@forEach
            m[mm.groupValues[1]] = mm.groupValues[2].toLongOrNull() ?: 0L
        }
        val t = m["MemTotal"] ?: return null
        return MemInfo(
            totalKB = t,
            freeKB = m["MemFree"] ?: 0L,
            availKB = m["MemAvailable"] ?: (m["MemFree"] ?: 0L),
            cachedKB = m["Cached"] ?: 0L,
            swapTotalKB = m["SwapTotal"] ?: 0L,
            swapFreeKB = m["SwapFree"] ?: 0L,
        )
    }

    fun loadAvgApp(): String? = Sysfs.read("/proc/loadavg")
}

/** 24h 容量趋势本地记录：60s 粒度 append，读取时按小时聚合（每小时取最后值） */
object CapacityHistory {
    private const val INTERVAL = 60_000L
    @Volatile private var lastWrite = 0L
    private var file: java.io.File? = null

    fun init(ctx: Context) { file = java.io.File(ctx.filesDir, "a41probe_cap.csv") }

    fun append(ts: Long, cap: Int?) {
        if (cap == null) return
        val now = System.currentTimeMillis()
        if (now - lastWrite < INTERVAL) return
        lastWrite = now
        runCatching {
            file?.appendText("$ts,$cap\n")
            // 防无界增长：超过 24h 数据量(1440)后裁剪到最近 1440 行
            val lines = file?.readLines()
            if (lines != null && lines.size > 2000) {
                file?.writeText(lines.takeLast(1440).joinToString("\n") + "\n")
            }
        }
    }

    /** 最近 24h 按小时聚合：List<(小时起点 ts, 该小时最后容量)>，升序 */
    fun last24h(): List<Pair<Long, Int>> {
        val f = file ?: return emptyList()
        // 只读尾部：文件最多 2000 行，24h 数据量 1440 行，避免全量解析
        val raw = runCatching { f.readLines().takeLast(1450) }.getOrElse { return emptyList() }
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        val byHour = LinkedHashMap<Long, Int>()
        raw.forEach { l ->
            val p = l.split(",")
            if (p.size == 2) {
                val t = p[0].toLongOrNull()
                val c = p[1].toIntOrNull()
                if (t != null && c != null && t >= cutoff) byHour[t / 3600_000L] = c
            }
        }
        return byHour.map { (h, c) -> (h * 3600_000L) to c }
    }
}
