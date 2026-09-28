package com.a41probe.monitor

import android.graphics.Color as AndroidColor
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.a41probe.monitor.R
import com.a41probe.monitor.data.MonitorViewModel
import com.a41probe.monitor.ui.components.MioArtBackground
import com.a41probe.monitor.ui.components.SpecialFace
import com.a41probe.monitor.ui.theme.ThemeConfig
import com.a41probe.monitor.ui.theme.SpecialConfig
import com.a41probe.monitor.ui.components.DotBadge
import com.a41probe.monitor.ui.components.Motion
import com.a41probe.monitor.ui.components.rememberAnimatorScale
import com.a41probe.monitor.ui.components.ProbeButton
import com.a41probe.monitor.ui.components.GlassSurface
import com.a41probe.monitor.ui.components.GlassSurfaceFlat
import com.a41probe.monitor.ui.components.rememberHaptics
import com.a41probe.monitor.ui.components.LocalScrollFraction
import com.a41probe.monitor.ui.components.rememberAnimatorScale
import com.a41probe.monitor.ui.components.OnboardingOverlay
import com.a41probe.monitor.ui.components.mioOnboardingPages
import com.a41probe.monitor.ui.components.MIO_PREFS
import com.a41probe.monitor.ui.components.KEY_ONBOARD_DONE
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
import com.a41probe.monitor.ui.theme.MioTheme
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
        CrashCatcher.install(this)   // v0.26.6(P1): 全局未捕获异常落盘 crash/crash.log（闪退排查）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(
                AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(
                AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        // v0.28.7: 关闭系统"导航栏对比度强制"。导航栏设为透明后，Android 10+ 默认还会在其区域
        // 叠一层浅色 scrim；半透明底栏正好压在这一区域上，于是显出一条硬边亮带
        // （真机实测：亮带上边缘 y≈2294 = 导航栏边界，关闭档因底栏不透明而无此带）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent { MioTheme { MainScreen() } }
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
    // v0.26: 首次启动使用引导——未完成标记时全屏覆盖；设置页可重看
    val appCtx = LocalContext.current
    val mioPrefs = remember {
        appCtx.getSharedPreferences(MIO_PREFS, Context.MODE_PRIVATE)
    }
    var onboardingPending by rememberSaveable {
        mutableStateOf(!mioPrefs.getBoolean(KEY_ONBOARD_DONE, false))
    }
    var showOnboarding by remember { mutableStateOf(false) }
    val onboardPages = remember { mioOnboardingPages() }
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
        // v0.27.0: 计时从"首帧可见"后开始——冷启动 Composition 到首帧之间 delay 已按墙钟
        // 流逝（实测首帧 ~900ms），会导致开屏实际只显示几百毫秒；withFrameNanos 等首帧渲染
        withFrameNanos { }
        val shownAt = System.currentTimeMillis()
        if (splash) {
            // 首帧后保证完整展示 900ms（立绘入场 520ms 含在窗口内）
            while (System.currentTimeMillis() - shownAt < 900) {
                kotlinx.coroutines.delay(40)
            }
            splash = false
            vm.armHeroRoll()
        }
        // v0.26.1: 首启引导在开屏立绘展示完毕后再弹出，不被 splash 遮挡
        if (onboardingPending) showOnboarding = true
    }
    // S5: 只在前台 STARTED 时收集，退后台不重组
    // v0.28.0: 权限三态走细粒度 privFlow（变化极低频），不再订阅整帧 snapshot
    val priv by vm.privFlow.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.STARTED)
    val lastAt by vm.lastSuccessAt.collectAsState()

    // v0.26: 首次启动使用引导——未完成时全屏覆盖，设置页可重看（见 OnboardingOverlay）

    // S5: 生命周期驱动采样：ON_START 启动采集，ON_STOP 停止采集 + 注销传感器
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    // v0.27.1: 切前台立即重刷权限状态（异步，不阻塞），配合 refresh 失败不翻转
                    vm.refreshShizuku()
                    vm.quickRefresh(); vm.setBackground(false); vm.start()
                }
                Lifecycle.Event.ON_STOP -> {
                    // v0.26.4: 退后台不停采——3s 低频保温，回前台立即有数据
                    vm.setBackground(true)
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

    // v1.3: 特殊预设（角色）——立绘/表情按当前预设解析，缺项自动回落澪
    val special = SpecialConfig.current()
    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Column(Modifier.fillMaxSize()) {
            AppBarRow(
                // S6: 服务在跑（未授权=黄"待授权"）与已授权分开展示
                serviceUp = priv.shizukuServiceUp,
                authorized = priv.shizukuActive,
                rootAvailable = priv.rootAvailable,   // v20.11: Root 优先显示
                lastSuccessAt = lastAt,
                onShizukuClick = { showShizuku = true },   // UX: 点徽章看提权说明
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // v0.25: 澪背景立绘——固定于内容区右下，雾玻璃卡片透出角色
                MioArtBackground(special.artFor(artKeyFor(screen)))
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
                        Screen.DASH -> DashboardScreen(vm,
                            onGoSettings = { screen = Screen.SET },
                            onGoBattery = { screen = Screen.BAT })
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
                            onShowOnboarding = { showOnboarding = true },
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
                    serviceUp = priv.shizukuServiceUp,
                    authorized = priv.shizukuActive,
                    rootAvailable = priv.rootAvailable,
                    onGoSettings = {
                        screen = Screen.SET
                        showShizuku = false
                    },
                )
            }
        }
        // v0.27.0: 开屏重设计——立绘 FillHeight 贴底（原固定 330dp 框平切脚部根除）、
        // 品牌置顶、入场淡入上浮；冷启动图片未解码完时以 alpha 0 等待，就绪后淡入，不再出现残缺帧
        AnimatedVisibility(
            visible = splash,
            exit = fadeOut(tween(250)),
        ) {
            val ctx = LocalContext.current
            val ver = remember(ctx) {
                try {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
                } catch (_: Exception) { "" }
            }
            // Reduce Motion：系统动画缩放=0 时入场直切（无淡入/上浮）
            val reduce = rememberAnimatorScale() <= 0f
            val appear = remember { Animatable(if (reduce) 1f else 0f) }
            LaunchedEffect(Unit) {
                if (!reduce) appear.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
            }
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Ink.accent, Ink.accent2))),
            ) {
                // 立绘：贴底、高 78% 屏、FillHeight——两侧透明留白被框自然裁去、角色无损；
                // 脚贴屏幕底边、脸完整位于屏幕上 1/3，任何帧都不平切
                Image(
                    painter = painterResource(special.artFor("dashboard")),
                    contentDescription = null,
                    contentScale = ContentScale.FillHeight,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(0.94f)
                        .fillMaxHeight(0.78f)
                        .graphicsLayer {
                            alpha = appear.value
                            translationY = (1f - appear.value) * 28.dp.toPx()
                        },
                )
                // 品牌区：置顶安全区，文字比立绘晚出现（stagger 淡入）
                val wordA = ((appear.value - 0.25f) / 0.75f).coerceIn(0f, 1f)
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 16.dp)
                        .graphicsLayer { alpha = wordA },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Mio 澪", color = Ink.onAccent, fontSize = 30.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("参数监控 · 纯只读", color = Ink.onAccent.copy(alpha = 0.85f),
                        fontSize = 13.sp, letterSpacing = 1.5.sp)
                    // 版本读空时整行省略，不留空位
                    if (ver.isNotEmpty()) {
                        Spacer(Modifier.height(9.dp))
                        Text("v$ver · ${Build.MODEL}",
                            color = Ink.onAccent.copy(alpha = 0.6f), fontSize = 10.5.sp,
                            letterSpacing = 0.5.sp)
                    }
                }
            }
        }

        // v0.26: 首次启动使用引导覆盖层（最上层；结束/跳过写持久化标记）
        if (showOnboarding) {
            OnboardingOverlay(
                pages = onboardPages,
                onFinish = {
                    mioPrefs.edit().putBoolean(KEY_ONBOARD_DONE, true).apply()
                    showOnboarding = false
                },
            )
        }
    }
}

/** App 状态条：蓝底白字 + 机型 + 徽章（iOS 风格蓝色 Header；徽章可点开提权说明）
 *  S6: 徽章三态——免提权(灰) / 待授权(黄，服务在跑未授权) / Shizuku(蓝白，已授权)
 *  M15: 右侧新鲜度徽章按"距上次成功采样"显示，停更才变黄 */
@Composable
private fun AppBarRow(
    serviceUp: Boolean, authorized: Boolean, rootAvailable: Boolean,
    lastSuccessAt: Long,
    onShizukuClick: () -> Unit,
) {
    // v0.28.0: 新鲜度 ticker 收敛在 AppBar 内部——MainActivity 不再每秒重组整棵组合树
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { nowMs = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    // v0.28.3: 滚动渐变——页面滚动后顶栏更通透、blur 渐入（学自华为"沉浸光感"）
    val scrollFrac = LocalScrollFraction.current
    val glassAlpha = remember(scrollFrac) { 0.85f - 0.12f * scrollFrac }
    val glassBlur = remember(scrollFrac) { 34f * scrollFrac }
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(46.dp),
        shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp),
        tint = Ink.accent, alpha = glassAlpha, shadow = 0.dp, blurRadius = glassBlur,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Text("Mio 澪", color = Ink.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp)
        Spacer(Modifier.width(8.dp))
        // O12: 长机型名不挤压右侧徽章
        Text(Build.MODEL, color = Ink.onAccent.copy(alpha = 0.8f), fontSize = 10.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).then(Modifier.padding(end = 8.dp)))
        Spacer(Modifier.weight(1f))
        Box(Modifier.clickable(onClick = onShizukuClick)) {   // UX: 点徽章 → 提权说明
            when {
                // v20.11: Root 优先于 Shizuku 显示（橙点 R）
                rootAvailable -> DotBadge(Ink.warn, "Root", onBlue = true)
                authorized -> DotBadge(Ink.onAccent, "Shizuku", onBlue = true)
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
            if (fresh) Ink.onAccent else Ink.warn,
            if (ageMs == Long.MAX_VALUE) "启动中" else if (fresh) "实时" else "停更 ${ageMs / 1000}s",
            onBlue = true,
            alpha = badgeAlpha,
        )
        }
    }
}

/** 底部导航：6 tab（仪表盘 / CPU / GPU / 电池 / 热力 / 设置）
 *  v20.7: 悬浮液态玻璃 pill——半透明白 + 背景模糊 + 高光描边 + 柔和阴影 */
@Composable
private fun BottomNav(screen: Screen, onSelect: (Screen) -> Unit) {
    val mainTabs = listOf(Screen.DASH, Screen.CPU, Screen.GPU, Screen.BAT, Screen.THERM, Screen.SET)
    val selIdx = mainTabs.indexOfFirst { t ->
        if (screen == Screen.SENS) t == Screen.SET else screen == t
    }.let { if (it < 0) 0 else it }
    val animScale = rememberAnimatorScale()
    val haptics = rememberHaptics()
    // v0.28.8: 指示器平滑滑动（不再"跳"到目标 Tab）——缓动统一用 Motion.easing
    val pos by animateFloatAsState(
        selIdx.toFloat(),
        tween(durationMillis = if (animScale > 0f) Motion.indicatorMs else 0, easing = Motion.easing),
        label = "tabIndicator")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
    ) {
        GlassSurfaceFlat(
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(22.dp),
            tint = Color.White, alpha = 0.72f, shadow = 8.dp,
        ) {
            Box(Modifier.fillMaxSize()) {
                // 高光指示器：随选中项平滑滑动（画在文字之下，文字始终清晰）
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(1f / mainTabs.size)
                        .graphicsLayer { translationX = pos * size.width }
                        .padding(vertical = 6.dp, horizontal = 3.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Ink.accent.copy(alpha = 0.14f))
                )
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    mainTabs.forEachIndexed { i, t ->
                        NavItem(
                            label = t.label,
                            active = i == selIdx,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = {
                                if (i != selIdx) haptics.tick()   // 仅在真的换页时给一次轻震
                                onSelect(t)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavItem(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val animScale = rememberAnimatorScale()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ms = if (pressed) Motion.pressMs else Motion.releaseMs
    // 按下轻微收缩（0.92，比卡片更明显一点，符合"小目标需要更大反馈"的手感）
    val sc by animateFloatAsState(
        if (pressed && animScale > 0f) 0.92f else 1f,
        tween(durationMillis = ms, easing = Motion.easing), label = "navPress")
    // 选中态颜色渐变（避免切换时文字颜色硬切）
    val col by animateColorAsState(
        if (active) Ink.accent else Ink.off,
        tween(durationMillis = if (animScale > 0f) 200 else 0, easing = Motion.easing), label = "navColor")
    Column(
        modifier = modifier
            .graphicsLayer { scaleX = sc; scaleY = sc }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = col,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("提权说明", color = Ink.tx2, fontSize = 11.sp, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(3.dp))
                Text("澪来解释三档权限", color = Ink.tx, fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            // v0.26.4 / v1.3.0: 表情小图统一走 SpecialFace（随角色预设换人）
            SpecialFace("think_head", Modifier.size(76.dp))
        }
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

/** 当前页面 → 立绘 key（特殊预设与澪默认共用同一套 key） */
private fun artKeyFor(s: Screen): String = when (s) {
    Screen.DASH -> "dashboard"
    Screen.CPU -> "cpu"
    Screen.GPU -> "gpu"
    Screen.BAT -> "battery"
    Screen.THERM -> "thermal"
    Screen.SENS -> "sensor"
    Screen.SET -> "settings"
    else -> ""
}
