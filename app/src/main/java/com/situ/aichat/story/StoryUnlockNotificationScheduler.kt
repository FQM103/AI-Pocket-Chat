package com.situ.aichat.story

import android.util.Log
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.notification.NotificationAlarmScheduler
import com.situ.aichat.notification.NotificationPayload
import com.situ.aichat.notification.Notifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 章节「解锁」通知调度（11.1g-1，1:1 iOS `StoryScheduleService.scheduleUnlockNotification` :102-134）。
 *
 * 追更（chase）章节在 materialize 时算好 `unlockAt`（11.1e-5）；本调度器在 `unlockAt` 排一个精确闹钟，到点经
 * [NotificationAlarmReceiver] → [Notifier.postStory] 发「故事更新」渠道的解锁通知（即使 app 已被国行 ROM 杀掉，
 * 系统也会拉起接收者补发——最接近 iOS `UNCalendarNotificationTrigger`）。
 *
 * - requestKey = `storyUnlock_{storyId}_{chapterNumber}`（1:1 iOS identifier）：同章重新生成 → 同 key 覆盖旧闹钟/通知。
 * - 精确闹钟**不跨重启**，故另提供 [refreshAllUnlockNotifications]：按 DB 里仍未解锁的章（`unlockAt > now`）重排，
 *   由回前台/开机触发（接线在 11.1g-2 的启动 pass）。
 *
 * 调用方 = 11.1g-2 自动连载检查（生成追更章后 [scheduleUnlock]）+ 启动 pass（[refreshAllUnlockNotifications]）。
 */
@Singleton
class StoryUnlockNotificationScheduler @Inject constructor(
    private val alarmScheduler: NotificationAlarmScheduler,
    private val storyRepository: StoryRepository,
    private val readingProgressStore: StoryReadingProgressStore,
) {

    /**
     * 为一章排到点解锁通知（[unlockAt] = epoch millis）。同章重排覆盖（同 requestKey）。
     * per-story「更新提醒」总闸关时跳过排程（ST7c·§6.5·总闸落 [StoryReadingProgressStore.unlockReminderEnabled]）——
     * 排程与重排（[refreshAllUnlockNotifications] 内部走本函数）两条路都被 gate 覆盖。
     */
    fun scheduleUnlock(storyId: String, storyTitle: String, chapterNumber: Int, chapterTitle: String, unlockAt: Long) {
        if (!readingProgressStore.unlockReminderEnabled(storyId)) {
            Log.d(TAG, "更新提醒已关，跳过排程 story=${storyId.take(8)} ch=$chapterNumber")
            return
        }
        val requestKey = unlockRequestKey(storyId, chapterNumber)
        alarmScheduler.scheduleExact(
            requestKey = requestKey,
            triggerAtMillis = unlockAt,
            payload = NotificationPayload(
                notificationId = requestKey.hashCode(),
                title = StoryUnlockNotificationText.title(),
                body = StoryUnlockNotificationText.body(storyTitle, chapterNumber, chapterTitle),
                category = Notifier.CATEGORY_STORY_UNLOCK,
                requestKey = requestKey,
                storyId = storyId,
            ),
        )
        Log.d(TAG, "解锁通知已排 story=${storyId.take(8)} ch=$chapterNumber at=$unlockAt")
    }

    /**
     * 按 DB 里仍未解锁的章（`unlockAt > nowMillis`）重排所有解锁闹钟（精确闹钟不跨重启的兜底）。
     * 幂等：同 requestKey 覆盖。回前台/开机调用。
     */
    suspend fun refreshAllUnlockNotifications(nowMillis: Long) {
        val pending = storyRepository.getFutureUnlockChapters(nowMillis)
        for (row in pending) {
            scheduleUnlock(row.storyId, row.storyTitle, row.chapterNumber, row.chapterTitle, row.unlockAt)
        }
        Log.d(TAG, "解锁通知重排完成：${pending.size} 章")
    }

    /**
     * 撤销某故事全部章节解锁闹钟（删除书通路·2026-08-04 缺口修复，调用方 = [StoryDeleter]）。
     * [chapterNumbers] 由调用方在级联删库**前**捕获（闹钟 key 含章号，删后章行已不在查不到）；传**全部**章号
     * 而非仅未解锁章最稳——对从未排过/已触发的章号 cancel 是无操作（[NotificationAlarmScheduler.cancel]
     * FLAG_NO_CREATE 取不到即返回），还顺带清掉「追更→自由」清 unlockAt 后可能残留的旧闹钟。
     * 不看「更新提醒」总闸：闸只管排不排，撤销恒执行（闸关前排下的闹钟也得撤）。
     */
    fun cancelUnlocks(storyId: String, chapterNumbers: Collection<Int>) {
        for (n in chapterNumbers) alarmScheduler.cancel(unlockRequestKey(storyId, n))
        Log.d(TAG, "解锁闹钟已撤 story=${storyId.take(8)} chapters=${chapterNumbers.size}")
    }

    /**
     * 撤销某故事全部章节解锁闹钟——**章行还在库里**的场景用（追更→自由清 unlockAt·2026-08-04 相邻缺口）：
     * 自查章号再走 [cancelUnlocks]。删除通路**不能**用本函数（级联删库后章行已不在、查回空表），
     * 走 [StoryDeleter] 的「先捕章号后删库」。
     */
    suspend fun cancelUnlocksForStory(storyId: String) {
        cancelUnlocks(storyId, storyRepository.getChapterNumbers(storyId))
    }

    private fun unlockRequestKey(storyId: String, chapterNumber: Int): String = "storyUnlock_${storyId}_$chapterNumber"

    private companion object {
        const val TAG = "StoryUnlockNotif"
    }
}
