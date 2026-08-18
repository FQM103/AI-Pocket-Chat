package com.situ.aichat.diagnostics.perf

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.DiaryDao
import com.situ.aichat.data.local.dao.LogDao
import com.situ.aichat.data.local.dao.MeetingAppointmentDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.MomentDao
import com.situ.aichat.data.local.dao.NotificationDeliveryDao
import com.situ.aichat.data.local.dao.StoryDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * T2-2 的 E24 分支（图纸 2026-07-30 §5）：[ScaleSnapshot] 取数超时 / 出错一律记 `-1`，
 * 「采集绝不成为新的性能问题」。断言从规格独立反推（-1 = 没量到，与真实的 0 可区分）。
 */
class ScaleSnapshotTest {

    private lateinit var characterDao: CharacterDao
    private lateinit var messageDao: MessageDao
    private lateinit var meetingAppointmentDao: MeetingAppointmentDao
    private lateinit var notificationDeliveryDao: NotificationDeliveryDao
    private lateinit var diaryDao: DiaryDao
    private lateinit var momentDao: MomentDao
    private lateinit var storyDao: StoryDao
    private lateinit var logDao: LogDao
    private lateinit var snapshot: ScaleSnapshot

    @Before
    fun setUp() {
        characterDao = mockk()
        messageDao = mockk()
        meetingAppointmentDao = mockk()
        notificationDeliveryDao = mockk()
        diaryDao = mockk()
        momentDao = mockk()
        storyDao = mockk()
        logDao = mockk()
        snapshot = ScaleSnapshot(
            characterDao, messageDao, meetingAppointmentDao, notificationDeliveryDao,
            diaryDao, momentDao, storyDao, logDao,
        )
        coEvery { characterDao.count() } returns 7
        coEvery { messageDao.countAll() } returns 12_345
        coEvery { characterDao.countInWorld() } returns 3
        coEvery { meetingAppointmentDao.countAll() } returns 21
        coEvery { notificationDeliveryDao.countAll() } returns 88
        coEvery { diaryDao.countAll() } returns 40
        coEvery { momentDao.countAllPosts() } returns 55
        coEvery { storyDao.countAllChapters() } returns 66
        coEvery { logDao.count() } returns 99
    }

    @Test
    fun `正常取数逐字段对号入座`() = runBlocking {
        assertEquals(
            ScaleNumbers(
                characters = 7, messages = 12_345, worldResidents = 3, meetingAppointments = 21,
                notificationRecords = 88, diaryEntries = 40, momentPosts = 55, storyChapters = 66,
                logEntries = 99,
            ),
            snapshot.capture(),
        )
    }

    @Test
    fun `任一查询抛异常时整份记 -1 而不是崩`() = runBlocking {
        coEvery { messageDao.countAll() } throws IOException("db busy")

        assertEquals(ScaleNumbers.UNAVAILABLE, snapshot.capture())
    }

    @Test
    fun `取数超时时整份记 -1（E24）`() = runBlocking {
        coEvery { messageDao.countAll() } coAnswers {
            delay(ScaleSnapshot.SCALE_SNAPSHOT_TIMEOUT_MS + 500)
            1
        }

        assertEquals(ScaleNumbers.UNAVAILABLE, snapshot.capture())
    }

    @Test
    fun `UNAVAILABLE 的每个字段都是 -1（与真实的 0 可区分）`() {
        val u = ScaleNumbers.UNAVAILABLE
        assertEquals(
            listOf(-1, -1, -1, -1, -1, -1, -1, -1, -1),
            listOf(
                u.characters, u.messages, u.worldResidents, u.meetingAppointments,
                u.notificationRecords, u.diaryEntries, u.momentPosts, u.storyChapters, u.logEntries,
            ),
        )
    }
}
