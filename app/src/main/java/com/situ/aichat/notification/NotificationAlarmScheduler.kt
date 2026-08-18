package com.situ.aichat.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 精确闹钟封装(P6.1a)——「可靠优先」模式的触发底座。把提前生成好的 [NotificationPayload] 烤进一个
 * `setExactAndAllowWhileIdle` 闹钟；到点由系统广播给 [NotificationAlarmReceiver] 发通知——无需 App
 * 存活、无需联网、扛 Doze。这是最接近 iOS `UNCalendarNotificationTrigger`「登记即触发」的安卓方案。
 *
 * Android 12+ 精确闹钟需 `USE_EXACT_ALARM`（本项目 sideload 可用，见记忆 release-and-compliance）；
 * 万一系统不允许精确闹钟，降级为 `setAndAllowWhileIdle`（不精确但仍能触发）并记日志。
 *
 * 后台任务统一挂 [com.situ.aichat.work.BackgroundScheduler](WorkManager)；本类是「定时弹一条已烤好的
 * 通知」的专用底座，与之互补（6.1c 的调度器据火花 / 日程决定要不要、何时排）。
 */
@Singleton
class NotificationAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alarmManager: AlarmManager?
        get() = context.getSystemService(AlarmManager::class.java)

    /**
     * 排一个精确闹钟，到点发出 [payload] 对应的通知。
     * @param requestKey 稳定唯一 key（同 key 再排会覆盖；[cancel] 也用它）。
     * @param triggerAtMillis 触发的绝对时间（RTC，epoch millis）。
     */
    fun scheduleExact(requestKey: String, triggerAtMillis: Long, payload: NotificationPayload) {
        val am = alarmManager ?: return
        val pi = buildPendingIntent(requestKey, payload, allowCreate = true) ?: return
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        // C1#2：HyperOS/MIUI 等国产 ROM 对**精确**闹钟有并发配额（公开 API 之外的私有限制），超额时
        // setExactAndAllowWhileIdle 会抛 SecurityException/IllegalStateException。裸调会让调用方（per-character
        // scheduleAll 循环等）整轮中断、后续角色全部漏排。这里就地降级到不精确闹钟
        // （仍能 AllowWhileIdle 扛 Doze、只是触发时刻不精准），保证「排不上精确就排个粗的」而非「一个失败全军覆没」。
        if (canExact) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                Log.d(TAG, "精确闹钟已排: key=$requestKey at=$triggerAtMillis")
            } catch (e: Exception) {
                runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi) }
                Log.w(TAG, "精确闹钟被拒(疑似配额)，降级为不精确: key=$requestKey", e)
            }
        } else {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi) }
            Log.w(TAG, "无精确闹钟权限，降级为不精确闹钟: key=$requestKey")
        }
    }

    /** 取消之前用同 [requestKey] 排的闹钟（不存在则无操作）。 */
    fun cancel(requestKey: String) {
        val am = alarmManager ?: return
        val pi = buildPendingIntent(requestKey, payload = null, allowCreate = false) ?: return
        am.cancel(pi)
        pi.cancel()
        Log.d(TAG, "闹钟已取消: key=$requestKey")
    }

    /**
     * 该 [requestKey] 的精确闹钟当前是否仍「活着」（E2）。原理：PendingIntent token 由 system_server 持有，**重启与
     * force-stop 都会清除**（框架行为·非 OEM 可定制），而普通进程死亡则存活——故 `FLAG_NO_CREATE` 取不到（null）即表示
     * 闹钟已被重启/force-stop 清掉。用于让调度器在「registry 台账非空但真实闹钟已没了」时强制重排，而非误信台账跳过。
     * 注：一次性闹钟触发后 token 可能仍在（未 cancel），故本探测语义是「是否被清除」而非「是否未触发」——正合所需。
     */
    fun isAlarmLive(requestKey: String): Boolean =
        buildPendingIntent(requestKey, payload = null, allowCreate = false) != null

    private fun buildPendingIntent(
        requestKey: String,
        payload: NotificationPayload?,
        allowCreate: Boolean,
    ): PendingIntent? {
        val intent = Intent(context, NotificationAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            // 用 requestKey 作 data，确保不同 key 的 PendingIntent 被 filterEquals 区分、互不覆盖。
            data = Uri.parse("aichat://notif/$requestKey")
            if (payload != null) {
                putExtra(EXTRA_ID, payload.notificationId)
                putExtra(EXTRA_TITLE, payload.title)
                putExtra(EXTRA_BODY, payload.body)
                putExtra(EXTRA_CONV, payload.conversationUuid)
                putExtra(EXTRA_CHAR_ID, payload.characterId)
                putExtra(EXTRA_DELIVERY_ID, payload.deliveryIdentifier)
                putExtra(EXTRA_CATEGORY, payload.category)
                putExtra(EXTRA_REQUEST_KEY, payload.requestKey)
                putExtra(EXTRA_SCHEDULED_AT, payload.scheduledAtMillis)
                putExtra(EXTRA_BADGE, payload.badgeCount)
                putExtra(EXTRA_STORY_ID, payload.storyId)
                putExtra(EXTRA_FRESH, payload.freshResolution)
                putExtra(EXTRA_AVATAR_PATH, payload.avatarPath)
                putExtra(EXTRA_OCCASION, payload.occasion)
            }
        }
        val flags = if (allowCreate) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        }
        return PendingIntent.getBroadcast(context, requestKey.hashCode(), intent, flags)
    }

    companion object {
        private const val TAG = "NotifAlarmScheduler"
        const val ACTION_FIRE = "com.situ.aichat.notification.FIRE"
        const val EXTRA_ID = "notif_id"
        const val EXTRA_TITLE = "notif_title"
        const val EXTRA_BODY = "notif_body"
        const val EXTRA_CONV = "notif_conv"
        const val EXTRA_CHAR_ID = "notif_char_id"
        const val EXTRA_DELIVERY_ID = "notif_delivery_id"
        const val EXTRA_CATEGORY = "notif_category"
        const val EXTRA_REQUEST_KEY = "notif_request_key"
        const val EXTRA_SCHEDULED_AT = "notif_scheduled_at"
        const val EXTRA_BADGE = "notif_badge"
        const val EXTRA_STORY_ID = "notif_story_id"
        const val EXTRA_FRESH = "notif_fresh_resolution"
        const val EXTRA_AVATAR_PATH = "notif_avatar_path"
        const val EXTRA_OCCASION = "notif_occasion"
    }
}
