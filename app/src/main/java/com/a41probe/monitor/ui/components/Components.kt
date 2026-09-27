package com.a41probe.monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Build
import androidx.compose.ui.graphics.BlurEffect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.a41probe.monitor.ui.theme.Ink
import com.a41probe.monitor.ui.theme.Priv

private val CardShape = RoundedCornerShape(18.dp)
private val TileShape = RoundedCornerShape(10.dp)

/**
 * 卡片容器：iOS 雾玻璃——半透明白 + 顶部微高光 + 柔化光泽层 + 细边 + 柔和冷阴影。
 *
 * v0.28.8 结构（三层，文字/图表永远在最上层、零模糊）：
 *   1) 玻璃底色层：按档位取半透明白（完整 0.62 / 简约 0.80 / 关闭实色）；
 *   2) 光泽层：顶部微弱高光 + 滚动时缓慢流动的斜向光带；仅"完整"档且 API31+ 施加 RenderEffect
 *      轻模糊（Android 12 以下自动降级为不模糊的静态渐变，视觉近似、零风险）；
 *   3) 内容层：文字与图表，不参与任何模糊。
 *
 * 交互反馈：onClick 非空时，按下 → 轻微收缩(0.985) + 阴影下沉(10dp→5dp) + 底色明暗下沉，
 * 抬起按 Motion.easing 平滑恢复；并附一次极轻震动。
 */
@Composable
fun ProbeCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val level = GlassConfig.effective()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val animScale = rememberAnimatorScale()
    val canAnim = animScale > 0f
    val haptics = rememberHaptics()

    /* v0.28.11 按压反馈回归需求原文：**轻微收缩 + 阴影变化 + 透明度变化**，抬起平滑恢复。
     * 去掉了 0.28.9 加的"两段式 + 强调色辉光"——用户反馈"太复杂、不符合要求"。 */
    val tapped = pressed && canAnim
    LaunchedEffect(pressed, canAnim) { if (tapped) haptics.press() }

    val ms = if (pressed) Motion.pressMs else Motion.releaseMs
    val baseElev = if (level == GlassLevel.OFF) 6.dp else 10.dp
    val elev by animateDpAsState(
        if (tapped) baseElev - 5.dp else baseElev,
        tween(durationMillis = if (canAnim) ms else 0, easing = Motion.easing), label = "cardElev")
    val scale by animateFloatAsState(
        if (tapped) 0.975f else 1f,
        tween(durationMillis = if (canAnim) ms else 0, easing = Motion.easing), label = "cardScale")
    val dim by animateFloatAsState(
        if (tapped) 0.90f else 1f,
        tween(durationMillis = if (canAnim) ms else 0, easing = Motion.easing), label = "cardDim")

    val fill = when (level) {
        GlassLevel.FULL -> Color.White.copy(alpha = 0.62f * dim)
        GlassLevel.LITE -> Color.White.copy(alpha = 0.80f * dim)
        GlassLevel.OFF -> Ink.card.copy(alpha = dim)
    }
    val edge = when (level) {
        GlassLevel.OFF -> Ink.stroke
        GlassLevel.LITE -> Color.White.copy(alpha = 0.55f)
        GlassLevel.FULL -> Color.White.copy(alpha = 0.65f)
    }
    // 顶部微弱高光（模拟玻璃光泽）：上亮下透明的窄渐变
    val sheen = remember {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.55f),
            0.14f to Color.White.copy(alpha = 0.12f),
            1f to Color.Transparent,
        )
    }
    val scrolling = LocalScrolling.current
    val sheenBlur = if (level == GlassLevel.FULL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        Modifier.blur(2.dp) else Modifier

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(
                elevation = elev, shape = CardShape, clip = false,
                ambientColor = Color(0x1F4A90D9), spotColor = Color(0x144A90D9),
            )
            .clip(CardShape)
            // 所有卡片都可按下（无 onClick 的卡片也给触感与视觉反馈，不做任何动作）
            .clickable(
                interactionSource = interaction, indication = null) {
                onClick?.invoke()
            }
    ) {
        // 1) 玻璃底色
        Box(Modifier.matchParentSize().background(fill))
        // 2) 光泽层（可模糊；文字图表在它之上，绝不被扭曲）
        Box(Modifier.matchParentSize().then(sheenBlur).background(sheen))
        // 2b) 细边：画在光泽之上、内容之下
        Box(Modifier.matchParentSize().border(1.dp, edge, CardShape))
        // 2b) 滚动时缓慢流动的高光带——状态在绘制期读取，不触发重组
        if (scrolling && level != GlassLevel.OFF && animScale > 0f) {
            val trans = rememberInfiniteTransition(label = "cardSheen")
            val pos by trans.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(Motion.sheenMs, easing = LinearEasing), RepeatMode.Restart),
                label = "cardSheenPos")
            Box(
                Modifier.matchParentSize().drawWithCache {
                    val w = size.width
                    val h = size.height
                    val cy = -h * 0.5f + pos * (h * 1.8f)
                    val brush = Brush.linearGradient(
                        0f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = 0.10f),
                        1f to Color.Transparent,
                        start = Offset(0f, cy - h * 0.35f),
                        end = Offset(w, cy + h * 0.35f),
                    )
                    onDrawBehind { drawRect(brush) }
                }
            )
        }
        // 3) 内容层：文字 / 图表，零模糊
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            content = { content() }
        )
    }
}

/**
 * SafeCard 作用域：content 内可调用 safe{} 包裹危险计算，
 * 捕获异常后降级为占位卡，避免单卡异常导致整页崩溃。
 */
class SafeCardScope internal constructor(
    private val onError: (String) -> Unit,
) {
    fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        onError(t.message ?: "模块读取异常")
        null
    }
}

/**
 * 卡片级错误边界：包装 ProbeCard，维护错误状态。
 * - composition 期间未捕获异常由 CrashCatcher 全局兜底落盘；
 * - content 内危险计算用 scope.safe{} 包裹后，单卡异常降级为统一占位，不影响同页其他卡片；
 * - 错误状态 rememberSaveable 持久化（旋转/深色模式重建不丢）；
 * - 数据变化时自动重试（error 状态在下次成功采集后由调用方清除，或用户切 tab 重建）。
 */
@Composable
fun SafeCard(
    modifier: Modifier = Modifier,
    fallbackTitle: String = "模块",
    onClick: (() -> Unit)? = null,
    content: @Composable SafeCardScope.() -> Unit,
) {
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = remember { SafeCardScope { msg -> error = msg } }
    if (error != null) {
        ProbeCard(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Ink.warn))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("$fallbackTitle 读取异常", color = Ink.tx, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(error ?: "该模块数据暂时不可用", color = Ink.tx3, fontSize = 11.sp, maxLines = 2)
                }
            }
        }
        return
    }
    ProbeCard(modifier, onClick = onClick) { scope.content() }
}

/** 卡片内灰底嵌套块：细分指标分组（对应 iOS 分组列表内嵌灰块） */
@Composable
fun NestedTile(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(TileShape)
            .background(Ink.panel)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        content = { content() }
    )
}

/** 卡片标题行：组标题（16sp 半粗黑）+ 提权小标 + 右侧标注 */
@Composable
fun CardTitle(
    label: String,
    priv: Priv? = null,
    rootActive: Boolean = false,
    right: @Composable (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink.tx, fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        // v20.16(P0-3): FREE 卡不画徽标——"—"对用户无信息，只会制造噪音（首屏徽标 7→3）
        if (priv != null && priv != Priv.FREE) {
            Spacer(Modifier.width(6.dp))
            // v20.12(S1-3): Root 通道填充数据时，S 徽标按 R 显示（与顶栏橙 Root 语义一致）
            PrivBadge(if (priv == Priv.SHIZUKU && rootActive) Priv.ROOT else priv)
        }
        if (right != null) {
            Spacer(Modifier.width(8.dp))
            right()
        }
    }
}

/** 提权小标：浅底圆角，无边框（L4: 11sp 保证低密度屏可读） */
@Composable
fun PrivBadge(priv: Priv, text: String? = null) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(priv.color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text ?: priv.tag,
            color = priv.color,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 状态徽章：小圆点 + 文字；onBlue=true 用于蓝色顶栏（白底反白）；alpha 供停更闪烁 */
@Composable
fun DotBadge(dotColor: Color, text: String, onBlue: Boolean = false, alpha: Float = 1f) {
    Row(
        modifier = Modifier
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(7.dp))
            .background(if (onBlue) Color.White.copy(alpha = 0.18f) else Ink.panel)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(5.dp).clip(RoundedCornerShape(2.5.dp)).background(dotColor))
        Spacer(Modifier.width(5.dp))
        Text(text, color = if (onBlue) Color.White else Ink.tx2, fontSize = 10.5.sp)
    }
}

/** 数值展示：大数字（蓝或主色）+ 单位 + 副说明 */
@Composable
fun StatValue(
    value: String,
    unit: String = "",
    color: Color = Ink.accent,
    sizeSp: Int = 28,
    sub: String? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, fontSize = sizeSp.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, color = Ink.tx2, fontSize = 12.sp)
            }
        }
        if (sub != null) {
            Spacer(Modifier.height(3.dp))
            // UX(L1): 口径说明是信任关键，12sp + 更深灰保证对比度
            Text(sub, color = Ink.tx3, fontSize = 12.sp)
        }
    }
}

/** iOS 设置式行：左黑标签 / 右数值（灰单位） / 状态点（未传状态不画点，L8） */
@Composable
fun StatRow(
    name: String,
    value: String,
    unit: String = "",
    stateColor: Color? = null,
    mono: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),   // L3: 行高透气
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stateColor != null) {
            Box(Modifier.size(5.dp).clip(RoundedCornerShape(2.5.dp)).background(stateColor))
            Spacer(Modifier.width(8.dp))
        }
        Text(name, color = Ink.tx, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(value, color = if (mono) Ink.accent else Ink.tx2,
            fontSize = if (mono) 14.sp else 13.sp,
            fontFamily = if (mono) FontFamily.Monospace else null,
            fontWeight = if (mono) FontWeight.Medium else FontWeight.Normal)
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(3.dp))
            Text(unit, color = Ink.off, fontSize = 11.sp)
        }
    }
}

/** 横条行（温度/占比）：名称 + 归一化条 + 值（灰底轨道） */
@Composable
fun BarRow(
    name: String,
    frac: Float,          // 0..1 归一化位置
    value: String,
    barColor: Color,
    nameWidth: Dp = 96.dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),   // L3: 行高透气
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = Ink.tx, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(nameWidth))
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(9.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Ink.panel)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac.coerceIn(0f, 1f))
                    .height(9.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(value, color = Ink.tx, fontSize = 12.5.sp,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
    }
}

/** 温度色阶：≥42 红 / ≥36 橙 / 其余绿 / 缺失灰 */
fun tempColor(c: Double?): Color {
    if (c == null) return Ink.off
    return when {
        c >= 42 -> Ink.danger
        c >= 36 -> Ink.warn
        else -> Ink.ok
    }
}

fun tempFrac(c: Double?): Float {
    if (c == null) return 0f
    return ((c - 20f) / 30f).toFloat().coerceIn(0f, 1f)
}

/** 传感器卡片（图标圆 + 状态点 + 名称/说明 + 数值多轴横排） */
@Composable
fun SensorTile(
    name: String,
    desc: String,
    valueLines: List<Pair<String, String>>,   // (数值, 单位)
    iconBg: Color,
    dotColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Ink.card)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(dotColor))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Ink.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = Ink.tx2, fontSize = 11.5.sp)
            Spacer(Modifier.height(6.dp))
            // 多轴等宽均分，窄屏不裁切（1 轴占满，3 轴各 1/3）
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                valueLines.forEach { (v, u) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                        Text(v, color = Ink.accent, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        if (u.isNotEmpty()) {
                            Spacer(Modifier.width(2.dp))
                            Text(u, color = Ink.off, fontSize = 9.5.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** 设置行（iOS 列表行：左标题 / 右控件或值） */
@Composable
fun SettingRow(
    title: String,
    sub: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink.tx, fontSize = 14.sp)
            if (sub != null) Text(sub, color = Ink.tx2, fontSize = 11.5.sp)
        }
        trailing()
    }
}

/** 按钮：accent 实底 / 普通浅底；ripple 在底色之上，48dp 最小触控目标 */
@Composable
fun ProbeButton(
    text: String,
    accent: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .pressClick(onClick = onClick)   // v20.7: 提到最外层，整钮含底色一起按压缩放
            .clip(RoundedCornerShape(10.dp))
            .background(if (accent) Ink.accent else Ink.panel)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (accent) Color.White else Ink.accent, fontSize = 13.sp,
            fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Medium)
    }
}

/**
 * v20.6：iOS 动效对齐——统一时长/弹簧/呼吸参数。
 * 依据：Apple HIG 与 SwiftUI（iOS17 起默认物理弹簧 response≈0.55；
 * 微交互 130-200ms；标准转场 200-350ms；Reduce Motion 需降级直切）。
 *  - numTween：250ms FastOutSlowIn → 监控数字平滑滑入，有静止时刻
 *  - barSpring：smooth 系（临界阻尼，无回弹，响应≈0.28s）→ 进度条/电量环
 * v20.16(P0-2)：数字从弹簧(0.82阻尼永远 settle 不下来，1s 采样下无一刻静止)
 * 改 tween——诊断确认"果冻晃"是整体奇怪感来源之一。
 */
object Motion {
    /** 统一缓动曲线：全工程动效只用这一条（柔和克制的进出场） */
    val easing = FastOutSlowInEasing

    /** 柔和回弹（松开恢复 / 指示器落位 / overscroll 归位）：欠阻尼但很快收敛 */
    fun <T> softSpring(): androidx.compose.animation.core.SpringSpec<T> =
        spring(dampingRatio = 0.72f, stiffness = 320f)

    /** 按压反馈：iOS pressSpring ≈ 0.14s */
    const val pressMs = 130
    /** 松开恢复：略长于按下，形成"按下快、回弹慢"的自然手感 */
    const val releaseMs = 240
    /** 数字滚动：tween 320ms（v0.28.8 由 250ms 放宽，CPU/温度/内存数值更平顺） */
    const val numMs = 320
    /** 进度条/环形：smooth 临界阻尼快速弹簧 */
    val barSpring = spring<Float>(dampingRatio = 1f, stiffness = 520f)
    /** tab 切换淡入（iOS tab 无转场，极短淡入） */
    const val tabMs = 150
    /** 电量环转动时长 */
    const val gaugeMs = 300
    /** 曲线最新点脉冲时长 */
    const val pulseMs = 340
    /** 数字小幅变化短 tween 时长 */
    const val numSmallMs = 220
    /** 底部 Tab 指示器滑动 */
    const val indicatorMs = 280
    /** 卡片滚动高光流动一个周期 */
    const val sheenMs = 1600
}

/**
 * v20.6：Reduce Motion 无障碍——读取系统动画缩放（ANIMATOR_DURATION_SCALE）。
 * 缩放=0（用户关闭动画）时返回 0，调用方应 snapTo 直切而非动画。
 * ContentObserver 监听变化，用户切换设置实时生效。
 */
@Composable
fun rememberAnimatorScale(): Float {
    val ctx = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    DisposableEffect(Unit) {
        val resolver: ContentResolver = ctx.contentResolver
        fun read() {
            scale = Settings.Global.getFloat(
                resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
        read()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { read() }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale
}

/**
 * v20.4/20.5 手感层：数字平滑滚动（iOS 计数器感）。
 * 首次直接定位（不从头滚上来）。v20.16(P0-1/P0-2)：
 *  - 删除呼吸缩放（13 个数字每秒同步"鼓一下"是满屏蠕动的元凶）；
 *  - spring 改 tween(250ms)——数字有静止时刻，不再果冻晃。
 * target=null 显示 "–"。
 */
@Composable
fun AnimatedNum(
    target: Float?,
    format: (Float) -> String,
    color: Color,
    sizeSp: Int = 26,
    weight: FontWeight = FontWeight.SemiBold,
    mono: Boolean = true,
    rollFromZero: Boolean = false,   // v20.20(P1-1): 冷启动 hero 从 0 滚入（进程级只滚一次）
    onRolled: () -> Unit = {},
) {
    if (target == null) {
        Text("–", color = color, fontSize = sizeSp.sp,
            fontWeight = weight, fontFamily = if (mono) FontFamily.Monospace else null)
        return
    }
    val anim = remember { Animatable(target) }
    val animScale = rememberAnimatorScale()
    val scrolling = LocalScrolling.current
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(target, rollFromZero, scrolling) {
        // v0.28.3: 页面滚动中数字直切——数字滚动动画帧不再与滚动帧叠加（卡顿根因之一）
        if (scrolling) { anim.snapTo(target); return@LaunchedEffect }
        if (rollFromZero && animScale > 0f) {
            // v20.20(P1-1): 首帧从 0 滚入——旧实现 Animatable 初始值=目标值导致永不播放；
            // 先 snapTo(0) 再 animateTo，滚完回调关闭开关（切 tab 回来不再重滚）
            anim.snapTo(0f)
            anim.animateTo(target, tween(durationMillis = 420, easing = FastOutSlowInEasing))
            onRolled()
        } else if (first) {
            first = false
            anim.snapTo(target)
        }
        else if (animScale <= 0f) { anim.snapTo(target) }   // Reduce Motion：直切
        else {
            val diff = kotlin.math.abs(target - anim.value)
            // v0.28.0: 值无实质变化直接定位——静止时不再触发 150ms 动画（每秒 13 个数字
            // 空转动画 = 整页持续重组的元凶之一）。0.03..0.5 平滑小步，>0.5 全速滚动。
            if (diff <= 0.03f) {
                anim.snapTo(target)
            } else if (diff <= 0.5f) {
                anim.animateTo(target, tween(durationMillis = Motion.numSmallMs, easing = FastOutSlowInEasing))
            } else {
                anim.animateTo(target, tween(durationMillis = Motion.numMs, easing = FastOutSlowInEasing))
            }
        }
    }
    // v20.16(P0-1): 呼吸缩放已删除——数值亚像素抖动也触发放大，满屏数字同步蠕动，
    // 是"有东西在动但定位不到"的直接来源。数字只保留值滚动动画。
    Text(format(anim.value), color = color, fontSize = sizeSp.sp,
        fontWeight = weight, fontFamily = if (mono) FontFamily.Monospace else null)
}

/** v20.5 / v0.28.8 按压反馈：按下轻微收缩 + 明暗下沉，抬起平滑恢复（统一用 Motion.easing）。
 *  按下 130ms、松开 240ms——"按得快、回得慢"是 iOS 手感的关键。
 *  haptic=true 时附一次极轻震动（仅用于有明确结果的按钮，避免满屏都震）。
 *  注意：卡片级的"阴影变化"由 ProbeCard 处理（它持有 shadow 修饰符），此处只管形状与明暗。 */
/** 兼容入口：保持 onClick 为唯一参数，既支持 `pressClick(onOpen)` 位置调用，
 *  也支持 `pressClick { ... }` 尾随 lambda（工程内两种写法都有）。 */
fun Modifier.pressClick(onClick: () -> Unit): Modifier = pressClickStyled(onClick = onClick)

/** 可调参版本：缩放幅度 / 明暗下沉 / 是否附轻震。 */
fun Modifier.pressClickStyled(
    scale: Float = 0.975f,
    dim: Float = 0.94f,
    haptic: Boolean = false,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale = rememberAnimatorScale()
    val animate = pressScale > 0f
    val ms = if (pressed) Motion.pressMs else Motion.releaseMs
    val s by animateFloatAsState(
        if (pressed && animate) scale else 1f,
        tween(durationMillis = ms, easing = Motion.easing), label = "pressScale")
    val a by animateFloatAsState(
        if (pressed && animate) dim else 1f,
        tween(durationMillis = ms, easing = Motion.easing), label = "pressDim")
    val haptics = rememberHaptics()
    this
        .graphicsLayer { scaleX = s; scaleY = s; alpha = a }
        .clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
        ) {
            if (haptic) haptics.press()
            onClick()
        }
}

/**
 * v20.7: 液态玻璃外壳（iOS 26 Liquid Glass 质感，学自 WWDC25 设计语言）。
 * 半透明着色 + 背景模糊（RenderEffect，API31+ 即 Android12 可用，A41 正好支持）+
 * 顶部高光描边（垂直渐变白边）+ 柔和阴影。
 * 克制原则：仅用于外壳层（顶栏 / 底部导航 / 悬浮件），内容卡片保持白卡。
 */
fun Modifier.liquidGlass(
    shape: Shape,
    tint: Color = Color.White,
    alpha: Float = 0.66f,
    shadow: Dp = 4.dp,
): Modifier = composed {
    // v20.8: 不再用 renderEffect blur——Compose 的 renderEffect 作用于整个 layer，
    // 会把条上的文字一起模糊（v20.7 真机"上下字看不清"根因）。
    // 玻璃质感由 垂直渐变半透明 + 顶部高光描边 + 柔和阴影 呈现，文字零模糊。
    val top = tint.copy(alpha = (alpha + 0.16f).coerceAtMost(0.94f))
    val bottom = tint.copy(alpha = (alpha - 0.18f).coerceAtLeast(0.22f))
    this
        .shadow(shadow, shape, clip = false)
        .background(
            Brush.verticalGradient(0f to top, 1f to bottom),
            shape,
        )
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.85f),
                    Color.White.copy(alpha = 0.28f),
                )
            ),
            shape = shape,
        )
}

/** v20.4/20.6 手感层：进度条平滑过渡（频率条/内存条/温度条共用）。
 *  v20.6：tween(420ms) → smooth 临界阻尼弹簧；支持 Reduce Motion 直切 */
@Composable
fun AnimatedFracBar(
    frac: Float,
    color: Color,
    height: Dp,
    corner: Dp,
    trackColor: Color = Ink.panel,
) {
    val target = frac.coerceIn(0f, 1f)
    val a = remember { Animatable(target) }
    val animScale = rememberAnimatorScale()
    val scrolling = LocalScrolling.current
    LaunchedEffect(target, scrolling) {
        // v0.28.3: 滚动中进度条直切
        if (scrolling || animScale <= 0f) a.snapTo(target)
        else a.animateTo(target, Motion.barSpring)
    }
    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(corner)).background(trackColor)) {
        Box(Modifier.fillMaxWidth(a.value).height(height).clip(RoundedCornerShape(corner)).background(color))
    }
}
