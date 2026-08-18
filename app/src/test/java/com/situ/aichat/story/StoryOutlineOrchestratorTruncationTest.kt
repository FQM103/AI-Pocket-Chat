package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * 弧线大纲的**截断拒收 + 额度换算**（图纸一 D4 · §3.5）测试。
 *
 * 期望从规格独立反推：`finish_reason=length`（LlmClient 已自动升额 ×3 重试过一次仍撞顶）⇒ **半截大纲绝不落库**，
 * 走既有「失败不阻塞章节生成」路——初弧原样返回、换弧保留旧弧，下次触发再试；`stop`/null ⇒ 照常采纳落库。
 * 额度按 `preferredCompressionMaxTokens(2000, isThinkingModel)`：普通 2000 / 思考 6000。
 */
class StoryOutlineOrchestratorTruncationTest {

    private lateinit var storyRepository: StoryRepository
    private lateinit var contextLog: ContextLogService
    private lateinit var orchestrator: StoryOutlineOrchestrator

    private fun config(thinking: Boolean = false) = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE, apiKey = "k",
        baseUrl = "https://example.test", modelName = "m", isThinkingModel = thinking,
    )

    /** 无大纲的新书 ⇒ decideOutlineAction 必判 GenerateInitialArc。 */
    private val story = StoryEntity(id = "s1", title = "书", genre = "言情", storyOutline = null)

    @Before
    fun setUp() {
        storyRepository = mockk(relaxed = true)
        contextLog = mockk()
        orchestrator = StoryOutlineOrchestrator(storyRepository, contextLog, mockk<StoryCharacterDataCollector>().also {
            coEvery { it.collectCharacterData(any(), any()) } returns emptyMap()
        })
        coEvery { storyRepository.getRoles("s1") } returns emptyList()
    }

    /**
     * 让大纲那次 completion 返回 [text] 并向第 9 个参数（onFinishReason 可选尾参）回调 [finishReason]。
     * @return 捕获 maxTokens 实参的槽，供额度断言取值
     */
    private fun outlineYields(text: String, finishReason: String?): CapturingSlot<Int> {
        val maxTokensSlot = slot<Int>()
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), capture(maxTokensSlot), any(), any(), any())
        } answers {
            arg<((String?) -> Unit)?>(8)?.invoke(finishReason)
            text
        }
        return maxTokensSlot
    }

    @Test
    fun `截断的大纲绝不落库且原样返回旧故事`() = runBlocking {
        outlineYields("第一幕：主角进城。第二幕：他遇见了", finishReason = "length")

        val result = orchestrator.ensureOutline(story, chapterNumber = 1, nowMillis = 1_000L) { config() }

        assertNull("半截大纲不许进 storyOutline", result.storyOutline)
        assertEquals("故事原样返回（失败不阻塞章节生成）", story, result)
        coVerify(exactly = 0) { storyRepository.updateOutline(any(), any(), any()) }
    }

    @Test
    fun `兼容层的 max_tokens finish_reason 同样拒收`() = runBlocking {
        outlineYields("半截大纲", finishReason = "max_tokens")

        val result = orchestrator.ensureOutline(story, chapterNumber = 1, nowMillis = 1_000L) { config() }

        coVerify(exactly = 0) { storyRepository.updateOutline(any(), any(), any()) }
        assertNull(result.storyOutline)
    }

    @Test
    fun `正常收尾的大纲照常落库`() = runBlocking {
        outlineYields("第一幕：主角进城。第二幕：他遇见了旧友。第三幕：真相揭晓。", finishReason = "stop")

        val result = orchestrator.ensureOutline(story, chapterNumber = 1, nowMillis = 1_000L) { config() }

        assertEquals("第一幕：主角进城。第二幕：他遇见了旧友。第三幕：真相揭晓。", result.storyOutline)
        coVerify(exactly = 1) {
            storyRepository.updateOutline("s1", "第一幕：主角进城。第二幕：他遇见了旧友。第三幕：真相揭晓。", 1)
        }
    }

    @Test
    fun `没有 finish_reason 的服务商照常落库`() = runBlocking {
        outlineYields("完整大纲。", finishReason = null)

        orchestrator.ensureOutline(story, chapterNumber = 1, nowMillis = 1_000L) { config() }

        coVerify(exactly = 1) { storyRepository.updateOutline("s1", "完整大纲。", 1) }
    }

    /**
     * CE 放行（卷一 chunk 3）：大纲那次 LLM 抛协程取消 → **如实重抛**，不再被「失败不阻塞」吞成 null
     * （吞掉的话，已取消的生成会带着空大纲继续往下跑完整条管线）。
     */
    @Test
    fun `大纲生成遇协程取消_如实重抛不再吞成null`() {
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws CancellationException("取消")

        assertThrows(CancellationException::class.java) {
            runBlocking { orchestrator.ensureOutline(story, chapterNumber = 1, nowMillis = 1_000L) { config() } }
        }
        coVerify(exactly = 0) { storyRepository.updateOutline(any(), any(), any()) }
    }

    @Test
    fun `非思考模型额度为基础值 2000`() = runBlocking {
        val maxTokens = outlineYields("完整大纲。", finishReason = "stop")

        orchestrator.ensureOutline(story, chapterNumber = 1, nowMillis = 1_000L) { config(thinking = false) }

        assertEquals(2_000, maxTokens.captured)
    }

    @Test
    fun `思考模型额度乘三为 6000`() = runBlocking {
        val maxTokens = outlineYields("完整大纲。", finishReason = "stop")

        orchestrator.ensureOutline(story, chapterNumber = 1, nowMillis = 1_000L) { config(thinking = true) }

        assertEquals("思考+正文同吃预算，第一次就给足（复用压缩同款 ×3）", 6_000, maxTokens.captured)
    }
}
