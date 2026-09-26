package com.a41probe.monitor

import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.a41probe.monitor.data.MonitorViewModel
import com.a41probe.monitor.ui.components.DotBadge
import com.a41probe.monitor.ui.components.Motion
import com.a41probe.monitor.ui.components.ProbeButton
import com.a41probe.monitor.ui.components.liquidGlass
import com.a41probe.monitor.ui.components.rememberAnimatorScale
import com.a41probe.monitor.ui.screens.AgentScreen
import com.a41probe.monitor.ui.screens.BatteryScreen
import com.a41probe.monitor.ui.screens.CpuScreen
import com.a41probe.monitor.ui.screens.DashboardScreen
import com.a41probe.monitor.ui.screens.GpuScreen
import com.a41probe.monitor.ui.screens.MonitorScreen
import com.a41probe.monitor.ui.screens.RemoteDetailScreen
import com.a41probe.monitor.ui.screens.SensorsScreen
import com.a41probe.monitor.ui.screens.SettingsScreen
import com.a41probe.monitor.ui.screens.ThermalScreen
import com.a41probe.monitor.ui.theme.A41Theme
import com.a41probe.monitor.ui.theme.Ink
import kotlinx.coroutines.delay

enum class Screen(val label: String) {
    DASH("仪表盘"), CPU("CPU"), GPU("GPU"), BAT("电池"), THERM("温度"),
    SENS("传感器"), SET("设置"),
    AGENT("被监控"), MONITOR("监控"), RDETAIL("设备详情")
}

/** 带底部导航的本机监控页集合；双模式全屏页不在其中。 */
private val MAIN_TABS = setOf(
    Screen.DASH, Screen.CPU, Screen.GPU, Screen.BAT, Screen.THERM, Screen.SENS, Screen.SET,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(
                AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        setContent { A41Theme { MainScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MonitorViewModel = viewModel()) {
    // v20.21(P2-18): tab 位置进程级保存——旋转/深色模式重建不丢当前页
    var screen by rememberSaveable(stateSaver = Saver<Screen, String>(
        save = { it.name },
        // v20.24(P1-2): 兜底——跨版本枚举重命名/删除时恢复不崩，回落仪表盘
        restore = { runCatching { Screen.valueOf(it) }.getOrDefault(Screen.DASH) },
    )) { mutableStateOf(Screen.DASH) }
    var showShizuku by remember { mutableStateOf(false) }   // UX: 顶栏徽章点击 → 提权说明
    var remoteKey by rememberSaveable { mutableStateOf("") } // P2-15: key 持久化，旋转/重建不空白
    // P2-14: 全屏双模式页系统返回键按层级回退（详情→监控→设置），而非直接退出 App
    BackHandler(enabled = screen !in MAIN_TABS) {
        screen = when (screen) {
            Screen.RDETAIL -> Screen.MONITOR
            else -> Screen.SET
        }
    }
    // v20.23(P2-20): 品牌画面 650→450ms——首屏最长延迟，克制动效基线内
    // v20.23(P1-1): splash 淡出同时 arm hero 首帧滚入（数字动画此时才可见）
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (splash) {
            delay(450)
            splash = false
            vm.armHeroRoll()
        }
    }
    // S5: 只在前台 STARTED 时收集，退后台不重组
    val snap by vm.snapshot.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.STARTED)
    val lastAt by vm.lastSuccessAt.collectAsState()

    // v20.14: 首启引导已移除——提权说明走顶栏徽章，授权入口统一在设置页「提权通道」卡

    // S5: 生命周期驱动采样：ON_START 启动采集，ON_STOP 停止采集 + 注销传感器
    val lifecycleOwner = LocalLifecycleOwner.current

    // UX(M15): 新鲜度 ticker——停更时徽章仍每秒变黄，不依赖采样成功触发重组
    // v20.23(P2-8): 退后台不空转——仅前台 STARTED 时更新时间戳
    var tick by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                tick = System.currentTimeMillis()
            }
            delay(1000)
        }
    }
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.start()
                Lifecycle.Event.ON_STOP -> {
                    vm.stop()
                    vm.unregisterSensors()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    vm.stop()
                    vm.unregisterSensors()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Column(Modifier.fillMaxSize()) {
            AppBarRow(
                // S6: 服务在跑（未授权=黄"待授权"）与已授权分开展示
                serviceUp = snap.shizukuServiceUp,
                authorized = snap.shizukuActive,
                rootAvailable = snap.rootAvailable,   // v20.11: Root 优先显示
                lastSuccessAt = lastAt,
                nowMs = tick,
                onShizukuClick = { showShizuku = true },   // UX: 点徽章看提权说明
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // v20.17(P1-7): tab 转场收敛为纯淡入淡出 150ms——iOS tab 无位移无缩放，
                // 移动+缩放+淡入三件套叠加是"每切一次都晃一下"的来源
                // P2-9: Reduce Motion 时 tab 转场直切（时长 0）
                val tabScale = rememberAnimatorScale()
                val tabDur = if (tabScale > 0f) Motion.tabMs else 0
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        fadeIn(tween(tabDur))
                            .togetherWith(fadeOut(tween(tabDur)))
                    },
                    label = "tab",
                ) { s ->
                    when (s) {
                        Screen.DASH -> DashboardScreen(vm, onGoSettings = { screen = Screen.SET })
                        Screen.CPU -> CpuScreen(vm)
                        Screen.GPU -> GpuScreen(vm)
                        Screen.BAT -> BatteryScreen(vm)
                        Screen.THERM -> ThermalScreen(vm)
                        Screen.SENS -> SensorsScreen(vm, onBack = { screen = Screen.SET })
                        Screen.SET -> SettingsScreen(
                            vm,
                            onOpenSensors = { screen = Screen.SENS },
                            onOpenAgent = { screen = Screen.AGENT },
                            onOpenMonitor = { screen = Screen.MONITOR },
                        )
                        Screen.AGENT -> AgentScreen(vm, onBack = { screen = Screen.SET })
                        Screen.MONITOR -> MonitorScreen(
                            onOpenDevice = { k -> remoteKey = k; screen = Screen.RDETAIL },
                            onBack = { screen = Screen.SET },
                        )
                        Screen.RDETAIL -> RemoteDetailScreen(
                            deviceKey = remoteKey,
                            onBack = { screen = Screen.MONITOR },
                        )
                    }
                }
            }
            // 双模式全屏页（被监控/监控/详情）不显示底部导航，页面自带返回
            if (screen in MAIN_TABS) BottomNav(screen, onSelect = { screen = it })
        }
        if (showShizuku) {
            ModalBottomSheet(
                onDismissRequest = { showShizuku = false },
                containerColor = Ink.card,
            ) {
                PrivGuideSheet(
                    serviceUp = snap.shizukuServiceUp,
                    authorized = snap.shizukuActive,
                    rootAvailable = snap.rootAvailable,
                    onGoSettings = {
                        screen = Screen.SET
                        showShizuku = false
                    },
                )
            }
        }
        // v20.19: 启动品牌画面层（最上层，650ms 后淡出；ModalBottomSheet 同期不会打开）
        AnimatedVisibility(
            visible = splash,
            exit = fadeOut(tween(200)),
        ) {
            val ctx = LocalContext.current
            val ver = remember(ctx) {
                try {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
                } catch (_: Exception) { "" }
            }
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Ink.accent, Ink.accent2))),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("A41 PROBE", color = Color.White, fontSize = 30.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("参数监控 · 纯只读", color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp, letterSpacing = 1.5.sp)
                    // v20.23(P2-19): 版本读空时整行省略，不留 30dp 空位
                    if (ver.isNotEmpty()) {
                        Spacer(Modifier.height(30.dp))
                        Text("v$ver · 中兴天机 A41 Ultra",
                            color = Color.White.copy(alpha = 0.6f), fontSize = 10.5.sp,
                            letterSpacing = 0.5.sp)
                    }
                }
            }
        }

        // v20.14: 首启引导移除——提权说明走顶栏徽章，授权入口统一在设置页「提权通道」卡
    }
}

/** App 状态条：蓝底白字 + 机型 + 徽章（对标搞机牛 iOS 蓝 Header；徽章可点开提权说明）
 *  S6: 徽章三态——免提权(灰) / 待授权(黄，服务在跑未授权) / Shizuku(蓝白，已授权)
 *  M15: 右侧新鲜度徽章按"距上次成功采样"显示，停更才变黄 */
@Composable
private fun AppBarRow(
    serviceUp: Boolean, authorized: Boolean, rootAvailable: Boolean,
    lastSuccessAt: Long, nowMs: Long,
    onShizukuClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp),
                tint = Ink.accent, alpha = 0.85f, shadow = 0.dp,   // v20.17: 提实，蓝更稳
            )   // v20.7: 蓝玻璃顶栏（液态玻璃质感，保留品牌蓝）
            .statusBarsPadding()
            .height(46.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("A41 PROBE", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp)
        Spacer(Modifier.width(8.dp))
        // O12: 长机型名不挤压右侧徽章
        Text(Build.MODEL, color = Color.White.copy(alpha = 0.8f), fontSize = 10.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).then(Modifier.padding(end = 8.dp)))
        Spacer(Modifier.weight(1f))
        Box(Modifier.clickable(onClick = onShizukuClick)) {   // UX: 点徽章 → 提权说明
            when {
                // v20.11: Root 优先于 Shizuku 显示（橙点 R）
                rootAvailable -> DotBadge(Ink.warn, "Root", onBlue = true)
                authorized -> DotBadge(Color.White, "Shizuku", onBlue = true)
                serviceUp -> DotBadge(Ink.warn, "待授权", onBlue = true)
                else -> DotBadge(Ink.off, "免提权", onBlue = true)
            }
        }
        Spacer(Modifier.width(6.dp))
        // M15: 距上次成功采样的真实新鲜度（lastSuccessAt=0 表示尚无成功采样）
        val ageMs = if (lastSuccessAt > 0) (nowMs - lastSuccessAt).coerceAtLeast(0) else Long.MAX_VALUE
        val fresh = ageMs < 3000
        // v20.5: 停更超 10s 徽章闪烁提醒（数据停了，不只是变黄）
        val stale = !fresh && ageMs < Long.MAX_VALUE && ageMs > 10_000
        // v20.23(P1-3): Reduce Motion 开启时停更徽章不闪烁（恒亮琥珀）——常驻无限动画清零
        val badgeAlpha = if (stale && rememberAnimatorScale() > 0f) {
            val tr = rememberInfiniteTransition(label = "stale")
            tr.animateFloat(
                initialValue = 0.45f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 600), RepeatMode.Reverse),
                label = "blink",
            ).value
        } else 1f
        DotBadge(
            if (fresh) Color.White else Ink.warn,
            if (ageMs == Long.MAX_VALUE) "启动中" else if (fresh) "实时" else "停更 ${ageMs / 1000}s",
            onBlue = true,
            alpha = badgeAlpha,
        )
    }
}

/** 底部导航：6 tab（仪表盘 / CPU / GPU / 电池 / 热力 / 设置）
 *  v20.7: 悬浮液态玻璃 pill——半透明白 + 背景模糊 + 高光描边 + 柔和阴影 */
@Composable
private fun BottomNav(screen: Screen, onSelect: (Screen) -> Unit) {
    val mainTabs = listOf(Screen.DASH, Screen.CPU, Screen.GPU, Screen.BAT, Screen.THERM, Screen.SET)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .liquidGlass(RoundedCornerShape(22.dp), tint = Color.White, alpha = 0.72f, shadow = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            mainTabs.forEach { t ->
                NavItem(
                    label = t.label,
                    // v20.17(P0-6): 传感器页从设置进入——期间保持"设置"高亮 + 页面顶部返回
                    active = if (screen == Screen.SENS) t == Screen.SET else screen == t,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onSelect(t) },
                )
            }
        }
    }
}

@Composable
private fun NavItem(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) Ink.accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = if (active) Ink.accent else Ink.off,
            fontSize = 10.5.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** 提权说明：解释顶栏徽章与 S / R 标记（UX: 点顶栏徽章即可查看，随时可查看） */
@Composable
private fun PrivGuideSheet(
    serviceUp: Boolean, authorized: Boolean, onGoSettings: () -> Unit,
    rootAvailable: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("提权说明", color = Ink.tx2, fontSize = 11.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(12.dp))
        PrivGuideRow("—", "免提权", "电量 · CPU 频率 · 传感器等基础参数")
        Spacer(Modifier.height(9.dp))
        PrivGuideRow("S", "Shizuku", "温度热区 · 负载 · GPU 温度（无 Root 时的替代档）")
        Spacer(Modifier.height(9.dp))
        PrivGuideRow("R", "仅 Root", "GPU 频率 · cpu0 上限 · 含 Shizuku 全部能力")
        Spacer(Modifier.height(12.dp))
        Text("卡片上的 S / R 标记表示该项需要的提权档位；未授权时对应数值置灰。",
            color = Ink.off, fontSize = 11.sp)
        Spacer(Modifier.height(16.dp))
        val btn = when {
            rootAvailable -> "Root 已激活 · 全参数可读"
            authorized -> "Shizuku 已激活 · 前往设置查看"
            serviceUp -> "服务运行中 · 立即去授权"
            else -> "前往设置页发起授权"
        }
        ProbeButton(
            text = btn,
            accent = !(rootAvailable || authorized),
            onClick = onGoSettings,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun PrivGuideRow(tag: String, name: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp))
                .background(Ink.panel)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(tag, color = Ink.accent, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(10.dp))
        Text(name, color = Ink.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(desc, color = Ink.tx2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
