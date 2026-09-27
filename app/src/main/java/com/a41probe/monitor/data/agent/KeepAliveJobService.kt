package com.a41probe.monitor.data.agent

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import androidx.core.content.ContextCompat

/** JobScheduler 周期兜底（15 分钟）：被监控服务被杀后检查并重新拉起。 */
class KeepAliveJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val on = getSharedPreferences(AgentService.PREFS, MODE_PRIVATE)
            .getBoolean(AgentService.KEY_AGENT_ON, false)
        if (on) {
            runCatching {
                ContextCompat.startForegroundService(
                    this, Intent(this, AgentService::class.java),
                )
            }
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?) = true
}
