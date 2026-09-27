package com.a41probe.monitor.data

/**
 * 单位阈值归一化 helper（v0.23.0）：按原始值量级自动判定单位，兼容不同厂商 sysfs 节点的单位差异。
 *
 * 量级判据（基于真实硬件范围）：
 * - CPU 频率：MHz 范围 300–5000，kHz 范围 300000–5000000+。阈值 100_000 稳分。
 * - 电压：µV 范围 3000000–5000000（3–5V），mV 范围 3000–5000。阈值 100_000 稳分。
 * - 电流：µA 范围 500000–3000000（0.5–3A），mA 范围 500–3000。阈值 10_000 稳分。
 *
 * 铁律：RAW 字段（freqKHz/maxKHz/inputVoltUv/inputCurUa）始终保存原始值零换算，
 * 归一化只作用于展示层（MHz/V/A）。CSV RAW 区原样输出。
 */
object Units {

    /** CPU 频率原始值 → MHz。
     *  raw > 100_000 → 视为 kHz，÷1000；raw in 1..100_000 → 视为已 MHz 直接用。
     *  raw ≤ 0 → null（无效/未使能）。 */
    fun cpuFreqMHz(raw: Int?): Int? {
        if (raw == null || raw <= 0) return null
        return if (raw > 100_000) raw / 1000 else raw
    }

    /** Long 版（兼容 root/su 读取的 Long 原始值）。 */
    fun cpuFreqMHz(raw: Long?): Int? {
        if (raw == null || raw <= 0) return null
        return if (raw > 100_000) (raw / 1000).toInt() else raw.toInt()
    }

    /** 电压原始值 → V。
     *  raw > 100_000 → 视为 µV，÷1e6；否则 → 视为 mV，÷1e3。
     *  raw ≤ 0 → null。 */
    fun voltageV(raw: Long?): Double? {
        if (raw == null || raw <= 0) return null
        return if (raw > 100_000) raw / 1_000_000.0 else raw / 1_000.0
    }

    /** 电流原始值 → A。
     *  raw > 10_000 → 视为 µA，÷1e6；否则 → 视为 mA，÷1e3。
     *  raw ≤ 0 → null。 */
    fun currentA(raw: Long?): Double? {
        if (raw == null || raw <= 0) return null
        return if (raw > 10_000) raw / 1_000_000.0 else raw / 1_000.0
    }

    // ===== v0.28.2 鲁棒性：物理范围安全钳制（脏数据 → null，不污染 UI/曲线）=====

    /** 电压安全范围钳制：手机锂电池 2.5..5.5V，超界返回 null */
    fun safeVoltage(v: Double?): Double? = v?.takeIf { it in 2.5..5.5 }

    /** 温度安全范围钳制：-40..150°C，超界返回 null */
    fun safeTemp(c: Double?): Double? = c?.takeIf { it in -40.0..150.0 }

    /** 电流安全范围钳制：绝对值 ≤ 12A，超界返回 null */
    fun safeCurrent(a: Double?): Double? = a?.takeIf { kotlin.math.abs(it) <= 12.0 }

    /** 功率安全范围钳制：0..150W（展示用正值口径；带符号电池功率请用 abs ≤ 150 自行钳制），超界返回 null */
    fun safePower(w: Double?): Double? = w?.takeIf { it in 0.0..150.0 }
}
