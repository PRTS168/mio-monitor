package com.a41probe.monitor.data.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机自启：用户开启过被监控模式且未手动终止时，开机完成后重新拉起服务。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val on = context.getSharedPreferences(AgentService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(AgentService.KEY_AGENT_ON, false)
        if (on) runCatching { AgentService.start(context) }
    }
}
