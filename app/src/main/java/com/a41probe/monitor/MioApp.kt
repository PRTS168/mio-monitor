package com.a41probe.monitor

import android.app.Application
import com.a41probe.monitor.ui.components.GlassConfig
import com.a41probe.monitor.ui.components.MioHaptics

/**
 * 自定义 Application：比 MainActivity 更早安装 CrashCatcher，
 * 覆盖 Application 初始化 / ContentProvider 阶段的崩溃兜底。
 */
class MioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashCatcher.install(this)
        // v0.28.3: 玻璃档位在进程最早期加载（SharedPreferences），UI 首帧即生效
        GlassConfig.init(this)
        // v0.28.8: 触感反馈是否可用（读系统总开关），同样在早期确定
        MioHaptics.init(this)
    }
}
