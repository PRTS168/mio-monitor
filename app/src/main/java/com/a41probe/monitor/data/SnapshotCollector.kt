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
            if (!ShizukuBridge.refresh()) {
                // v0.27.1: 探测失败 → 800ms 后立即重试（下一帧），不让失败状态粘住数帧
                lastShizukuRefresh = now - 1200
            }
        }
        return ShizukuBridge.active && ShizukuBridge.authorized
    }

    /**
     * v0.27.0: App 域轻快照——只取 BatteryManager / meminfo 等 App 域即时数据（<100ms），
     * 冷启动首帧先推给 UI，不等 Shizuku 绑定 / 串行 transact / dumpsys；
     * 提权数据（CPU 占用 / 结温 / 热区）在首次完整 [collect] 后自然补上。
     */
    fun collectQuick(): Snapshot = runCatching {
        val battery = BatteryReader.read(ctx, null, false)
        Snapshot(
            battery = battery,
            memTotalMB = SysReader.memTotalMB(),
            mem = SysReader.memInfo(),
        )
    }.getOrElse { Snapshot() }

    fun collect(halSync: Boolean = false): Snapshot {
        val shizukuOn = shizukuOn()
        // Root 优先——root 可用则走 su 通道；无 root 再回落 Shizuku；都没有则 App 域
        val rootOn = RootBridge.available
        // v0.24: 统一提权执行器——root 用 su、否则 Shizuku 用 shell，null=纯 App 域。
        val priv: ((String) -> String?)? = when {
            rootOn -> { cmd -> RootBridge.exec(cmd) }
            shizukuOn -> { cmd -> ShizukuBridge.exec(cmd) }
            else -> null
        }

        // v0.27.0: 提权档先跑一次 bulk——一条 shell 同时拿 热区 / loadavg / stat / cooling
        // 以及 8 核当前频率(cpuCurKHz)、power_supply 输入侧 V/I(psu)，每帧 transact 11+ → 1
        var bulk: BulkReader.Bulk? = null
        if (rootOn) {
            bulk = BulkReader.readRoot()
            if (bulk != null) RootBridge.noteExecOk() else RootBridge.noteExecFail()
        }
        if (bulk == null && shizukuOn) bulk = BulkReader.readShizuku()

        // Reader 复用 bulk 已取回的数据（override），不再各自 priv transact
        val battery = BatteryReader.read(ctx, priv, rootOn, psuOverride = bulk?.psu)
        val cores = CpuReader.read(priv, rootOn, curOverride = bulk?.cpuCurKHz)
        var gpu = GpuReader.read(priv, rootOn)

        val thermalAll: List<ThermalZone>
        val load: String?
        val cpuTotal: Double?
        var perCore: List<Int?> = emptyList()
        var procStatRaw: String? = null
        var coreStatRaw: List<String> = emptyList()

        if (bulk != null) {
            // thermal glob 命中 0 区 / load 缺失时仍回退 App 域，不丢帧（P1-Data-1）
            thermalAll = if (bulk.zones.isNotEmpty()) bulk.zones else ThermalReader.readAppDomain()
            load = bulk.load ?: SysReader.loadAvgApp()
            cpuTotal = diffCpuTotal(bulk.stat)
            perCore = diffCores(bulk.coreStats)
            procStatRaw = bulk.stat
            coreStatRaw = bulk.coreStats
            // v0.28.1: GPU 热区名/编号随平台而异（A41=gpuss-0/1；SM8750 等新平台可能是
            // gpuss-N 或 gpu*），精确匹配 gpuss-0/1 在 iQOO 上一个都取不到 → GPU 页全空。
            // 改为：过滤所有 GPU 热区、只留有有效读数者、按名排序取前两个。
            val gpuZones = bulk.zones.filter {
                val n = it.name.lowercase()
                n.startsWith("gpuss") || n == "gpu" || n.startsWith("gpu")
            }.mapNotNull { z -> Units.safeTemp(z.tempC)?.let { z } }.sortedBy { it.name }
            val hasGpuZone = bulk.zones.any {
                val n = it.name.lowercase(); n.startsWith("gpu")
            }
            gpu = gpu.copy(
                temp0C = gpuZones.getOrNull(0)?.tempC,
                temp1C = gpuZones.getOrNull(1)?.tempC,
                tempState = when {
                    gpuZones.isNotEmpty() -> DataState.OK
                    hasGpuZone -> DataState.NEED_PRIV
                    else -> DataState.UNAVAILABLE
                },
            )
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

        // Thermal HAL：本机 UI 走 ViewModel 独立协程异步刷新（cachedOnly，dumpsys 不挡主帧）；
        // Agent 被监控端(halSync=true)仍同步读取
        val halExec: (String) -> String? = when {
            rootOn -> { cmd -> RootBridge.exec(cmd) }
            shizukuOn -> { cmd -> ShizukuBridge.exec(cmd) }
            else -> { _ -> null }
        }
        val hal = when {
            rootOn || shizukuOn ->
                if (halSync) ThermalHalReader.get(halExec) else ThermalHalReader.cachedOnly()
            else -> null
        }

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
            governor = CpuReader.governor(),
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
