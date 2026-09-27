package com.a41probe.monitor.ui.components

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.a41probe.monitor.ui.theme.Ink

/* ============================================================
 * v0.28.3 液态玻璃全面重做（学自 WWDC25 Liquid Glass 设计语言）
 *
 * 结构设计（规避 v20.7 历史坑"renderEffect 把文字一起糊掉"）：
 *  - 玻璃壳层：clip + 半透明垂直渐变 + 顶部高光描边 + 轻模糊
 *    （Modifier.blur 只作用于壳层自身绘制，GPU 合成时柔化磨砂边缘）；
 *  - 内容层：兄弟 Box，叠在壳上，文字/图标零模糊。
 *  - 性能分级：FULL（模糊）/ LITE（静态渐变）/ OFF（纯色卡片）。
 *  - 滚动渐变：顶栏等外壳读取 LocalScrollFraction，随页面滚动 透明→模糊。
 * ============================================================ */

/** 玻璃档位 */
enum class GlassLevel { FULL, LITE, OFF }

/**
 * 全局玻璃配置。设置页可改，SharedPreferences 持久化；
 * API<31 无 RenderEffect 自动降级 LITE（静态渐变，视觉近似、零崩溃风险）。
 */
object GlassConfig {
    private const val PREFS = "mio_glass"
    private const val KEY_LEVEL = "glass_level"

    /* v0.28.5 修复：档位必须用 Compose 状态保存。
     * 此前是普通 @Volatile 变量，而各玻璃面用 `remember { GlassConfig.effective() }` 读取——
     * remember 无 key，值算一次就缓存整场，设置页切换后没有任何重组被触发，
     * 因此"选了关闭/简约却没反应"（设置本身已写入偏好，只是界面不更新）。
     * 改为 mutableStateOf 后，set() 会令所有读取方立即重组，真正做到"切换立即生效"。 */
    private val levelState = mutableStateOf(GlassLevel.FULL)

    fun init(ctx: Context) {
        levelState.value = try {
            when (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LEVEL, GlassLevel.FULL.ordinal)) {
                1 -> GlassLevel.LITE
                2 -> GlassLevel.OFF
                else -> GlassLevel.FULL
            }
        } catch (_: Exception) { GlassLevel.FULL }
    }

    fun set(ctx: Context, l: GlassLevel) {
        levelState.value = l
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_LEVEL, l.ordinal).apply()
        } catch (_: Exception) {}
    }

    fun current(): GlassLevel = levelState.value

    /** 实际生效档位：关闭优先；无 RenderEffect 自动降级为静态渐变 */
    fun effective(): GlassLevel = when {
        levelState.value == GlassLevel.OFF -> GlassLevel.OFF
        Build.VERSION.SDK_INT < 31 -> GlassLevel.LITE
        else -> levelState.value
    }
}

/* ---- 滚动上下文（全局）：各页面 LazyColumn 写入，顶栏/动画组件读取 ---- */

/* 注意（v0.28.4 性能修复）：这两个上下文必须用**动态** compositionLocalOf。
 * 它们由 ScrollAware 包裹在页面内容外层，而取值随滚动逐像素变化
 * （firstVisibleItemScrollOffset / 200f）。staticCompositionLocalOf 的语义是
 * "值一变、提供者以下整棵子树全部重组"——滚动时会把整个 LazyColumn 及其所有
 * item 每帧重组一遍，这是"滑动卡顿"的主要来源。
 * 动态版本只为真正读取该值的 composable 订阅失效（本工程只有少量组件读
 * LocalScrolling；LocalScrollFraction 的读者在提供者之外，读到的是默认值），
 * 因此滚动时不再触发整页重组。 */

/** 页面列表是否正在滚动（滚动中动画组件自动直切，避免掉帧） */
val LocalScrolling = compositionLocalOf { false }

/** 页面滚动进度 0..1（首个 item 滚出约 200dp 即满），供顶栏透明→模糊渐变 */
val LocalScrollFraction = compositionLocalOf { 0f }

/**
 * 页面接入滚动上下文的包裹层。用法：
 *   val listState = rememberLazyListState()
 *   ScrollAware(listState) { LazyColumn(state = listState) { ... } }
 */
@Composable
fun ScrollAware(listState: LazyListState, content: @Composable () -> Unit) {
    val ctx by remember(listState) {
        derivedStateOf {
            val frac = ((listState.firstVisibleItemIndex +
                listState.firstVisibleItemScrollOffset / 200f).coerceAtLeast(0f)).coerceAtMost(1f)
            listState.isScrollInProgress to frac
        }
    }
    /* v0.28.8: overscroll 柔和视觉变化——拉到顶/底还在拉时，页面轻微位移 + 极轻压缩，
     * 松手用软弹簧归位；幅度刻意压得很小（位移 ≤ ~22px、压缩 ≤ 3%），只给"到边了"的手感，
     * 不做 iOS 那种大幅拉伸。过卷量在绘制期读取，不触发重组。 */
    val animScale = rememberAnimatorScale()
    var over by remember { mutableFloatStateOf(0f) }
    val conn = remember(listState, animScale) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset, available: Offset, source: NestedScrollSource,
            ): Offset {
                // available != 0 → 滚动容器已无法消费，即处于过卷状态
                if (available.y != 0f && animScale > 0f) {
                    over = (over + available.y * 0.28f).coerceIn(-70f, 70f)
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && animScale > 0f && over != 0f) {
                val a = Animatable(over)
                a.animateTo(0f, Motion.softSpring()) { over = value }
            }
        }
    }
    CompositionLocalProvider(
        LocalScrolling provides ctx.first,
        LocalScrollFraction provides ctx.second,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .nestedScroll(conn)
                .graphicsLayer {
                    translationY = over * 0.30f
                    val k = 1f - kotlin.math.abs(over) * 0.00045f
                    scaleX = k
                    scaleY = k
                },
        ) { content() }
    }
}

/** 页面便捷入口：remember state + 滚动上下文一步完成 */
@Composable
fun rememberScrollAwareState(): LazyListState {
    val state = rememberLazyListState()
    val ctx by remember(state) {
        derivedStateOf {
            val frac = ((state.firstVisibleItemIndex +
                state.firstVisibleItemScrollOffset / 200f).coerceAtLeast(0f)).coerceAtMost(1f)
            state.isScrollInProgress to frac
        }
    }
    CompositionLocalProvider(
        LocalScrolling provides ctx.first,
        LocalScrollFraction provides ctx.second,
    ) { }
    return state
}

/**
 * 玻璃外壳（Liquid Glass Surface）。
 *
 * 结构：
 *   1) 阴影层（shape 外）
 *   2) 玻璃壳：clip(shape) + 半透明垂直渐变 + 顶部高光描边；FULL 档加
 *      RenderEffect blur（只作用壳层，GPU 合成时模糊底层内容）
 *   3) 内容层：兄弟 Box，clip(shape)，文字/图标零模糊
 *
 * 克制原则（学自 Apple）：玻璃仅用于外壳层（顶栏 / 底部导航 / 悬浮件），
 * 内容卡片保持白卡；玻璃元素不叠放。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    tint: Color = Color.White,
    alpha: Float = 0.72f,
    shadow: Dp = 8.dp,
    blurRadius: Float = 30f,
    content: @Composable () -> Unit,
) {
    val level = GlassConfig.effective()
    if (level == GlassLevel.OFF) {
        // v0.28.7: OFF 用 tint 实色（此前写死 Ink.card 白）。顶栏 tint 是蓝色，白底会让白色标题
        // 在白底上消失——真机实测：关闭档顶栏变成一块白板、标题看不见。
        Box(modifier.clip(shape).background(tint)) { content() }
        return
    }
    val top = tint.copy(alpha = (alpha + 0.16f).coerceAtMost(0.94f))
    val bottom = tint.copy(alpha = (alpha - 0.18f).coerceAtLeast(0.22f))
    val grad = remember(tint, top, bottom) { Brush.verticalGradient(0f to top, 1f to bottom) }
    val borderBrush = remember {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.28f))
        )
    }
    // FULL 档：对玻璃壳渐变做轻模糊（柔化边缘/磨砂质感近似，跨 Compose 版本稳定）。
    // 注意：Modifier.blur 只模糊壳层自身绘制（渐变），文字/图标是兄弟层，零模糊。
    val shellBlur = if (level == GlassLevel.FULL) Modifier.blur(3.dp) else Modifier

    Box(modifier.shadow(shadow, shape, clip = false)) {
        // 玻璃壳：**不透明底 + 半透明渐变** + 高光边；FULL 档轻模糊柔化磨砂边缘。
        // v0.28.7: 加不透明底层——顶栏同样存在"下层透出"问题（状态栏边界/窗口底色比页面亮），
        // 半透明壳会让它显出一条横向亮带；同时它也让 OFF 之外的档位视觉效果稳定、可预期。
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(tint)
                .background(grad)
                .then(shellBlur)
                .border(width = 1.dp, brush = borderBrush, shape = shape)
        )
        // 内容层：不模糊
        Box(Modifier.matchParentSize().clip(shape)) { content() }
    }
}

/**
 * 单层无模糊玻璃壳——v0.28.4 起用于**底部导航**。
 *
 * 与 [GlassSurface] 的唯一区别：不叠第二层、也不使用 Modifier.blur；
 * 其余（单层垂直渐变 + 1dp 高光描边 + 柔和阴影 + 内容按 shape 裁切）保持一致。
 *
 * 为什么底栏必须用它（2026-09-27 对真机截图做逐像素剖面得到的事实）：
 *  1. A41 Ultra（Adreno 730）上，[GlassSurface] 的"壳层 + 内容兄弟层"双层半透明
 *     叠加、且壳层带 blur(3.dp)，会在导航栏内部合成出一条**硬边亮带**：同一条竖线上
 *     页面灰底 #F2F2F7 之上出现 #FBFBFD 的硬跳变，其上方还有一段比页面灰底更暗的
 *     凹陷（#EDEDEE）——这两块都不是该位置代码能画出来的形状；
 *  2. 同一份代码在 OPPO 机型上完全没有该现象 → 属于 GPU 合成层面的问题，不是布局错误；
 *  3. v0.30.0 曾以"去模糊 + 不再叠层"验证过亮带消失（有效改动只有这一处，该版本其余
 *     UI 改动已整体废弃）。
 *
 * 装机判定标准：底栏内同一条竖线上不再出现硬跳变；观感（半透明白、高光边、悬浮阴影）
 * 应与 28.3 保持一致，仅少了肉眼几乎不可见的 3dp 壳层柔化。
 */
@Composable
fun GlassSurfaceFlat(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    tint: Color = Color.White,
    alpha: Float = 0.72f,
    shadow: Dp = 8.dp,
    content: @Composable () -> Unit,
) {
    val level = GlassConfig.effective()
    if (level == GlassLevel.OFF) {
        // v0.28.7: OFF 用 tint 实色（此前写死 Ink.card 白）。顶栏 tint 是蓝色，白底会让白色标题
        // 在白底上消失——真机实测：关闭档顶栏变成一块白板、标题看不见。
        Box(modifier.clip(shape).background(tint)) { content() }
        return
    }
    val top = tint.copy(alpha = (alpha + 0.16f).coerceAtMost(0.94f))
    val bottom = tint.copy(alpha = (alpha - 0.18f).coerceAtLeast(0.22f))
    val grad = remember(tint, top, bottom) { Brush.verticalGradient(0f to top, 1f to bottom) }
    val borderBrush = remember {
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.28f))
        )
    }
    Box(
        modifier
            .shadow(shadow, shape, clip = false)
            .clip(shape)
            // v0.28.7: 先铺一层**不透明**底（tint 实色），再叠半透明渐变。
            // 底栏此前只有半透明渐变，导致其下方那层（y≈2294 起、系统导航栏边界以下的更亮底色）
            // 透过底栏显出一条硬边亮带——真机三档实测：仅"关闭"（不透明）无此带。
            // 加不透明底后，玻璃光泽（渐变 + 高光边）保留，但下层再也透不上来。
            .background(tint)
            .background(grad)
            .border(width = 1.dp, brush = borderBrush, shape = shape),
    ) { content() }
}
