package com.a41probe.monitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.a41probe.monitor.data.ClusterRole
import com.a41probe.monitor.data.CpuCore
import com.a41probe.monitor.data.CpuReader
import com.a41probe.monitor.data.MonitorViewModel
import com.a41probe.monitor.data.Sysfs
import com.a41probe.monitor.ui.components.AreaLine
import com.a41probe.monitor.ui.components.AnimatedFracBar
import com.a41probe.monitor.ui.components.AnimatedNum
import com.a41probe.monitor.ui.components.CardTitle
import com.a41probe.monitor.ui.components.ProbeCard
import com.a41probe.monitor.ui.components.Sparkline
import com.a41probe.monitor.ui.components.StatRow
import com.a41probe.monitor.ui.theme.Ink
import com.a41probe.monitor.ui.theme.Priv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** CPU：总占用（S）、8 核频率时间线（—）、档位分布（—）、调度与限频 */
@Composable
fun CpuScreen(vm: MonitorViewModel) {
    val snap by vm.snapshot.collectAsState()
    val cpuTotal by vm.cpuTotalPercent.collectAsState()
    val cpuTotalHist by vm.cpuTotalHist.collectAsState()
    val corePercent by vm.corePercent.collectAsState()
    val hist by vm.freqHist.collectAsState()

    // sysfs 读取移出组合阶段：后台线程读一次，避免每秒主线程 IO
    var gov by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { gov = withContext(Dispatchers.IO) { CpuReader.governor() } }

    // time_in_state：动态取最低频簇（能效核）的首个核，标题用该簇 label
    // 每帧重算 groupBy（核数≤10，开销可忽略）——授权后 maxMHz 由 null 变真值立即生效，不缓存
    val tisCluster = snap.cores.groupBy { it.cluster }
        .map { (label, cs) -> Triple(label, cs.first().index,
            cs.mapNotNull { it.maxMHz }.minOrNull() ?: Int.MAX_VALUE) }
        .minByOrNull { it.third }
    val tisCore = tisCluster?.second ?: 0
    val tisLabel = tisCluster?.first ?: "小核"
    var tis by remember { mutableStateOf<List<Pair<Int, Float>>>(emptyList()) }
    // M22: time_in_state 读一次文件约数毫秒，频率变化会每秒触发重算 → 5s 节流
    var lastTisRead by remember { mutableStateOf(0L) }
    LaunchedEffect(tisCore, snap.cores.getOrNull(tisCore)?.freqMHz) {
        val now = System.currentTimeMillis()
        if (now - lastTisRead < 5_000) return@LaunchedEffect
        lastTisRead = now
        tis = withContext(Dispatchers.IO) { timeInStateFreqs(tisCore) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 总占用
        item {
            ProbeCard {
                CardTitle("总占用", Priv.SHIZUKU, rootActive = snap.rootAvailable,
                    right = { Text("proc/stat 差分", color = Ink.off, fontSize = 10.5.sp) })
                Spacer(Modifier.height(8.dp))
                if (cpuTotal != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedNum(cpuTotal!!.toFloat(), { f -> "%.0f".format(f) },
                            color = Ink.accent, sizeSp = 26)
                        Text("%", color = Ink.accent, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 2.dp))
                    }
                } else {
                    Text("–", color = Ink.off, fontSize = 26.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(if (snap.rootAvailable) "Root 读取中…" else "需 Shizuku · 授权后显示总占用",
                        color = Ink.off, fontSize = 10.5.sp)
                }
                Spacer(Modifier.height(10.dp))
                AreaLine(cpuTotalHist, color = Ink.accent, height = 84.dp,
                    fixedRange = 100f, axisLabels = listOf("100%", "50%", "0%"),
                    latestLabel = { f -> "${f.roundToInt()}%" },
                    emptyText = if (snap.rootAvailable) "Root 读取中…" else "需 Shizuku 授权")
            }
        }
        // 核心频率：4×2 网格（对标搞机牛：编号 + 频率大数字 + 每核独立迷你曲线）
        item {
            ProbeCard {
                CardTitle("核心频率", Priv.FREE,
                    right = { Text("实时 · 60s", color = Ink.off, fontSize = 10.5.sp) })
                Spacer(Modifier.height(9.dp))
                val freqRows = snap.cores.indices.chunked(4)
                freqRows.forEachIndexed { rowIdx, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { idx ->
                            Box(Modifier.weight(1f)) {
                                CoreFreqCell(
                                    core = snap.cores.getOrNull(idx),
                                    hist = hist.getOrElse(idx) { emptyList() },
                                )
                            }
                        }
                    }
                    if (rowIdx < freqRows.lastIndex) Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(9.dp))
                ClusterLegend(snap.cores)
                Spacer(Modifier.height(6.dp))
                Text("同簇共享频率域（DVFS）· 同簇核心频率始终一致",
                    color = Ink.off, fontSize = 10.sp)
            }
        }
        // v20: 分核占用（S /proc/stat 差分）
        item {
            ProbeCard {
                CardTitle("分核占用", Priv.SHIZUKU, rootActive = snap.rootAvailable,
                    right = { Text("proc/stat · 实时", color = Ink.off, fontSize = 10.5.sp) })
                Spacer(Modifier.height(9.dp))
                if (corePercent.none { it != null }) {
                    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                        Text(if (snap.rootAvailable) "Root 读取中…" else "需 Shizuku · 授权后显示每核占用",
                            color = Ink.off, fontSize = 11.sp)
                    }
                } else {
                    val busyRows = snap.cores.indices.chunked(4)
                    busyRows.forEachIndexed { rowIdx, row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { idx ->
                                Box(Modifier.weight(1f)) {
                                    CoreBusyCell(idx = idx, percent = corePercent.getOrNull(idx),
                                        core = snap.cores.getOrNull(idx))
                                }
                            }
                        }
                        if (rowIdx < busyRows.lastIndex) Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.height(9.dp))
                    ClusterLegend(snap.cores)
                }
            }
        }
        // 档位分布（A510 time_in_state；频率变化时后台重算）
        item {
            if (tis.isNotEmpty()) {
                ProbeCard {
                    CardTitle("档位驻留 · $tisLabel", Priv.FREE)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                        tis.forEach { (freq, frac) ->
                            val active = freq == snap.cores.getOrNull(tisCore)?.freqMHz
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(36.dp),
                                    contentAlignment = Alignment.BottomCenter,
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight(frac.coerceIn(0.02f, 1f))
                                            .background(if (active) Ink.accent else Ink.panel)
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                // v20.15: 每档标 MHz——柱子不再是"无字灰条"，当前档蓝色加粗
                                Text("$freq", color = if (active) Ink.accent else Ink.off,
                                    fontSize = 8.5.sp, fontFamily = FontFamily.Monospace,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("当前档蓝色高亮 · 数字为该档 MHz · 柱高=该档驻留占比",
                        color = Ink.off, fontSize = 10.sp)
                }
            }
        }
        // 调度与限频
        item {
            ProbeCard {
                CardTitle("调度与限频")
                StatRow("governor", gov ?: "–", stateColor = Ink.ok, mono = true)
                // 各簇上限：按簇动态显示 label+"上限"（PRIME→PERF→BAL→EFF 排序），Root 补读真值，未读到置灰
                val roleOrder = listOf(ClusterRole.PRIME, ClusterRole.PERFORMANCE, ClusterRole.BALANCE, ClusterRole.EFFICIENCY)
                val clusterInfo = snap.cores.groupBy { it.cluster }.map { (label, cs) ->
                    Triple(label, cs.mapNotNull { it.maxMHz }.maxOrNull(), cs.first().colorRole)
                }.sortedBy { roleOrder.indexOf(it.third) }
                clusterInfo.forEach { (label, maxMhz, _) ->
                    StatRow("$label 上限", maxMhz?.toString() ?: "–",
                        unit = "MHz",
                        stateColor = if (maxMhz != null) Ink.ok else Ink.off, mono = true)
                }
                StatRow("降频事件", if (snap.battery.isCharging) "限频中（快充）" else "无",
                    stateColor = if (snap.battery.isCharging) Ink.warn else Ink.ok)
                Spacer(Modifier.height(4.dp))
                // UX: 空闲核驻留最低档是 cpufreq 正常行为（如 X2 806MHz），消除"频率卡死"误解
                Text("核心空闲时驻留最低档（如 X2 806MHz），负载后由调度器抬频",
                    color = Ink.off, fontSize = 10.sp, maxLines = 2)
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

/** 单核占用格：核号 + 簇色百分比 + 水平条（对标搞机牛分核占用） */
@Composable
private fun CoreBusyCell(idx: Int, percent: Int?, core: CpuCore?) {
    val color = core?.let { clusterColorForRole(it.colorRole) } ?: Ink.off
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.panel)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("C$idx", color = Ink.off, fontSize = 9.5.sp)
            Spacer(Modifier.weight(1f))
            Text(percent?.toString()?.plus("%") ?: "–", color = color, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(5.dp))
        // v20.20(P1-7): 接入 AnimatedFracBar——占用条弹簧平滑，与同屏频率条节奏一致
        // P2-10: 轨道色一并对齐全局 Ink.panel
        AnimatedFracBar(
            frac = if (percent != null) (percent / 100f).coerceIn(0f, 1f) else 0f,
            color = if (percent != null) color.copy(alpha = 0.85f) else Ink.panel,
            height = 9.dp, corner = 4.dp,
        )
    }
}

/** 读取小核 time_in_state，返回 [(MHz, 驻留占比)] */
private val RE_WS = Regex("\\s+")   // M18: 文件级复用，避免热路径反复编译
private fun timeInStateFreqs(core: Int): List<Pair<Int, Float>> {
    val raw = Sysfs.read("/sys/devices/system/cpu/cpu$core/cpufreq/stats/time_in_state") ?: return emptyList()
    val rows = raw.lines().mapNotNull { l ->
        val p = l.trim().split(RE_WS)
        if (p.size == 2) (p[0].toIntOrNull() to p[1].toLongOrNull()) else null
    }.filter { it.first != null && it.second != null }
    val total = rows.sumOf { it.second!! }.toFloat()
    if (total <= 0) return emptyList()
    return rows.map { (it.first!! / 1000) to (it.second!! / total) }
}

// ===== 核心频率网格（对标搞机牛工具箱） =====

/** 簇角色色：PRIME 暖橙（最大性能核最醒目）· PERF 蓝 · EFF 青绿 · BAL 紫（统一引用 Ink 令牌） */
private fun clusterColorForRole(role: ClusterRole?): Color = when (role) {
    ClusterRole.PRIME -> Ink.clusterPrime
    ClusterRole.PERFORMANCE -> Ink.clusterPerf
    ClusterRole.EFFICIENCY -> Ink.clusterEff
    ClusterRole.BALANCE -> Ink.clusterBal
    null -> Ink.accent
}

/** 单核频率格：编号 + 簇色点 / 频率大数字 / MHz / 该核迷你频率曲线 */
@Composable
private fun CoreFreqCell(core: CpuCore?, hist: List<Float>) {
    val color = core?.let { clusterColorForRole(it.colorRole) } ?: Ink.off
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.panel)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("C${core?.index ?: "–"}", color = Ink.off, fontSize = 9.5.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.height(3.dp))
        AnimatedNum(core?.freqMHz?.toFloat(), { f -> "%.0f".format(f) },
            color = color, sizeSp = 16, weight = FontWeight.SemiBold)
        Text("MHz", color = Ink.off, fontSize = 8.5.sp)
        Spacer(Modifier.height(3.dp))
        // 每核独立迷你曲线：化整为零，不再 8 线叠图
        Sparkline(
            hist, color = color, modifier = Modifier.fillMaxWidth(),
            height = 16.dp, fill = false, strokeW = 1.3f,
        )
    }
}

/** 簇图例：按 snapshot.cores 动态去重，圆点 + 簇名（含核心数），按角色排序 */
@Composable
private fun ClusterLegend(cores: List<CpuCore>) {
    val roleOrder = listOf(ClusterRole.PRIME, ClusterRole.PERFORMANCE, ClusterRole.BALANCE, ClusterRole.EFFICIENCY)
    val items = remember(cores) {
        val byLabel = LinkedHashMap<String, Pair<ClusterRole?, Int>>()
        cores.forEach { c ->
            val prev = byLabel[c.cluster]
            byLabel[c.cluster] = (c.colorRole ?: prev?.first) to ((prev?.second ?: 0) + 1)
        }
        byLabel.entries.map { (label, p) -> label to p }
            .sortedBy { roleOrder.indexOf(it.second.first) }
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items.forEach { (label, p) ->
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(clusterColorForRole(p.first)))
                Text(if (p.second > 1) "$label×${p.second}" else label,
                    color = Ink.tx2, fontSize = 10.5.sp)
            }
        }
    }
}
