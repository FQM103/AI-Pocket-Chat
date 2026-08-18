package com.situ.aichat.maintenance

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 启动自愈维护任务的 **24h 节流**台账（14.7b）。对齐 iOS `AppBootstrapService` 里那批用 UserDefaults
 * `*_lastCheck` 时间戳门控的每日维护（当前：闲置角色关系淡化）。设备本地（SharedPreferences）、**不进备份**
 * ——这是「上次跑过的时刻」运行态，换机/恢复后该重新跑一次而非沿用旧时刻。
 *
 * 用法：[isDue] 判距上次是否已过 [intervalMs]（从未跑=true）→ 跑完调 [markRun] 记录本次时刻。
 */
@Singleton
class MaintenanceThrottleStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        context.getSharedPreferences("startup_maintenance_throttle", Context.MODE_PRIVATE)
    }

    /** 距上次跑 [key] 是否已超 [intervalMs]（从未跑过返回 true）。 */
    fun isDue(key: String, intervalMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        val last = prefs.getLong(key, -1L)
        return last < 0L || now - last >= intervalMs
    }

    /** 记录 [key] 本次运行时刻。 */
    fun markRun(key: String, now: Long = System.currentTimeMillis()) {
        prefs.edit { putLong(key, now) }
    }

    /** 读文本戳（成长原型校准内容指纹·D-14）；从未写过返回 ""。 */
    fun readTextStamp(key: String): String = prefs.getString(key, "") ?: ""

    /** 写文本戳（内容指纹）。 */
    fun writeTextStamp(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    companion object {
        const val KEY_RELATIONSHIP_DECAY = "relationship_decay_last"
        const val KEY_ARCHETYPE_CALIBRATION_FINGERPRINT = "archetype_calibration_fingerprint"
        /** 校准快路（微图纸「指纹五项加固」③）：APK lastUpdateTime 十进制串——未重装则设定不可能变。 */
        const val KEY_ARCHETYPE_APK_STAMP = "archetype_calibration_apk_stamp"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
