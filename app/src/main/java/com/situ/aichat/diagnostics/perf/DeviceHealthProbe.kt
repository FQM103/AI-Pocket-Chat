package com.situ.aichat.diagnostics.perf

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 热节流档位 + 电池温度（图纸 §3.4 尺 3 的「发热」一半）。
 *
 * 为什么不量 GPU 每帧毫秒（图纸 J4）：App 进程内**拿不到** GPU 侧真实耗时，那需要电脑上的 Profiler。
 * 能拿到的是系统给的热节流档位与电池温度 —— 而且这两个数字比 GPU 毫秒更贴近用户感受（烫手就是烫手）。
 *
 * 取数一律 `runCatching` 兜底：某些 ROM 的 [PowerManager.getCurrentThermalStatus] 会抛或给未知值（§5 E22），
 * 电池 sticky 广播也可能取不到（§5 E23）—— 取不到就如实记 `-1` / `NaN`，绝不崩、也绝不编一个好看的数。
 */
@Singleton
class DeviceHealthProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 现取一条健康样本。[scene] = 当时所在的被观测场景（没有则 null）。 */
    fun sample(header: PerfHeader, scene: String?): PerfSample.Health {
        val status = currentThermalStatus()
        return PerfSample.Health(
            header = header,
            thermalStatus = status,
            thermalName = thermalNameOf(status),
            batteryTempC = currentBatteryTempC(),
            scene = scene,
        )
    }

    /**
     * 导出报告的设备头（图纸 §3.2）。全是「这台机器是什么样」的客观事实——分析时没有它，毫秒数就没有参照系
     * （120Hz 还是 60Hz、堆上限多大、是不是低内存机，结论完全不同）。
     */
    fun deviceHeader(appVersionName: String): PerfDeviceHeader {
        val metrics = context.resources.displayMetrics
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val refreshHz = runCatching {
            val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
            display?.refreshRate?.roundToInt() ?: FrameMetricsProbe.DEFAULT_REFRESH_HZ
        }.getOrDefault(FrameMetricsProbe.DEFAULT_REFRESH_HZ)
        return PerfDeviceHeader(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            appVersionName = appVersionName,
            refreshHz = refreshHz,
            densityDpi = metrics.densityDpi,
            screenWidthPx = metrics.widthPixels,
            screenHeightPx = metrics.heightPixels,
            maxHeapMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt(),
            isLowRamDevice = activityManager?.isLowRamDevice ?: false,
            locale = context.resources.configuration.locales[0].toLanguageTag(),
        )
    }

    /** 热节流档位；取不到 → `-1`。 */
    private fun currentThermalStatus(): Int = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        power?.currentThermalStatus ?: UNKNOWN_THERMAL_STATUS
    }.getOrDefault(UNKNOWN_THERMAL_STATUS)

    /**
     * 电池温度（℃）。读 `ACTION_BATTERY_CHANGED` 的 **sticky** 广播（`registerReceiver(null, …)` = 只取当前值、
     * **不注册常驻接收器**，采集本身不该常驻任何东西）；取不到 → `Double.NaN`。
     */
    private fun currentBatteryTempC(): Double = runCatching {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (tenths == Int.MIN_VALUE) Double.NaN else tenths / 10.0
    }.getOrDefault(Double.NaN)

    companion object {
        const val UNKNOWN_THERMAL_STATUS = -1
        const val UNKNOWN_THERMAL_NAME = "unknown"

        /** 档位名（`PowerManager.THERMAL_STATUS_*` 0..6）。未知档位一律 [UNKNOWN_THERMAL_NAME]，不猜。 */
        fun thermalNameOf(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> UNKNOWN_THERMAL_NAME
        }
    }
}
