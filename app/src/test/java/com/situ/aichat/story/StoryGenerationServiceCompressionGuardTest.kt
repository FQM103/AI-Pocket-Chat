package com.situ.aichat.story

import com.situ.aichat.data.local.dao.StoryChapterSummaryRow
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.repository.StoryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * R5#3 摘要压缩重试守卫行为测试（T2·ST3b）——验证「同一故事本进程内连续失败 2 次后不再白烧 LLM、
 * 成功一次即复位、不同故事互不影响、协程取消不计失败」。
 *
 * 被测对象随压缩域搬家改指 [StoryCompressionCoordinator]（2026-08-03 生成时序卷一 chunk 1·只搬不改，
 * 断言一字未动）；创作配置由 suspend 供给函数直接给出，路由本身由
 * [StoryGenerationServiceCompressionRoutingTest] 另钉。
 *
 * 手法：MockK 假掉 LlmClient / StoryRepository（compression 取材全链真跑：
 * buildNewSummariesBlock / buildCompressionPrompt 纯逻辑不 mock）；Log.* 走 returnDefaultValues。
 */
class StoryGenerationServiceCompressionGuardTest {

    private lateinit var llmClient: LlmClient
    private lateinit var storyRepository: StoryRepository
    private lateinit var coordinator: StoryCompressionCoordinator

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.test",
        modelName = "m",
    )

    @Before
    fun setUp() {
        llmClient = mockk()
        storyRepository = mockk()
        coordinator = StoryCompressionCoordinator(
            llmClient = llmClient,
            storyRepository = storyRepository,
            storyBibleCompressor = mockk(relaxed = true),
        )
        // 压缩取材全链就绪：故事在库、未压缩摘要区间非空 → 每次调用都走到 LLM。
        coEvery { storyRepository.getStory(any()) } answers { StoryEntity(id = firstArg(), title = "书") }
        coEvery { storyRepository.getChapterSummaries(any()) } returns listOf(
            StoryChapterSummaryRow(1, "第一章摘要内容"),
            StoryChapterSummaryRow(2, "第二章摘要内容"),
            StoryChapterSummaryRow(3, "第三章摘要内容"),
        )
        coEvery { storyRepository.updateCompressedSummary(any(), any(), any()) } just Runs
    }

    private fun stubLlmThrows() {
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("boom")
    }

    private fun stubLlmReturns(text: String) {
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } returns text
    }

    private fun compress(storyId: String) = runBlocking {
        coordinator.compressSummaryChainIfNeeded(storyId, currentChapter = 3) { config }
    }

    @Test
    fun `连续失败两次后第三次不再调压缩`() {
        stubLlmThrows()
        compress("s1")
        compress("s1")
        compress("s1") // 守卫拦下，不再烧 LLM
        coVerify(exactly = 2) { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { storyRepository.updateCompressedSummary(any(), any(), any()) }
    }

    @Test
    fun `成功一次即复位失败计数`() {
        stubLlmThrows()
        compress("s1") // 失败 1
        stubLlmReturns("压缩好的全局摘要")
        compress("s1") // 成功 → 复位
        coVerify(exactly = 1) { storyRepository.updateCompressedSummary("s1", "压缩好的全局摘要", 3) }
        stubLlmThrows()
        compress("s1") // 复位后重新计：失败 1
        compress("s1") // 失败 2
        compress("s1") // 守卫拦下
        coVerify(exactly = 4) { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) }
    }

    /** 非流式 completion 不剥内联 <think>——storySummary 落库后回注 prompt 并回喂下轮压缩，落库前必须剥净。 */
    @Test
    fun `压缩结果剥净think标签后落库`() {
        stubLlmReturns("<think>先按时间线合并。</think>压缩好的全局摘要")
        compress("s1")
        coVerify(exactly = 1) { storyRepository.updateCompressedSummary("s1", "压缩好的全局摘要", 3) }
    }

    @Test
    fun `不同故事互不影响`() {
        stubLlmThrows()
        compress("s1")
        compress("s1") // s1 锁定
        stubLlmReturns("另一部的压缩摘要")
        compress("s2") // s2 不受 s1 影响，照常压缩
        coVerify(exactly = 3) { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { storyRepository.updateCompressedSummary("s2", "另一部的压缩摘要", 3) }
        compress("s1") // s1 仍锁定
        coVerify(exactly = 3) { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `空响应同样计一次失败`() {
        stubLlmReturns("   ")
        compress("s1")
        compress("s1")
        compress("s1") // 两次空响应后锁定
        coVerify(exactly = 2) { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { storyRepository.updateCompressedSummary(any(), any(), any()) }
    }

    /** 截断防线（记忆护栏 G2 同款）：finish_reason=length（升额后仍被掐断，压缩现走创作槽·思考模型尤易）→
     * 非空的半截摘要绝不落库回喂，与空响应同路计一次失败（两次后锁定）。 */
    @Test
    fun `截断结果不落库_与空响应同样计一次失败`() {
        // completion 回调 onFinishReason("length")（末位实参）后返回非空半截文本。
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            lastArg<((String?) -> Unit)?>()?.invoke("length")
            "半截被掐断的摘要"
        }
        compress("s1")
        compress("s1")
        compress("s1") // 两次截断后锁定
        coVerify(exactly = 2) { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { storyRepository.updateCompressedSummary(any(), any(), any()) }
    }

    /**
     * 协程取消：不计失败（既有语义）+ **如实重抛**（卷一 chunk 3 语义升级——原来吞掉不抛，压缩 job 会以
     * 「正常完成」收场、日志把取消记成失败；重抛后 job 以取消完成，join 方读到的结局才是真的）。
     */
    @Test
    fun `协程取消不计失败_且如实重抛`() {
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) } throws CancellationException("取消")
        assertThrows(CancellationException::class.java) { compress("s1") }
        assertThrows(CancellationException::class.java) { compress("s1") }
        stubLlmThrows()
        compress("s1") // 两次取消没进熔断计数 ⇒ 未被锁定，仍会尝试
        coVerify(exactly = 3) { llmClient.completion(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { storyRepository.updateCompressedSummary(any(), any(), any()) }
    }
}
