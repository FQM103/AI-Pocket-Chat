package com.situ.aichat.story

import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.LlmClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * 第三级·元数据结构化的**接线命门测试**（图纸一 §7-T2-1 · 照 `StoryGenerationServiceBannedWiringTest` 哲学）。
 *
 * 核心命题：**正文永远来自代码切分侧，LLM 产物只能填元数据**。为此假 LLM 的响应里故意塞了一个
 * `content` 字段——若实现回退成「整文结构化 + decodePayload」，章节正文当场被模型改写版顶掉，
 * [structuring_never_replaces_content] 立刻变红。
 */
class StoryPayloadResolverStructuringTest {

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.test",
        modelName = "m",
    )

    /** 分隔符命中、但元数据是一整行 JSON blob ⇒ 逐行 key:value 解析拿不到 title/mood ⇒ 必落第三级（E9）。 */
    private val body = "正文段落，他推开了那扇门。房间里空无一人，只有窗帘在动。"
    private val rawWithUnparsableMetadata =
        "$body\n---METADATA---\n{\"title\":\"第七章\",\"mood\":\"tense\",\"summary\":\"他终于推开了门\"}"

    /** 假 LLM 的整理结果——**故意带 content 字段**，用来钉死「模型文本永不进正文」。 */
    private val structuredJson =
        """{"title":"第七章","teaser":null,"mood":"tense","hasChoice":false,"choicePrompt":null,""" +
            """"choiceOptions":null,"summary":"他终于推开了门","currentArc":"真相临近","characterStates":null,""" +
            """"openThreads":null,"nextChapterBeats":null,"isEnding":false,""" +
            """"content":"这是模型改写过的正文，绝不许出现在章节里。"}"""

    /** 必填齐（title/mood/正文）、质量字段缺 ⇒ 必走第二级·轻量补全（CE 放行用例取材）。 */
    private val rawMissingQualityFields = "正文开始，主角推开了门。\n---METADATA---\ntitle: 第一章\nmood: peaceful"

    private fun resolver(llmClient: LlmClient) = StoryPayloadResolver(llmClient, mockk(relaxed = true))

    /** E9 命门：第三级走完，正文必须**逐字节**等于代码切分出来的那段。 */
    @Test
    fun structuring_never_replaces_content() = runBlocking {
        val llmClient = mockk<LlmClient>()
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any()) } returns structuredJson

        val payload = resolver(llmClient).resolvePayload(rawWithUnparsableMetadata, chapterNumber = 7, structConfig = config)

        assertEquals("正文必须是代码切分侧那一份，一个字都不能少、一个字都不能改", body, payload.content)
    }

    /** E10：元数据由 LLM 补齐（base 侧 title/mood 恰恰缺）。 */
    @Test
    fun structuring_fills_metadata_from_llm() = runBlocking {
        val llmClient = mockk<LlmClient>()
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any()) } returns structuredJson

        val payload = resolver(llmClient).resolvePayload(rawWithUnparsableMetadata, chapterNumber = 7, structConfig = config)

        assertEquals("第七章", payload.title)
        assertEquals("tense", payload.mood)
        assertEquals("他终于推开了门", payload.summary)
        assertEquals("真相临近", payload.currentArc)
        assertEquals(false, payload.isEnding)
    }

    /** E10b：mood 过 [StoryMoods] 归一——词表外的怪值丢弃，由 buildPayload 兜 peaceful。 */
    @Test
    fun structuring_normalizes_mood() = runBlocking {
        val llmClient = mockk<LlmClient>()
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any()) } returns
            """{"title":"第七章","mood":"极度紧绷"}"""

        val payload = resolver(llmClient).resolvePayload(rawWithUnparsableMetadata, chapterNumber = 7, structConfig = config)

        assertEquals("第七章", payload.title)
        assertEquals("peaceful", payload.mood)
        assertEquals(body, payload.content)
    }

    /** E11-a：LLM 吐垃圾 → 最终兜底（默认元数据 + 代码切分正文），整次生成**不失败**。 */
    @Test
    fun garbage_response_falls_back_without_failing_generation() = runBlocking {
        val llmClient = mockk<LlmClient>()
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any()) } returns "我没办法整理这段元数据。"

        val payload = resolver(llmClient).resolvePayload(rawWithUnparsableMetadata, chapterNumber = 7, structConfig = config)

        assertEquals(body, payload.content)
        assertEquals("默认标题兜底", "第7章", payload.title)
        assertEquals("默认心情兜底", "peaceful", payload.mood)
        assertNull(payload.summary)
    }

    /** E11-b：LLM 返回空串（内部抛 EmptyResponse）→ 被吞掉走兜底，正文照样保住。 */
    @Test
    fun empty_response_falls_back_without_failing_generation() = runBlocking {
        val llmClient = mockk<LlmClient>()
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any()) } returns "   "

        val payload = resolver(llmClient).resolvePayload(rawWithUnparsableMetadata, chapterNumber = 7, structConfig = config)

        assertEquals(body, payload.content)
        assertEquals("第7章", payload.title)
    }

    /** E11-c：网络异常同样只损失元数据——旧整文路会把整章生成一起毁掉，新路不会。 */
    @Test
    fun network_failure_falls_back_without_failing_generation() = runBlocking {
        val llmClient = mockk<LlmClient>()
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any()) } throws RuntimeException("boom")

        val payload = resolver(llmClient).resolvePayload(rawWithUnparsableMetadata, chapterNumber = 7, structConfig = config)

        assertEquals(body, payload.content)
        assertEquals("第7章", payload.title)
    }

    /**
     * CE 放行·L3（卷一 chunk 3）：元数据结构化那次调用抛协程取消 → **如实重抛**，不再被 runCatching 吞成兜底。
     * 取消 ≠ 「LLM 整理失败」：吞掉它，已取消的生成会带着默认元数据继续走到落库。
     */
    @Test
    fun `第三级遇协程取消_如实重抛不走兜底`() {
        val llmClient = mockk<LlmClient>()
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any()) } throws CancellationException("取消")

        assertThrows(CancellationException::class.java) {
            runBlocking {
                resolver(llmClient).resolvePayload(rawWithUnparsableMetadata, chapterNumber = 7, structConfig = config)
            }
        }
    }

    /** CE 放行·L2（卷一 chunk 3）：轻量补全那次调用抛协程取消 → 同样如实重抛，不降级成「已有字段 + 默认值」。 */
    @Test
    fun `第二级轻量补全遇协程取消_如实重抛不降级`() {
        val llmClient = mockk<LlmClient>()
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any()) } throws CancellationException("取消")

        assertThrows(CancellationException::class.java) {
            runBlocking {
                resolver(llmClient).resolvePayload(rawMissingQualityFields, chapterNumber = 1, structConfig = config)
            }
        }
    }

    /** E12：压根没切出元数据（无分隔符、尾部也不命中）→ **零 LLM 调用**直接兜底，不白烧 token。 */
    @Test
    fun no_metadata_text_means_zero_llm_calls() = runBlocking {
        val llmClient = mockk<LlmClient>()
        val raw = "他推开门，屋里没有人。他站了很久，最后转身离开。"

        val payload = resolver(llmClient).resolvePayload(raw, chapterNumber = 3, structConfig = config)

        assertEquals(raw, payload.content)
        assertEquals("第3章", payload.title)
        coVerify(exactly = 0) { llmClient.completion(any(), any(), any(), any(), any(), any()) }
    }
}
