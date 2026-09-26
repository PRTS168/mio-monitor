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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.a41probe.monitor.data.monitor.DeviceEntry
import com.a41probe.monitor.data.monitor.DeviceSource
import com.a41probe.monitor.data.monitor.MonitorRepository
import com.a41probe.monitor.data.remote.ConnState
import com.a41probe.monitor.data.remote.PrivLevel
import com.a41probe.monitor.data.remote.RemoteHalTemp
import com.a41probe.monitor.data.remote.RemoteSnapshot
import com.a41probe.monitor.ui.components.ProbeButton
import com.a41probe.monitor.ui.components.ProbeCard
import com.a41probe.monitor.ui.components.pressClick
import com.a41probe.monitor.ui.theme.Ink

/** 监控端：多设备仪表盘。 */
@Composable
fun MonitorScreen(
    onOpenDevice: (String) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { MonitorRepository.start(ctx) }
    // P2-16: 离开监控页仅停 mDNS 扫描（省电），保活已建立的 TCP 连接；再进入时恢复扫描
    DisposableEffect(Unit) { onDispose { MonitorRepository.stopDiscovery() } }
    val devices by MonitorRepository.devices.collectAsState()
    val scanning by MonitorRepository.scanning.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProbeButton(text = "返回", onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("监控端", color = Ink.tx, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("局域网 · mDNS 自动发现", color = Ink.tx2, fontSize = 12.sp)
                }
            }
        }

        // 扫描状态
        item {
            ProbeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(9.dp).clip(RoundedCornerShape(5.dp))
                            .background(if (scanning) Ink.ok else Ink.off)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        if (scanning) "正在扫描局域网 · 发现 ${devices.size} 台设备"
                        else "扫描已停止",
                        color = Ink.tx, fontSize = 13.5.sp, modifier = Modifier.weight(1f),
                    )
                    ProbeButton(text = "添加设备", onClick = { showAdd = true })
                }
            }
        }

        if (devices.isEmpty()) {
            item {
                ProbeCard {
                    Text("尚未发现被监控设备", color = Ink.tx, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("请在另一台手机的「被监控模式」中开启服务，并确保两台手机连同一局域网；" +
                        "也可点「添加设备」手动输入 IP。",
                        color = Ink.tx2, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }

        items(devices.size) { idx ->
            DeviceCard(
                e = devices[idx],
                onOpen = { onOpenDevice(devices[idx].key) },
                onRemove = { MonitorRepository.remove(devices[idx].key) },
            )
        }

        item { Spacer(Modifier.height(8.dp)) }
    }

    if (showAdd) {
        AddDeviceDialog(
            onDismiss = { showAdd = false },
            onConfirm = { host, port ->
                showAdd = false
                MonitorRepository.manualAdd(host, port)
            },
        )
    }
}

@Composable
private fun DeviceCard(
    e: DeviceEntry,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val s = e.snap
    val info = e.info
    val online = e.state == ConnState.ONLINE && s != null
    val title = info?.model?.ifBlank { null } ?: e.host
    val soc = info?.soc?.ifBlank { null }

    ProbeCard(modifier = Modifier.pressClick(onOpen)) {
        // 头部
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Ink.tx, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            PrivTag(s?.priv ?: info?.priv ?: PrivLevel.FREE)
            Spacer(Modifier.width(6.dp))
            StateDot(e.state)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            buildString {
                append("${e.host}:${e.port}")
                if (soc != null) append(" · $soc")
                if (e.latencyMs >= 0) append(" · ${e.latencyMs}ms")
            },
            color = Ink.tx2, fontSize = 11.sp,
        )

        if (!online) {
            Spacer(Modifier.height(10.dp))
            Text(e.log.ifBlank { stateText(e.state) },
                color = if (e.state == ConnState.RETRYING) Ink.warn else Ink.tx3,
                fontSize = 12.5.sp)
            Spacer(Modifier.height(8.dp))
            Row {
                ProbeButton(text = "重新连接", onClick = { MonitorRepository.connect(e.key) })
                Spacer(Modifier.width(8.dp))
                ProbeButton(text = "移除", onClick = onRemove)
            }
            return@ProbeCard
        }

        // hero
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroCell(
                Modifier.weight(1f),
                s.cpuTotal?.let { "%.0f".format(it) } ?: "–",
                "CPU 占用", Ink.accent,
            )
            HeroCell(
                Modifier.weight(1f),
                halTemp(s, 0)?.let { "%.1f".format(it) } ?: "–",
                "结温", Ink.warn,
            )
            HeroCell(
                Modifier.weight(1f),
                s.battery.capacity?.toString() ?: "–",
                "电量", Ink.ok,
            )
        }

        // CPU 频率
        SectionTitle("CPU 频率 · ${s.freqs.size} 核")
        val labelColor = remember(s.clusters, s.maxFreqs) { remoteClusterColorMap(s) }
        s.freqs.forEachIndexed { i, f ->
            MiniFreqRow(
                label = s.clusters.getOrNull(i) ?: "cpu$i",
                freq = f,
                max = s.maxFreqs.getOrNull(i),
                color = labelColor[s.clusters.getOrNull(i)] ?: Ink.accent,
            )
        }

        // 温度（HAL）
        SectionTitle("温度 · Thermal HAL")
        MiniGrid {
            GridItem("结温最高", halTemp(s, 0)?.fmt1())
            GridItem("外壳", halTemp(s, 3)?.fmt1())
            GridItem("GPU", halTemp(s, 1)?.fmt1())
            GridItem("电池", halTemp(s, 2)?.fmt1())
            GridItem("NPU", halTemp(s, 9)?.fmt1())
            GridItem("热状态", thermalStatusText(s.thermalStatus))
        }

        // 电池
        SectionTitle("电池")
        MiniGrid {
            GridItem("电压", s.battery.voltage?.fmt1())
            GridItem("电流", s.battery.currentA?.fmt1())
            GridItem("功率", s.battery.powerW?.fmt1())
            GridItem("状态", s.battery.chargeMode ?: s.battery.status)
            GridItem("健康度", s.battery.healthPercent?.let { "%.0f".format(it) })
            GridItem("循环", s.battery.cycle?.toString())
        }

        // 内存 / GPU / 充电
        SectionTitle("内存 · GPU · 充电")
        FlatRow("内存占用", "%.1f / %.1f GB".format(s.mem.totalGB - s.mem.availGB, s.mem.totalGB))
        FlatRow("GPU 频率", s.gpuFreq?.let { "$it MHz" } ?: "–")
        FlatRow(
            "充电协商上限",
            if (s.battery.negotVoltV != null || s.battery.negotCurA != null)
                "${s.battery.negotVoltV?.fmt1() ?: "–"}V · ${s.battery.negotCurA?.fmt1() ?: "–"}A"
            else "–",
        )
        FlatRow(
            "充电器输入",
            s.battery.inputPowerW?.let { "${it.fmt1()}W" }
                ?: if (s.priv == PrivLevel.ROOT) "–" else "仅 Root",
        )

        Spacer(Modifier.height(10.dp))
        Row {
            ProbeButton(text = "查看详情", accent = true, onClick = onOpen)
            Spacer(Modifier.width(8.dp))
            ProbeButton(text = "移除", onClick = onRemove)
        }
    }
}

@Composable
private fun HeroCell(modifier: Modifier, value: String, label: String, color: Color) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.panel)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = color, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Ink.tx2, fontSize = 10.5.sp)
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, color = Ink.tx2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 13.dp, bottom = 5.dp))
}

@Composable
private fun MiniFreqRow(label: String, freq: Int?, max: Int?, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Ink.tx2, fontSize = 10.5.sp, modifier = Modifier.width(40.dp))
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Ink.stroke)
        ) {
            val frac = if (freq != null && max != null && max > 0)
                (freq.toFloat() / max).coerceIn(0f, 1f) else 0f
            Box(
                Modifier.fillMaxWidth(frac).height(7.dp)
                    .clip(RoundedCornerShape(4.dp)).background(color)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(freq?.toString() ?: "–", color = Ink.tx2, fontSize = 10.5.sp,
            modifier = Modifier.width(46.dp))
    }
}

@Composable
private fun MiniGrid(content: @Composable GridScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        GridScope.content()
    }
}

private object GridScope {
    @Composable
    fun GridItem(name: String, value: String?) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(name, color = Ink.tx2, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(value ?: "–", color = Ink.tx, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun FlatRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(name, color = Ink.tx2, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        Text(value, color = Ink.tx, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PrivTag(priv: PrivLevel) {
    val (bg, fg) = when (priv) {
        PrivLevel.SHIZUKU -> Ink.accent.copy(alpha = 0.13f) to Ink.accent
        PrivLevel.ROOT -> Ink.ok.copy(alpha = 0.15f) to Ink.ok
        PrivLevel.FREE -> Ink.stroke to Ink.tx2
    }
    Box(
        Modifier.clip(RoundedCornerShape(7.dp)).background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(priv.label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StateDot(state: ConnState) {
    val c = when (state) {
        ConnState.ONLINE -> Ink.ok
        ConnState.CONNECTING, ConnState.DISCOVERED -> Ink.accent
        ConnState.RETRYING -> Ink.warn
        ConnState.OFFLINE -> Ink.off
    }
    Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(c))
}

@Composable
private fun AddDeviceDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动添加设备") },
        text = {
            Column {
                Text("输入被监控端的 IP 与端口（端口见被监控模式「运行状态」）",
                    color = Ink.tx2, fontSize = 11.5.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = host, onValueChange = { host = it },
                    label = { Text("IP 地址") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("端口") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = port.toIntOrNull() ?: 0
                if (host.isNotBlank() && p > 0) onConfirm(host.trim(), p)
            }) { Text("连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---- helpers ----
private fun halTemp(s: RemoteSnapshot, type: Int): Double? =
    s.hal.filter { it.type == type }.mapNotNull { it.tempC }.maxOrNull()

private fun Double.fmt1(): String = "%.1f".format(this)

private fun thermalStatusText(st: Int?): String = when (st) {
    null, 0 -> "正常"
    1 -> "轻微"
    2 -> "中度"
    3 -> "严重"
    4 -> "紧急"
    5 -> "关机"
    6 -> "关机"
    else -> "–"
}

private fun stateText(state: ConnState): String = when (state) {
    ConnState.DISCOVERED -> "已发现，准备连接…"
    ConnState.CONNECTING -> "连接中…"
    ConnState.RETRYING -> "连接失败，准备重连…"
    ConnState.OFFLINE -> "设备离线"
    ConnState.ONLINE -> "在线"
}

/**
 * 远端 label→颜色：RemoteSnapshot 无角色字段，按各簇 maxFreq 降序推断角色
 * （与本机 CpuTopologyDetector 同口径：最大=PRIME 暖橙 / 次大=PERF 蓝 / 最小=EFF 青绿 / 4簇第3=BAL 紫）。
 */
private fun remoteClusterColorMap(s: RemoteSnapshot): Map<String, Color> {
    val byLabel = HashMap<String, Int>()
    s.maxFreqs.forEachIndexed { i, m ->
        if (m != null) {
            val l = s.clusters.getOrNull(i) ?: return@forEachIndexed
            byLabel[l] = maxOf(byLabel[l] ?: 0, m)
        }
    }
    val order = byLabel.entries.sortedByDescending { it.value }.map { it.key }
    val n = order.size
    return order.mapIndexed { idx, l ->
        l to when {
            n <= 1 -> Ink.clusterPrime
            idx == 0 -> Ink.clusterPrime
            idx == 1 -> if (n >= 3) Ink.clusterPerf else Ink.clusterEff
            idx == 2 -> if (n >= 4) Ink.clusterBal else Ink.clusterEff
            else -> Ink.clusterEff
        }
    }.toMap()
}
