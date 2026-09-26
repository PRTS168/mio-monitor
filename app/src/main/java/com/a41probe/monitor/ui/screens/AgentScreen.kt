package com.a41probe.monitor.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.a41probe.monitor.data.MonitorViewModel
import com.a41probe.monitor.data.agent.AgentService
import com.a41probe.monitor.data.agent.AgentState
import com.a41probe.monitor.data.remote.PrivLevel
import com.a41probe.monitor.ui.components.BrandKeepAlive
import com.a41probe.monitor.ui.components.ProbeButton
import com.a41probe.monitor.ui.components.ProbeCard
import com.a41probe.monitor.ui.theme.Ink
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 被监控模式页（AgentScreen）。 */
@Composable
fun AgentScreen(
    vm: MonitorViewModel,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val running by AgentState.running.collectAsState()
    val port by AgentState.port.collectAsState()
    val startedAt by AgentState.startedAt.collectAsState()
    val clients by AgentState.clients.collectAsState()
    val priv by AgentState.priv.collectAsState()

    // P2-5: Android 13+ 开启服务前申请通知权限，保证常驻通知可见
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> AgentService.start(ctx) }
    fun notifGranted(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
        else ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { AgentHeader(onBack) }

        // 总开关
        item {
            ProbeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("开启被监控模式", color = Ink.tx, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text("启动前台服务，在局域网广播本机，允许其他设备查看",
                            color = Ink.tx2, fontSize = 11.5.sp, lineHeight = 16.sp)
                    }
                    // 防重入：切换后等真实 running 状态到位（或 2s 超时）才接受下一次，
                    // 避免无线输入重复事件/快速连点造成 stop→start 抖动。
                    var busy by remember { mutableStateOf(false) }
                    LaunchedEffect(running) { busy = false }
                    LaunchedEffect(busy) { if (busy) { delay(2000); busy = false } }
                    Switch(
                        checked = running,
                        onCheckedChange = { v ->
                            if (!busy && v != running) {
                                busy = true
                                when {
                                    !v -> AgentService.stop(ctx)
                                    notifGranted() -> AgentService.start(ctx)
                                    else -> notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Ink.accent),
                    )
                }
            }
        }

        // 采集授权档位
        item {
            ProbeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("采集授权档位", color = Ink.tx, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        val shown = if (running) priv else privFromVm(vm)
                        Text("当前：${shown.label}" + when (shown) {
                            PrivLevel.FREE -> "（仅基础参数）"
                            PrivLevel.SHIZUKU -> "（含 Thermal HAL/热缓解/协商值）"
                            PrivLevel.ROOT -> "（含充电器输入侧/协议识别）"
                        }, color = Ink.tx2, fontSize = 11.5.sp)
                    }
                    ProbeButton(text = "检测授权", onClick = {
                        vm.refreshShizuku()
                    })
                }
                Spacer(Modifier.height(8.dp))
                Text("授权在「设置 - 提权通道」完成；无授权也能用基础参数，不会整块空白。",
                    color = Ink.tx3, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }

        // 运行状态
        item {
            ProbeCard {
                Text("运行状态", color = Ink.tx, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                InfoRow("状态", if (running) "运行中" else "已终止",
                    if (running) Ink.ok else Ink.off)
                if (running) {
                    InfoRow("服务端口", port.toString())
                    InfoRow("启动时间", fmtTime(startedAt))
                }
            }
        }

        // 防杀后台策略
        item {
            ProbeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("防杀后台策略", color = Ink.tx, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("推荐全部开启", color = Ink.tx3, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                GuardRow(
                    title = "前台服务通知",
                    sub = "常驻通知，降低被杀概率（START_STICKY 自动重启）",
                    enabled = running,
                    trailing = { StateTag(if (running) "已开启" else "随启动", running) },
                )
                val ignoring = remember { mutableStateOf(isIgnoringBattery(ctx)) }
                GuardRow(
                    title = "忽略电池优化",
                    sub = "加入白名单，系统休眠时不被冻结",
                    enabled = true,
                    trailing = {
                        ProbeButton(
                            text = if (ignoring.value) "已允许" else "去设置",
                            onClick = {
                                if (!ignoring.value) requestIgnoreBattery(ctx)
                                ignoring.value = isIgnoringBattery(ctx)
                            },
                        )
                    },
                )
                var keep by remember {
                    mutableStateOf(
                        ctx.getSharedPreferences(AgentService.PREFS, Context.MODE_PRIVATE)
                            .getBoolean(AgentService.KEY_KEEP_AWAKE, false)
                    )
                }
                GuardRow(
                    title = "锁屏保持连接",
                    sub = "锁屏后维持 WiFi 与采集（略微增加耗电）",
                    enabled = true,
                    trailing = {
                        Switch(
                            checked = keep,
                            onCheckedChange = { v ->
                                keep = v
                                ctx.getSharedPreferences(AgentService.PREFS, Context.MODE_PRIVATE)
                                    .edit().putBoolean(AgentService.KEY_KEEP_AWAKE, v).apply()
                                // 运行中改变需重启生效
                                if (running) {
                                    AgentService.stop(ctx)
                                    AgentService.start(ctx)
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Ink.accent),
                        )
                    },
                )
            }
        }

        // 厂商后台保活（国产 ROM 关键，v0.24）
        item {
            ProbeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("厂商后台保活", color = Ink.tx, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("重要", color = Ink.warn, fontSize = 11.sp,
                        modifier = Modifier.clip(RoundedCornerShape(7.dp))
                            .background(Ink.warn.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("仅靠前台服务在国产系统仍可能被清理，请按机型完成下列设置：",
                    color = Ink.tx3, fontSize = 11.5.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(6.dp))
                val keepItems = remember { BrandKeepAlive.items(ctx) }
                keepItems.forEach { ki ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(ki.title, color = Ink.tx, fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium)
                            Text(ki.sub, color = Ink.tx3, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        ProbeButton(text = ki.button, onClick = {
                            if (!BrandKeepAlive.launch(ctx, ki)) android.widget.Toast
                                .makeText(ctx, "未找到该机型设置页，已打开应用信息",
                                    android.widget.Toast.LENGTH_SHORT).show()
                        })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Ink.accent.copy(alpha = 0.07f)).padding(10.dp),
                ) {
                    Text(BrandKeepAlive.RECENT_LOCK_TIP, color = Ink.tx3,
                        fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }

        // 已连接监控端
        item {
            ProbeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已连接监控端", color = Ink.tx, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${clients.size} 台", color = Ink.tx2, fontSize = 12.sp)
                    Spacer(Modifier.width(10.dp))
                    ProbeButton(text = "断开全部", onClick = {
                        // 重启服务即断开全部监控端
                        AgentService.stop(ctx)
                        AgentService.start(ctx)
                    })
                }
                Spacer(Modifier.height(8.dp))
                if (clients.isEmpty()) {
                    Text("暂无监控端连接", color = Ink.tx3, fontSize = 12.5.sp)
                } else {
                    clients.forEach { c ->
                        InfoRow(c.host, "自 ${fmtTime(c.connectedAt)}")
                    }
                }
            }
        }

        // 终止
        item {
            ProbeButton(
                text = "终止被监控模式",
                accent = false,
                onClick = { AgentService.stop(ctx) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { Spacer(Modifier.height(6.dp)) }
    }
}

@Composable
private fun AgentHeader(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProbeButton(text = "返回", onClick = onBack)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("被监控模式", color = Ink.tx, fontSize = 20.sp,
                fontWeight = FontWeight.Bold)
            Text("本机 · ${Build.MODEL}", color = Ink.tx2, fontSize = 12.sp)
        }
    }
}

@Composable
private fun GuardRow(
    title: String,
    sub: String,
    enabled: Boolean,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Ink.tx else Ink.tx3, fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium)
            Text(sub, color = Ink.tx2, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

@Composable
private fun InfoRow(name: String, value: String, dot: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(dot))
            Spacer(Modifier.width(8.dp))
        }
        Text(name, color = Ink.tx2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Ink.tx, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StateTag(text: String, good: Boolean) {
    Box(
        Modifier.clip(RoundedCornerShape(7.dp))
            .background((if (good) Ink.ok else Ink.off).copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, color = if (good) Ink.ok else Ink.tx2, fontSize = 11.sp)
    }
}

private fun privFromVm(vm: MonitorViewModel): PrivLevel {
    val s = vm.snapshot.value
    return when {
        s.rootAvailable -> PrivLevel.ROOT
        s.shizukuActive -> PrivLevel.SHIZUKU
        else -> PrivLevel.FREE
    }
}

private fun isIgnoringBattery(ctx: Context): Boolean {
    val pm = ctx.getSystemService(PowerManager::class.java)
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

private fun requestIgnoreBattery(ctx: Context) {
    runCatching {
        val i = Intent(
            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}"),
        )
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}

private fun fmtTime(ms: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
