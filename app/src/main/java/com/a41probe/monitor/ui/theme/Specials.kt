package com.a41probe.monitor.ui.theme

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * 特殊预设（角色）：与「配色预设」分开的一类方案。
 *
 *  - 配色预设（Palettes）：只换 UI 颜色，立绘保持当前角色；
 *  - 特殊预设（Specials）：整组方案 —— 自带立绘 / 表情 / 配色，选中即整套切换。
 *
 * 素材按 **drawable 资源名**解析（`Resources.getIdentifier`），不硬编码 `R.drawable.*`：
 * 角色美术素材属于第三方版权物，**不随本仓库分发**；把图片放进
 * `app/src/main/res/drawable-nodpi/` 即自动启用，缺失时该预设不出现在设置里，
 * 已选中的也会回落默认角色，不会画出空白，更不会崩。
 *
 * 立绘按「页面 key」、表情按「表情 key」查表，缺项回落默认角色（澪）；
 * 新增角色 = 加一条记录 + 一组同名图片。
 */
data class SpecialTheme(
    val id: String,
    val label: String,
    val subtitle: String,
    /** null = 不绑定配色（沿用当前配色预设） */
    val palette: Palette?,
    /** 页面 key -> drawable 资源名 */
    val art: Map<String, String>,
    /** 表情 key -> drawable 资源名 */
    val expr: Map<String, String>,
    /** 设置页头像（优先头部特写，小框内脸完整） */
    val thumb: String,
) {
    /** 页面背景立绘；0 = 该页不绘制 */
    fun artFor(key: String): Int {
        val name = art[key] ?: MIO_ART[key] ?: return 0
        return ResNames.id(name)
    }

    /** 小框表情，缺项回落默认角色 */
    fun exprFor(key: String): Int {
        val name = expr[key] ?: MIO_EXPR[key] ?: MIO_FALLBACK_EXPR
        return ResNames.id(name)
    }

    /** 设置页头像资源（缺失返回 0，调用方应跳过绘制） */
    fun thumbRes(): Int = ResNames.id(thumb)

    /** 素材是否齐备：头像与首页立绘都存在才算（避免只放了一半素材的半残预设） */
    val available: Boolean
        get() = thumbRes() != 0 && artFor("dashboard") != 0
}

/** 默认角色（澪）的资源名表；同时作为所有特殊预设的回落表 */
val MIO_ART: Map<String, String> = mapOf(
    "dashboard" to "mio_dashboard",
    "cpu" to "mio_cpu",
    "gpu" to "mio_gpu",
    "battery" to "mio_battery",
    "thermal" to "mio_thermal",
    "sensor" to "mio_sensor",
    "settings" to "mio_settings",
)

val MIO_EXPR: Map<String, String> = mapOf(
    "think" to "mio_expr_think", "think_head" to "mio_expr_think_head",
    "hi" to "mio_expr_hi", "hi_head" to "mio_expr_hi_head",
    "shy" to "mio_expr_shy", "shy_head" to "mio_expr_shy_head",
    "wow" to "mio_expr_wow", "wow_head" to "mio_expr_wow_head",
    "go" to "mio_expr_go", "go_head" to "mio_expr_go_head",
)

private const val MIO_FALLBACK_EXPR = "mio_expr_think_head"

/** 资源名 → drawable id（进程级缓存；未初始化或不存在返回 0） */
internal object ResNames {
    private var res: android.content.res.Resources? = null
    private var pkg: String = ""
    private val cache = HashMap<String, Int>()

    fun init(ctx: Context) {
        val c = ctx.applicationContext
        res = c.resources
        pkg = c.packageName
        cache.clear()
    }

    fun id(name: String): Int {
        if (name.isEmpty()) return 0
        cache[name]?.let { return it }
        val r = res ?: return 0
        val v = runCatching { r.getIdentifier(name, "drawable", pkg) }.getOrDefault(0)
        cache[name] = v
        return v
    }
}

object Specials {

    /** 默认角色：澪 —— 不绑定配色，沿用当前配色预设 */
    val MIO = SpecialTheme(
        id = "mio", label = "澪", subtitle = "默认",
        palette = null,
        art = MIO_ART, expr = MIO_EXPR,
        thumb = "mio_expr_think_head",
    )

    /**
     * 砂狼シロコ（Blue Archive）：整组方案。
     * 配色取自素材包 palette.json；该包强调色压白字只有 3.25:1，故 onAccent 用深青
     * （≈4.8:1），顶栏/选中态的文字一律走 Ink.onAccent，不再硬编码白色。
     * 立绘与表情素材不随仓库分发，本地放入同名图片即生效。
     */
    val SHIROKO = SpecialTheme(
        id = "shiroko", label = "砂狼シロコ", subtitle = "Blue Archive",
        palette = Palette(
            id = "shiroko", label = "砂狼シロコ",
            accent = Color(0xFF0E99B4), accent2 = Color(0xFF6DCBDD), onAccent = Color(0xFF06282F),
            accentSoft = Color(0x240E99B4), curve = Color(0xFF2FA7C1), shadowTint = Color(0xFF12313A),
            bg = Color(0xFFF3F8FA), card = Color(0xFFFFFFFF), panel = Color(0xFFE9F2F5), stroke = Color(0xFFD7E5EB),
            tx = Color(0xFF1F2A37), tx2 = Color(0xFF5E6B7A), tx3 = Color(0xFF46566A), off = Color(0xFF8B98A6),
            clusterPrime = Color(0xFFB66C2B), clusterPerf = Color(0xFF2B67B6),
            clusterEff = Color(0xFF29A393), clusterBal = Color(0xFF8F35B6),
            iconBgBlue = Color(0xFFEEF5F8), iconBgCyan = Color(0xFFEDF6F8), iconBgGreen = Color(0xFFEDF6F2),
            iconBgOrange = Color(0xFFF8F3ED), iconBgPurple = Color(0xFFF2EFF6), iconBgYellow = Color(0xFFF8F5ED),
        ),
        art = mapOf(
            "dashboard" to "shiroko_dashboard",
            "cpu" to "shiroko_cpu",
            "gpu" to "shiroko_gpu",
            "battery" to "shiroko_battery",
            "thermal" to "shiroko_thermal",
            "sensor" to "shiroko_sensor",
            "settings" to "shiroko_settings",
        ),
        expr = mapOf(
            "think" to "shiroko_expr_think", "think_head" to "shiroko_expr_think_head",
            "hi" to "shiroko_expr_hi", "hi_head" to "shiroko_expr_hi_head",
            "shy" to "shiroko_expr_shy", "shy_head" to "shiroko_expr_shy_head",
            "wow" to "shiroko_expr_wow", "wow_head" to "shiroko_expr_wow_head",
            "go" to "shiroko_expr_go", "go_head" to "shiroko_expr_go_head",
        ),
        thumb = "shiroko_expr_think_head",
    )

    private val builtIn = listOf(MIO, SHIROKO)

    /** 素材齐备、可用于设置的预设（缺素材的角色自动隐藏） */
    fun all(): List<SpecialTheme> = builtIn.filter { it.available }

    fun of(id: String?): SpecialTheme = builtIn.firstOrNull { it.id == id } ?: MIO

    /** 供 Palettes.of 解析角色自带配色（非角色 id 返回 null） */
    fun paletteOf(id: String?): Palette? = builtIn.firstOrNull { it.id == id }?.palette
}

/**
 * 统一解析资源 key（v1.3.0）：`"expr:hi_head"` / `"art:dashboard"` → drawable id。
 * 缺项一律回落默认角色；无冒号时按立绘 key 处理。在组合中调用即可随角色切换自动刷新。
 */
fun artKeyResolve(key: String): Int {
    val sp = SpecialConfig.current()
    val i = key.indexOf(':')
    if (i <= 0) return sp.artFor(key)
    val kind = key.substring(0, i)
    val name = key.substring(i + 1)
    return if (kind == "expr") sp.exprFor(name) else sp.artFor(name)
}

/** 当前特殊预设（角色）。状态由 mutableStateOf 承载，切换后立绘立即刷新。 */
object SpecialConfig {
    private const val PREFS = "mio_theme"
    private const val KEY_ID = "special_id"

    private val state = mutableStateOf(Specials.MIO)

    fun init(ctx: Context) {
        ResNames.init(ctx)
        val sp = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull() ?: return
        val palId = sp.getString("palette_id", null)
        // 首选存档角色；素材缺失（例如未随包分发的角色图）或没存过时，
        // 退回「自带配色与当前配色一致的角色」，最后回落默认角色
        state.value = Specials.of(sp.getString(KEY_ID, null)).takeIf { it.available }
            ?: Specials.all().firstOrNull { it.palette?.id == palId }
            ?: Specials.MIO
    }

    fun set(ctx: Context, s: SpecialTheme) {
        state.value = s
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ID, s.id).apply()
        }
    }

    fun current(): SpecialTheme = state.value
}
