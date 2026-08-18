package com.situ.aichat.diagnostics.perf

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.DiaryDao
import com.situ.aichat.data.local.dao.LogDao
import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.MomentDao
import com.situ.aichat.data.local.dao.NotificationDeliveryDao
import com.situ.aichat.data.local.dao.StoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 规模数（图纸 §3.2 逐字锁定字段名）。全 `-1` = 没取到（超时/出错），分析侧据此区分「真的是 0」与「没量到」。
 */
@Serializable
data class ScaleNumbers(
    val characters: Int,
    val messages: Int,
    val worldResidents: Int,
    val meetingAppointments: Int,
    val notificationRecords: Int,
    val diaryEntries: Int,
    val momentPosts: Int,
    val storyChapters: Int,
    val logEntries: Int,
) {
    companion object {
        /** 取数超时或失败的占位（§5 E24）。 */
        val UNAVAILABLE = ScaleNumbers(-1, -1, -1, -1, -1, -1, -1, -1, -1)
    }
}

/**
 * 规模数快照（图纸 §2.1）：一次性 COUNT 查询，**只在 flush 时刻取**，绝不常驻订阅任何 Flow ——
 * 否则采集本身就成了本轮要修的那类问题（图纸 §2.3 职责边界）。
 *
 * 超时护栏（§5 E24）：整趟取数超 [SCALE_SNAPSHOT_TIMEOUT_MS] 就整体放弃记 [ScaleNumbers.UNAVAILABLE]，
 * 保证「采集绝不成为新的性能问题」。全程 IO 线程。
 */
@Singleton
class ScaleSnapshot @Inject constructor(
    private val characterDao: CharacterDao,
    private val messageDao: MessageDao,
    private val meetingAppointmentDao: MeetingAppointmentDao,
    private val notificationDeliveryDao: NotificationDeliveryDao,
    private val diaryDao: DiaryDao,
    private val momentDao: MomentDao,
    private val storyDao: StoryDao,
    private val logDao: LogDao,
) {

    suspend fun capture(): ScaleNumbers = withContext(Dispatchers.IO) {
        withTimeoutOrNull(SCALE_SNAPSHOT_TIMEOUT_MS) {
            runCatching {
                ScaleNumbers(
                    characters = characterDao.count(),
                    messages = messageDao.countAll(),
                    // M17 的「入世角色数 N」= 已加入世界的正式角色（joinedWorld = 1）。
                    worldResidents = characterDao.countInWorld(),
                    meetingAppointments = meetingAppointmentDao.countAll(),
                    notificationRecords = notificationDeliveryDao.countAll(),
                    diaryEntries = diaryDao.countAll(),
                    momentPosts = momentDao.countAllPosts(),
                    storyChapters = storyDao.countAllChapters(),
                    logEntries = logDao.count(),
                )
            }.getOrDefault(ScaleNumbers.UNAVAILABLE)
        } ?: ScaleNumbers.UNAVAILABLE
    }

    companion object {
        /** 图纸 §9② 锁定值。 */
        const val SCALE_SNAPSHOT_TIMEOUT_MS = 2000L
    }
}
