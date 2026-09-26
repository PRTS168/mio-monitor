package com.a41probe.monitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.a41probe.monitor.data.CapacityHistory
import com.a41probe.monitor.data.MonitorViewModel
import com.a41probe.monitor.ui.components.AnimatedNum
import com.a41probe.monitor.ui.components.AreaLine
import com.a41probe.monitor.ui.components.CardTitle
import com.a41probe.monitor.ui.components.GaugeRing
import com.a41probe.monitor.ui.components.ProbeCard
import com.a41probe.monitor.ui.components.Sparkline
import com.a41probe.monitor.ui.components.StatRow
import com.a41probe.monitor.ui.components.tempColor
import com.a41probe.monitor.ui.theme.Ink
import com.a41probe.monitor.ui.theme.Priv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private fun f1(v: Double?): String = if (v == null) "–" else ((v * 10).roundToInt() / 10.0).toString()

/** v20.21: 小时数 → 人类可读时长（"X 小时 Y 分"/"X 分钟"） */
private fun estH(hours: Double): String {
    val min = (hours.coerceAtLeast(0.0) * 60).roundToInt()
    return if (min >= 60) "${min / 60} 小时 ${min % 60} 分" else "$min 分钟"
}

/** GPU：占用曲线（单指标面积线）、频率（R）、gpuss 温度（S）、温度曲线 */
@Composable
fun GpuScreen(vm: MonitorViewModel) {
    val snap by vm.snapshot.collectAsState()
    val busyHist by vm.busyHist.collectAsState()
    val tempH by vm.gpuTempHist.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ProbeCard {
                CardTitle("占用", Priv.FREE, right = {
                    // UX(M10): 人话副标题
                    Text("使用率 · 内核上报", color = Ink.off, fontSize = 10.5.sp)
                })
                Spacer(Modifier.height(6.dp))
                Text("${snap.gpu.busyPercent ?: "–"}%", color = Ink.accent, fontSize = 26.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                // GPU 占用有绝对 0..100 语义：固定量程，3% 显示在 3% 位置（真实比例，不放大）
                // S3: y 轴刻度 0/50/100%，曲线不再只有形状没有量级
                AreaLine(busyHist, color = Ink.accent, height = 96.dp, fixedRange = 100f,
                    axisLabels = listOf("100%", "50%", "0%"),
                    latestLabel = { f -> "${f.roundToInt()}%" })
            }
        }
        item {
            ProbeCard {
                // v20.12(S1-7): 频率档位随真实状态——未提权标 R，已提权无节点标"不可用"
                CardTitle("频率", when (snap.gpu.freqState) {
                    com.a41probe.monitor.ui.theme.DataState.NEED_PRIV -> Priv.ROOT
                    else -> null
                })
                Spacer(Modifier.height(8.dp))
                val f = snap.gpu.freqMHz
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (f != null) "$f MHz" else "–",
                        color = if (f != null) Ink.tx else Ink.off,
                        fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            f != null -> "kgsl gpuclk · ${if (snap.rootAvailable) "Root 通道" else "App 域直读"}"
                            snap.gpu.freqState == com.a41probe.monitor.ui.theme.DataState.NEED_PRIV ->
                                "需 Root · 授权后读取"
                            else -> "未找到频率节点 · 已试 kgsl/devfreq"
                        },
                        color = Ink.off, fontSize = 10.5.sp)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProbeCard(Modifier.weight(1f)) {
                    // S5: 用户可见标题汉化，sysfs 代号保留为副标题（工具感）
                    CardTitle("GPU 核心 0", Priv.SHIZUKU, rootActive = snap.rootAvailable)
                    Spacer(Modifier.height(6.dp))
                    val t = snap.gpu.temp0C
                    Text("${f1(t)}°C", color = if (t != null) tempColor(t) else Ink.off,
                        fontSize = 20.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold)
                    Text(if (t != null) "温区 gpuss-0" else
                        if (snap.rootAvailable) "Root 读取中…" else "需 Shizuku · 授权后读取",
                        color = Ink.off, fontSize = 10.sp)
                }
                ProbeCard(Modifier.weight(1f)) {
                    CardTitle("GPU 核心 1", Priv.SHIZUKU, rootActive = snap.rootAvailable)
                    Spacer(Modifier.height(6.dp))
                    val t = snap.gpu.temp1C
                    Text("${f1(t)}°C", color = if (t != null) tempColor(t) else Ink.off,
                        fontSize = 20.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold)
                    Text(if (t != null) "温区 gpuss-1" else
                        if (snap.rootAvailable) "Root 读取中…" else "需 Shizuku · 授权后读取",
                        color = Ink.off, fontSize = 10.sp)
                }
            }
        }
        item {
            ProbeCard {
                CardTitle("温度曲线", right = {
                    // UX(M4): 时间窗 + 数据源，同屏可读
                    Text("GPU 核心 0 · 近 60s", color = Ink.off, fontSize = 10.5.sp)
                })
                Spacer(Modifier.height(10.dp))
                AreaLine(tempH, color = Ink.warn, height = 96.dp)
                Spacer(Modifier.height(6.dp))
                Text("当前 ${f1(snap.gpu.temp0C)} °C", color = Ink.tx2, fontSize = 11.sp)
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

/** 电池：状态卡 + 功率/温度曲线 + iOS 详情列表 + 双口径温度 */
@Composable
fun BatteryScreen(vm: MonitorViewModel) {
    val snap by vm.snapshot.collectAsState()
    val b = snap.battery
    val health = b.healthPercent
    val powerHist by vm.powerHist.collectAsState()
    val voltHist by vm.voltHist.collectAsState()
    val curHist by vm.curHist.collectAsState()
    val tempHist by vm.tempHist.collectAsState()
    // v20: 24h 容量趋势（本地记录，60s 粒度；页面可见时每 60s 刷新一次）
    var cap24 by remember { mutableStateOf(emptyList<Pair<Long, Int>>()) }
    LaunchedEffect(Unit) {
        while (true) {
            cap24 = withContext(Dispatchers.IO) { CapacityHistory.last24h() }
            delay(60_000)
        }
    }

    // v19 双口径：大字优先适配器输入侧功率（充电器实际输出，需提权）；读不到回退电池侧 V×I
    val inputW = b.inputPowerW
    val showW = inputW ?: b.powerShown
    val mode = b.chargeMode
    val stateText = if (b.isCharging) (if (mode == "快充") "快充中" else "充电中") else "放电中"
    val fast = b.isCharging && (mode == "快充" || (inputW ?: 0.0) >= 15.0)

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ProbeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // UX(M7): 读不到电量时画空环 + "–"，不把"未知"伪装成 0%
                    GaugeRing(
                        fraction = b.capacity?.let { it / 100f },
                        center = b.capacity?.toString() ?: "–",
                        color = Ink.accent,   // v20.20(P1-6): 与仪表盘恒蓝统一，充电态已有闪电/文案表达
                        size = 92.dp, strokeW = 8.dp, unit = "%",
                        centerAnimated = b.capacity?.toFloat(),
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stateText, color = Ink.accent,
                            fontSize = 12.sp, letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(if (b.isCharging) "+" else "−", color = Ink.accent, fontSize = 26.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                            AnimatedNum(showW?.toFloat(), { f -> "%.1f".format(f) },
                                color = Ink.accent, sizeSp = 26, weight = FontWeight.SemiBold)
                            Spacer(Modifier.width(4.dp))
                            Text("W", color = Ink.tx2, fontSize = 14.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                        // 双口径副行：适配器输入侧（快充真相） + 电池侧 V×I
                        Text(
                            if (inputW != null)
                                "充电器 ${f1(b.inputVoltV)}V · ${f1(b.inputCurA)}A · ~${f1(inputW)}W"
                            else if (snap.rootAvailable) "电池侧 V×I · 输入侧节点不可用"
                            else "电池侧 V×I · 充电器输入需提权",
                            color = if (inputW != null) Ink.accent else Ink.tx2,
                            fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                        Text("电池 ${f1(b.voltage)} V · ${if (b.isCharging) "+" else "−"}${f1(b.currentDisplay)} A · $mode · ${b.tech ?: "–"}",
                            color = Ink.tx2, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(6.dp))
                        Text("66W 为适配器额定峰值 · 实时功率随电量/温度/协议浮动",
                            color = Ink.off, fontSize = 10.sp)
                        // v20.21: 充满 / 续航估算——按当前功率与学习容量推算，标注口径不冒充实测
                        val est = when {
                            b.isCharging && b.capacity != null && b.chargeFull != null &&
                                    b.voltage != null && (inputW ?: 0.0) > 0.5 ->
                                // v20.24(P0-1): chargeFull=mAh → Ah（÷1000）再乘电压除功率得小时；
                                // 原公式 mWh/W=毫小时，放大 1000 倍（50%·25W 显示 380 小时）
                                "预计充满 " + estH(((100 - b.capacity) / 100.0) * b.chargeFull / 1000.0 * b.voltage / (inputW ?: 0.0))
                            !b.isCharging && b.capacity != null && b.chargeFull != null &&
                                    (b.currentDisplay ?: 0.0) > 0.02 ->
                                "预计续航 " + estH(b.capacity / 100.0 * b.chargeFull / (b.currentDisplay!! * 1000))
                            else -> null
                        }
                        if (est != null) {
                            Spacer(Modifier.height(4.dp))
                            Text("$est（按当前功率估算）", color = Ink.tx2, fontSize = 10.5.sp)
                        }
                    }
                }
            }
        }
        item {
            val negV = b.maxChargingVoltageUv
            val negA = b.maxChargingCurrentUa
            if (b.isCharging && (negV != null || negA != null)) {
                ProbeCard {
                    CardTitle("充电器协商", Priv.FREE,
                        right = { Text("系统上报 · 免提权", color = Ink.off, fontSize = 10.5.sp) })
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${f1(negV?.div(1_000_000.0))} V", color = Ink.accent, fontSize = 20.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(16.dp))
                        Text("${f1(negA?.div(1_000_000.0))} A", color = Ink.clusterEff, fontSize = 20.sp,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        val peak = if (negV != null && negA != null) negV * negA / 1e12 else null
                        Text("上限 ~${f1(peak)} W", color = Ink.tx2, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("协商档位 ≠ 充电器标称：66W 头受线材 / 配件认证 / 电量温度限制，可能只协商到低压小电流",
                        color = Ink.off, fontSize = 10.sp)
                }
            }
        }
        item {
            ProbeCard {
                CardTitle("功率曲线", right = {
                    // UX(M4): 明示时间窗，用户知道窗口宽度与方向
                    Text("近 60s · V×I 实时", color = Ink.off, fontSize = 10.5.sp)
                })
                Spacer(Modifier.height(10.dp))
                // O11: 均值只随历史变化重算；powerHist 为原始 W 值，图表内部动态归一化
                val avgW = remember(powerHist) {
                    if (powerHist.isEmpty()) null else powerHist.average().toFloat()
                }
                AreaLine(powerHist, color = Ink.accent, height = 110.dp,
                    avgValue = avgW, fixedRange = 30f,
                    axisLabels = listOf("30W", "15W", "0W"),
                    latestLabel = { f -> "%.1fW".format(f) })
                Spacer(Modifier.height(6.dp))
                Text("当前 ${f1(b.powerShown)} W${if (avgW != null) " · 均值 ${f1(avgW.toDouble())} W" else ""}",
                    color = Ink.tx2, fontSize = 11.sp)
            }
        }
        // v20: 电压 / 电流实时曲线（探针数据一直有，补齐可视化）
        item {
            ProbeCard {
                CardTitle("电压 / 电流", Priv.FREE,
                    right = { Text("近 60s · 同源实时", color = Ink.off, fontSize = 10.5.sp) })
                Spacer(Modifier.height(10.dp))
                // S1#3: 电压固定量程 3.3-4.5V（电池实际工作区间），动态归一化会把 ±0.05V 放大成心跳
                AreaLine(voltHist, color = Ink.accent, height = 78.dp,
                    fixedRange = 1.2f, fixedCenter = 3.9f,
                    axisLabels = listOf("4.5V", "3.9V", "3.3V"),
                    latestLabel = { f -> "%.2fV".format(f) })
                Spacer(Modifier.height(4.dp))
                Text("电压", color = Ink.off, fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))
                // S1#4: 电流 0-6A（快充峰值不截顶，日常 0-2A 放电仍有可见变化）
                // v20.16(P0-5): A710 蓝→A510 青绿——电压蓝/电流蓝两根线几乎同色，用户分不清
                AreaLine(curHist, color = Ink.clusterEff, height = 78.dp,
                    fixedRange = 6f, axisLabels = listOf("6A", "3A", "0A"),
                    latestLabel = { f -> "%.1fA".format(f) })
                Spacer(Modifier.height(4.dp))
                Text("电流（取绝对值）", color = Ink.off, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                Text("当前 ${f1(b.voltage)} V · ${f1(b.currentDisplay)} A",
                    color = Ink.tx2, fontSize = 11.sp)
            }
        }
        item {
            ProbeCard {
                CardTitle("温度曲线", right = {
                    Text("内核上报", color = Ink.off, fontSize = 10.5.sp)
                })
                Spacer(Modifier.height(10.dp))
                AreaLine(tempHist, color = Ink.warn, height = 96.dp)
                Spacer(Modifier.height(6.dp))
                Text("当前 ${f1(b.tempC)} °C · 广播 ${f1(b.tempBmC)} °C",
                    color = Ink.tx2, fontSize = 11.sp)
                // v20.21: 近60s 极值——窗口内温度范围一眼可见
                val tMax = tempHist.maxOrNull()
                val tMin = tempHist.minOrNull()
                if (tMax != null && tMin != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("近60s 最高 ${f1(tMax.toDouble())} °C · 最低 ${f1(tMin.toDouble())} °C",
                        color = Ink.off, fontSize = 10.5.sp)
                }
            }
        }
        item {
            ProbeCard {
                CardTitle("电池详情", Priv.FREE)
                Spacer(Modifier.height(6.dp))
                val wear = if (health != null) (100 - health.roundToInt()) else null
                // sysfs health 原始值映射（Good/Overheat/Cold...），不伪造"良好"
                val healthLabel = when (b.health?.lowercase()) {
                    "good" -> "良好"
                    "overheat" -> "过热"
                    "cold" -> "过冷"
                    "dead" -> "损坏"
                    "over voltage" -> "过压"
                    "unknown" -> "未知"
                    else -> b.health ?: "–"
                }
                // UX(M13): 只有真异常（过热/损坏/过压/过冷）才橙色；"未知"中性灰
                val healthCol = when (b.health?.lowercase()) {
                    "overheat", "dead", "over voltage", "cold" -> Ink.warn
                    else -> Ink.off
                }
                StatRow("健康状态", healthLabel, stateColor = healthCol)
                StatRow("设计容量", "${b.chargeDesign ?: "–"}", "mAh", mono = true)
                StatRow("满电学习容量", "${b.chargeFull ?: "–"}", "mAh", mono = true)
                // UX(L15): 磨损度并入健康度，不再单独成行（互为补数，重复）
                StatRow("健康度（学习/设计）",
                    if (health != null) "${health.roundToInt()}%${if (wear != null) "（磨损 $wear%）" else ""}" else "–",
                    stateColor = if (health != null && health >= 80) Ink.ok else Ink.warn)
                StatRow("循环次数", "${b.cycleCount ?: "–"}", "次", mono = true)
                StatRow("充电类型", mode)
                StatRow("电池技术", b.tech ?: "–")
                StatRow("开路电压 OCV", f1(b.voltageOcvV), "V", mono = true)
                StatRow("充电截止电压", f1(b.voltageMaxV), "V", mono = true)
                StatRow("充入电量（循环）",
                    "${b.chargeCounterUah?.div(1000) ?: "–"}", "mAh", mono = true)
                // P2-11: PMIC 语义 cur=0 = 未施加额外限流，裸 "0/40" 易误读为"被限到 0"
                StatRow("充电限流档位",
                    run {
                        val c = b.chargeControlLimit; val m = b.chargeControlLimitMax
                        when {
                            c == null && m == null -> "–"
                            c == 0 -> "未限流（0/${m ?: "–"}）"
                            else -> "$c/${m ?: "–"}"
                        }
                    }, mono = true)
                Spacer(Modifier.height(4.dp))
                Text("容量口径：高通燃料计学习值（MASTERSLAVE 双电芯平均）",
                    color = Ink.off, fontSize = 10.sp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProbeCard(Modifier.weight(1f)) {
                    CardTitle("温度 · 内核", Priv.FREE)
                    Spacer(Modifier.height(8.dp))
                    Text("${f1(b.tempC)}°C", color = Ink.tx, fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
                ProbeCard(Modifier.weight(1f)) {
                    CardTitle("温度 · 系统广播")
                    Spacer(Modifier.height(8.dp))
                    Text("${f1(b.tempBmC)}°C", color = Ink.tx, fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    // UX(M12): 解释双口径差异，避免"到底哪个准"的困惑
                    Text("两处采样点不同，属正常小差异", color = Ink.off, fontSize = 10.sp)
                }
            }
        }
        // v20: 24h 容量趋势落地（本地记录，非占位）
        item {
            ProbeCard {
                CardTitle("容量趋势 · 24h",
                    right = { Text("本地记录 · 60s 粒度", color = Ink.off, fontSize = 10.5.sp) })
                Spacer(Modifier.height(10.dp))
                if (cap24.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                        Text("积累中 · 每 60s 记录一次容量", color = Ink.off, fontSize = 11.sp)
                    }
                } else {
                    // S1#2: 24h 稀疏序列按点数铺满宽度，不再挤在右端 40%
                    Sparkline(cap24.map { it.second.toFloat() }, color = Ink.accent, height = 56.dp,
                        stretchToFull = true)
                    Spacer(Modifier.height(6.dp))
                    Text("最近 ${cap24.size} 小时 · 当前 ${b.capacity ?: "–"}%",
                        color = Ink.tx2, fontSize = 11.sp)
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}
