package com.a41probe.monitor.data

import com.a41probe.monitor.ui.theme.DataState
import com.a41probe.monitor.ui.theme.Priv

/** 单个参数：值 + 单位 + 状态 + 口径说明 */
data class Param(
    val value: String = "–",
    val unit: String = "",
    val state: DataState = DataState.OK,
    val note: String = "",
    val priv: Priv = Priv.FREE,
) {
    val display: String get() = if (state == DataState.UNAVAILABLE) "不可用" else value
    companion object {
        fun nope(priv: Priv, note: String = "需更高权限") =
            Param(value = "需" + priv.tag, unit = "", state = DataState.NEED_PRIV, note = note, priv = priv)
    }
}

/**
 * 单个逻辑核。cluster 为簇 label 字符串（真实 Cortex 名如 "X2"/"A710"/"A510"，识别不到则通用名
 * "小核"/"中核"/"大核"/"超大核"），由 CpuTopologyDetector 动态探测，与机型解耦。
 * colorRole 为该核所属簇的颜色角色（PRIME/PERFORMANCE/EFFICIENCY/BALANCE）。
 */
data class CpuCore(
    val index: Int,
    val cluster: String,
    val freqMHz: Int? = null,
    val maxMHz: Int? = null,
    /** 该核所属簇的颜色角色（按簇 maxMHz 降序分配），UI 据此上色 */
    val colorRole: ClusterRole? = null,
    /** RAW：scaling_cur_freq 原始 kHz（未÷1000），CSV RAW 区留证 */
    val freqKHz: Int? = null,
    /** RAW：频率上限原始 kHz */
    val maxKHz: Int? = null,
) {
    val clusterLabel: String get() = cluster
}

data class BatteryData(
    val capacity: Int? = null,
    val voltage: Double? = null,
    val currentA: Double? = null,
    /** power_now 节点：充电头端功率（驱动平均口径，充电时有效，放电残留不可信） */
    val powerW: Double? = null,
    val tempC: Double? = null,
    val tempBmC: Double? = null,
    /** RAW：BatteryManager EXTRA_TEMPERATURE 原始 int（0.1°C），CSV RAW 区留证 */
    val tempBmRaw: Int? = null,
    val chargeFull: Int? = null,
    val chargeDesign: Int? = null,
    val cycleCount: Int? = null,
    val health: String? = null,
    val status: String? = null,
    val tech: String? = null,
    /** 充电档位：sysfs charge_type（Fast/Standard/Trickle…），需提权可读 */
    val chargeType: String? = null,
    /** 适配器输入侧电压 V（usb/ucsi input_voltage_now，需提权）——快充功率的真相口径 */
    val inputVoltV: Double? = null,
    /** 适配器输入侧电流 A（input_current_now，需提权） */
    val inputCurA: Double? = null,
    // v20.21 RAW 区原始字段（零换算，与该帧读取同源）：
    val voltageNowUv: Long? = null,      // voltage_now 原始 µV
    val currentNowUa: Long? = null,      // current_now 原始 µA
    val powerNowUw: Long? = null,        // power_now 原始 µW
    val tempRaw: Int? = null,            // temp 原始 0.1°C
    val chargeFullUah: Long? = null,     // charge_full 原始 µAh
    val chargeDesignUah: Long? = null,   // charge_full_design 原始 µAh
    val inputVoltUv: Long? = null,       // 适配器输入电压原始 µV
    val inputCurUa: Long? = null,        // 适配器输入电流原始 µA
    // v20.24 P0（uevent 同时刻快照 + 协商上限）：
    val voltageOcvV: Double? = null,        // 开路电压 OCV（uevent VOLTAGE_OCV）
    val voltageMaxV: Double? = null,        // 充电截止/满电电压（VOLTAGE_MAX，实测 4.46V）
    val chargeCounterUah: Long? = null,     // 当前循环充入电量（CHARGE_COUNTER µAh）
    val chargeControlLimit: Int? = null,    // 充电限流档位（0–max）
    val chargeControlLimitMax: Int? = null, // 限流档上限（实测 40）
    val maxChargingCurrentUa: Long? = null, // 协商充电电流上限（广播 max_charging_current µA，免提权）
    val maxChargingVoltageUv: Long? = null, // 协商充电电压上限（max_charging_voltage µV，免提权）
    val ueventUsed: Boolean = false,        // 主 V/I 是否取自 uevent 同时刻快照
    val state: DataState = DataState.OK,
) {
    val healthPercent: Double?
        get() = if (chargeFull != null && chargeDesign != null && chargeDesign > 0)
            // v0.28.2: 健康度物理钳制 0..120%——>120% 视为脏数据（设计容量即上限），返回 null
            (chargeFull * 100.0 / chargeDesign).takeIf { it in 0.0..120.0 } else null
    val isCharging: Boolean get() = status?.equals("Charging", true) == true
    /** 已充满（BatteryManager FULL / sysfs "Full"）：仍插着线但已停充，不能判为放电 */
    val isFull: Boolean get() = status?.equals("Full", true) == true
    /** 接电但未在充电（NOT_CHARGING，少见过渡态） */
    val isNotCharging: Boolean get() = status?.equals("Not charging", true) == true
    /** 是否处于接电状态（充电中 / 已充满 / 接电未充）——符号与副文案统一口径 */
    val isPlugged: Boolean get() = isCharging || isFull || isNotCharging
    /** 电流展示值：原始符号随机型/电芯不统一，统一取绝对值，正负由 UI 按充电态标注 */
    val currentDisplay: Double? get() = currentA?.let { kotlin.math.abs(it) }
    /** 充电输入功率展示值（power_now，充电头端口径）：仅 CSV 参考列使用 */
    val powerDisplay: Double? get() = powerW?.let { kotlin.math.abs(it) }
    /** 实时电池功率：电压×电流现算，同源自洽，带符号（充电+ / 放电-），任何机型成立 */
    val powerCalcW: Double?
        get() = voltage?.let { v -> currentA?.let { c -> v * c } }
    /** UI 主展示功率：统一用 V×I（充放电一致；power_now 是充电头端口径，充电才有效、放电残留假值，不用于 UI） */
    val powerShown: Double?
        get() = powerCalcW?.let { kotlin.math.abs(it) }
    /** 适配器输入侧功率 W（充电器实际输出，双口径真相）：input V×I，缺任一返回 null */
    val inputPowerW: Double?
        get() = inputVoltV?.let { v -> inputCurA?.let { c -> v * c } }
    /** UI 实际采用的输入侧功率：物理范围 0..150W 且电池确实在进电（电流≥0.1A）。
     *  满电/停充后输入侧节点可能残留最后协商 V/I（如 9V·1.8A），不屏蔽会显示假充电功率 */
    val effectiveInputPowerW: Double?
        get() = inputPowerW?.takeIf { it in 0.0..150.0 && (currentDisplay ?: 0.0) >= 0.1 }
    /** UI 统一展示功率（hero / 曲线 / 副文案 / CSV 一处定义三处复用）：满电显 0；
     *  否则输入侧优先、回退电池侧 V×I，统一钳 0..150W（P1-UI-2/3） */
    val uiPowerW: Double?
        get() = when {
            isFull -> 0.0
            else -> (effectiveInputPowerW ?: powerShown)?.coerceIn(0.0, 150.0)
        }
    /**
     * 充电档位人话（v20.3 重构）：charge_type 是 PMIC 协商档，恒为 Fast，不反映实时功率，
     * 不再参与判定（真机实测 5V/0.5A 也读 Fast）。改以实时物理量判定：
     *  电池电流 ≥2A → 快充；适配器输入电压 ≥9V（PD/QC 高压协商）→ 快充；
     *  电流 ≥0.5A → 普通充电；其余 → 涓流。
     */
    val chargeMode: String
        get() = when {
            isFull -> "已充满"
            isNotCharging -> "未充电"
            !isCharging -> "放电"
            // v0.26.8: 固件常提前把 capacity 标到 100，CV 末段仍在进电（实测 100% 时还有 19.6W）
            capacity != null && capacity >= 100 ->
                if ((currentDisplay ?: 0.0) >= 0.5) "满电收尾" else "涓流补电"
            (currentDisplay ?: 0.0) >= 2.0 -> "快充"
            (inputVoltV ?: 0.0) >= 9.0 -> "快充"
            (currentDisplay ?: 0.0) >= 0.5 -> "普通充电"
            else -> "涓流"
        }
}

data class GpuData(
    val busyPercent: Int? = null,
    val freqMHz: Int? = null,
    val freqState: DataState = DataState.OK,
    val temp0C: Double? = null,
    val temp1C: Double? = null,
    val tempState: DataState = DataState.OK,
    /** RAW：gpuclk/gpu_clk/gpu_clock/devfreq cur_freq 命中节点的原始值（未换算），CSV RAW 区留证 */
    val freqRaw: Long? = null,
    /** RAW：实际命中的频率节点路径，与 freqRaw 配对说明单位（Hz 或 kHz） */
    val freqNode: String? = null,
)

/** 内存摘要（/proc/meminfo，App 域可读）：单位 kB，派生换算 GB/占用比 */
data class MemInfo(
    val totalKB: Long = 0,
    val freeKB: Long = 0,
    val availKB: Long = 0,
    val cachedKB: Long = 0,
    val swapTotalKB: Long = 0,
    val swapFreeKB: Long = 0,
) {
    val totalGB: Double get() = totalKB / 1048576.0
    val availGB: Double get() = availKB / 1048576.0
    val usedPercent: Int
        get() = if (totalKB <= 0) 0
        else ((totalKB - availKB) * 100 / totalKB).toInt().coerceIn(0, 100)
    val swapUsedPercent: Int
        get() = if (swapTotalKB <= 0) 0
        else ((swapTotalKB - swapFreeKB) * 100 / swapTotalKB).toInt().coerceIn(0, 100)
    val swapUsedGB: Double get() = (swapTotalKB - swapFreeKB) / 1048576.0
    val swapTotalGB: Double get() = swapTotalKB / 1048576.0
}

data class ThermalZone(
    val id: Int,
    val name: String,
    val tempC: Double? = null,
    val state: DataState = DataState.OK,
    val priv: Priv = Priv.SHIZUKU,
    /** RAW：temp 原始 m°C（未÷1000），CSV RAW 区留证 */
    val tempMC: Int? = null,
) {
    /** v0.28.2: 物理范围安全温度（-40..150°C），超界返回 null——消费方优先用此口径 */
    val tempSafe: Double? get() = tempC?.takeIf { it in -40.0..150.0 }
}

/** 热缓解设备（cooling_device）：cur/max 等级，cur>0 = 系统正在限制该部件 */
data class CoolingDev(
    val id: Int,
    val name: String,
    val cur: Int? = null,
    val max: Int? = null,
) {
    val active: Boolean get() = (cur ?: 0) > 0
    // v0.28.2: cur 为负（异常/未使能占位）判 0，不计算负占比
    val ratio: Float get() = if (max != null && max > 0 && cur != null && cur >= 0)
        cur.toFloat() / max.toFloat() else 0f
}

/** Thermal HAL 官方温度（dumpsys thermalservice）：带类型分类，比 84 zone 权威 */
data class HalTemp(
    val name: String,
    val type: Int,
    val tempC: Double? = null,
) {
    // mType: 0 CPU / 1 GPU / 2 BATTERY / 3 SKIN / 6 BCL_VOLTAGE / 7 BCL_CURRENT / 8 BCL_PERCENT / 9 NPU
    val typeLabel: String get() = when (type) {
        0 -> "CPU"; 1 -> "GPU"; 2 -> "电池"; 3 -> "外壳"
        6 -> "电压"; 7 -> "电流"; 8 -> "电量"; 9 -> "NPU"; else -> "其他"
    }
}

/** 官方热阈值红线（hotThrottlingThresholds[3]=降频, [6]=关机） */
data class ThermalThreshold(
    val name: String,
    val type: String,
    val throttleC: Double? = null,
    val shutdownC: Double? = null,
)

data class Snapshot(
    val ts: Long = System.currentTimeMillis(),
    val battery: BatteryData = BatteryData(),
    val cores: List<CpuCore> = emptyList(),
    val gpu: GpuData = GpuData(),
    val loadStr: String? = null,
    val memTotalMB: Int? = null,
    val mem: MemInfo? = null,
    val thermalAll: List<ThermalZone> = emptyList(),
    val thermalTop: List<ThermalZone> = emptyList(),
    /** v20.21: CPU 总占用 %（/proc/stat 差分，需 Shizuku/Root），进 Snapshot 供 CSV 导出 */
    val cpuTotalPercent: Double? = null,
    /** v20.21: 8 核分核占用 %（/proc/stat 差分），进 Snapshot 供 CSV 导出 */
    val corePercent: List<Int?> = emptyList(),
    /** v20.21: /proc/stat "cpu " 总行原文（jiffies），RAW 区留证 */
    val procStat: String? = null,
    /** v20.21: /proc/stat "cpuN " 每核行原文列表，RAW 区留证 */
    val coreStat: List<String> = emptyList(),
    /** v20.21: scaling_governor 本帧值（CpuReader 同源采集），RAW/RENDERED 共用 */
    val governor: String? = null,
    /** v20.24: 热缓解设备（cooling_device）实时等级 */
    val cooling: List<CoolingDev> = emptyList(),
    /** v20.24: Thermal HAL 官方温度（5s 粒度） */
    val halTemps: List<HalTemp> = emptyList(),
    /** v20.24: Thermal HAL 官方热状态 0–6 */
    val thermalStatus: Int? = null,
    /** v20.24: 官方热阈值红线 */
    val thresholds: List<ThermalThreshold> = emptyList(),
    val shizukuActive: Boolean = false,
    val shizukuServiceUp: Boolean = false,    // UX(S6): Shizuku 服务进程在跑（未必已授权）
    val shizukuAuthorized: Boolean = false,   // S6: 三态化——服务在跑 ≠ 已授权
    val rootAvailable: Boolean = false,       // S7: Root 档真实探测（su -c id -u）
    val sampleMs: Long = 0,
    /** v20.17(P1-5): 距上次成功采样超 3s → 数据停更，UI 大数字统一变灰 */
    val isStale: Boolean = false,
) {
    // 最高结温：多厂商结温类传感器关键词（高通 cpuss/cpu/gpuss；MTK mtktscpu/mt-cpu/mttcpu；
    // Exynos big-/little-thermal/g3d；麒麟/Tensor cpuN/gpu-thermal/soc/ap），tof/xo/usb 等外围黑名单排除。
    // 只取正温度>0 的读数（0/负=未使能），tof-therm 恒定假温不顶替"结温"。
    val maxThermal: ThermalZone? get() = thermalAll
        .filter { z -> z.tempC != null && z.tempC > 0 && isCpuThermalName(z.name) }
        .maxByOrNull { it.tempC!! }
    val thermalAvg: Double?
        // 只统计有效正读数：0/负值为未使能或休眠传感器，计入会拉低均值
        get() = thermalAll.mapNotNull { it.tempC }.filter { it > 0 }.let {
            if (it.isEmpty()) null else it.sum() / it.size
        }
}

/**
 * 判断 thermal_zone type 是否属于芯片结温类（CPU/GPU SoC 核心），多厂商关键词白名单 + 外围黑名单。
 * 高通：cpuss/cpu/gpuss；MTK：mtktscpu/mt-cpu/mttcpu；Exynos：big-/little-thermal/g3d；
 * 麒麟/Tensor：cpuN/gpu-thermal/soc/ap。黑名单：tof-therm/xo-therm/usb-therm。
 */
private fun isCpuThermalName(name: String): Boolean {
    if (name == "tof-therm" || name.startsWith("xo-therm") || name.startsWith("usb-therm")) return false
    return name.startsWith("cpuss") || name.startsWith("cpu") || name.startsWith("gpuss") ||
        name.startsWith("mtktscpu") || name.startsWith("mt-cpu") || name.startsWith("mttcpu") ||
        name.startsWith("big-thermal") || name.startsWith("little-thermal") || name.startsWith("g3d") ||
        name.startsWith("soc") || name.startsWith("ap") || name.contains("gpu-thermal")
}
