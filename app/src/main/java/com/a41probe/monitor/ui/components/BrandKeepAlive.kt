package com.a41probe.monitor.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 厂商后台保活引导（v0.24）：国产 ROM 后台管理激进，仅标准电池白名单不足以保活，
 * 需引导用户到各厂商「自启动 / 后台运行 / 应用速冻」页开启。
 * 各 ROM Activity 名随版本变化，采用多候选 + runCatching，失败回退标准设置/应用详情页。
 */
object BrandKeepAlive {

    data class KeepItem(
        val id: String,
        val title: String,
        val sub: String,
        val button: String,
        val components: List<String>,   // 候选 component，形如 "pkg/.Act" 或 "pkg/com.xx.Act"
        val action: String? = null,    // 或标准 Settings action
    )

    /** 多任务加锁的纯文字提示（无统一 intent，因厂商交互各异）。 */
    const val RECENT_LOCK_TIP =
        "从屏幕底部上滑并停留进入「多任务」，找到 Mio 澪，下拉卡片或点击锁形图标将其锁定，避免被一键清理。"

    fun items(ctx: Context): List<KeepItem> {
        val brand = Build.BRAND.lowercase()
        val mfr = Build.MANUFACTURER.lowercase()
        val isOppo = brand.contains("oppo") || brand.contains("oneplus") ||
            mfr.contains("oppo") || mfr.contains("oneplus") || hasPkg(ctx, "com.coloros.safecenter")
        val isVivo = brand.contains("vivo") || brand.contains("iqoo") || mfr.contains("vivo")
        val isMi = brand.contains("xiaomi") || brand.contains("redmi") || mfr.contains("xiaomi") ||
            hasPkg(ctx, "com.miui.securitycenter")
        val isHuawei = brand.contains("huawei") || brand.contains("honor") || mfr.contains("huawei")
        val isSamsung = brand.contains("samsung") || mfr.contains("samsung")

        val out = ArrayList<KeepItem>()
        when {
            isOppo -> {
                out += KeepItem(
                    "oppo_auto", "允许自启动",
                    "ColorOS 默认禁止应用自启动，服务被杀后无法恢复，必须开启。",
                    "去开启",
                    listOf(
                        "com.coloros.safecenter/.permission.startup.StartupAppListActivity",
                        "com.coloros.safecenter/.startupapp.StartupAppListActivity",
                        "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
                        "com.oppo.safe/.permission.startup.StartupAppListActivity",
                    ),
                )
                out += KeepItem(
                    "oppo_freeze", "允许后台运行 · 关闭应用速冻",
                    "关闭「应用速冻 / 睡眠待机优化」，允许锁屏后继续运行。",
                    "去设置",
                    listOf(
                        "com.coloros.oppoguardelf/.powerutils.PowerConsumeDetailActivity",
                        "com.coloros.oppoguardelf/.PowerUtils.AppRuntimePermissions.AppRuntimePermissionsTopActivity",
                    ),
                    action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                )
            }
            isVivo -> out += KeepItem(
                "vivo_auto", "允许自启动与后台运行",
                "在 i 管家中允许 Mio 澪 自启动、后台弹出与后台运行。",
                "去开启",
                listOf(
                    "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager",
                    "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
                    "com.iqoo.secure/.safeguard.PurviewTabActivity",
                ),
            )
            isMi -> out += KeepItem(
                "mi_auto", "允许自启动",
                "MIUI 默认禁止自启动，需在安全中心允许 Mio 澪 自启动。",
                "去开启",
                listOf(
                    "com.miui.securitycenter/.permission.autostart.EditAutoStartActivity",
                    "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            )
            isHuawei -> out += KeepItem(
                "hw_auto", "应用启动管理改为手动",
                "关闭自动管理，手动允许自启动、关联启动与后台活动。",
                "去开启",
                listOf(
                    "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
                    "com.huawei.systemmanager/.appcontrol.StartupDetectActivity",
                ),
            )
            isSamsung -> out += KeepItem(
                "ss_bg", "允许后台活动 · 移除电池限制",
                "在电池与设备维护中允许 Mio 澪 后台运行、不受限制。",
                "去设置",
                listOf("com.samsung.android.sm/.ui.managespace.MainRunningActivity"),
                action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            )
            else -> out += KeepItem(
                "gen_bg", "允许后台运行",
                "在应用信息中关闭电池优化、允许后台活动。",
                "去设置",
                emptyList(),
                action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            )
        }
        return out
    }

    /** 打开保活设置：候选 component → 标准 action → 应用详情页兜底；返回是否成功。 */
    fun launch(ctx: Context, item: KeepItem): Boolean {
        for (cmp in item.components) {
            val parts = cmp.split("/")
            if (parts.size != 2) continue
            val cls = if (parts[1].startsWith(".")) parts[0] + parts[1] else parts[1]
            val i = Intent().apply {
                component = ComponentName(parts[0], cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { ctx.startActivity(i); return true } catch (_: Throwable) {}
        }
        item.action?.let { a ->
            try {
                ctx.startActivity(Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Throwable) {}
        }
        return try {
            val i = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i); true
        } catch (_: Throwable) { false }
    }

    private fun hasPkg(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Throwable) { false }
}
