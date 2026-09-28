package com.a41probe.monitor.ui.theme

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * 配色主题。
 *
 * 约定：
 *  - 语义色（温度红/橙/绿、警告/危险）不参与换肤；
 *  - onAccent：压在强调色上的文字色（浅色主色必须配深色文字）；
 *  - accentSoft：强调色浅底（选中态/指示器）；curve：曲线主色；shadowTint：带主题色温的阴影；
 *  - 卡片一律纯白、页面底为主色调的极浅色（只带一丝色温），保证卡片与背景始终分得开。
 */
data class Palette(
    val id: String,
    val label: String,
    val accent: Color,
    val accent2: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val curve: Color,
    val shadowTint: Color,
    val bg: Color,
    val card: Color,
    val panel: Color,
    val stroke: Color,
    val tx: Color,
    val tx2: Color,
    val tx3: Color,
    val off: Color,
    val clusterPrime: Color,
    val clusterPerf: Color,
    val clusterEff: Color,
    val clusterBal: Color,
    val iconBgBlue: Color,
    val iconBgCyan: Color,
    val iconBgGreen: Color,
    val iconBgOrange: Color,
    val iconBgPurple: Color,
    val iconBgYellow: Color,
)

object Palettes {

    /** 默认：iOS 蓝白（与稳定版观感一致） */
    val BLUE = Palette(
        id = "blue", label = "蓝",
        accent = Color(0xFF007AFF), accent2 = Color(0xFF5AC8FA), onAccent = Color(0xFFFFFFFF),
        accentSoft = Color(0x24007AFF), curve = Color(0xFF3E9BFF), shadowTint = Color(0xFF2A3138),
        bg = Color(0xFFF2F2F7), card = Color(0xFFFFFFFF), panel = Color(0xFFEEEEF0), stroke = Color(0xFFE3E3E8),
        tx = Color(0xFF1C1C1E), tx2 = Color(0xFF8E8E93), tx3 = Color(0xFF6D6D72), off = Color(0xFF8F8F95),
        clusterPrime = Color(0xFFE07B39), clusterPerf = Color(0xFF4A86D8),
        clusterEff = Color(0xFF2F9E8F), clusterBal = Color(0xFF9B59B6),
        iconBgBlue = Color(0xFFE5F1FF), iconBgCyan = Color(0xFFE6F9FF), iconBgGreen = Color(0xFFE3F9E8),
        iconBgOrange = Color(0xFFFFF2E6), iconBgPurple = Color(0xFFF2E6FF), iconBgYellow = Color(0xFFFFF9E6),
    )

    /** 1. 薄荷青柠：主色 #6EC7A6（用户指定） */
    val MINT = Palette(
        id = "mint", label = "薄荷",
        accent = Color(0xFF6EC7A6), accent2 = Color(0xFFA8E6CF), onAccent = Color(0xFF2C4A3E),
        accentSoft = Color(0x246EC7A6), curve = Color(0xFF57B996), shadowTint = Color(0xFF2C4A3E),
        bg = Color(0xFFF7FFFC), card = Color(0xFFFFFFFF), panel = Color(0xFFEDF7F3), stroke = Color(0xFFDCEBE5),
        tx = Color(0xFF2C4A3E), tx2 = Color(0xFF6B8A7D), tx3 = Color(0xFF5C7A6D), off = Color(0xFF8AA79B),
        clusterPrime = Color(0xFFD9884A), clusterPerf = Color(0xFF5E9BD1),
        clusterEff = Color(0xFF4FB39A), clusterBal = Color(0xFF9B7FC4),
        iconBgBlue = Color(0xFFE4F5EE), iconBgCyan = Color(0xFFE3F7F0), iconBgGreen = Color(0xFFE1F4E6),
        iconBgOrange = Color(0xFFF7EEE2), iconBgPurple = Color(0xFFF0E9F7), iconBgYellow = Color(0xFFF7F4E3),
    )

    /** 2. 雾感天青：主色 #6BB6E0（用户指定） */
    val SKY = Palette(
        id = "sky", label = "天青",
        accent = Color(0xFF6BB6E0), accent2 = Color(0xFFB4D8F0), onAccent = Color(0xFF243E52),
        accentSoft = Color(0x246BB6E0), curve = Color(0xFF54A6D4), shadowTint = Color(0xFF243E52),
        bg = Color(0xFFF5FAFF), card = Color(0xFFFFFFFF), panel = Color(0xFFECF4FA), stroke = Color(0xFFDCE9F3),
        tx = Color(0xFF243E52), tx2 = Color(0xFF6A8AA1), tx3 = Color(0xFF587B93), off = Color(0xFF8AA5B8),
        clusterPrime = Color(0xFFD98A4A), clusterPerf = Color(0xFF5E82C4),
        clusterEff = Color(0xFF4FA79B), clusterBal = Color(0xFF9A80C2),
        iconBgBlue = Color(0xFFE3F0FA), iconBgCyan = Color(0xFFE4F4FB), iconBgGreen = Color(0xFFE5F5EC),
        iconBgOrange = Color(0xFFF8EFE4), iconBgPurple = Color(0xFFF1EAF8), iconBgYellow = Color(0xFFF8F5E6),
    )

    /** 3. 暖奶米白：主色 #D4B896（用户指定） */
    val CREAM = Palette(
        id = "cream", label = "奶白",
        accent = Color(0xFFD4B896), accent2 = Color(0xFFE9DCC9), onAccent = Color(0xFF4A3F33),
        accentSoft = Color(0x24D4B896), curve = Color(0xFFC9A87F), shadowTint = Color(0xFF4A3F33),
        bg = Color(0xFFFBF7F0), card = Color(0xFFFFFFFF), panel = Color(0xFFF4EEE3), stroke = Color(0xFFE7DDCB),
        tx = Color(0xFF4A3F33), tx2 = Color(0xFF8B7D6B), tx3 = Color(0xFF7A6C5B), off = Color(0xFFA79A88),
        clusterPrime = Color(0xFFC9803F), clusterPerf = Color(0xFF6B87B8),
        clusterEff = Color(0xFF5E9B84), clusterBal = Color(0xFF9B7BA8),
        iconBgBlue = Color(0xFFEDE6DA), iconBgCyan = Color(0xFFEAE7DC), iconBgGreen = Color(0xFFE8EFE0),
        iconBgOrange = Color(0xFFF5EAE0), iconBgPurple = Color(0xFFF0E9F0), iconBgYellow = Color(0xFFF6F0DE),
    )

    /** 4. 浅草晨雾：主色 #8CBF7A（用户指定） */
    val DEW = Palette(
        id = "dew", label = "浅草",
        accent = Color(0xFF8CBF7A), accent2 = Color(0xFFC5E0B4), onAccent = Color(0xFF364A2E),
        accentSoft = Color(0x248CBF7A), curve = Color(0xFF7BB168), shadowTint = Color(0xFF364A2E),
        bg = Color(0xFFF9FFF6), card = Color(0xFFFFFFFF), panel = Color(0xFFF0F7EC), stroke = Color(0xFFDFEBD7),
        tx = Color(0xFF364A2E), tx2 = Color(0xFF728A68), tx3 = Color(0xFF63795A), off = Color(0xFF90A686),
        clusterPrime = Color(0xFFD68A4E), clusterPerf = Color(0xFF5F92C6),
        clusterEff = Color(0xFF4EAE92), clusterBal = Color(0xFF9A82C0),
        iconBgBlue = Color(0xFFE8F2E4), iconBgCyan = Color(0xFFE6F3E8), iconBgGreen = Color(0xFFE7F4E2),
        iconBgOrange = Color(0xFFF7EFE3), iconBgPurple = Color(0xFFF1EAF6), iconBgYellow = Color(0xFFF8F5E4),
    )

    /** v1.3 候选：晴空蓝（强调色深到能压白字，卡片纯白浮在淡色底上） */
    val AZURE = Palette(
        id = "azure", label = "晴空蓝",
        accent = Color(0xFF2A81E5), accent2 = Color(0xFF73A5DE), onAccent = Color(0xFFFFFFFF),
        accentSoft = Color(0x242A81E5), curve = Color(0xFF579CEA), shadowTint = Color(0xFF253241),
        bg = Color(0xFFF3F6F9), card = Color(0xFFFFFFFF), panel = Color(0xFFECF1F6), stroke = Color(0xFFD6E1ED),
        tx = Color(0xFF161F29), tx2 = Color(0xFF637488), tx3 = Color(0xFF415162), off = Color(0xFF929DAA),
        clusterPrime = Color(0xFFB66C2B), clusterPerf = Color(0xFF2B67B6),
        clusterEff = Color(0xFF29A393), clusterBal = Color(0xFF8F35B6),
        iconBgBlue = Color(0xFFF0F3F8), iconBgCyan = Color(0xFFF0F6F8), iconBgGreen = Color(0xFFF0F7F3),
        iconBgOrange = Color(0xFFF9F4EE), iconBgPurple = Color(0xFFF4F0F7), iconBgYellow = Color(0xFFF9F6EE),
    )

    /** v1.3 候选：薄荷青（强调色深到能压白字，卡片纯白浮在淡色底上） */
    val MINTY = Palette(
        id = "minty", label = "薄荷青",
        accent = Color(0xFF229175), accent2 = Color(0xFF73DEC3), onAccent = Color(0xFFFFFFFF),
        accentSoft = Color(0x24229175), curve = Color(0xFF2CBA96), shadowTint = Color(0xFF25413A),
        bg = Color(0xFFF3F9F7), card = Color(0xFFFFFFFF), panel = Color(0xFFECF6F3), stroke = Color(0xFFD6EDE8),
        tx = Color(0xFF162925), tx2 = Color(0xFF587971), tx3 = Color(0xFF41625A), off = Color(0xFF92AAA4),
        clusterPrime = Color(0xFFB66C2B), clusterPerf = Color(0xFF2B67B6),
        clusterEff = Color(0xFF29A393), clusterBal = Color(0xFF8F35B6),
        iconBgBlue = Color(0xFFF0F3F8), iconBgCyan = Color(0xFFF0F6F8), iconBgGreen = Color(0xFFF0F7F3),
        iconBgOrange = Color(0xFFF9F4EE), iconBgPurple = Color(0xFFF4F0F7), iconBgYellow = Color(0xFFF9F6EE),
    )

    /** v1.3 候选：雾天青（强调色深到能压白字，卡片纯白浮在淡色底上） */
    val HAZE = Palette(
        id = "haze", label = "雾天青",
        accent = Color(0xFF2989B0), accent2 = Color(0xFF73C0DE), onAccent = Color(0xFFFFFFFF),
        accentSoft = Color(0x242989B0), curve = Color(0xFF3BA6D1), shadowTint = Color(0xFF253941),
        bg = Color(0xFFF3F7F9), card = Color(0xFFFFFFFF), panel = Color(0xFFECF3F6), stroke = Color(0xFFD6E7ED),
        tx = Color(0xFF162429), tx2 = Color(0xFF5E7882), tx3 = Color(0xFF415962), off = Color(0xFF92A3AA),
        clusterPrime = Color(0xFFB66C2B), clusterPerf = Color(0xFF2B67B6),
        clusterEff = Color(0xFF29A393), clusterBal = Color(0xFF8F35B6),
        iconBgBlue = Color(0xFFF0F3F8), iconBgCyan = Color(0xFFF0F6F8), iconBgGreen = Color(0xFFF0F7F3),
        iconBgOrange = Color(0xFFF9F4EE), iconBgPurple = Color(0xFFF4F0F7), iconBgYellow = Color(0xFFF9F6EE),
    )

    /** v1.3 候选：暖奶米（强调色深到能压白字，卡片纯白浮在淡色底上） */
    val SAND = Palette(
        id = "sand", label = "暖奶米",
        accent = Color(0xFFAC7536), accent2 = Color(0xFFDEAC73), onAccent = Color(0xFFFFFFFF),
        accentSoft = Color(0x24AC7536), curve = Color(0xFFC78F4F), shadowTint = Color(0xFF413425),
        bg = Color(0xFFF9F6F3), card = Color(0xFFFFFFFF), panel = Color(0xFFF6F1EC), stroke = Color(0xFFEDE2D6),
        tx = Color(0xFF292116), tx2 = Color(0xFF82715E), tx3 = Color(0xFF625341), off = Color(0xFFAA9F92),
        clusterPrime = Color(0xFFB66C2B), clusterPerf = Color(0xFF2B67B6),
        clusterEff = Color(0xFF29A393), clusterBal = Color(0xFF8F35B6),
        iconBgBlue = Color(0xFFF0F3F8), iconBgCyan = Color(0xFFF0F6F8), iconBgGreen = Color(0xFFF0F7F3),
        iconBgOrange = Color(0xFFF9F4EE), iconBgPurple = Color(0xFFF4F0F7), iconBgYellow = Color(0xFFF9F6EE),
    )

    /** v1.3 候选：浅草（强调色深到能压白字，卡片纯白浮在淡色底上） */
    val GRASS = Palette(
        id = "grass", label = "浅草",
        accent = Color(0xFF508F26), accent2 = Color(0xFF9EDE73), onAccent = Color(0xFFFFFFFF),
        accentSoft = Color(0x24508F26), curve = Color(0xFF67B731), shadowTint = Color(0xFF304125),
        bg = Color(0xFFF6F9F3), card = Color(0xFFFFFFFF), panel = Color(0xFFF0F6EC), stroke = Color(0xFFDFEDD6),
        tx = Color(0xFF1E2916), tx2 = Color(0xFF657958), tx3 = Color(0xFF4E6241), off = Color(0xFF9CAA92),
        clusterPrime = Color(0xFFB66C2B), clusterPerf = Color(0xFF2B67B6),
        clusterEff = Color(0xFF29A393), clusterBal = Color(0xFF8F35B6),
        iconBgBlue = Color(0xFFF0F3F8), iconBgCyan = Color(0xFFF0F6F8), iconBgGreen = Color(0xFFF0F7F3),
        iconBgOrange = Color(0xFFF9F4EE), iconBgPurple = Color(0xFFF4F0F7), iconBgYellow = Color(0xFFF9F6EE),
    )

    /** v1.3 候选：紫藤（强调色深到能压白字，卡片纯白浮在淡色底上） */
    val WISTERIA = Palette(
        id = "wisteria", label = "紫藤",
        accent = Color(0xFF986ACD), accent2 = Color(0xFFA573DE), onAccent = Color(0xFFFFFFFF),
        accentSoft = Color(0x24986ACD), curve = Color(0xFFB390DA), shadowTint = Color(0xFF322541),
        bg = Color(0xFFF6F3F9), card = Color(0xFFFFFFFF), panel = Color(0xFFF1ECF6), stroke = Color(0xFFE1D6ED),
        tx = Color(0xFF1F1629), tx2 = Color(0xFF746388), tx3 = Color(0xFF514162), off = Color(0xFF9D92AA),
        clusterPrime = Color(0xFFB66C2B), clusterPerf = Color(0xFF2B67B6),
        clusterEff = Color(0xFF29A393), clusterBal = Color(0xFF8F35B6),
        iconBgBlue = Color(0xFFF0F3F8), iconBgCyan = Color(0xFFF0F6F8), iconBgGreen = Color(0xFFF0F7F3),
        iconBgOrange = Color(0xFFF9F4EE), iconBgPurple = Color(0xFFF4F0F7), iconBgYellow = Color(0xFFF9F6EE),
    )

    val ALL = listOf(AZURE, MINTY, HAZE, SAND, GRASS, WISTERIA)

    fun of(id: String?): Palette = ALL.firstOrNull { it.id == id }
        ?: Specials.paletteOf(id) ?: AZURE
}

object ThemeConfig {
    private const val PREFS = "mio_theme"
    private const val KEY_ID = "palette_id"

    @Volatile private var currentId: String = "azure"

    fun init(ctx: Context) {
        val id = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ID, "azure")
        }.getOrNull()
        currentId = Palettes.of(id).id
        Ink.applyPalette(Palettes.of(id))
    }

    fun set(ctx: Context, p: Palette) {
        currentId = p.id
        Ink.applyPalette(p)
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ID, p.id).apply()
        }
    }

    fun current(): Palette = Palettes.of(currentId)
}
