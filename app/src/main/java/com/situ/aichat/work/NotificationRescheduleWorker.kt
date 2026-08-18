package com.situ.aichat.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.situ.aichat.meeting.MeetupNotificationService
import com.situ.aichat.notification.CalendarNotificationScheduler
import com.situ.aichat.notification.NotificationScheduler
import com.situ.aichat.pet.PetReminderScheduler
import com.situ.aichat.story.StoryUnlockNotificationScheduler
import com.situ.aichat.world.notify.WorldNotifyService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 通知重排后台任务（P6.1c）。委托 [NotificationScheduler.scheduleAll] 为所有角色重算并重排主动消息通知。
 *
 * 三个触发口（对齐 iOS 的「启动 / 进前台 / BGAppRefresh 都重烤」）：
 * - app 启动一次性（[com.situ.aichat.ui.AppViewModel]，6.1c-ii 接线）；
 * - 每日周期（兜底「用户长时间不开 App」也能续上次日的通知）；
 * - 开机重排（[com.situ.aichat.notification.BootReceiver]，精确闹钟不跨重启保留）。
 *
 * P6.3：同时重排**日历事件通知**（[CalendarNotificationScheduler]）——精确闹钟同样不跨重启保留，
 * 故开机 / 每日 / 启动都需重烤一遍。
 *
 * P11 11.1g：同时重排**故事章节解锁通知**（[StoryUnlockNotificationScheduler]）——精确闹钟不跨重启，
 * 开机后按 DB 里仍未解锁的章重烤（对齐 iOS `UNCalendarNotificationTrigger` 跨重启存活；回前台另有 g-2 兜底）。
 *
 * 13.7c：同时重排**宠物饿/病提醒**（[PetReminderScheduler]）——精确闹钟不跨重启，开机后按宠物当前状态预测重烤
 * （前台另有 [com.situ.aichat.pet.PetReminderSync] 兜：无 UI 进程的开机场景靠本 worker）。
 *
 * Phase 10：同时重排**未来约定见面到点通知**（[MeetupNotificationService]）——精确闹钟不跨重启，开机后按真理源
 * 里仍 confirmed-future 的约定重烤（前台另有 [com.situ.aichat.ui.AppViewModel.onAppForeground] 兜：force-stop 场景）。
 *
 * W14：同时重排**世界在途到达闹钟**（[WorldNotifyService.rescheduleArrivals]）——「TA 到达你的城」/「你到达目的地」精确闹钟
 * 不跨重启也不跨换机，开机 / 每日 / 备份导入后按 `world_travel` 里仍未到点的在途行重烤（否则重启或恢复备份后到达提醒永不触发）。
 *
 * 不强制联网：无网时日程驱动 LLM 自动回退到角色模板 / 保底文案，仍能烤出通知；日历 / 故事解锁 / 宠物 / 世界文案为静态，亦无需网络。
 */
@HiltWorker
class NotificationRescheduleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: NotificationScheduler,
    private val calendarNotificationScheduler: CalendarNotificationScheduler,
    private val storyUnlockNotificationScheduler: StoryUnlockNotificationScheduler,
    private val petReminderScheduler: PetReminderScheduler,
    private val meetupNotificationService: MeetupNotificationService,
    private val worldNotifyService: WorldNotifyService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        scheduler.scheduleAll()
        calendarNotificationScheduler.refreshForForeground()
        storyUnlockNotificationScheduler.refreshAllUnlockNotifications(System.currentTimeMillis())
        petReminderScheduler.rescheduleAll()
        meetupNotificationService.rescheduleAll()
        worldNotifyService.rescheduleArrivals(System.currentTimeMillis())
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "通知重排 worker 异常，将重试", e)
        Result.retry()
    }

    companion object {
        const val TAG = "NotifRescheduleWorker"
        const val UNIQUE_DAILY = "notif_reschedule_daily"
        const val UNIQUE_ONESHOT = "notif_reschedule_oneshot"
    }
}
