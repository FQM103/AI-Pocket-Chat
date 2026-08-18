package com.situ.aichat.ui.chat

import android.content.Context
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.StickerRepository
import com.situ.aichat.offline.OfflineMeetingAction
import com.situ.aichat.offline.OfflineMeetingActionType
import com.situ.aichat.offline.OfflineMeetingService
import com.situ.aichat.prompt.PromptBuilder.AssistantDeliveryMode
import com.situ.aichat.tts.TtsProviderType
import com.situ.aichat.tts.TtsService
import com.situ.aichat.tts.TtsVoiceProfile
import com.situ.aichat.tts.VoiceResponseChunker
import com.situ.aichat.tts.provider.MiniMaxVoiceTagsCapability
import com.situ.aichat.util.AudioStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ChatReplyDeliverer 行为测试——验证刀7 投递层协作者「真的能用」（不止编译过）。
 *
 * 手法：MockK 假掉 messageRepo/conversationRepo/characterRepo/stickerRepo/ttsService/offlineMeetingService/
 * calendarHandler/appContext；打字/递送/视图可见/错误流用真 MutableStateFlow；ReplyParser/MessageSplitter/
 * ContentFilterService/StickerService/AssistantResponsePreprocessor 用**真实现**（投递的清洗/分段/分发是被测行为）。
 * 语音路用 mockkObject(AudioStore)（只 unmockkObject 自己·绝不 unmockkAll 污染同 JVM 后续测试类）。
 * 覆盖：文字 happy(落库+预览+旗标归位)/空正文返空不落库/线下单段标线下刷活动时间/immediate 兜底单条不记心情/
 * 情绪标签三处写入/语音 happy 落语音条重置轮次/语音合成失败退回文字报错/打字槽开关/线下邀约结构化回合分发卡。
 */
class ChatReplyDelivererTest {

    private lateinit var messageRepo: MessageRepository
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var characterRepo: CharacterRepository
    private lateinit var stickerRepo: StickerRepository
    private lateinit var ttsService: TtsService
    private lateinit var offlineMeetingService: OfflineMeetingService
    private lateinit var calendarHandler: ChatCalendarActionHandler
    private lateinit var appContext: Context
    private lateinit var errorFlow: MutableStateFlow<String?>
    private lateinit var isDelivering: MutableStateFlow<Boolean>
    private lateinit var pendingAssistantSlot: MutableStateFlow<TypingSlot?>
    private lateinit var isViewVisible: MutableStateFlow<Boolean>
    private lateinit var deliverer: ChatReplyDeliverer

    private val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)

    private fun convo(offline: Boolean = false, sessionId: String? = null) = ConversationEntity(
        uuid = "conv-1", title = "标题", characterUuid = "c1", creationDate = 0L,
        isInOfflineMode = offline, currentOfflineSessionId = sessionId,
    )

    /** 默认单段（min=max=1）便于确定性断言；个别用例覆盖。 */
    private fun settings(min: Int = 1, max: Int = 1) = AppSettings(replySegmentMin = min, replySegmentMax = max)

    private fun voicePlan(isVoice: Boolean) = VoicePlan(
        plan = AssistantDeliveryPlan(
            if (isVoice) AssistantDeliveryMode.VOICE else AssistantDeliveryMode.TEXT,
            if (isVoice) AssistantDeliveryReason.SCHEDULED_VOICE else AssistantDeliveryReason.TEXT,
        ),
        capability = MiniMaxVoiceTagsCapability(
            providerType = TtsProviderType.SYSTEM, modelName = "m", characterHasRemoteVoice = false,
            userToggleEnabled = false, isVoiceMode = isVoice, isOfflineMode = false,
        ),
        config = null,
        profile = TtsVoiceProfile(),
        apiKey = "",
        roundsSinceLastVoice = 0,
        threshold = 3,
    )

    @Before
    fun setUp() {
        messageRepo = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        stickerRepo = mockk(relaxed = true)
        ttsService = mockk(relaxed = true)
        offlineMeetingService = mockk(relaxed = true)
        calendarHandler = mockk(relaxed = true)
        appContext = mockk(relaxed = true)
        every { appContext.getString(any<Int>()) } returns "语音合成失败"
        coEvery { stickerRepo.getAllForPrompt() } returns emptyList()
        coEvery { conversationRepo.get("conv-1") } returns convo()
        errorFlow = MutableStateFlow(null)
        isDelivering = MutableStateFlow(false)
        pendingAssistantSlot = MutableStateFlow(null)
        isViewVisible = MutableStateFlow(false) // 不可见 → 跳过打字延迟，测试秒级跑完
        deliverer = ChatReplyDeliverer(
            appContext = appContext,
            conversationUuid = "conv-1",
            messageRepo = messageRepo,
            conversationRepo = conversationRepo,
            characterRepo = characterRepo,
            stickerRepo = stickerRepo,
            ttsService = ttsService,
            offlineMeetingService = offlineMeetingService,
            calendarHandler = calendarHandler,
            errorFlow = errorFlow,
            isDelivering = isDelivering,
            pendingAssistantSlot = pendingAssistantSlot,
            isViewVisible = isViewVisible,
        )
    }

    @Test
    fun 文字happy_落库一条_写会话预览_递送旗标归位() = runBlocking {
        val slot = mutableListOf<MessageEntity>()
        val result = deliverer.deliverAssistantReply(
            "你好呀今天过得怎么样", character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = null,
        )
        coVerify { messageRepo.upsert(capture(slot)) }
        assertEquals(1, slot.size)
        assertEquals("你好呀今天过得怎么样", slot[0].content)
        assertEquals("assistant", slot[0].roleRaw)
        assertEquals(1, result.messages.size)
        coVerify { conversationRepo.recordLastMessage("conv-1", any(), "assistant", any()) }
        assertFalse(isDelivering.value)
        assertNull(pendingAssistantSlot.value) // 打字占位=渲染层唯一「打字中」信号（S4：旧布尔链已删）
        coVerify(exactly = 0) { conversationRepo.recordMood(any(), any(), any(), any()) } // 无情绪标签 → 不记心情
    }

    @Test
    fun 空正文_返回空_不落库() = runBlocking {
        val result = deliverer.deliverAssistantReply(
            "", character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = null,
        )
        assertTrue(result.messages.isEmpty())
        assertFalse(result.deliveredStructuredAction)
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }

    @Test
    fun 线下模式_单段_标线下_刷活动时间不写列表预览() = runBlocking {
        coEvery { conversationRepo.get("conv-1") } returns convo(offline = true, sessionId = "sess-1")
        val slot = mutableListOf<MessageEntity>()
        deliverer.deliverAssistantReply(
            "我们走在街上。一起聊着天。", character, settings(min = 2, max = 4),
            dotsAppearMillis = 0L, immediate = false, voicePlan = null,
        )
        coVerify { messageRepo.upsert(capture(slot)) }
        assertEquals(1, slot.size) // 线下恒单段（不分句），无论分段范围
        assertTrue(slot[0].isOfflineMode)
        assertEquals("sess-1", slot[0].offlineSessionId)
        coVerify { conversationRepo.touchLastMessageDate("conv-1", any()) }
        coVerify(exactly = 0) { conversationRepo.recordLastMessage(any(), any(), any(), any()) }
    }

    @Test
    fun immediate兜底_整段单条不分句_不记心情() = runBlocking {
        val slot = mutableListOf<MessageEntity>()
        deliverer.deliverAssistantReply(
            "第一句。第二句。第三句。", character, settings(min = 2, max = 4),
            dotsAppearMillis = 0L, immediate = true, voicePlan = null,
        )
        coVerify { messageRepo.upsert(capture(slot)) }
        assertEquals(1, slot.size) // immediate=取消兜底 → 整条合并单泡
        coVerify(exactly = 0) { conversationRepo.recordMood(any(), any(), any(), any()) }
    }

    @Test
    fun 含情绪标签_记录心情到会话和角色() = runBlocking {
        deliverer.deliverAssistantReply(
            "你好呀[情绪:😊|yellow|开心]", character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = null,
        )
        coVerify { conversationRepo.recordMood("conv-1", "😊", "开心", any()) }
        coVerify { characterRepo.updateMood("c1", "😊", "开心", any()) }
        coVerify { characterRepo.appendMoodHistory("c1", any(), 200) } // moodHistoryMaxCount 默认 200
    }

    @Test
    fun 语音happy_合成成功_落语音条_轮次清零() = runBlocking {
        // VoiceResponseChunker 内部用 android.icu（真 Android 框架），纯 JVM 跑不动 → mockkObject 隔离（chunker 自有测试覆盖）。
        mockkObject(AudioStore)
        mockkObject(VoiceResponseChunker)
        try {
            every { VoiceResponseChunker.chunkForVoice(any(), any(), any()) } returns listOf("用语音说句话")
            coEvery { ttsService.synthesize(any(), any(), any(), any(), any()) } returns ByteArray(10)
            coEvery { AudioStore.saveBytes(any(), any(), any()) } returns "audio/x.mp3"
            coEvery { AudioStore.durationSeconds(any()) } returns 3.0
            val slot = mutableListOf<MessageEntity>()
            deliverer.deliverAssistantReply(
                "用语音说句话", character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = voicePlan(isVoice = true),
            )
            coVerify { messageRepo.upsert(capture(slot)) }
            val voiceMsg = slot.first { it.isVoiceMessage }
            assertEquals("audio/x.mp3", voiceMsg.audioRelativePath)
            assertEquals(3.0, voiceMsg.audioDuration!!, 0.0001)
            coVerify { conversationRepo.updateVoiceRounds("conv-1", 0, any()) } // 语音发出 → 轮次清 0
        } finally {
            unmockkObject(AudioStore)
            unmockkObject(VoiceResponseChunker)
        }
    }

    @Test
    fun 语音合成失败_退回文字_置错误提示() = runBlocking {
        mockkObject(VoiceResponseChunker)
        try {
            every { VoiceResponseChunker.chunkForVoice(any(), any(), any()) } returns listOf("用语音说句话")
            coEvery { ttsService.synthesize(any(), any(), any(), any(), any()) } returns null // 合成失败
            val slot = mutableListOf<MessageEntity>()
            deliverer.deliverAssistantReply(
                "用语音说句话", character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = voicePlan(isVoice = true),
            )
            coVerify { messageRepo.upsert(capture(slot)) }
            assertTrue(slot.none { it.isVoiceMessage }) // 退回纯文字
            assertEquals("语音合成失败", errorFlow.value)
        } finally {
            unmockkObject(VoiceResponseChunker)
        }
    }

    @Test
    fun 打字槽_开则发布占位_关则清空() {
        deliverer.openTypingSlot()
        assertNotNull(pendingAssistantSlot.value)
        deliverer.closeTypingSlot()
        assertNull(pendingAssistantSlot.value)
    }

    @Test
    fun 线下邀约动作_分发卡_算结构化回合_无正文不落库() = runBlocking {
        val action = OfflineMeetingAction(
            action = OfflineMeetingActionType.SUGGEST_MEETING, location = "咖啡馆", activity = "喝咖啡",
        )
        val result = deliverer.deliverAssistantReply(
            "", character, settings(), dotsAppearMillis = 0L, immediate = false, voicePlan = null,
            toolOfflineActions = listOf(action), hasOfflineMeetingToolCall = true,
        )
        assertTrue(result.deliveredStructuredAction)
        assertTrue(result.messages.isEmpty())
        coVerify { offlineMeetingService.handleSuggestMeeting("conv-1", action, emotionTag = null) }
        coVerify(exactly = 0) { messageRepo.upsert(any()) }
    }
}
