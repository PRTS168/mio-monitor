package com.a41probe.monitor.ui.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/* ===== 设计令牌（iOS 视觉语言）=====
 * v1.1：令牌**名字与类型完全不变**，内部改为读取可切换的 Palette（Compose 状态）。
 * 因此全工程几百处 `Ink.xxx` 调用点一个字都不用改，切换主题自动重组、即时生效。
 * 语义色（温度红/橙/绿、警告/危险）固定不随主题变化——见 Palette.kt 说明。 */
private val paletteState = mutableStateOf(Palettes.BLUE)

object Ink {
    val bg: Color get() = paletteState.value.bg
    val card: Color get() = paletteState.value.card
    val panel: Color get() = paletteState.value.panel
    val stroke: Color get() = paletteState.value.stroke

    val tx: Color get() = paletteState.value.tx        // 主文字
    val tx2: Color get() = paletteState.value.tx2      // 次级文字
    val tx3: Color get() = paletteState.value.tx3      // 口径说明小字
    val off: Color get() = paletteState.value.off      // 置灰/不可用

    val accent: Color get() = paletteState.value.accent
    val accent2: Color get() = paletteState.value.accent2
    /** 压在强调色上的文字/图标色（浅色主题用深色文字，默认蓝用白色） */
    val onAccent: Color get() = paletteState.value.onAccent
    /** 强调色浅底：选中态 / Tab 指示器 */
    val accentSoft: Color get() = paletteState.value.accentSoft
    /** 曲线主色：比 accent 柔和一档 */
    val curve: Color get() = paletteState.value.curve
    /** 阴影色：带主题色温 */
    val shadowTint: Color get() = paletteState.value.shadowTint
    val m: Color get() = paletteState.value.accent2

    // 语义色：固定
    val ok = Color(0xFF34C759)
    val warn = Color(0xFFFF9500)
    val danger = Color(0xFFFF3B30)

    val clusterPrime: Color get() = paletteState.value.clusterPrime
    val clusterPerf: Color get() = paletteState.value.clusterPerf
    val clusterEff: Color get() = paletteState.value.clusterEff
    val clusterBal: Color get() = paletteState.value.clusterBal

    val iconBgBlue: Color get() = paletteState.value.iconBgBlue
    val iconBgCyan: Color get() = paletteState.value.iconBgCyan
    val iconBgGreen: Color get() = paletteState.value.iconBgGreen
    val iconBgOrange: Color get() = paletteState.value.iconBgOrange
    val iconBgPurple: Color get() = paletteState.value.iconBgPurple
    val iconBgYellow: Color get() = paletteState.value.iconBgYellow

    internal fun applyPalette(p: Palette) { paletteState.value = p }
}

// 提权档位标注：颜色改为计算属性，随主题即时变化（原先写死在枚举构造参数里，换肤不生效）
enum class Priv(val tag: String) {
    FREE("—"),
    SHIZUKU("S"),
    ROOT("R");

    val color: Color get() = when (this) {
        FREE -> Ink.off
        SHIZUKU -> Ink.accent
        ROOT -> Ink.warn
    }

    companion object { fun of(tag: String) = entries.firstOrNull { it.tag == tag } ?: FREE }
}

// 数据状态机
enum class DataState { OK, NEED_PRIV, UNAVAILABLE, STALE }
