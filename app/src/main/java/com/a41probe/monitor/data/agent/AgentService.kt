package com.a41probe.monitor.data.agent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.a41probe.monitor.MainActivity
import com.a41probe.monitor.R
import com.a41probe.monitor.data.SnapshotCollector
import com.a41probe.monitor.data.remote.PrivLevel
import com.a41probe.monitor.data.remote.SnapshotEncoder
import com.a41probe.monitor.data.remote.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 被监控端前台服务：常驻通知 + START_STICKY（被杀自动重启），后台每秒采集，
 * 经 TCP Server 向所有已连接监控端推送快照；mDNS 广播由 [AgentNsd] 负责。
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var nsd: AgentNsd? = null
    private var collector: SnapshotCollector? = null
    @Volatile private var active = false
    private var startedAt = 0L
    @Volatile private var shuttingDown = false
    private var nsdRegistered = false
    // P2-4: 首次采集完成后才允许发 hello，保证 hello 的 priv 不误报 FREE
    private val firstCollected = CompletableDeferred<Unit>()

    private class Conn(
        val host: String,
        val socket: Socket,
        val outgoing: Channel<String>,
        var job: Job? = null,
    )
    private val conns = CopyOnWriteArrayList<Conn>()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground(0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!active && !shuttingDown) start()
        return START_STICKY   // 被杀后系统自动重建服务
    }

    // P1-1: 任何非 ACTION_STOP 的销毁路径（系统回收/外部 stopService/划掉任务）都要释放资源
    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // v0.24: 用户从多任务"划掉"卡片（未点终止）时的守护。
    // ① 进程被杀前立即重新拉起前台服务；② AlarmManager 1.5s 后兜底重建
    // （在已授予"自启动"的 ROM 上生效；ColorOS 等仍需用户在系统设置开自启动 + 多任务加锁，
    //  见 AgentScreen 厂商保活引导）。
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!shuttingDown) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    this, Intent(this, AgentService::class.java))
            }
            runCatching {
                // P1-1: 改用 getForegroundService，闹钟触发即走 startForegroundService，
                // 规避 Android 12+ 后台 startService 限制（API26，minSdk26 可用）
                val pi = PendingIntent.getForegroundService(
                    this, 1, Intent(this, AgentService::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
                val am = getSystemService(ALARM_SERVICE) as AlarmManager
                am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1500, pi)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun start() {
        if (active || shuttingDown) return
        collector = SnapshotCollector(this)
        nsd = AgentNsd(this)

        // P2-10: 先 bind 成功再置 active，失败则不进入半启动状态
        val srv = ServerSocket()
        srv.reuseAddress = true
        try {
            srv.bind(InetSocketAddress(0))
        } catch (t: Throwable) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        server = srv
        val port = srv.localPort
        active = true
        startedAt = System.currentTimeMillis()

        // accept 循环
        scope.launch {
            while (active) {
                val s = try {
                    srv.accept()
                } catch (t: Throwable) {
                    if (!active) break else { delay(200); continue }
                }
                handleClient(s)
            }
        }

        // 采集 + 广播循环
        scope.launch {
            val baseInfo = SnapshotEncoder.buildDeviceInfo(this@AgentService)
            AgentState.setInfo(baseInfo)
            while (active && isActive) {
                val t0 = System.currentTimeMillis()
                val snap = try {
                    collector!!.collect(halSync = true)
                } catch (t: Throwable) {
                    delay(500); continue
                }
                val priv = SnapshotEncoder.privOf(snap)
                AgentState.setPriv(priv)
                if (!firstCollected.isCompleted) firstCollected.complete(Unit)
                if (!nsdRegistered) {
                    nsdRegistered = true
                    nsd?.register(baseInfo.copy(priv = priv), port)
                    AgentState.setRunning(true, port)
                }
                val json = SnapshotEncoder.encodeSnapshot(snap)
                conns.forEach { c -> c.outgoing.trySend(json) }
                updateNotification(conns.size, priv)
                val elapsed = System.currentTimeMillis() - t0
                delay((INTERVAL_MS - elapsed).coerceAtLeast(200))
            }
        }

        applyLocks()
        // v0.27.2: 记住开启状态（开机自启）+ JobScheduler 周期兜底
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AGENT_ON, true).apply()
        scheduleKeepAlive()
    }

    private fun handleClient(socket: Socket) {
        val host = socket.inetAddress?.hostAddress ?: "?"
        val outgoing = Channel<String>(capacity = Channel.BUFFERED)
        val conn = Conn(host, socket, outgoing)
        conns.add(conn)
        conn.job = scope.launch {
            try {
                socket.tcpNoDelay = true
                val out = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())
                // P2-4: 等首次采集完成（最多 3s）再发 hello，确保 priv 正确
                withTimeoutOrNull(3000) { firstCollected.join() }
                val info = AgentState.info.value.copy(priv = AgentState.priv.value)
                Wire.writeFrame(out, SnapshotEncoder.encodeHello(info))
                AgentState.addClient(MonitorClient(host, System.currentTimeMillis()))
                updateNotification(conns.size, AgentState.priv.value)
                // 读协程：监控端目前只收不发，读到 EOF/异常即对方断开
                val reader = launch {
                    while (true) {
                        val f = try { Wire.readFrame(input) } catch (t: Throwable) { null }
                        if (f == null) {
                            // P2-6: 立即收尾——关闭 Channel 让写循环退出、关 socket 唤醒阻塞
                            outgoing.close()
                            runCatching { socket.close() }
                            break
                        }
                    }
                }
                for (frame in outgoing) Wire.writeFrame(out, frame)
                reader.cancel()
            } catch (t: Throwable) {
                // 写失败/连接断开
            } finally {
                conns.remove(conn)
                AgentState.removeClient(host)
                runCatching { socket.close() }
                updateNotification(conns.size, AgentState.priv.value)
            }
        }
    }

    private fun shutdown() {
        if (shuttingDown) return   // 幂等：ACTION_STOP 与 onDestroy 可能先后到达
        shuttingDown = true
        active = false
        // 通知所有监控端 bye
        val bye = SnapshotEncoder.encodeBye()
        conns.forEach { c ->
            c.outgoing.trySend(bye)
            c.outgoing.close()
            c.job?.cancel()
            runCatching { c.socket.close() }
        }
        conns.clear()
        nsd?.unregister()
        nsdRegistered = false
        runCatching { server?.close() }
        releaseLocks()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AGENT_ON, false).apply()
        cancelKeepAlive()
        scope.cancel()   // P1-1: 取消 accept/采集协程，避免泄漏
        AgentState.setRunning(false)
        AgentState.clearClients()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // P1-2: 取消 onTaskRemoved 排的兜底闹钟，避免显式停止后被残留闹钟复活
        runCatching {
            val pi = PendingIntent.getForegroundService(
                this, 1, Intent(this, AgentService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
            (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(pi)
            pi.cancel()
        }
        stopSelf()
    }

    // ---- 锁屏保持（WakeLock / WifiLock） ----
    private fun applyLocks() {
        val keep = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_KEEP_AWAKE, false)
        if (keep) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mio:agent").apply {
                setReferenceCounted(false); acquire(LOCK_TIMEOUT)
            }
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Mio:wifi").apply {
                setReferenceCounted(false); acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null; wifiLock = null
    }

    // ---- JobScheduler 兜底 ----
    private fun scheduleKeepAlive() {
        runCatching {
            val js = getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(
                KEEP_JOB_ID, ComponentName(this, KeepAliveJobService::class.java),
            )
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            js.schedule(job)
        }
    }

    private fun cancelKeepAlive() {
        runCatching {
            (getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(KEEP_JOB_ID)
        }
    }

    // ---- 通知 ----
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Mio 澪 · 被监控服务", android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Mio 澪 被监控模式常驻"; setShowBadge(false) }
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun startAsForeground(clientCount: Int) {
        val n = buildNotification(clientCount, AgentState.priv.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(clientCount: Int, priv: PrivLevel) {
        if (!active) return
        val nm = getSystemService(android.app.NotificationManager::class.java)
        runCatching { nm.notify(NOTIF_ID, buildNotification(clientCount, priv)) }
    }

    private fun buildNotification(clientCount: Int, priv: PrivLevel): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val mins = if (startedAt > 0) (System.currentTimeMillis() - startedAt) / 60000 else 0
        val text = "权限档 ${priv.label} · ${clientCount} 台监控端连接 · 已运行 ${mins} 分钟"
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, AgentService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mio 澪 被监控模式运行中")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(R.drawable.ic_stat_agent, "终止", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.a41probe.agent.STOP"
        private const val CHANNEL_ID = "a41_agent"
        private const val NOTIF_ID = 4101
        private const val INTERVAL_MS = 1000L
        private const val LOCK_TIMEOUT = 12 * 60 * 60 * 1000L  // 12h 安全上限，防泄漏
        const val PREFS = "a41_agent"
        const val KEY_KEEP_AWAKE = "keep_awake"
        const val KEY_AGENT_ON = "agent_on"
        private const val KEEP_JOB_ID = 4102

        fun start(ctx: Context) {
            val i = Intent(ctx, AgentService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, AgentService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }
    }
}
