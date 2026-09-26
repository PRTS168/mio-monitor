package com.a41probe.monitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.a41probe.monitor.data.MonitorViewModel
import com.a41probe.monitor.data.CoolingDev
import com.a41probe.monitor.data.ThermalZone
import com.a41probe.monitor.ui.components.DotBadge
import com.a41probe.monitor.ui.components.pressClick
import com.a41probe.monitor.ui.components.BarRow
import com.a41probe.monitor.ui.components.CardTitle
import com.a41probe.monitor.ui.components.ProbeCard
import com.a41probe.monitor.ui.components.tempColor
import com.a41probe.monitor.ui.components.tempFrac
import com.a41probe.monitor.ui.theme.Ink
import com.a41probe.monitor.ui.theme.Priv
import kotlin.math.roundToInt

private fun f1(v: Double?): String = if (v == null) "–" else ((v * 10).roundToInt() / 10.0).toString()

private data class HeadRow(val label: String, val cur: Double, val throttle: Double)

/** 按 CPU/GPU/外壳/电池/NPU 五类，取每类中"当前/降频阈值"比值最高的代表 */
private fun buildHeadroom(snap: com.a41probe.monitor.data.Snapshot): List<HeadRow> {
    val typeMap = mapOf("CPU" to "CPU", "GPU" to "GPU", "SKIN" to "外壳",
        "BATTERY" to "电池", "NPU" to "NPU")
    // P2-13: 阈值必须 >0，否则 cur/throttle 除零得 Infinity，误显成"已顶满红线"
    return snap.thresholds.filter { (it.throttleC ?: 0.0) > 0.0 && it.type in typeMap }
        .mapNotNull { th ->
            val cur = snap.halTemps.firstOrNull { it.name == th.name }?.tempC
                ?: return@mapNotNull null
            HeadRow(typeMap[th.type]!!, cur, th.throttleC!!)
        }
        .groupBy { it.label }
        .map { (_, rows) -> rows.maxByOrNull { it.cur / it.throttle }!! }
        .sortedByDescending { it.cur / it.throttle }
}

private fun headColor(frac: Float): Color = when {
    frac >= 0.9f -> Ink.danger
    frac >= 0.75f -> Ink.warn
    else -> Ink.ok
}

/** cooling type → 人话 */
private fun coolingZh(type: String): String = when (type) {
    "thermal-cpufreq-0" -> "小核限频"
    "thermal-cpufreq-1" -> "中核限频"
    "thermal-cpufreq-2" -> "大核限频"
    "gpu" -> "GPU 限频"
    "thermal-devfreq-0" -> "总线限频"
    "ddr-cdev" -> "DDR 降速"
    "display-fps" -> "屏幕降帧"
    "panel0-backlight" -> "屏幕降亮"
    "ufs" -> "存储限速"
    "battery" -> "充电限流"
    "cdsp_hw" -> "NPU 限频"
    "cdsp" -> "DSP 限频"
    "wlan" -> "网络限速"
    else -> type
}

private fun coolingColor(r: Float): Color = when {
    r >= 0.67f -> Ink.danger
    r >= 0.34f -> Ink.warn
    else -> Ink.accent
}

/** Thermal HAL 官方热状态徽章（0 正常 … 6 关机临界） */
@Composable
private fun ThermalStatusBadge(status: Int?) {
    if (status == null) return
    val pair = when (status) {
        0 -> "正常" to Ink.ok
        1 -> "偏热" to Ink.accent
        2 -> "温热" to Ink.warn
        3 -> "较热" to Ink.warn
        4 -> "很热" to Ink.danger
        5 -> "紧急" to Ink.danger
        else -> "关机临界" to Ink.danger
    }
    DotBadge(pair.second, "热状态·${pair.first}")
}

/** 热力：最高/平均 + 分组热区 + 84 网格 */
@Composable
fun ThermalScreen(vm: MonitorViewModel) {
    val snap by vm.snapshot.collectAsState()
    val all = snap.thermalAll
    val avg = snap.thermalAvg

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 最高 / 平均
        item {
            ProbeCard {
                if (all.isEmpty()) {
                    // v20.10: 无 Shizuku 也先给出可读温度（电池温度 App 域可读），不整页空白引导
                    val bt = snap.battery.tempC
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                CardTitle("电池温度", Priv.FREE)
                                Spacer(Modifier.height(3.dp))
                                Text("${f1(bt)}°C", color = tempColor(bt), fontSize = 22.sp,
                                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                Text("内核上报 · 免提权可读", color = Ink.off, fontSize = 10.sp)
                            }
                            Box(Modifier.width(1.dp).height(44.dp).background(Ink.stroke))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                CardTitle("84 区热区", Priv.SHIZUKU, rootActive = snap.rootAvailable)
                                Spacer(Modifier.height(3.dp))
                                Text("未解锁", color = Ink.off, fontSize = 22.sp,
                                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                Text(if (snap.rootAvailable) "Root 读取中…" else "需 Shizuku 授权",
                                    color = Ink.off, fontSize = 10.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("授权后解锁全部 84 个温度区（结温/外壳/射频等）· 设置页一键发起",
                            color = Ink.tx2, fontSize = 11.sp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            CardTitle("最高结温", Priv.SHIZUKU, rootActive = snap.rootAvailable)
                            Spacer(Modifier.height(3.dp))
                            val top = snap.maxThermal
                            Text("${f1(top?.tempC)}°C",
                                color = tempColor(top?.tempC), fontSize = 22.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                            Text(top?.name ?: "无权限", color = Ink.off, fontSize = 10.sp)
                        }
                        Box(Modifier.width(1.dp).height(44.dp).background(Ink.stroke))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            CardTitle("平均")
                            Spacer(Modifier.height(3.dp))
                            Text("${f1(avg)}°C", color = Ink.tx, fontSize = 22.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                            Text("全温区均值", color = Ink.off, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 外壳侧温度：只取背板热敏电阻 skin*（真实外壳温度）。
                    // tof-therm 是相机 ToF 传感器、非外壳温度且读数恒定 46.9℃ 异常，混入会让"外壳侧"虚高（46.9℃ > 结温 43℃ 反直觉）
                    val shellZones = all.filter { z ->
                        z.tempC != null && z.tempC > 0 && z.name.startsWith("skin")
                    }
                    val shellMax = shellZones.maxByOrNull { it.tempC!! }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("外壳侧", color = Ink.tx2, fontSize = 11.sp)
                        Spacer(Modifier.width(8.dp))
                        if (shellMax != null) {
                            Text("${f1(shellMax.tempC)}°C",
                                color = Ink.tx, fontSize = 17.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(6.dp))
                            Text(shellMax.name, color = Ink.off, fontSize = 9.5.sp, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        } else {
                            Text("–", color = Ink.off, fontSize = 15.sp)
                        }
                    }
                    Text("背板热敏电阻(skin) · 手感参考，握持面热惯性偏热，背板通常低于结温 15~25°C",
                        color = Ink.off, fontSize = 10.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        // 距温度红线（HAL 官方阈值 vs 当前温度）
        item {
            val rows = remember(snap.thresholds, snap.halTemps) { buildHeadroom(snap) }
            if (rows.isNotEmpty()) {
                ProbeCard {
                    CardTitle("距温度红线", Priv.SHIZUKU, rootActive = snap.rootAvailable,
                        right = { Text("Thermal HAL", color = Ink.off, fontSize = 10.5.sp) })
                    Spacer(Modifier.height(6.dp))
                    rows.forEach { r ->
                        val frac = (r.cur / r.throttle).toFloat().coerceIn(0f, 1f)
                        BarRow(name = r.label, nameWidth = 48.dp, frac = frac,
                            value = "${f1(r.cur)}/${f1(r.throttle)}°",
                            barColor = headColor(frac))
                    }
                    Spacer(Modifier.height(3.dp))
                    Text("进度 = 当前温度 / 官方温度阈值 · 外壳/电池为安全线，其余为降频线",
                        color = Ink.off, fontSize = 10.sp)
                }
            }
        }
        // 热缓解动作（cooling devices 实时等级；默认只显示已触发，避免常温 13 行灰条刷屏）
        item {
            val devs = snap.cooling
            if (devs.isNotEmpty()) {
                ProbeCard {
                    CardTitle("热缓解动作", Priv.SHIZUKU, rootActive = snap.rootAvailable,
                        right = { ThermalStatusBadge(snap.thermalStatus) })
                    Spacer(Modifier.height(6.dp))
                    var expanded by remember { mutableStateOf(false) }
                    val ordered = remember(devs) {
                        devs.sortedWith(compareByDescending<CoolingDev> { it.active }
                            .thenByDescending { it.ratio })
                    }
                    val shown = if (expanded) ordered else ordered.filter { it.active }
                    if (shown.isEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text("当前无热缓解动作 · 系统未限制性能",
                            color = Ink.tx2, fontSize = 12.sp)
                    } else {
                        shown.forEach { c ->
                            val col = if (c.active) coolingColor(c.ratio) else Ink.off
                            BarRow(name = coolingZh(c.name), nameWidth = 88.dp,
                                frac = c.ratio,
                                value = "${c.cur ?: "–"}/${c.max ?: "–"}",
                                barColor = col)
                        }
                    }
                    val inactiveN = devs.count { !it.active }
                    if (inactiveN > 0) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth().pressClick { expanded = !expanded },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (expanded) "收起未触发项"
                                else "查看全部 ${devs.size} 项 · $inactiveN 项未触发",
                                color = Ink.accent, fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        // 分组热区
        item { GroupedZones(all, snap.rootAvailable) }
        // 84 网格
        item {
            ProbeCard {
                CardTitle(if (all.isEmpty()) "全部 84 区" else "全部 ${all.size} 区", Priv.SHIZUKU,
                    rootActive = snap.rootAvailable)
                Spacer(Modifier.height(8.dp))
                Grid84(all)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (all.isEmpty())
                        "App 域无权限 · 需 Shizuku"
                    else "20–50°C 归一化",
                    color = Ink.off, fontSize = 10.sp,
                )
            }
        }
        item {
            Text("≥42°C 红 · ≥36°C 橙 · 其余绿 · 灰格=未启用传感器",
                color = Ink.off, fontSize = 10.5.sp,
                modifier = Modifier.padding(horizontal = 4.dp))
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

private val GROUP_KEYS = listOf(
    "CPU簇" to listOf("cpuss", "cpu"),
    "GPU/NPU/内存" to listOf("gpuss", "nsp", "ddr", "video"),
    "射频/充电/外壳" to listOf("pa", "pm8350", "battery", "skin", "usb", "tof"),
)

/** UX(M10): sysfs 热区代号 → 人话前缀 + 编号（cpuss-0-0 → "CPU 0-0"，原名语义保留） */
private fun zoneNameZh(raw: String): String {
    val (zh, rest) = when {
        raw.startsWith("cpuss") -> "CPU" to raw.removePrefix("cpuss")
        raw.startsWith("cpu") -> "CPU" to raw.removePrefix("cpu")   // cpu-1-5 单核热点
        raw.startsWith("gpuss") -> "GPU" to raw.removePrefix("gpuss")
        raw.startsWith("nsp") -> "NPU" to raw.removePrefix("nsp")
        raw.startsWith("ddr") -> "内存" to raw.removePrefix("ddr")
        raw.startsWith("video") -> "多媒体" to raw.removePrefix("video")
        raw.startsWith("battery") -> "电池" to raw.removePrefix("battery")
        raw.startsWith("skin") -> "外壳" to raw.removePrefix("skin")
        raw.startsWith("usb") -> "充电" to raw.removePrefix("usb")
        raw.startsWith("tof") -> "ToF" to raw.removePrefix("tof")
        raw.startsWith("pm8350") -> "电源" to raw.removePrefix("pm8350")
        raw.startsWith("pa") -> "射频" to raw.removePrefix("pa")
        else -> return raw
    }
    return zh + rest.replaceFirst("-", " ").replace("_", " ")
}

@Composable
private fun GroupedZones(all: List<ThermalZone>, rootActive: Boolean) {
    // M10: 分组过滤/排序只随数据变化重算，滚动重组不再全量执行
    // L16: 保留每组总数，超出 5 条时给出"还有 N 个"提示
    val groups = remember(all) {
        GROUP_KEYS.map { (title, prefixes) ->
            val allItems = prefixes.flatMap { p ->
                all.filter { it.name.startsWith(p) && it.tempC != null }
            }.distinctBy { it.id }.sortedByDescending { it.tempC }
            Triple(title, allItems.take(5), allItems.size)
        }.filter { (_, items, _) -> items.isNotEmpty() }
    }
    groups.forEach { (title, items, total) ->
        ProbeCard {
            CardTitle(title, Priv.SHIZUKU, rootActive = rootActive)
            Spacer(Modifier.height(2.dp))
            items.forEach { z ->
                BarRow(
                    name = zoneNameZh(z.name),
                    frac = tempFrac(z.tempC),
                    value = "${f1(z.tempC)}°",
                    barColor = tempColor(z.tempC),
                )
            }
            if (total > items.size) {
                Spacer(Modifier.height(3.dp))
                Text("还有 ${total - items.size} 个热区", color = Ink.off, fontSize = 10.5.sp)
            }
        }
    }
}

@Composable
private fun Grid84(all: List<ThermalZone>) {
    val cells = all.take(84)
    if (cells.isEmpty()) {
        // 占位 84 格（置灰）
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(7) { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(12) { c ->
                        val idx = r * 12 + c
                        Box(Modifier.weight(1f).height(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Ink.stroke))
                    }
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        cells.chunked(12).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { z ->
                    // 负值 = 未使能/未接传感器（mmw、sdr 等 -273℃ 占位），画明显灰格（stroke）不误导
                    val valid = z.tempC != null && z.tempC > 0
                    Box(Modifier.weight(1f).height(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (valid) tempColor(z.tempC).copy(alpha = 0.85f)
                            else Ink.stroke))
                }
                repeat(12 - row.size) {
                    Box(Modifier.weight(1f).height(14.dp))
                }
            }
        }
    }
}
