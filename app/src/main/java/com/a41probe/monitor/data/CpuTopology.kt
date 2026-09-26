package com.a41probe.monitor.data

import java.io.File

/**
 * CPU 簇颜色角色（按该簇 maxMHz 降序分配，与机型解耦）。
 * 最大性能核=PRIME（暖橙）· 次大=PERFORMANCE（蓝）· 最小=EFFICIENCY（青绿）· 4 簇时第 3=BALANCE（紫）。
 * 1~4 簇自适应；真机 A41(SM8450 三簇)：X2=PRIME / A710=PERFORMANCE / A510=EFFICIENCY。
 */
enum class ClusterRole { PRIME, PERFORMANCE, EFFICIENCY, BALANCE }

/** 一个 CPU 簇：label（真实 Cortex 名或通用名）、所属逻辑核索引、该簇最高频率、颜色角色 */
data class CoreCluster(
    val label: String,
    val cpuIndexes: List<Int>,
    val maxMHz: Int?,
    val colorRole: ClusterRole,
)

/** 整机 CPU 拓扑：核数 + 分簇结果。拓扑恒定，启动读一次后缓存。 */
data class CpuTopology(
    val coreCount: Int,
    val clusters: List<CoreCluster>,
)

/**
 * 动态 CPU 拓扑探测：
 *  - 核数：/sys/devices/system/cpu/possible 位图解析
 *  - 分簇：枚举 cpufreq/policyN 目录的 related_cpus（回落 affected_cpus / cpuinfo_shared_cpu_list），同 policy 即一簇
 *  - 微架构：解析 /proc/cpuinfo 每核 CPU implementer + CPU part（hex），ARM part 表映射 Cortex/X 型号
 *  - 簇 label 优先真实 Cortex 名（X2/A710/A510…），识别不到按 max_freq 从低到高用通用名（小核/中核/大核/超大核）
 *  - 颜色角色按簇 maxMHz 降序分配；拓扑恒定，@Volatile + 双重检查缓存
 */
object CpuTopologyDetector {

    @Volatile private var cached: CpuTopology? = null
    private val lock = Any()

    fun get(): CpuTopology = cached ?: synchronized(lock) {
        cached ?: detect().also { cached = it }
    }

    /** 某逻辑核所属簇 label（供 CpuReader 填充 CpuCore.cluster） */
    fun clusterLabelForCore(coreIndex: Int): String =
        get().clusters.firstOrNull { coreIndex in it.cpuIndexes }?.label
            ?: "cpu$coreIndex"

    /** 某逻辑核颜色角色（供 CpuReader 填充 CpuCore.colorRole） */
    fun colorRoleForCore(coreIndex: Int): ClusterRole =
        get().clusters.firstOrNull { coreIndex in it.cpuIndexes }?.colorRole
            ?: ClusterRole.BALANCE

    /** 授权状态变化后可主动失效拓扑缓存以重读 maxMHz（可选；A41 cpuinfo_max_freq App 域可读，不受影响） */
    fun invalidate() { synchronized(lock) { cached = null } }

    // ===== 内部探测 =====

    /** ARM 公版 PartNum → 短名（implementer=0x41 Arm；现代高通 0x51 复用同一编号） */
    private val ARM_PART_NAMES: Map<Int, String> = buildMap {
        put(0xd03, "A53"); put(0xd05, "A55"); put(0xd07, "A57")
        put(0xd08, "A72"); put(0xd09, "A73"); put(0xd0a, "A75")
        put(0xd0b, "A76"); put(0xd0d, "A77"); put(0xd41, "A78")
        put(0xd44, "X1"); put(0xd46, "A510"); put(0xd47, "A710")
        put(0xd48, "X2"); put(0xd4d, "A715"); put(0xd4e, "X3")
        put(0xd80, "A520"); put(0xd81, "A720"); put(0xd82, "X4")
        put(0xd85, "X925"); put(0xd87, "A725")
    }

    private fun detect(): CpuTopology {
        val cores = runCatching { parsePossible() }.getOrDefault(emptyList())
            .ifEmpty { listOf(0) }
        val groups = runCatching { detectClusters(cores) }.getOrDefault(listOf(cores))
        val archByCore = runCatching { parseCpuinfo() }.getOrDefault(emptyMap())

        val clusters = groups.map { idxs ->
            val first = idxs.first()
            CoreCluster(
                label = archByCore[first] ?: "",
                cpuIndexes = idxs,
                maxMHz = readClusterMaxMHz(first),
                colorRole = ClusterRole.BALANCE, // 占位，下面按 freq 重算
            )
        }
        val n = clusters.size
        // 按 maxMHz 降序排名（决定角色）；按升序排名（决定识别失败时的通用名）
        val byDesc = clusters.sortedByDescending { it.maxMHz ?: -1 }
        val byAsc = clusters.sortedBy { it.maxMHz ?: Int.MAX_VALUE }
        val generic = listOf("小核", "中核", "大核", "超大核")
        val ascRank = HashMap<CoreCluster, Int>()
        byAsc.forEachIndexed { i, c -> ascRank[c] = i }

        val result = clusters.map { c ->
            val descRank = byDesc.indexOf(c)
            val role = when (n) {
                1 -> ClusterRole.PRIME
                else -> when (descRank) {
                    0 -> ClusterRole.PRIME
                    1 -> if (n >= 3) ClusterRole.PERFORMANCE else ClusterRole.EFFICIENCY
                    2 -> if (n >= 4) ClusterRole.BALANCE else ClusterRole.EFFICIENCY
                    else -> ClusterRole.EFFICIENCY
                }
            }
            val label = if (c.label.isNotBlank()) c.label
            else generic.getOrElse(ascRank[c] ?: 0) { "核${ascRank[c] ?: 0}" }
            c.copy(label = label, colorRole = role)
        }
        return CpuTopology(cores.size, result)
    }

    /** possible 位图解析："0-7" / "0-3,4,5" → 核索引列表 */
    private fun parsePossible(): List<Int> {
        val s = Sysfs.read("/sys/devices/system/cpu/possible") ?: "0-7"
        return parseCpuList(s).ifEmpty { listOf(0) }
    }

    /** 枚举 cpufreq policy 目录，related_cpus 相同的核归一簇；policy 列表本身即簇 */
    private fun detectClusters(cores: List<Int>): List<List<Int>> {
        val dir = File("/sys/devices/system/cpu/cpufreq")
        val policies = dir.listFiles()?.filter { it.name.startsWith("policy") } ?: emptyList()
        if (policies.isEmpty()) return listOf(cores)
        val groups = policies.mapNotNull { pdir ->
            val rel = Sysfs.read("${pdir.path}/related_cpus")
                ?: Sysfs.read("${pdir.path}/affected_cpus")
                ?: Sysfs.read("${pdir.path}/cpuinfo_shared_cpu_list")
            rel?.let { parseCpuList(it).filter { it in cores } }
        }.filter { it.isNotEmpty() }.distinct()
        return groups.ifEmpty { listOf(cores) }
    }

    /** 该簇首个核的最高频率 MHz：cpuinfo_max_freq(kHz÷1000) → 回落 scaling_max_freq → root su 补读 */
    private fun readClusterMaxMHz(firstCore: Int): Int? {
        val base = "/sys/devices/system/cpu/cpu$firstCore/cpufreq"
        var v = Sysfs.readInt("$base/cpuinfo_max_freq") ?: Sysfs.readInt("$base/scaling_max_freq")
        // root 补读（与 CpuReader 同链）：App 域 EACCES 时 su 补读 cpuinfo_max_freq / scaling_max_freq
        if (v == null && RootBridge.available) {
            v = RootBridge.exec("cat $base/cpuinfo_max_freq 2>/dev/null || cat $base/scaling_max_freq 2>/dev/null")
                ?.trim()?.toIntOrNull()
        }
        return v?.div(1000)
    }

    /** 解析 /proc/cpuinfo：processor:N 分块，取 CPU implementer + CPU part → 核短名 */
    private fun parseCpuinfo(): Map<Int, String> {
        val out = HashMap<Int, String>()
        val raw = Sysfs.read("/proc/cpuinfo") ?: return out
        var cpu = -1; var imp = -1; var part = -1
        fun flush() {
            if (cpu >= 0 && part >= 0) archName(imp, part)?.let { out[cpu] = it }
        }
        raw.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx < 0) { if (line.isBlank()) flush(); return@forEach }
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "processor" -> { flush(); cpu = value.toIntOrNull() ?: -1; imp = -1; part = -1 }
                "CPU implementer" -> imp = value.removePrefix("0x").toIntOrNull(16) ?: -1
                "CPU part" -> part = value.removePrefix("0x").toIntOrNull(16) ?: -1
            }
        }
        flush()
        return out
    }

    /** (implementer, part) 二元组识别；现代高通 0x51 part 落在 ARM 区间时复用公版名 */
    private fun archName(imp: Int, part: Int): String? {
        ARM_PART_NAMES[part]?.let { return it }
        if (imp == 0x51) return when (part) {
            0x800, 0x802, 0x804 -> "Kryo-G"
            0x801, 0x803, 0x805 -> "Kryo-S"
            0x001 -> "Oryon"
            else -> null
        }
        return null
    }

    /** 解析内核 cpu list 字符串：兼容 "0 1 2 3"（空格）/"0-3"（范围）/"0-2,4,5"（逗号混合） */
    private fun parseCpuList(s: String): List<Int> {
        val out = mutableListOf<Int>()
        s.trim().split(Regex("[,\\s]+")).forEach { token ->
            val r = token.split("-")
            if (r.size == 2) {
                val a = r[0].toIntOrNull()
                val b = r[1].toIntOrNull()
                if (a != null && b != null && a <= b) out.addAll(a..b)
            } else {
                token.toIntOrNull()?.let { out.add(it) }
            }
        }
        return out
    }
}
