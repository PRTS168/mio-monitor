package com.a41probe.monitor.data

import android.content.Context
import com.a41probe.monitor.ui.theme.DataState

/**
 * 独立采集器：把"一帧完整 [Snapshot]"的采集与差分状态从 ViewModel 抽出，
 * 供 MonitorViewModel（本机 UI）与 AgentService（被监控端推送）共用，保证两端口径完全一致。
 *
 * 线程：[collect] 为阻塞调用，调用方必须放到 Dispatchers.IO。
 */
class SnapshotCollector(private val ctx: Context) {

    private var lastStat: LongArray? = null
    private var lastCoreStat: List<LongArray>? = null

    // App 域 EACCES 是稳定态，30s 内不重复全扫热区目录
    @Volatile private var noPrivUntil = 0L
    // Shizuku 状态 2s 最小刷新间隔
    @Volatile private var lastShizukuRefresh = 0L

    private fun shizukuOn(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastShizukuRefresh > 2000) {
            lastShizukuRefresh = now
            ShizukuBridge.refresh()
        }
        return ShizukuBridge.active && ShizukuBridge.authorized
    }

    fun collect(): Snapshot {
        val shizukuOn = shizukuOn()
        // Root 优先——root 可用则走 su 通道；无 root 再回落 Shizuku；都没有则 App 域
        val rootOn = RootBridge.available
        // v0.24: 统一提权执行器——root 用 su、否则 Shizuku 用 shell，null=纯 App 域。
        // 让 Battery/Cpu/Gpu 三个 Reader 在 Shizuku 档也能读 Mali/MTK 等受保护节点；
        // 此前它们只在 root 时提权，Shizuku 已授权却仍走 App 域，导致大量参数缺失（适配不足根因）。
        val priv: ((String) -> String?)? = when {
            rootOn -> { cmd -> RootBridge.exec(cmd) }
            shizukuOn -> { cmd -> ShizukuBridge.exec(cmd) }
            else -> null
        }

        val battery = BatteryReader.read(ctx, priv, rootOn)
        val cores = CpuReader.read(priv, rootOn)
        val governor = CpuReader.governor()
        var gpu = GpuReader.read(priv, rootOn)

        val thermalAll: List<ThermalZone>
        val load: String?
        val cpuTotal: Double?
        var perCore: List<Int?> = emptyList()
        var procStatRaw: String? = null
        var coreStatRaw: List<String> = emptyList()

        // root 失败/失权自动回落 Shizuku，不互斥跳过
        var bulk: BulkReader.Bulk? = null
        if (rootOn) {
            bulk = BulkReader.readRoot()
            if (bulk != null) RootBridge.noteExecOk() else RootBridge.noteExecFail()
        }
        if (bulk == null && shizukuOn) {
            bulk = BulkReader.readShizuku()
        }
        if (rootOn || shizukuOn) {
            if (bulk != null) {
                thermalAll = bulk.zones
                load = bulk.load
                cpuTotal = diffCpuTotal(bulk.stat)
                perCore = diffCores(bulk.coreStats)
                procStatRaw = bulk.stat
                coreStatRaw = bulk.coreStats
                gpu = gpu.copy(
                    temp0C = bulk.zones.firstOrNull { it.name == "gpuss-0" }?.tempC,
                    temp1C = bulk.zones.firstOrNull { it.name == "gpuss-1" }?.tempC,
                    tempState = if (bulk.zones.any { it.name.startsWith("gpuss") })
                        DataState.OK else DataState.NEED_PRIV,
                )
            } else {
                thermalAll = emptyList()
                load = null
                cpuTotal = null
                gpu = gpu.copy(temp0C = null, temp1C = null, tempState = DataState.NEED_PRIV)
            }
        } else {
            val now = System.currentTimeMillis()
            thermalAll = if (now < noPrivUntil) emptyList() else {
                ThermalReader.readAppDomain().also {
                    if (it.isEmpty()) noPrivUntil = now + 30_000
                }
            }
            load = SysReader.loadAvgApp()
            cpuTotal = null
        }

        val memTotal = SysReader.memTotalMB()
        val memInfo = SysReader.memInfo()

        // Thermal HAL 低频读取（ThermalHalReader 内部 5s 节流；仅提权档能跑 dumpsys）
        val halExec: (String) -> String? = when {
            rootOn -> { cmd -> RootBridge.exec(cmd) }
            shizukuOn -> { cmd -> ShizukuBridge.exec(cmd) }
            else -> { _ -> null }
        }
        val hal = if (rootOn || shizukuOn) ThermalHalReader.get(halExec) else null

        return Snapshot(
            battery = battery,
            cores = cores,
            gpu = gpu,
            loadStr = load,
            memTotalMB = memTotal,
            mem = memInfo,
            thermalAll = thermalAll,
            thermalTop = ThermalReader.topN(thermalAll.filter { it.name != "tof-therm" }, 8),
            cpuTotalPercent = cpuTotal,
            corePercent = perCore,
            procStat = procStatRaw,
            coreStat = coreStatRaw,
            governor = governor,
            cooling = bulk?.cooling ?: emptyList(),
            halTemps = hal?.temps ?: emptyList(),
            thermalStatus = hal?.status,
            thresholds = hal?.thresholds ?: emptyList(),
            shizukuActive = shizukuOn,
            shizukuServiceUp = ShizukuBridge.active,
            shizukuAuthorized = ShizukuBridge.active && ShizukuBridge.authorized,
            rootAvailable = RootBridge.available,
        )
    }

    /** /proc/stat 首行差分：total = 全部字段和，idle = idle+iowait */
    private fun diffCpuTotal(line: String?): Double? {
        val cur = parseStat(line) ?: return null
        val prev = lastStat
        lastStat = cur
        if (prev == null) return null
        val dTotal = cur.sum() - prev.sum()
        val dIdle = (cur.getOrNull(3)?.plus(cur.getOrNull(4) ?: 0L) ?: 0L) -
                (prev.getOrNull(3)?.plus(prev.getOrNull(4) ?: 0L) ?: 0L)
        if (dTotal <= 0) return null
        return ((dTotal - dIdle) / dTotal.toDouble() * 100).coerceIn(0.0, 100.0)
    }

    private fun parseStat(line: String?): LongArray? {
        if (line == null) return null
        val nums = line.split(RE_WS).drop(1)
            .mapNotNull { it.toLongOrNull() }.toLongArray()
        return if (nums.size >= 5) nums else null
    }

    /** 分核占用差分：/proc/stat cpuN 行各自算，返回实际核数元素（缺核/首采样为 null） */
    private fun diffCores(lines: List<String>): List<Int?> {
        if (lines.isEmpty()) return emptyList()
        val cur = lines.mapNotNull { parseStat(it) }
        if (cur.isEmpty()) return emptyList()
        val prev = lastCoreStat
        lastCoreStat = cur
        if (prev == null || prev.size != cur.size) return List(cur.size) { null }
        return cur.mapIndexed { i, c ->
            val p = prev.getOrNull(i)
            if (p == null || p.size < 5 || c.size < 5) null
            else {
                val dTotal = c.sum() - p.sum()
                val dIdle = (c.getOrNull(3)?.plus(c.getOrNull(4) ?: 0L) ?: 0L) -
                        (p.getOrNull(3)?.plus(p.getOrNull(4) ?: 0L) ?: 0L)
                if (dTotal <= 0) null
                else ((dTotal - dIdle) / dTotal.toDouble() * 100).coerceIn(0.0, 100.0).toInt()
            }
        }
    }

    companion object {
        private val RE_WS = Regex("\\s+")
    }
}
