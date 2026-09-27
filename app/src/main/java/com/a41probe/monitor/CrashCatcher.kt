package com.a41probe.monitor

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.26.4: 全局未捕获异常落盘——进程级兜底，任何线程（UI/采集/后台服务/网络）
 * 的未捕获崩溃都会写入 App 私有目录 crash.log（保留最近 2000 行，环形裁剪），
 * 供下次启动定位"疑似突发闪退"的根因。不吞异常：记录后仍交给系统默认 handler。
 */
object CrashCatcher {
    private const val MAX_LINES = 2000

    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val appCtx = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            runCatching { write(appCtx, thread, t) }
            prev?.uncaughtException(thread, t)
        }
    }

    private fun write(ctx: Context, thread: Thread, t: Throwable) {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "crash")
        dir.mkdirs()
        val f = File(dir, "crash.log")
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val head = "[$ts] thread=${thread.name} | ${android.os.Process.myPid()}\n$sw\n----\n"
        val old = if (f.exists()) f.readText() else ""
        val kept = old.lines().takeLast(MAX_LINES).joinToString("\n")
        f.writeText(head + kept)
    }

    /** 崩溃日志路径（设置页·关于 展示用） */
    fun logPath(ctx: Context): String =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "crash").absolutePath
}
