package com.situ.aichat.prompt.diary

import android.content.Context
import com.situ.aichat.R
import com.situ.aichat.data.calendar.CalendarReader
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.UserProfileDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.OfflineMeetingMemoryRepository
import com.situ.aichat.data.repository.PetRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T2 端到端接线守卫（补 2026-07-13 优化的复核缺口）：只有纯函数 [DiaryGenerationService.formatChatGroups]
 * 与 builder sentinel 被独立覆盖，但**「按角色列举→每角色各自取→角色名解析」的 DAO 接线 + `composePersona` 的
 * bio/city 拼装 + 分组喂进 builder 的整链**此前无覆盖（改实现测试不红的假绿面）。这里 mock 全部 DAO/repo，跑真 [generateDraft]，
 * 截获实发给 LLM 的 system prompt，断言从**设计规格**独立反推（非照抄实现）：入戏开场 / 关于我 / 多角色分组真名 /
 * 用户恒「我」 / 要求段在底部 / 1000 字。
 */
class DiaryGenerationServiceTest {

    private val context = mockk<Context>(relaxed = true)
    private val contextLog = mockk<ContextLogService>()
    private val apiConfigRepo = mockk<ApiConfigRepository>()
    private val messageDao = mockk<MessageDao>()
    private val userProfileDao = mockk<UserProfileDao>()
    private val calendarReader = mockk<CalendarReader>()
    private val settingsRepo = mockk<SettingsRepository>()
    private val petRepository = mockk<PetRepository>()
    private val offlineMeetingMemoryRepository = mockk<OfflineMeetingMemoryRepository>()
    private val characterRepo = mockk<CharacterRepository>()

    private val service = DiaryGenerationService(
        context = context, contextLog = contextLog, apiConfigRepo = apiConfigRepo,
        messageDao = messageDao, userProfileDao = userProfileDao,
        calendarReader = calendarReader, settingsRepo = settingsRepo, petRepository = petRepository,
        offlineMeetingMemoryRepository = offlineMeetingMemoryRepository, characterRepo = characterRepo,
    )

    private fun msg(conv: String, role: String, content: String, ts: Long) = MessageEntity(
        messageUUID = "m$ts", conversationUuid = conv, roleRaw = role, content = content,
        timestamp = ts, messageKindRaw = MessageKind.PLAIN_TEXT.raw,
    )

    @Before fun setUp() {
        mockkObject(LocaleManager)
        every { LocaleManager.wrap(any()) } returns context
        // 默认所有资源占位为 "s"；只把参与断言的关键串换成真模板（其余不影响本测）。
        every { context.getString(any()) } returns "s"
        every { context.getString(R.string.diary_prompt_intro) } returns "你就是「%1\$s」。"
        every { context.getString(R.string.diary_prompt_persona_header) } returns "## 关于我"
        every { context.getString(R.string.diary_prompt_persona_city) } returns "我现在住在%1\$s。"
        every { context.getString(R.string.diary_prompt_chat_summary_header) } returns "## 今日聊天记录摘要"
        every { context.getString(R.string.diary_chat_group_header) } returns "### 今天和 %1\$s 聊了"
        every { context.getString(R.string.diary_role_me) } returns "我"
        every { context.getString(R.string.diary_role_other) } returns "对方"
        every { context.getString(R.string.diary_chat_line) } returns "[%1\$s] %2\$s：%3\$s"
        every { context.getString(R.string.diary_prompt_requirements_header) } returns "## 要求"
        every { context.getString(R.string.diary_prompt_word_count) } returns "- 全文约 %1\$s 字"

        coEvery { apiConfigRepo.resolveConfigValues(any()) } returns mockk<ApiConfigValues>()
        coEvery { settingsRepo.getAppSettings() } returns AppSettings()
        coEvery { userProfileDao.get() } returns UserProfileEntity(nickname = "小明", bio = "我是个爱猫的人", cityName = "上海")
        every { calendarReader.hasPermission() } returns false
        coEvery { petRepository.getAll() } returns emptyList()
        coEvery { offlineMeetingMemoryRepository.meetingsOnDay(any(), any()) } returns emptyList()
        // 两个角色（按各自首条消息时间序）·各自取（每角色独立·不被挤占）。
        coEvery { messageDao.characterUuidsWithMessagesInRange(any(), any()) } returns listOf("c1", "c2")
        coEvery { messageDao.messagesForCharacterInRange("c1", any(), any(), any()) } returns listOf(
            msg("cv1", "user", "你睡了吗？", 1L),
            msg("cv1", "assistant", "被你吵醒了", 2L),
        )
        coEvery { messageDao.messagesForCharacterInRange("c2", any(), any(), any()) } returns listOf(
            msg("cv2", "user", "早呀", 3L),
            msg("cv2", "assistant", "今天想去爬山", 4L),
        )
        coEvery { characterRepo.get("c1") } returns CharacterEntity(uuid = "c1", name = "夏晴子", creationDate = 0L)
        coEvery { characterRepo.get("c2") } returns CharacterEntity(uuid = "c2", name = "小满", creationDate = 0L)
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns "今天很平静。\nMOOD: 😌"
    }

    @After fun tearDown() = unmockkObject(LocaleManager)

    @Test fun `assembled prompt — embodiment, persona from bio+city, per-character grouping, requirements at bottom`(): Unit = runBlocking {
        val sent = slot<List<ChatMessageDto>>()
        coEvery {
            contextLog.completion(any(), any(), any(), capture(sent), any(), any(), any(), any(), any())
        } returns "今天很平静。\nMOOD: 😌"

        service.generateDraft(dateMillis = 1_700_000_000_000L)

        val system = sent.captured.first().content.orEmpty()

        // 1) 入戏开场用真实用户名。
        assertTrue("入戏开场", system.contains("你就是「小明」。"))
        // 2) 关于我 = bio 原文 + 城市行（composePersona 接线）。
        assertTrue("关于我标题", system.contains("## 关于我"))
        assertTrue("bio 原文注入", system.contains("我是个爱猫的人"))
        assertTrue("城市行拼装", system.contains("我现在住在上海。"))
        // 3) 按角色分组：两个小标题带各自真名（会话→角色→名字接线打通）。
        assertTrue("夏晴子分组标题", system.contains("### 今天和 夏晴子 聊了"))
        assertTrue("小满分组标题", system.contains("### 今天和 小满 聊了"))
        // 4) 角色消息挂各自真名、用户恒「我」（张冠李戴守卫）。
        assertTrue(system.contains("夏晴子：被你吵醒了"))
        assertTrue(system.contains("小满：今天想去爬山"))
        assertTrue(system.contains("我：你睡了吗？"))
        assertTrue(system.contains("我：早呀"))
        assertFalse("小满的话绝不挂夏晴子名下", system.contains("夏晴子：今天想去爬山"))
        // 5) 段序：关于我(顶) → 聊天摘要(中) → 要求(底)。
        assertTrue("关于我在聊天摘要之前", system.indexOf("## 关于我") < system.indexOf("## 今日聊天记录摘要"))
        assertTrue("要求段在聊天摘要之后（底部）", system.indexOf("## 今日聊天记录摘要") < system.indexOf("## 要求"))
        // 6) 字数默认 1000。
        assertTrue("字数 1000", system.contains("全文约 1000 字"))
        // 7) 保险丝边界内（2 角色 ≤4）：每角色份额仍是完整 CHAT_TAKE=150。
        coVerify { messageDao.messagesForCharacterInRange("c1", any(), any(), 150) }
    }

    @Test fun `chat material fuse — six characters split the budget, DAO asked for 100 each`(): Unit = runBlocking {
        // 保险丝接线（2026-07-13 复核 🔵-3 用户拍板）：>4 角色时每角色份额=600/n（6→100），只均摊缩水、绝不整组丢角色。
        val six = listOf("c1", "c2", "c3", "c4", "c5", "c6")
        coEvery { messageDao.characterUuidsWithMessagesInRange(any(), any()) } returns six
        coEvery { messageDao.messagesForCharacterInRange(any(), any(), any(), any()) } returns emptyList()
        coEvery { characterRepo.get(any()) } returns null

        service.generateDraft(dateMillis = 1_700_000_000_000L)

        six.forEach { uuid ->
            coVerify { messageDao.messagesForCharacterInRange(uuid, any(), any(), 100) }
        }
    }

    @Test fun `persona section omitted entirely when bio and city both empty`(): Unit = runBlocking {
        coEvery { userProfileDao.get() } returns UserProfileEntity(nickname = "小明", bio = "", cityName = null)
        val sent = slot<List<ChatMessageDto>>()
        coEvery {
            contextLog.completion(any(), any(), any(), capture(sent), any(), any(), any(), any(), any())
        } returns "今天很平静。\nMOOD: 😌"

        service.generateDraft(dateMillis = 1_700_000_000_000L)

        val system = sent.captured.first().content.orEmpty()
        assertTrue("仍有入戏开场", system.contains("你就是「小明」。"))
        assertFalse("bio/city 皆空 → 无关于我段", system.contains("## 关于我"))
    }
}
