package com.a41probe.monitor.data

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.a41probe.monitor.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 周期采集 ViewModel：1s 采样，采集逻辑委托 [SnapshotCollector]（与被监控端 Agent 共用同一口径）。
 *  生命周期由 MainActivity 的 LifecycleEventObserver 驱动 start()/stop()。 */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    // 冷启动 hero 首帧滚入开关——splash 结束后 arm，滚完即关（进程级只一次）
    var heroReady by mutableStateOf(false)
        private set
    fun armHeroRoll() { heroReady = true }
    fun heroRollDone() { heroReady = false }

    /** 历史曲线缓冲（最近 60 点，**原始值**：MHz / % / V / A / W / °C） */
    private val _freqHist = MutableStateFlow<List<List<Float>>>(emptyList())
    val freqHist: StateFlow<List<List<Float>>> = _freqHist.asStateFlow()
    private val _busyHist = MutableStateFlow<List<Float>>(emptyList())
    val busyHist: StateFlow<List<Float>> = _busyHist.asStateFlow()
    private val _voltHist = MutableStateFlow<List<Float>>(emptyList())
    val voltHist: StateFlow<List<Float>> = _voltHist.asStateFlow()
    private val _curHist = MutableStateFlow<List<Float>>(emptyList())
    val curHist: StateFlow<List<Float>> = _curHist.asStateFlow()
    private val _powerHist = MutableStateFlow<List<Float>>(emptyList())
    val powerHist: StateFlow<List<Float>> = _powerHist.asStateFlow()
    private val _tempHist = MutableStateFlow<List<Float>>(emptyList())
    val tempHist: StateFlow<List<Float>> = _tempHist.asStateFlow()
    // GPU 自身温度（gpuss-0）历史
    private val _gpuTempHist = MutableStateFlow<List<Float>>(emptyList())
    val gpuTempHist: StateFlow<List<Float>> = _gpuTempHist.asStateFlow()
    // CPU 总占用曲线
    private val _cpuTotalHist = MutableStateFlow<List<Float>>(emptyList())
    val cpuTotalHist: StateFlow<List<Float>> = _cpuTotalHist.asStateFlow()

    // 分核占用（采集后从 Snapshot 同步，供 CpuScreen）
    private val _corePercent = MutableStateFlow<List<Int?>>(emptyList())
    val corePercent: StateFlow<List<Int?>> = _corePercent.asStateFlow()
    // CPU 总占用（采集后从 Snapshot 同步）
    private val _cpuTotalPercent = MutableStateFlow<Double?>(null)
    val cpuTotalPercent: StateFlow<Double?> = _cpuTotalPercent.asStateFlow()

    private var job: Job? = null
    private var ctx: Context = app.applicationContext
    private val collector = SnapshotCollector(ctx)

    // 最近一次成功采样时间戳
    private val _lastSuccessAt = MutableStateFlow(0L)
    val lastSuccessAt: StateFlow<Long> = _lastSuccessAt.asStateFlow()
    // 停更状态
    private val _isStale = MutableStateFlow(false)
    val isStale: StateFlow<Boolean> = _isStale.asStateFlow()

    // 传感器实时采样（仅传感器页可见时注册）
    private val _sensorsLive = MutableStateFlow<Map<Int, FloatArray>>(emptyMap())
    val sensorsLive: StateFlow<Map<Int, FloatArray>> = _sensorsLive.asStateFlow()
    private var sensorManager: SensorManager? = null
    private var sensorListener: SensorEventListener? = null

    init {
        CapacityHistory.init(app)
        viewModelScope.launch(Dispatchers.IO) {
            ShizukuBridge.refresh()
            RootBridge.refresh()
        }
    }

    /** 传感器页 DisposableEffect 调用：注册 5 类实时通道 */
    fun registerSensors() {
        if (sensorManager != null) return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sensorManager = sm
        val lastEmitByType = HashMap<Int, Long>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val now = System.currentTimeMillis()
                val prev = lastEmitByType[e.sensor.type] ?: 0L
                if (now - prev < 200) return
                lastEmitByType[e.sensor.type] = now
                _sensorsLive.update { it + (e.sensor.type to e.values.clone()) }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sensorListener = listener
        val types = intArrayOf(
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY,
        )
        types.forEach { t ->
            sm.getDefaultSensor(t)?.let { s ->
                runCatching { sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL) }
            }
        }
    }

    fun unregisterSensors() {
        sensorManager?.let { sm -> sensorListener?.let { runCatching { sm.unregisterListener(it) } } }
        sensorManager = null
        sensorListener = null
    }

    override fun onCleared() {
        stop()
        unregisterSensors()
        super.onCleared()
    }

    fun start() {
        if (job != null) return
        _running.value = true
        job = viewModelScope.launch {
            while (true) {
                val started = System.currentTimeMillis()
                try {
                    val snap = withContext(Dispatchers.IO) { collector.collect() }
                    // 从 Snapshot 同步派生 state（UI 依赖）
                    _corePercent.value =
                        if (snap.corePercent.isNotEmpty()) snap.corePercent
                        else List(snap.cores.size) { null }
                    _cpuTotalPercent.value = snap.cpuTotalPercent
                    updateHist(snap)
                    _snapshot.value = snap.copy(sampleMs = System.currentTimeMillis() - started)
                    _lastSuccessAt.value = System.currentTimeMillis()
                    _isStale.value = false
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (BuildConfig.DEBUG) android.util.Log.w("A41VM", "collect failed", t)
                }
                val elapsed = System.currentTimeMillis() - started
                _isStale.value = System.currentTimeMillis() - _lastSuccessAt.value > 3000
                delay((1000 - elapsed).coerceAtLeast(100))
            }
        }
    }

    /** 历史曲线：读不到（null）不补 0，避免"假下探"；缺失即不追加。全存原始值。 */
    private fun updateHist(s: Snapshot) {
        val max = 60
        _freqHist.value = s.cores.mapIndexed { i, c ->
            c.freqMHz?.let { f ->
                (_freqHist.value.getOrElse(i) { emptyList() } + f.toFloat())
                    .takeLast(max)
            } ?: _freqHist.value.getOrElse(i) { emptyList() }.takeLast(max)
        }
        s.gpu.busyPercent?.let { _busyHist.value = (_busyHist.value + it.toFloat()).takeLast(max) }
        s.battery.voltage?.let { _voltHist.value = (_voltHist.value + it.toFloat()).takeLast(max) }
        s.battery.currentDisplay?.let { _curHist.value = (_curHist.value + it.toFloat()).takeLast(max) }
        s.battery.powerShown?.let { _powerHist.value = (_powerHist.value + it.toFloat()).takeLast(max) }
        s.battery.tempC?.let { _tempHist.value = (_tempHist.value + it.toFloat()).takeLast(max) }
        s.gpu.temp0C?.let { _gpuTempHist.value = (_gpuTempHist.value + it.toFloat()).takeLast(max) }
        _cpuTotalPercent.value?.let { _cpuTotalHist.value = (_cpuTotalHist.value + it.toFloat()).takeLast(max) }
        CapacityHistory.append(System.currentTimeMillis(), s.battery.capacity)
    }

    fun stop() {
        job?.cancel(); job = null; _running.value = false
    }

    fun refreshShizuku() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ShizukuBridge.refresh()
                RootBridge.refresh()
            }
            _snapshot.value = _snapshot.value.copy(
                shizukuServiceUp = ShizukuBridge.active,
                shizukuActive = ShizukuBridge.active && ShizukuBridge.authorized,
                shizukuAuthorized = ShizukuBridge.active && ShizukuBridge.authorized,
                rootAvailable = RootBridge.available,
            )
        }
    }

    /** 热区最高温（用于仪表盘卡） */
    fun thermalTopC(): Pair<Double?, ThermalZone?> =
        (snapshot.value.maxThermal?.tempC)?.let { it to snapshot.value.maxThermal } ?: (null to null)

    /** GPU 占用百分比 */
    fun gpuBusy(): Int? = snapshot.value.gpu.busyPercent

    companion object {
        fun fmt(v: Double?, digits: Int = 1): String =
            if (v == null) "–" else (v * Math.pow(10.0, digits.toDouble())).roundToInt()
                .div(Math.pow(10.0, digits.toDouble())).toString()
    }
}
