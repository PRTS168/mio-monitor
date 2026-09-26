package com.a41probe.monitor.ui.theme

import androidx.compose.ui.graphics.Color

// ===== 设计令牌（v7 对标搞机牛：iOS 视觉语言，蓝白体系） =====
object Ink {
    val bg = Color(0xFFF2F2F7)          // 页面底色（iOS 系统灰）
    val card = Color(0xFFFFFFFF)        // 卡片纯白
    val panel = Color(0xFFEEEEF0)       // 嵌套灰底小块（L2: 与页面底拉开 ~8 灰阶，强光下可辨）
    val stroke = Color(0xFFE5E5EA)      // 分隔线
    val tx = Color(0xFF1C1C1E)          // 主文字（iOS label）
    val tx2 = Color(0xFF8E8E93)         // 次级文字（iOS secondary）
    val tx3 = Color(0xFF6D6D72)         // 口径说明小字（L1: 对比度 ≥4.5:1）
    val off = Color(0xFF8F8F95)         // 置灰/不可用（M13：由 AEAEB2 加深，小字达 ~3.2:1）

    val accent = Color(0xFF007AFF)      // 主强调（iOS 蓝）
    val accent2 = Color(0xFF5AC8FA)     // 渐变末端（iOS 浅蓝）
    val ok = Color(0xFF34C759)          // 正常/健康（iOS 绿）
    val warn = Color(0xFFFF9500)        // 警告（iOS 橙）
    val danger = Color(0xFFFF3B30)      // 危险/高温（iOS 红）
    val m = Color(0xFF4FA3D1)           // 次要强调（Material secondary 映射）

    // 簇角色色（跨屏统一引用，勿在 Screen 内重复硬编码；按簇 maxMHz 降序分配角色）
    val clusterPrime = Color(0xFFE07B39)   // 最大性能核 · 暖橙（原 X2 色）
    val clusterPerf = Color(0xFF4A86D8)     // 中性能核 · 蓝（原 A710 色）
    val clusterEff = Color(0xFF2F9E8F)      // 能效核 · 青绿（原 A510 色）
    val clusterBal = Color(0xFF9B59B6)     // 第4簇平衡核 · 紫（新增）

    // 图标浅底色（O8：传感器等图标圆底，iOS 8 色体系）
    val iconBgBlue = Color(0xFFE5F1FF)
    val iconBgCyan = Color(0xFFE6F9FF)
    val iconBgGreen = Color(0xFFE3F9E8)
    val iconBgOrange = Color(0xFFFFF2E6)
    val iconBgPurple = Color(0xFFF2E6FF)
    val iconBgYellow = Color(0xFFFFF9E6)
}

// 提权档位标注（浅色下：灰 / 蓝 / 橙）
enum class Priv(val tag: String, val color: Color) {
    FREE("—", Ink.off),      // 免提权
    SHIZUKU("S", Ink.accent), // Shizuku
    ROOT("R", Ink.warn);    // 仅 Root
    companion object { fun of(tag: String) = entries.firstOrNull { it.tag == tag } ?: FREE }
}

// 数据状态机
enum class DataState { OK, NEED_PRIV, UNAVAILABLE, STALE }
