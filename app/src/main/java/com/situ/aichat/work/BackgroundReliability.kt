package com.situ.aichat.work

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService

/**
 * 后台运行保障(P5.0)的系统设置导航 + 状态查询。
 *
 * 国行 ROM 杀后台靠两项一次性设置兜底：① 免电池优化(有公开 API 可查状态、可弹系统对话框)；
 * ② 自启动白名单(无公开 API，只能跳厂商安全中心，小米=MIUI 自启动管理；查不到状态)。
 * 所有跳转都用 try/catch 兜底到「应用详情设置」，避免个别 ROM 无对应 Activity / 包不可见时崩溃。
 */
object BackgroundReliability {

    /** 当前是否已被豁免电池优化(= 可在后台较稳定运行)。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService<PowerManager>() ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 弹出系统「忽略电池优化」对话框；失败则回退到电池优化设置列表，再不行回退应用详情。 */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        if (startSafely(context, direct)) return
        if (startSafely(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        openAppDetailsSettings(context)
    }

    /**
     * 跳到自启动/后台启动管理页（C1：覆盖主流国行 OEM，不再只有小米）。按设备品牌选候选组件，逐个 try（个别机型
     * 组件名随版本漂移，多备一两个），全失败回退应用详情设置。其他国行机型上「自启动白名单」此前只跳应用详情=无目标
     * 开关、引导落空 → 后台被杀、提醒不触发。组件名取自各 ROM 安全中心公开 Activity（华为/荣耀·OPPO 系·vivo 系·魅族·三星）。
     */
    fun openAutoStartSettings(context: Context) {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        for ((pkg, cls) in autoStartComponentsForBrand(brand)) {
            if (startSafely(context, Intent().apply { component = ComponentName(pkg, cls) })) return
        }
        openAppDetailsSettings(context)
    }

    /** 应用详情设置页(所有设备都有，作为统一兜底)。 */
    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        startSafely(context, intent)
    }

    val isXiaomi: Boolean
        get() {
            val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
            return listOf("xiaomi", "redmi", "poco").any { brand.contains(it) }
        }

    /**
     * 按设备品牌字符串（`MANUFACTURER + " " + BRAND` 小写）返回自启动/后台启动管理候选组件（pkg→class，纯函数·单测）。
     * 逐个 try（组件名随 ROM 版本漂移，多备一两个）；无匹配品牌=空列表 → 调用方回退应用详情。组件名取自各 ROM 安全中心
     * 公开 Activity（华为/荣耀·OPPO 系[含 realme/一加 ColorOS·新旧 coloros/oppo/oplus 包]·vivo 系[含 iQOO]·魅族·三星）。
     */
    internal fun autoStartComponentsForBrand(brand: String): List<Pair<String, String>> {
        fun has(vararg keys: String) = keys.any { brand.contains(it) }
        return when {
            has("xiaomi", "redmi", "poco") -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            has("huawei", "honor") -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            )
            has("oppo", "realme", "oneplus") -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            )
            has("vivo", "iqoo") -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            )
            has("meizu") -> listOf(
                "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
            )
            has("samsung") -> listOf(
                "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
                "com.samsung.android.sm" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            )
            else -> emptyList()
        }
    }

    private fun startSafely(context: Context, intent: Intent): Boolean = try {
        // 显式组件跳厂商页时，API 30+ 的包可见性可能直接抛 ActivityNotFound；统一 try/catch 兜底。
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}
