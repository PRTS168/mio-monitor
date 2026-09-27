package com.a41probe.monitor.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

/**
 * 触感强度档位（设置 → 显示 → 触感强度）。
 * LIGHT / MEDIUM / HEAVY 三档，持久化在 `mio_haptics`。
 */
enum class HapticLevel { LIGHT, MEDIUM, HEAVY }

/**
 * 轻触感反馈。设计基于 A41 Ultra 的真机实测结论：
 *
 *  - 该机内核为 `qcom,qpnp-vibrator-ldo`（线性马达），但**厂商 HAL 不报振幅能力**
 *    （`mMaxAmplitudes count=0`、无 primitives/effects、`mHapticChannelMaxVibrationAmplitude=NaN`），
 *    因此 App 传的振幅参数会被忽略——**强度只能靠"系统常量 + 时长"表达**；
 *  - 裸 `vibrate()` 在 `dumpsys vibrator_manager` 里记录为 `scale: 0.00`（ROM 按最低触感标度播放），
 *    所以裸震动**很轻**；而 `performHapticFeedback(系统常量)` 走 ROM 自己的标度，才是"别的软件有轻有重"的原因；
 *  - `CLOCK_TICK` 在该 ROM 上**未被映射**：框架返回"已执行"却不震，且因此不触发兜底——已弃用。
 *
 * 三档映射：
 *  - 轻：`CONTEXT_CLICK`（实测有效、力度适中）；常量不可用时裸 12–14ms
 *  - 中：`LONG_PRESS`（系统里更重的常量）；不可用时裸 18–22ms
 *  - 重：`LONG_PRESS` **再加**一段 20–24ms 裸脉冲，保证严格重于"中"
 *
 * 尊重系统"触感反馈"总开关；无马达/系统不允许时静默降级，绝不崩溃。
 */
object MioHaptics {
    private const val PREFS = "mio_haptics"
    private const val KEY_LEVEL = "haptic_level"

    @Volatile private var enabled = true
    @Volatile private var level: HapticLevel = HapticLevel.MEDIUM

    fun init(ctx: Context) {
        enabled = runCatching {
            Settings.System.getInt(
                ctx.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1
        }.getOrDefault(true)
        level = runCatching {
            when (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_LEVEL, HapticLevel.MEDIUM.ordinal)) {
                0 -> HapticLevel.LIGHT
                2 -> HapticLevel.HEAVY
                else -> HapticLevel.MEDIUM
            }
        }.getOrDefault(HapticLevel.MEDIUM)
    }

    fun set(ctx: Context, l: HapticLevel) {
        level = l
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_LEVEL, l.ordinal).apply()
        }
    }

    fun current(): HapticLevel = level

    /** 走系统触感常量（返回 false = 该系统常量在该 ROM 上不可用，调用方退回裸震动） */
    fun system(view: View?, constant: Int): Boolean =
        runCatching { enabled && view != null && view.performHapticFeedback(constant) }
            .getOrDefault(false)

    /** 按当前档位播放一次反馈。[isPress] 区分"卡片按下"（略长）与"Tab 切换"（略短）。 */
    fun play(ctx: Context, view: View?, isPress: Boolean) {
        if (!enabled) return
        val short = if (isPress) 14L else 12L
        val medium = if (isPress) 22L else 18L
        val heavy = if (isPress) 24L else 20L
        when (level) {
            HapticLevel.LIGHT ->
                if (!system(view, HapticFeedbackConstants.CONTEXT_CLICK)) oneShot(ctx, short)

            HapticLevel.MEDIUM ->
                if (!system(view, HapticFeedbackConstants.LONG_PRESS)) oneShot(ctx, medium)

            HapticLevel.HEAVY -> {
                system(view, HapticFeedbackConstants.LONG_PRESS)
                oneShot(ctx, heavy)   // 叠加，确保"重"严格重于"中"
            }
        }
    }

    /* ---------- 按住即持续震动（设置页"轻/中/重"三个键） ---------- */

    private const val HOLD_MS = 10_000L   // 单次持续震动上限；松手会立刻 cancel

    /** 按住开始持续震动：支持振幅的机型按档位给振幅；不支持的机型用**占空比**模拟轻重。 */
    fun startHold(ctx: Context, l: HapticLevel) {
        if (!enabled) return
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            v.cancel()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION") v.vibrate(HOLD_MS); return@runCatching
            }
            val effect = if (v.hasAmplitudeControl()) {
                val amp = when (l) {
                    HapticLevel.LIGHT -> 90
                    HapticLevel.MEDIUM -> 165
                    HapticLevel.HEAVY -> 255
                }
                VibrationEffect.createOneShot(HOLD_MS, amp)
            } else {
                // 无振幅控制：占空比表达强弱——重=常开；中=短间歇；轻=明显间歇
                when (l) {
                    HapticLevel.HEAVY -> VibrationEffect.createOneShot(HOLD_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                    HapticLevel.MEDIUM -> VibrationEffect.createWaveform(loopOf(22L, 6L), -1)
                    HapticLevel.LIGHT -> VibrationEffect.createWaveform(loopOf(10L, 14L), -1)
                }
            }
            v.vibrate(effect)
        }
    }

    /** 松手：立刻停止持续震动 */
    fun stopHold(ctx: Context) {
        runCatching { vibrator(ctx)?.cancel() }
    }

    /** 把 (on, off) 拼成约 10 秒的图案，供无振幅机型做"持续但有节奏"的震动 */
    private fun loopOf(on: Long, off: Long): LongArray {
        val cycles = (HOLD_MS / (on + off)).toInt().coerceIn(1, 600)
        val out = LongArray(cycles * 2 + 1)
        out[0] = 0L
        var i = 1
        repeat(cycles) { out[i++] = on; out[i++] = off }
        return out
    }

    private fun vibrator(ctx: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    /**
     * 裸单次震动（兜底路径）。不支持振幅控制的机型上系统会忽略振幅，强度只由时长决定，
     * 因此这类机型自动把脉冲加长 1.5 倍补偿。
     */
    private fun oneShot(ctx: Context, ms: Long) {
        if (!enabled) return
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = if (v.hasAmplitudeControl()) 200 else VibrationEffect.DEFAULT_AMPLITUDE
                val dur = if (v.hasAmplitudeControl()) ms else (ms * 1.5f).toLong().coerceAtLeast(ms)
                v.vibrate(VibrationEffect.createOneShot(dur, amp))
            } else {
                @Suppress("DEPRECATION") v.vibrate((ms * 1.5f).toLong())
            }
        }
    }
}

/** Compose 侧取用：remember 一次，避免每次重组重建回调 */
class MioHapticsScope internal constructor(
    private val ctx: Context,
    private val view: View?,
) {
    /** Tab 切换 */
    fun tick() = MioHaptics.play(ctx, view, isPress = false)

    /** 卡片 / 按钮按下 */
    fun press() = MioHaptics.play(ctx, view, isPress = true)
}

@Composable
fun rememberHaptics(): MioHapticsScope {
    val ctx = LocalContext.current
    val view = LocalView.current
    return remember(ctx, view) { MioHapticsScope(ctx, view) }
}
