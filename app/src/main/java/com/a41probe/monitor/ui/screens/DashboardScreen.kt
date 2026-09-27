package com.a41probe.monitor.ui.screens

import com.a41probe.monitor.ui.components.MioPageTitle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.a41probe.monitor.data.MonitorViewModel
import com.a41probe.monitor.ui.components.AnimatedFracBar
import com.a41probe.monitor.ui.components.AnimatedNum
import com.a41probe.monitor.ui.components.AreaLine
import com.a41probe.monitor.ui.components.CardTitle
import com.a41probe.monitor.ui.components.NestedTile
import com.a41probe.monitor.ui.components.SafeCard
import com.a41probe.monitor.ui.components.tempColor
import com.a41probe.monitor.ui.theme.Ink
import com.a41probe.monitor.ui.theme.Priv
import kotlin.math.roundToInt
import com.a41probe.monitor.ui.components.ScrollAware

private fun fmt1(v: Double?): String = if (v == null) "–" else ((v * 10).roundToInt() / 10.0).toString()

@Composable
fun DashboardScreen(
    vm: MonitorViewModel,
    onGoSettings: () -> Unit = {},
    onGoBattery: () -> Unit = {},
) {
    // v0.28.0: 细粒度订阅——电池/内存/结温/GPU/负载/权限各自独立，不再整页每秒重组
    val b by vm.batteryFlow.collectAsState()
    val cpuTotal by vm.cpuTotalPercent.collectAsState()
    val cpuTotalHist by vm.cpuTotalHist.collectAsState()
    val top by vm.maxThermalFlow.collectAsState()
    val tc = top?.tempC
    val stale by vm.isStale.collectAsState()
    val priv by vm.privFlow.collectAsState()
    val hasPriv = priv.shizukuActive || priv.rootAvailable
    val mem by vm.memFlow.collectAsState()
    val gpu by vm.gpuFlow.collectAsState()
    val load by vm.loadFlow.collectAsState()

        val listState = rememberLazyListState()
    ScrollAware(listState) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            MioPageTitle("仪表盘", "电量 · CPU · 内存 · 温度")
        }

        // ① 总览 2×2：电量 / CPU / 内存 / 温度，一眼四项重点。
        //   免提权：电量、内存（meminfo）、电池温度均 App 域可读；CPU 格显示“–”，
        //   温度格自动换为“电池温度”。首屏永远有鲜活数字，未授权也不打折。
        item {
            SafeCard(fallbackTitle = "总览") {
                CardTitle("总览", right = {
                    if (!hasPriv) Text("免提权模式", color = Ink.off, fontSize = 10.5.sp)
                })
                Spacer(Modifier.height(10.dp))
                Row {
                    MetricTile("电量", b.capacity?.toString() ?: "–", "%",
                        if (stale || b.capacity == null) Ink.off else Ink.accent,
                        animated = b.capacity?.toFloat(), sizeSp = 30)
                    MetricTile("CPU 占用", cpuTotal?.roundToInt()?.toString() ?: "–", "%",
                        if (stale || cpuTotal == null) Ink.off else Ink.accent, mono = true,
                        animated = cpuTotal?.toFloat(), sizeSp = 30,
                        rollFromZero = vm.heroReady, onRolled = { vm.heroRollDone() })
                }
                Spacer(Modifier.height(14.dp))
                Row {
                    MetricTile("内存已用", mem?.usedPercent?.toString() ?: "–", "%",
                        if (stale || mem?.usedPercent == null) Ink.off else Ink.accent,
                        animated = mem?.usedPercent?.toFloat(), sizeSp = 30)
                    // 有授权显示最高结温；免提权回退电池温度（BatteryManager 可读）
                    val tval: Double? = if (hasPriv) tc else b.tempC
                    val tlabel = if (hasPriv) "最高结温" else "电池温度"
                    MetricTile(tlabel, fmt1(tval), "°C",
                        if (stale) Ink.off else tempColor(tval), mono = true,
                        animated = tval?.toFloat(), fmt = "%.1f", sizeSp = 30)
                }
                if (!hasPriv) {
                    Spacer(Modifier.height(10.dp))
                    // v20.20(P2-2): 说明行整行可点直达设置——首屏就给出授权路径
                    Text("免提权可读电量 / 内存 / 电池温度 · 设置页授权后解锁 CPU 占用 / 结温 / 热区",
                        color = Ink.off, fontSize = 10.5.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onGoSettings))
                }
            }
        }

        // ② CPU 占用曲线（近 60s）
        item {
            SafeCard(fallbackTitle = "CPU 占用") {
                CardTitle("CPU 占用", Priv.SHIZUKU, rootActive = priv.rootAvailable,
                    right = {
                        if (!hasPriv) {
                            // v20.20(P1-4): 触控区 ≥48dp——免提权用户主 CTA
                            Text("去授权 ›", color = Ink.accent, fontSize = 10.5.sp,
                                modifier = Modifier
                                    .padding(horizontal = 10.dp)
                                    .clickable(onClick = onGoSettings))
                        } else {
                            Text("近 60s · proc/stat", color = Ink.off, fontSize = 10.5.sp)
                        }
                    })
                Spacer(Modifier.height(10.dp))
                NestedTile {
                    AreaLine(cpuTotalHist, color = if (stale) Ink.off else Ink.accent, height = 92.dp,
                        fixedRange = 100f, axisLabels = listOf("100%", "50%", "0%"),
                        latestLabel = { f -> "${f.roundToInt()}%" },
                        // v0.27.0: 已授权但 hist 尚未攒点时显示“读取中…”，不再误报“需授权”
                        emptyText = if (hasPriv) "读取中…" else "需 Shizuku 授权")
                }
            }
        }

        // ③ 电池概览迷你卡：状态 + V·I + 功率，整卡点击进电池页（详情/健康/循环在专页）
        item {
            SafeCard(Modifier.fillMaxWidth().clickable(onClick = onGoBattery), fallbackTitle = "电池概览") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(batteryStateText(b),
                            color = Ink.accent, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text("电池概览", color = Ink.off, fontSize = 10.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    val vi = if (b.voltage != null)
                        "%.1f V · %s%s A".format(
                            b.voltage,
                            if (b.isPlugged) "+" else "−",
                            b.currentDisplay?.let { "%.1f".format(it) } ?: "–")
                    else "–"
                    Text(vi, color = Ink.tx, fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(10.dp))
                    // 功率与电池页 hero 同源：统一 uiPowerW（满电0/输入侧/回退/钳位）
                    val showW = b.uiPowerW
                    Text(if (showW != null)
                        (if (b.isPlugged) "+" else "−") + "%.1f W".format(showW)
                        else "–",
                        color = Ink.tx, fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(6.dp))
                    Text("›", color = Ink.off, fontSize = 15.sp)
                }
            }
        }

        // ④ GPU 占用 | 负载
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SafeCard(Modifier.weight(1f), fallbackTitle = "GPU 占用") {
                    CardTitle("GPU占用", Priv.FREE)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedNum(gpu.busyPercent?.toFloat(), { f -> "%.0f".format(f) },
                            color = Ink.accent, sizeSp = 26)
                        Text("%", color = Ink.accent, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 2.dp))
                    }
                    Spacer(Modifier.height(2.dp))
                    Text("GPU 使用率 · 内核上报", color = Ink.off, fontSize = 10.5.sp)
                }
                SafeCard(Modifier.weight(1f), fallbackTitle = "负载") {
                    CardTitle("负载", Priv.SHIZUKU)
                    Spacer(Modifier.height(8.dp))
                    Text(load?.split(" ")?.getOrNull(0) ?: "–",
                        color = if (load != null) Ink.tx else Ink.off,
                        fontSize = 26.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    // UX: 直接显示 1/5/15 分钟三值，用户能看懂负载含义
                    Text(if (load != null) "1 / 5 / 15 分钟均值"
                            else "需 Shizuku · 1/5/15 分钟均值",
                        color = Ink.off, fontSize = 10.5.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // ⑤ 内存详情（总览只给已用%，此处给可用/总量/缓存/Swap）
        item {
            SafeCard(fallbackTitle = "内存") {
                CardTitle("内存", Priv.FREE,
                    right = { Text("meminfo · 实时", color = Ink.off, fontSize = 10.5.sp) })
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            AnimatedNum(mem?.availGB?.toFloat(), { f -> "%.1f".format(f) },
                                color = Ink.tx, sizeSp = 22)
                            Text(" / ", color = Ink.tx2, fontSize = 18.sp,
                                fontFamily = FontFamily.Monospace)
                            AnimatedNum(mem?.totalGB?.toFloat(), { f -> "%.1f".format(f) },
                                color = Ink.tx, sizeSp = 22)
                        }
                        Text("可用 / 总量 GB", color = Ink.off, fontSize = 10.5.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        MemBar("已用", mem?.usedPercent, Ink.accent)
                        Spacer(Modifier.height(8.dp))
                        // v20.21(P2-12): Swap 青绿——与"已用"主蓝区分
                        MemBar("Swap", mem?.swapUsedPercent, Ink.clusterEff)
                    }
                }
                Spacer(Modifier.height(8.dp))
                val m = mem   // v0.28.0: 委托属性不可 smart cast，先解引用局部 val
                Text(
                    if (m != null)
                        "缓存 %.1f GB · Swap %.1f / %.1f GB".format(
                            m.cachedKB / 1048576.0, m.swapUsedGB, m.swapTotalGB)
                    else "免提权可读 · 正在读取",
                    color = Ink.off, fontSize = 10.5.sp)
            }
        }

        // 底部留白
        item { Spacer(Modifier.height(4.dp)) }
    
    }}
}

/** 电池状态人话文案（与电池页 stateText 同口径） */
private fun batteryStateText(b: com.a41probe.monitor.data.BatteryData): String = when {
    b.isFull -> "已充满"
    b.isNotCharging -> "未充电"
    b.capacity != null && b.capacity >= 100 && b.isCharging ->
        if ((b.currentDisplay ?: 0.0) >= 0.5) "满电收尾中" else "涓流补电中"
    b.isCharging -> if (b.chargeMode == "快充") "快充中" else "充电中"
    else -> "放电中"
}

/** 内存条：标签 + 百分比 + 水平填充条（与 8 核频率条同风格） */
@Composable
private fun MemBar(label: String, percent: Int?, color: androidx.compose.ui.graphics.Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Ink.tx2, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text(percent?.toString()?.plus("%") ?: "–", color = Ink.tx2, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(4.dp))
        AnimatedFracBar(
            frac = (percent ?: 0) / 100f, color = color.copy(alpha = 0.85f),
            height = 9.dp, corner = 4.dp,
        )
    }
}

/** 总览卡格子：标签 + 大数字 + 单位（Row 内 weight 均分）
 *  animated 非空时数字平滑滚动（首次直接定位） */
@Composable
private fun RowScope.MetricTile(
    label: String, value: String, unit: String, color: androidx.compose.ui.graphics.Color,
    mono: Boolean = false,
    animated: Float? = null, fmt: String = "%.0f",
    sizeSp: Int = 26,
    rollFromZero: Boolean = false,
    onRolled: () -> Unit = {},
) {
    Column(Modifier.weight(1f)) {
        Text(label, color = Ink.tx2, fontSize = 11.5.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            if (animated != null) {
                AnimatedNum(animated, color = color, sizeSp = sizeSp, mono = mono,
                    format = { f -> String.format(fmt, f) },
                    rollFromZero = rollFromZero, onRolled = onRolled)
            } else {
                Text(value, color = color, fontSize = sizeSp.sp,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                    fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(3.dp))
            Text(unit, color = Ink.off, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}
