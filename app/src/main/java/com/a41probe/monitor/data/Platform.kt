package com.a41probe.monitor.data

import android.os.Build
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * SoC 平台识别器（v0.23.0）：识别硬件平台而非枚举机型。
 *
 * 设计原则（成熟监控工具分层回退策略的元信息层）：
 * - 运行时采集**始终以 sysfs/proc 文件存在性探测为准**，平台识别只提供元信息 + 语义修正，
 *   绝不能反过来变成按平台硬编码节点路径。
 * - 平台识别结果用于：① UI 设备信息展示；② 状态语义修正（区分「平台无此节点」与「权限不够」）；
 *   ③ 未来厂商补丁挂钩点。
 * - 读取系统属性（ro.board.platform / ro.soc.model / ro.soc.manufacturer）+ Build 字段，
 *   归一化为 Platform 枚举 + 商用 SoC 名；结果进程级缓存。
 */
object PlatformDetector {

    enum class Platform(val tag: String, val friendly: String) {
        QCOM("qcom", "高通 Qualcomm"),
        MTK("mtk", "联发科 MediaTek"),
        EXYNOS("exynos", "三星 Exynos"),
        HISILICON("hisilicon", "海思 HiSilicon"),
        GOOGLE_TENSOR("tensor", "Google Tensor"),
        SAMSUNG("samsung", "三星 Samsung"),
        UNISOC("unisoc", "展锐 Unisoc"),
        UNKNOWN("unknown", "未知"),
    }

    /** 平台识别结果（全部真实读取，不写死）。 */
    data class Info(
        val platform: Platform,
        /** 商用 SoC 名，如 "Snapdragon 8 Gen 1"；识别不到为 null */
        val socName: String?,
        /** ro.board.platform 原始值，如 "taro" */
        val boardPlatform: String?,
        /** ro.soc.model / Build.SOC_MODEL，如 "SM8450" */
        val socModel: String?,
        /** ro.soc.manufacturer，如 "Qualcomm" */
        val socManufacturer: String?,
        /** Build.BOARD */
        val board: String,
        /** Build.HARDWARE */
        val hardware: String,
    ) {
        /** 展示用一行摘要：平台类型 + 商用名（缺则 socModel + boardPlatform） */
        val displayLine: String
            get() = buildString {
                append(platform.friendly)
                socName?.let { append(" · $it") }
                    ?: socModel?.let { append(" · $it") }
                    ?: boardPlatform?.let { append(" · $it") }
            }
    }

    @Volatile private var cached: Info? = null

    /** 获取平台识别结果（进程级缓存，恒定不变）。 */
    fun get(): Info {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val info = detect()
            cached = info
            return info
        }
    }

    /** 高通平台代号 → 商用 SoC 名映射（ro.board.platform 小写匹配）。
     *  查不到返回 null，由调用方回退 socModel / boardPlatform 原文。 */
    private val QCOM_CODENAME_MAP = mapOf(
        // 旗舰 8 系
        "msmnile" to "Snapdragon 855",
        "kona" to "Snapdragon 865",
        "kona_ac" to "Snapdragon 870",
        "lahaina" to "Snapdragon 888",
        "taro" to "Snapdragon 8 Gen 1",
        "kalama" to "Snapdragon 8 Gen 2",
        "pineapple" to "Snapdragon 8 Gen 3",
        "sun" to "Snapdragon 8 Elite",
        // 中端
        "atoll" to "Snapdragon 765G",
        "shima" to "Snapdragon 778G",
        "cape" to "Snapdragon 7+ Gen 2",
        "sm6225" to "Snapdragon 695",
        "holi" to "Snapdragon 695",
        "bengal" to "Snapdragon 662",
        "trinket" to "Snapdragon 665",
    )

    /** ro.soc.model（如 SM8450）→ 商用名补充映射（小写匹配）。 */
    private val QCOM_SOCMODEL_MAP = mapOf(
        "sm8450" to "Snapdragon 8 Gen 1",
        "sm8475" to "Snapdragon 8+ Gen 1",
        "sm8550" to "Snapdragon 8 Gen 2",
        "sm8650" to "Snapdragon 8 Gen 3",
        "sm8750" to "Snapdragon 8 Elite",
        "sm8350" to "Snapdragon 888",
        "sm8250" to "Snapdragon 865",
        "sm7325" to "Snapdragon 778G+",
        "sm7475" to "Snapdragon 7+ Gen 2",
    )

    private fun detect(): Info {
        val boardPlatform = getprop("ro.board.platform")
        val socModelProp = getprop("ro.soc.model")
        val socManufacturer = getprop("ro.soc.manufacturer")
        val buildSocModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL?.takeIf { it.isNotBlank() }
        } else null
        val socModel = socModelProp?.takeIf { it.isNotBlank() } ?: buildSocModel
        val board = Build.BOARD ?: ""
        val hardware = Build.HARDWARE ?: ""

        val platform = classify(boardPlatform, socModel, socManufacturer, board, hardware)
        val socName = resolveSocName(platform, boardPlatform, socModel)

        return Info(
            platform = platform,
            socName = socName,
            boardPlatform = boardPlatform,
            socModel = socModel,
            socManufacturer = socManufacturer,
            board = board,
            hardware = hardware,
        )
    }

    /** 按多信号归一化平台类型。优先级：socManufacturer > boardPlatform > socModel > hardware > board。 */
    private fun classify(
        boardPlatform: String?, socModel: String?, socManufacturer: String?,
        board: String, hardware: String,
    ): Platform {
        val bp = boardPlatform?.lowercase() ?: ""
        val sm = socModel?.lowercase() ?: ""
        val man = socManufacturer?.lowercase() ?: ""
        val hw = hardware.lowercase()
        val bd = board.lowercase()

        // Google Tensor：socModel 含 tensor 或 Pixel 设备代号
        if (sm.contains("tensor") || man.contains("google") ||
            setOf("oriole", "raven", "cheetah", "panther", "lynx", "tangorpro", "shiba", "husky", "akita").contains(bd)) {
            return Platform.GOOGLE_TENSOR
        }
        // 高通：socManufacturer=qualcomm / socModel=SM* / boardPlatform 含高通代号 / hardware=qcom
        if (man.contains("qualcomm") || sm.startsWith("sm") || sm.startsWith("sdm") ||
            bp.contains("kona") || bp.contains("lahaina") || bp.contains("taro") ||
            bp.contains("kalama") || bp.contains("pineapple") || bp.contains("msmnile") ||
            bp.contains("atoll") || bp.contains("shima") || bp.contains("cape") ||
            bp.contains("holi") || bp.contains("bengal") || bp.contains("trinket") ||
            bp == "sun" || hw.contains("qcom")) {
            return Platform.QCOM
        }
        // 联发科：boardPlatform=mt* / socModel=MT* / hardware 含 mt
        if (bp.startsWith("mt") || sm.startsWith("mt") || hw.startsWith("mt") ||
            man.contains("mediatek") || man.contains("mtk")) {
            return Platform.MTK
        }
        // 展锐 Unisoc（原 Spreadtrum）：boardPlatform=sp*/ums*/sc* / hardware=sprd /
        // socManufacturer 含 unisoc/spreadtrum。
        // 注意：高通 bp 多为代号（pineapple/taro/kalama/msmnile…），不以 sp/ums/sc 开头，
        // 且高通判定在前（man=qualcomm 优先命中），不会误判。
        if (bp.startsWith("sp") || bp.startsWith("ums") || bp.startsWith("sc") ||
            hw.contains("sprd") || man.contains("unisoc") || man.contains("spreadtrum")) {
            return Platform.UNISOC
        }
        // Exynos：boardPlatform 含 exynos/universal / socModel 含 exynos
        if (bp.contains("exynos") || bp.contains("universal") || sm.contains("exynos") ||
            man.contains("samsung") && sm.contains("exynos")) {
            return Platform.EXYNOS
        }
        // 海思：boardPlatform 含 hi/kirin / hardware 含 hi36
        if (bp.contains("hi3") || bp.contains("kirin") || hw.contains("hi36") ||
            man.contains("hisilicon")) {
            return Platform.HISILICON
        }
        // 三星（非 Exynos，如某些三星自研）
        if (man.contains("samsung") || bd.contains("exynos")) {
            return Platform.SAMSUNG
        }
        return Platform.UNKNOWN
    }

    /** 解析商用 SoC 名：优先 socModel 映射，其次 boardPlatform 代号映射。 */
    private fun resolveSocName(platform: Platform, boardPlatform: String?, socModel: String?): String? {
        if (platform != Platform.QCOM) {
            // 非高通：socModel 非空直接用（如 "Exynos 2200"、"Dimensity 9200"、"Tensor G3"）
            return socModel?.takeIf { it.isNotBlank() }
        }
        socModel?.lowercase()?.let { QCOM_SOCMODEL_MAP[it] }?.let { return it }
        boardPlatform?.lowercase()?.let { QCOM_CODENAME_MAP[it] }?.let { return it }
        // 都查不到：返回 socModel 原文（如 "SM8450"），仍比 null 有信息量
        return socModel?.takeIf { it.isNotBlank() }
    }

    /** 读系统属性：优先反射 SystemProperties（快），失败回退 getprop 命令（兼容）。 */
    private fun getprop(key: String): String? {
        // 反射 android.os.SystemProperties.get(String)
        runCatching {
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("get", String::class.java)
            val v = m.invoke(null, key) as? String
            if (!v.isNullOrBlank()) return v
        }
        // 回退：getprop 命令
        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText().trim() }
            p.waitFor()
            out.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
