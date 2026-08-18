package com.situ.aichat.offline

import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B2 · T2-B2（图纸「审计盲区补扫」）：线下见面写角色日程事件的 activity/location 第三人称指名。
 * 驱动 startManualOfflineMeeting → enterOfflineMode（db.withTransaction 真路径），捕获 scheduleDao.insertEvents
 * 写入的 ScheduleEventEntity，断言 activity/location 用真名——该事件 2-5 天后回流【最近几天做过什么】喂日程生成、
 * 且在日程时间线用户可见（V-b）。空昵称回退「用户」= 旧字节（E1·E4 首次线下无当日日程 → scheduleFor 返 null 建新日程）。
 * db.withTransaction stub 复用 MeetingMissedReactionServiceTest 先例（receiver=firstArg·block=secondArg）。
 */
class OfflineMeetingScheduleEventNamingTest {

    @Before fun setUp() = mockkStatic("androidx.room.RoomDatabaseKt")
    @After fun tearDown() = unmockkAll()

    /** db.withTransaction 直接执行 block（enterOfflineMode 返回 String?）。 */
    private fun db(): AppDatabase {
        val database = mockk<AppDatabase>()
        coEvery { database.withTransaction<String?>(any()) } coAnswers { secondArg<suspend () -> String?>().invoke() }
        return database
    }

    private fun convRepo(): ConversationRepository {
        val repo = mockk<ConversationRepository>(relaxed = true)
        val convo = mockk<ConversationEntity> {
            every { isInOfflineMode } returns false
            every { characterUuid } returns "char1"
        }
        coEvery { repo.get("conv1") } returns convo
        return repo
    }

    private fun charRepo(): CharacterRepository {
        val repo = mockk<CharacterRepository>()
        coEvery { repo.get("char1") } returns mockk<CharacterEntity> { every { cityName } returns "上海" }
        return repo
    }

    /** 驱动手动发起线下见面（E4：无当日日程 → 建新 → 追加事件），返回捕获的唯一日程事件。 */
    private fun startAndCaptureEvent(nickname: String?): ScheduleEventEntity {
        val captured = slot<List<ScheduleEventEntity>>()
        val scheduleDao = mockk<ScheduleDao>(relaxed = true)
        coEvery { scheduleDao.scheduleFor(any(), any()) } returns null // 无当日日程 → 建新
        coEvery { scheduleDao.eventsForSchedule(any()) } returns emptyList()
        coEvery { scheduleDao.insertEvents(capture(captured)) } returns Unit
        val userProfileDao = mockk<UserProfileDao>()
        coEvery { userProfileDao.get() } returns nickname?.let { UserProfileEntity(nickname = it) }

        val service = OfflineMeetingService(
            db(), mockk<MessageRepository>(relaxed = true), convRepo(), charRepo(), scheduleDao, userProfileDao,
        )
        runBlocking { service.startManualOfflineMeeting("conv1", location = "咖啡馆", activity = "喝咖啡") }
        return captured.captured.single()
    }

    @Test fun realNickname_activityAndLocationUseName() {
        val event = startAndCaptureEvent("小明")
        assertTrue("activity 用真名", event.activity.contains("与小明在咖啡馆喝咖啡"))
        assertTrue("location 用真名", event.location.contains("与小明的见面地点"))
        assertFalse("activity 无通用码", event.activity.contains("与用户"))
        assertFalse("location 无通用码", event.location.contains("与用户"))
    }

    @Test fun blankNickname_fallsBackToUser_E1() {
        val event = startAndCaptureEvent(null)
        assertTrue("activity 回退用户", event.activity.contains("与用户在咖啡馆喝咖啡"))
        assertTrue("location 回退用户", event.location.contains("与用户的见面地点"))
    }
}
