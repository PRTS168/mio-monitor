package com.a41probe.monitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.a41probe.monitor.data.ClusterRole
import com.a41probe.monitor.data.MonitorViewModel
import com.a41probe.monitor.ui.components.AnimatedFracBar
import com.a41probe.monitor.ui.components.AnimatedNum
import com.a41probe.monitor.ui.components.AreaLine
import com.a41probe.monitor.ui.components.CardTitle
import com.a41probe.monitor.ui.components.GaugeRing
import com.a41probe.monitor.ui.components.ProbeCard
import com.a41probe.monitor.ui.components.tempColor
import com.a41probe.monitor.ui.theme.Ink
import com.a41probe.monitor.ui.theme.Priv
import kotlin.math.roundToInt

private fun fmt1(v: Double?): String = if (v == null) "–" else ((v * 10).roundToInt() / 10.0).toString()

/** 簇角色色（与 CPU 页一致，统一引用 Ink 令牌） */
private fun clusterColorForRole(role: ClusterRole?): Color = when (role) {
    ClusterRole.PRIME -> Ink.clusterPrime
    ClusterRole.PERFORMANCE -> Ink.clusterPerf
    ClusterRole.EFFICIENCY -> Ink.clusterEff
    ClusterRole.BALANCE -> Ink.clusterBal
    null -> Ink.accent
}

@Composable
fun DashboardScreen(vm: MonitorViewModel, onGoSettings: () -> Unit = {}) {
    val snap by vm.snapshot.collectAsState()
    val b = snap.battery
    val health = b.healthPercent
    // UX: 仪表盘补充 CPU 总占用（proc/stat 差分，需 Shizuku）
    val cpuTotal by vm.cpuTotalPercent.collectAsState()
    val cpuTotalHist by vm.cpuTotalHist.collectAsState()
    val top = snap.maxThermal
    val tc = top?.tempC
    // v20.17(P1-5): 停更（>3s 无成功采样）时大数字统一变灰
    val stale by vm.isStale.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ① 首屏总览（v20.18 权限自适应）：
        //   有提权 → CPU 占用 + 最高结温；免提权 → 电量 + 电池温度（均 App 域可读）。
        //   首屏永远有鲜活大数字，"一下子找到重点"在未授权时也不打折。
        item {
            ProbeCard {
                val hasPriv = snap.shizukuActive || snap.rootAvailable
                if (hasPriv) {
                    CardTitle("总览")
                    Spacer(Modifier.height(10.dp))
                    Row {
                        MetricTile("CPU 占用", cpuTotal?.roundToInt()?.toString() ?: "–", "%",
                            if (stale || cpuTotal == null) Ink.off else Ink.accent, mono = true,
                            animated = cpuTotal?.toFloat(), sizeSp = 36,
                            rollFromZero = vm.heroReady, onRolled = { vm.heroRollDone() })
                        MetricTile("最高结温", fmt1(tc), "°C",
                            if (stale) Ink.off else tempColor(tc), mono = true,
                            animated = tc?.toFloat(), fmt = "%.1f", sizeSp = 36)
                    }
                } else {
                    // 免提权模式：电量 + 电池温度（免提权可读），副标引导授权
                    CardTitle("总览", right = {
                        Text("免提权模式", color = Ink.off, fontSize = 10.5.sp)
                    })
                    Spacer(Modifier.height(10.dp))
                    Row {
                        MetricTile("电量", b.capacity?.toString() ?: "–", "%",
                            if (stale || b.capacity == null) Ink.off else Ink.accent,
                            animated = b.capacity?.toFloat(), sizeSp = 36)
                        MetricTile("电池温度", fmt1(b.tempC), "°C",
                            if (stale) Ink.off else tempColor(b.tempC), mono = true,
                            animated = b.tempC?.toFloat(), fmt = "%.1f", sizeSp = 36)
                    }
                    Spacer(Modifier.height(8.dp))
                    // v20.20(P2-2): 说明行整行可点直达设置——首屏就给出授权路径
                    Text("免提权可读 · 设置页授权后解锁 CPU 占用 / 结温 / 热区",
                        color = Ink.off, fontSize = 10.5.sp,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable(onClick = onGoSettings))
                }
            }
        }
        // v20: CPU 占用曲线（S）
        item {
            ProbeCard {
                CardTitle("CPU 占用", Priv.SHIZUKU, rootActive = snap.rootAvailable,
                    right = {
                        if (!(snap.shizukuActive || snap.rootAvailable)) {
                            // v20.20(P1-4): 触控区 ≥48dp——免提权用户主 CTA，小字难点
                            Text("去授权 ›", color = Ink.accent, fontSize = 10.5.sp,
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 48.dp)
                                    .padding(horizontal = 10.dp)
                                    .clickable(onClick = onGoSettings))
                        } else {
                            Text("近 60s · proc/stat", color = Ink.off, fontSize = 10.5.sp)
                        }
                    })
                Spacer(Modifier.height(10.dp))
                AreaLine(cpuTotalHist, color = if (stale) Ink.off else Ink.accent, height = 92.dp,
                    fixedRange = 100f, axisLabels = listOf("100%", "50%", "0%"),
                    latestLabel = { f -> "${f.roundToInt()}%" },
                    emptyText = if (snap.rootAvailable) "Root 读取中…" else "需 Shizuku 授权")
            }
        }
        // ② 8 核频率
        item {
            ProbeCard {
                CardTitle("CPU 频率 · ${snap.cores.size}核", Priv.FREE)
                snap.cores.forEach { c ->
                    // 上限直接用该核 maxMHz（CpuTopology 探测），读不到不画条（灰条）
                    val max = c.maxMHz?.toFloat()
                    val frac = if (max != null && max > 0)
                        ((c.freqMHz ?: 0).toFloat() / max).coerceIn(0f, 1f) else 0f
                    val color = clusterColorForRole(c.colorRole)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.clusterLabel, color = color, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(42.dp))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            AnimatedFracBar(
                                frac = frac, color = color.copy(alpha = 0.85f),
                                height = 9.dp, corner = 4.dp,   // v20.18: 与全局横条统一
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (c.freqMHz != null) c.freqMHz.toString() else "–",
                            color = Ink.tx2, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(46.dp), textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
        // ② 电池大卡（电量环 + 电参数 + 健康度一行）
        item {
            ProbeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // UX(M7): 读不到电量时画空环 + "–"，不把"未知"伪装成 0%
                    GaugeRing(
                        fraction = b.capacity?.let { it / 100f },
                        center = b.capacity?.toString() ?: "–",
                        color = Ink.accent,
                        unit = "%",
                        centerAnimated = b.capacity?.toFloat(),
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        val fast = b.isCharging && b.chargeMode == "快充"   // v20.7: 口径与 chargeMode 归一
                        Text(
                            when {
                                fast -> "快充中"
                                b.isCharging -> "充电中"
                                else -> "放电中"
                            },
                            color = Ink.accent,
                            fontSize = 11.sp, letterSpacing = 1.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            AnimatedNum(b.voltage?.toFloat(), { f -> "%.1f".format(f) },
                                color = Ink.tx, sizeSp = 16, weight = FontWeight.Medium)
                            Text(" V", color = Ink.tx, fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(14.dp))
                            Text(if (b.isCharging) "+" else "−", color = Ink.tx, fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                            AnimatedNum(b.currentDisplay?.toFloat(), { f -> "%.1f".format(f) },
                                color = Ink.tx, sizeSp = 16, weight = FontWeight.Medium)
                            Text(" A", color = Ink.tx, fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text((if (b.isCharging) "+" else "−") + fmt1(b.powerShown) + " W",
                                color = Ink.tx2, fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.width(6.dp))
                            Text(if (b.isCharging) "充电功率 V×I" else "放电功率 V×I",
                                color = Ink.off, fontSize = 9.5.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.stroke))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("健康度", color = Ink.tx2, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedNum(health?.toFloat(), { f -> "%.0f".format(f) },
                            color = Ink.ok, sizeSp = 15, weight = FontWeight.SemiBold)
                        Text("%", color = Ink.ok, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 1.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text("满充 ${b.chargeFull ?: "–"} mAh", color = Ink.tx2, fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(12.dp))
                    Text("循环 ${b.cycleCount ?: "–"}", color = Ink.tx2, fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
        // ③ GPU 占用 | 负载
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProbeCard(Modifier.weight(1f)) {
                    CardTitle("GPU占用", Priv.FREE)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedNum(snap.gpu.busyPercent?.toFloat(), { f -> "%.0f".format(f) },
                            color = Ink.accent, sizeSp = 26)
                        Text("%", color = Ink.accent, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 2.dp))
                    }
                    Spacer(Modifier.height(2.dp))
                    // UX(M10): 人话副标题，不再裸露 gpu_busy_percentage
                    Text("GPU 使用率 · 内核上报", color = Ink.off, fontSize = 10.5.sp)
                }
                ProbeCard(Modifier.weight(1f)) {
                    CardTitle("负载", Priv.SHIZUKU)
                    Spacer(Modifier.height(8.dp))
                    val load = snap.loadStr
                    Text(load?.split(" ")?.getOrNull(0) ?: "–",
                        color = if (load != null) Ink.tx else Ink.off,
                        fontSize = 26.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    // UX: 直接显示 1/5/15 分钟三值，用户能看懂负载含义
                    Text(load ?: (if (snap.shizukuActive) "loadavg · 1/5/15 分钟"
                            else "需 Shizuku · loadavg 1/5/15 分钟"),
                        color = Ink.off, fontSize = 10.5.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // v20: 内存概览（免提权 /proc/meminfo）
        item {
            ProbeCard {
                CardTitle("内存", Priv.FREE,
                    right = { Text("meminfo · 实时", color = Ink.off, fontSize = 10.5.sp) })
                Spacer(Modifier.height(8.dp))
                val mem = snap.mem
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
                        // v20.21(P2-12): Swap 青绿——与"已用"主蓝条区分度不足，换簇色避免撞色
                        MemBar("Swap", mem?.swapUsedPercent, Ink.clusterEff)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (mem != null)
                        "缓存 %.1f GB · Swap %.1f / %.1f GB".format(
                            mem.cachedKB / 1048576.0, mem.swapUsedGB, mem.swapTotalGB)
                    else "免提权可读 · 正在读取",
                    color = Ink.off, fontSize = 10.5.sp)
            }
        }
        // ⑤ 电池温度（v20.18：有提权时保留——总览承担最高结温，此处给免提权可读的电池温度；
        // 免提权时总览已承担电池温度，跳过避免重复）
        if (snap.shizukuActive || snap.rootAvailable) {
        item {
            ProbeCard {
                CardTitle("电池温度", Priv.FREE)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    AnimatedNum(b.tempC?.toFloat(), { f -> "%.1f".format(f) },
                        color = if (stale) Ink.off else Ink.tx, sizeSp = 22)
                    Text("°C", color = if (stale) Ink.off else Ink.tx, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 2.dp))
                }
                Spacer(Modifier.height(2.dp))
                Text("内核上报", color = Ink.off, fontSize = 10.5.sp)
            }
        }
        }
        // 底部留白
        item { Spacer(Modifier.height(4.dp)) }
    }
}

/** 内存条：标签 + 百分比 + 水平填充条（与 8 核频率条同风格） */
@Composable
private fun MemBar(label: String, percent: Int?, color: Color) {
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

/** 总览卡 2×2 格子：标签 + 大数字 + 单位（Row 内使用，weight 均分）
 *  v20.4: animated 非空时数字平滑滚动（首次直接定位），prefix 显示符号前缀 */
@Composable
private fun RowScope.MetricTile(
    label: String, value: String, unit: String, color: Color, mono: Boolean = false,
    animated: Float? = null, fmt: String = "%.0f", prefix: String = "",
    sizeSp: Int = 26,   // v20.17(P1-1): 字号档位可调——总览 hero 36sp，普通指标 26sp
    rollFromZero: Boolean = false,   // v20.20(P1-1): 冷启动 hero 从 0 滚入
    onRolled: () -> Unit = {},
) {
    Column(Modifier.weight(1f)) {
        Text(label, color = Ink.tx2, fontSize = 11.5.sp)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            if (prefix.isNotEmpty()) {
                Text(prefix, color = color, fontSize = sizeSp.sp,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                    fontWeight = FontWeight.SemiBold)
            }
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
