package com.situ.aichat.story

import android.util.Log
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * StoryArchiver T2（ST11 C4）：完结归档的守卫三态——语义从 ST10-4 的
 * `StoryBookshelfViewModel.archiveStory` 平移（只搬不改），期望仍从原始缺陷现象独立反推：
 * 长按/点按时刻的 UI 快照可能陈旧（PITFALLS 1b）→ 落库前必须 fresh 读；生成中归档会与生成落库赛跑 → 拒绝。
 *
 * 抽出后本类是**两个入口共用**的唯一实现（书架长按「完结归档」+ 阅读器建议卡「就此完结」），
 * 故守卫在此测一次即覆盖两处；两个 VM 各自只剩「结果 → toast」映射。
 */
class StoryArchiverTest {

    private val repository = mockk<StoryRepository>()
    private val taskManager = mockk<StoryGenerationTaskManager>()
    private val activeGenerations =
        MutableStateFlow<Map<String, StoryGenerationTaskManager.GenerationProgress>>(emptyMap())

    private val archiver = StoryArchiver(repository, taskManager)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { taskManager.activeGenerations } returns activeGenerations
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    private fun story(status: String) = StoryEntity(id = "s1", title = "书", status = status)

    @Test
    fun 连载中_标记完结并回ARCHIVED() = runBlocking {
        coEvery { repository.getStory("s1") } returns story(StoryStatus.SERIALIZING)
        coEvery { repository.updateStatus(any(), any(), any()) } returns Unit

        val result = archiver.archive("s1", nowMillis = 999L)

        assertEquals(StoryArchiver.Result.ARCHIVED, result)
        coVerify(exactly = 1) { repository.updateStatus("s1", StoryStatus.COMPLETED, 999L) }
    }

    /** 等待选择 / 已暂停 / 生成失败三态都可归档（= ST10-4 转换表的归档边）。 */
    @Test
    fun 等选与暂停与失败_均可归档() = runBlocking {
        coEvery { repository.updateStatus(any(), any(), any()) } returns Unit
        for (status in listOf(StoryStatus.WAITING_CHOICE, StoryStatus.PAUSED, StoryStatus.GENERATION_FAILED)) {
            coEvery { repository.getStory("s1") } returns story(status)
            assertEquals("$status 应可归档", StoryArchiver.Result.ARCHIVED, archiver.archive("s1", 1L))
        }
    }

    /** 幂等：已完结再归档 → 静默、零写库（重复点击不该重复刷 updatedAt/弹 toast）。 */
    @Test
    fun 已完结_幂等静默且零写库() = runBlocking {
        coEvery { repository.getStory("s1") } returns story(StoryStatus.COMPLETED)

        val result = archiver.archive("s1", nowMillis = 1L)

        assertEquals(StoryArchiver.Result.SKIPPED, result)
        coVerify(exactly = 0) { repository.updateStatus(any(), any(), any()) }
    }

    /** fresh 读发现状态已是 generating（UI 快照陈旧）→ 拒绝、零写库。 */
    @Test
    fun fresh读到生成中_拒绝且零写库() = runBlocking {
        coEvery { repository.getStory("s1") } returns story(StoryStatus.GENERATING)

        val result = archiver.archive("s1", nowMillis = 1L)

        assertEquals(StoryArchiver.Result.BUSY, result)
        coVerify(exactly = 0) { repository.updateStatus(any(), any(), any()) }
    }

    /** 状态还没翻到 generating、但手动生成任务已活跃 → 同样拒绝（两路守卫缺一不可）。 */
    @Test
    fun 手动生成任务活跃_拒绝且零写库() = runBlocking {
        coEvery { repository.getStory("s1") } returns story(StoryStatus.SERIALIZING)
        activeGenerations.value = mapOf("s1" to mockk())

        val result = archiver.archive("s1", nowMillis = 1L)

        assertEquals(StoryArchiver.Result.BUSY, result)
        coVerify(exactly = 0) { repository.updateStatus(any(), any(), any()) }
    }

    /** 故事已不存在（并发删除）→ 静默、零写库，绝不 NPE。 */
    @Test
    fun 故事不存在_静默且零写库() = runBlocking {
        coEvery { repository.getStory("s1") } returns null

        val result = archiver.archive("s1", nowMillis = 1L)

        assertEquals(StoryArchiver.Result.SKIPPED, result)
        coVerify(exactly = 0) { repository.updateStatus(any(), any(), any()) }
    }
}
