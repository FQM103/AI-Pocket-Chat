package com.situ.aichat.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.situ.aichat.work.ProactiveNotificationWorker
import com.situ.aichat.world.notify.WorldNotifyWorker

/**
 * 精确闹钟触发后的广播接收者(P6.1a)。读出烤进闹钟的 [NotificationPayload]，经 [Notifier] 发出通知。
 * 在 AndroidManifest 注册(exported=false，仅本应用的闹钟 PendingIntent 能拉起)。
 *
 * 到点时 App 可能已被国行 ROM 杀掉，由系统拉起本接收者补发通知。onReceive 必须轻量、同步完成。
 * - 「可靠优先」/ 日历 / 红包 / 故事 / 宠物：直接 [Notifier] 构建并 notify（无 IO / 无网络）。
 * - 13.7d「智能合并」主动消息（[NotificationPayload.freshResolution]=true）：不在此处发，而是 [enqueueFresh] 起一个
 *   加急 worker 现写最新文案、失败退预烤兜底——把会超时的 LLM 调用挪出 receiver 的 ~10s 窗口（onReceive 仍只
 *   做轻量入队）。
 */
class NotificationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationAlarmScheduler.ACTION_FIRE) return
        val id = intent.getIntExtra(NotificationAlarmScheduler.EXTRA_ID, 0)
        val title = intent.getStringExtra(NotificationAlarmScheduler.EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(NotificationAlarmScheduler.EXTRA_BODY) ?: return
        val conversationUuid = intent.getStringExtra(NotificationAlarmScheduler.EXTRA_CONV)
        val category = intent.getStringExtra(NotificationAlarmScheduler.EXTRA_CATEGORY)
        val storyId = intent.getStringExtra(NotificationAlarmScheduler.EXTRA_STORY_ID)
        // 6.1d：透传物化 / 跳转所需字段；Notifier.post 发出后会落「待物化标记」。
        val payload = NotificationPayload(
            notificationId = id,
            title = title,
            body = body,
            conversationUuid = conversationUuid,
            characterId = intent.getStringExtra(NotificationAlarmScheduler.EXTRA_CHAR_ID),
            deliveryIdentifier = intent.getStringExtra(NotificationAlarmScheduler.EXTRA_DELIVERY_ID),
            category = category,
            requestKey = intent.getStringExtra(NotificationAlarmScheduler.EXTRA_REQUEST_KEY),
            scheduledAtMillis = intent.getLongExtra(NotificationAlarmScheduler.EXTRA_SCHEDULED_AT, 0L),
            badgeCount = intent.getIntExtra(NotificationAlarmScheduler.EXTRA_BADGE, 0),
            freshResolution = intent.getBooleanExtra(NotificationAlarmScheduler.EXTRA_FRESH, false),
            avatarPath = intent.getStringExtra(NotificationAlarmScheduler.EXTRA_AVATAR_PATH),
            occasion = intent.getStringExtra(NotificationAlarmScheduler.EXTRA_OCCASION),
        )
        // P9.3b 红包过期预警：走非物化路径（瞬时提醒，不落待物化标记），与前台扫描同 id onlyAlertOnce 去重。
        // P11 11.1g-1 故事章节解锁：STORY 渠道 + 故事深链，不物化（chapter 早已落库，仅到点提醒）。
        when {
            category == Notifier.CATEGORY_RED_PACKET_EXPIRING -> Notifier.postRedPacketExpiring(context, payload)
            category == Notifier.CATEGORY_STORY_UNLOCK && storyId != null ->
                Notifier.postStory(context, payload.notificationId, storyId, payload.title, payload.body)
            // 13.7c 宠物饿/病提醒：深链进宠物详情，不物化（characterId = 宠物所属角色 uuid）。
            category == Notifier.CATEGORY_PET && payload.characterId != null ->
                Notifier.postPet(context, payload.notificationId, payload.title, payload.body, payload.characterId)
            // Phase 10 未来约定见面到点：角色喊你赴约，深链回会话进沉浸（赴约），不物化。
            category == Notifier.CATEGORY_MEETUP -> Notifier.postMeetup(context, payload)
            // W8 世界到达：到点不直接发——入队 worker 验真再发（行还在吗/档位/封顶/排队），receiver 保持零 IO。
            category == NotifierWorld.CATEGORY_WORLD_ARRIVAL && payload.requestKey != null ->
                WorldNotifyWorker.enqueue(context, payload.requestKey)
            // 13.7d 智能合并：到点起加急 worker 现写最新文案（失败退预烤 payload.body 兜底），把 LLM 挪出 receiver 窗口。
            payload.freshResolution -> ProactiveNotificationWorker.enqueueFresh(context, payload)
            else -> Notifier.post(context, payload)
        }
    }
}
