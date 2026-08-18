package com.situ.aichat.prompt.diary

import android.content.Context
import com.situ.aichat.R
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.local.entity.MonthlyReviewEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.DiaryRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.util.LocaleManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * T1+T2：月度回顾（R5·契约 §2 F4）。提示词哨兵序 + 心情快照编解码 round-trip + 服务守卫链
 * （幂等已有直取零 LLM / 无素材 Empty / happy 落库月首对齐 / 开关关兜底不动）。
 */
class MonthlyReviewTest {

    // MARK: - T1 纯函数

    @Test fun `prompt sentinel order, mood section omitted when empty`() {
        val full = MonthlyReviewService.buildPrompt(
            intro = "INTRO", reqHeader = "REQH", reqLines = listOf("R1", "R2"),
            linesHeader = "LH", lines = listOf("L1", "L2"),
            moodHeader = "MH", moodLine = "😊 2", outputOnly = "OO",
        )
        assertEquals(
            listOf("INTRO", "", "REQH", "R1", "R2", "", "LH", "L1", "L2", "", "MH", "😊 2", "", "OO")
                .joinToString("\n"),
            full,
        )
        val noMood = MonthlyReviewService.buildPrompt(
            intro = "INTRO", reqHeader = "REQH", reqLines = listOf("R1"),
            linesHeader = "LH", lines = listOf("L1"),
            moodHeader = "MH", moodLine = "", outputOnly = "OO",
        )
        assertEquals(listOf("INTRO", "", "REQH", "R1", "", "LH", "L1", "", "OO").joinToString("\n"), noMood)
    }

    @Test fun `mood counts encode-decode round trip`() {
        val counts = listOf("😊" to 8, "😌" to 6)
        val json = MonthlyReviewService.encodeMoodCounts(counts)
        assertEquals("{\"😊\":8,\"😌\":6}", json)
        assertEquals(counts, MonthlyReviewService.decodeMoodCounts(json))
        assertEquals(emptyList<Pair<String, Int>>(), MonthlyReviewService.decodeMoodCounts(""))
        assertEquals(emptyList<Pair<String, Int>>(), MonthlyReviewService.decodeMoodCounts("garbage"))
    }

    // MARK: - T2 服务守卫链（MockK 全假）

    private val context = mockk<Context>(relaxed = true)
    private val contextLog = mockk<ContextLogService>()
    private val apiConfigRepo = mockk<ApiConfigRepository>()
    private val diaryRepository = mockk<DiaryRepository>(relaxed = true)
    private val userProfileDao = mockk<UserProfileDao>()
    private val settingsRepo = mockk<SettingsRepository>()
    private val service = MonthlyReviewService(
        context = context, contextLog = contextLog, apiConfigRepo = apiConfigRepo,
        diaryRepository = diaryRepository, userProfileDao = userProfileDao, settingsRepo = settingsRepo,
    )

    private val zone: ZoneId = ZoneId.systemDefault()
    private val monthStart: Long =
        LocalDate.of(2026, 6, 1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun publishedEntry(day: Int, mood: String? = "😊") = DiaryEntryEntity(
        uuid = "e$day",
        content = "六月第 $day 天的日记正文",
        timestamp = LocalDate.of(2026, 6, day).atStartOfDay(zone).plusHours(21).toInstant().toEpochMilli(),
        moodEmoji = mood,
    )

    @Before fun setUp() {
        mockkObject(LocaleManager)
        every { LocaleManager.wrap(any()) } returns context
        every { context.getString(any()) } returns "s"
        every { context.getString(any(), *anyVararg()) } returns "s"
        coEvery { settingsRepo.getAppSettings() } returns AppSettings(diaryAutoGenerateEnabled = true)
        coEvery { userProfileDao.get() } returns null
        coEvery { apiConfigRepo.resolveConfigValues(any()) } returns mockk<ApiConfigValues>()
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "亲爱的你，这个月辛苦了。"
    }

    @After fun tearDown() = unmockkObject(LocaleManager)

    @Test fun `existing review returns Exists without any LLM call`(): Unit = runBlocking {
        val existing = MonthlyReviewEntity(uuid = "r", monthStartMillis = monthStart, content = "旧回顾")
        coEvery { diaryRepository.monthlyReviewFor(monthStart) } returns existing

        val result = service.generateForMonth(monthStart)

        assertTrue(result is MonthlyReviewService.Result.Exists)
        coVerify(exactly = 0) {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun `month without published user diaries yields Empty`(): Unit = runBlocking {
        coEvery { diaryRepository.monthlyReviewFor(monthStart) } returns null
        coEvery { diaryRepository.entriesInRange(any(), any()) } returns listOf(
            publishedEntry(5).copy(isDraft = true),                 // 草稿不算
            publishedEntry(6).copy(isPetDiary = true),              // 宠物不算
            publishedEntry(7).copy(authorCharacterUuid = "c1"),     // TA 的信不算
        )

        assertTrue(service.generateForMonth(monthStart) is MonthlyReviewService.Result.Empty)
        coVerify(exactly = 0) { diaryRepository.insertMonthlyReview(any()) }
    }

    @Test fun `happy path lands month-aligned review with mood snapshot`(): Unit = runBlocking {
        coEvery { diaryRepository.monthlyReviewFor(any()) } returns null
        coEvery { diaryRepository.entriesInRange(any(), any()) } returns
            listOf(publishedEntry(5), publishedEntry(6, mood = "😌"), publishedEntry(7))
        val saved = slot<MonthlyReviewEntity>()
        coEvery { diaryRepository.insertMonthlyReview(capture(saved)) } returns Unit

        // 传月中任意时刻也应归一化到月首。
        val midMonth = LocalDate.of(2026, 6, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        val result = service.generateForMonth(midMonth)

        assertTrue(result is MonthlyReviewService.Result.Success)
        assertEquals(monthStart, saved.captured.monthStartMillis)
        assertEquals("亲爱的你，这个月辛苦了。", saved.captured.content)
        assertEquals(listOf("😊" to 2, "😌" to 1), MonthlyReviewService.decodeMoodCounts(saved.captured.moodCountsJson))
    }

    @Test fun `review requirements use the letter-voice no-ai line, not the diary one`(): Unit = runBlocking {
        // POV 修复回归钉（2026-07-13 独立复核 67713387）：回顾是写给 TA 的「信」（第二人称），
        // 不得复用日记版 diary_prompt_no_ai——那条已改成「像只写给自己看的日记」，与信式语境矛盾。
        every { context.getString(R.string.diary_review_req_no_ai) } returns "REVIEW_NO_AI"
        every { context.getString(R.string.diary_prompt_no_ai) } returns "DIARY_NO_AI"
        coEvery { diaryRepository.monthlyReviewFor(any()) } returns null
        coEvery { diaryRepository.entriesInRange(any(), any()) } returns listOf(publishedEntry(5))
        val sent = slot<List<ChatMessageDto>>()
        coEvery {
            contextLog.completion(any(), any(), any(), capture(sent), any(), any(), any(), any(), any())
        } returns "亲爱的你，这个月辛苦了。"

        service.generateForMonth(monthStart)

        val system = sent.captured.first().content.orEmpty()
        assertTrue("要求段须用回顾专用「别露 AI」行", system.contains("REVIEW_NO_AI"))
        assertFalse("绝不再引用日记版 no_ai（信/日记语境冲突）", system.contains("DIARY_NO_AI"))
    }

    @Test fun `worker fallback respects the auto-generate switch`(): Unit = runBlocking {
        coEvery { settingsRepo.getAppSettings() } returns AppSettings(diaryAutoGenerateEnabled = false)

        service.checkAndGenerateLastMonth()

        coVerify(exactly = 0) { diaryRepository.monthlyReviewFor(any()) }
    }
}
