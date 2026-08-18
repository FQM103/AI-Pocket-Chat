package com.situ.aichat.notification

import android.content.Context
import androidx.core.content.edit

/**
 * 全局「上一次出声通知」时刻台账（W8 图纸 §3.4）：整个 app 里任何**出声**通知真正弹出时（[Notifier.postSafely]
 * 成功 + 非静音 channel）刷新一次时间戳；唯一读者 = 世界到达通知的门 7 排队（世界永远让路，绝不与聊天 / 日历
 * 通知连震）。现有各通知路径只写不读，行为零变化。
 *
 * 是 Kotlin `object`（同 [Notifier] / [PendingDeliveryStore]）：[Notifier.postSafely] 非 Hilt，直接静态调用。
 * SharedPreferences 单值，读写极轻（`apply` 异步落盘·同进程内存缓存即时可见——世界 worker 与发通知在同一主进程）。
 */
object NotificationPostLedger {

    private const val PREFS_NAME = "notification_post_ledger"
    private const val KEY_LAST_POST_AT = "last_alert_post_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 出声通知发出成功时记下当前墙钟时刻（[Notifier.postSafely] 调）。 */
    fun recordPost(context: Context) {
        prefs(context).edit { putLong(KEY_LAST_POST_AT, System.currentTimeMillis()) }
    }

    /** 上一次出声通知的时刻（epoch millis·从未出声 → 0）。 */
    fun lastPostAt(context: Context): Long = prefs(context).getLong(KEY_LAST_POST_AT, 0L)
}
