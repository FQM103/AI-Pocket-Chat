package com.situ.aichat.story

import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.LlmClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L2 轻量补全剥净内联 <think>（T2·MockK 假 LLM）。非流式 completion 不剥思考标签，而
 * [StoryMetadataParser] 逐行认「key: value」——思考里的草稿行（如 `openThreads: …`）会被当真字段
 * 合并进结果，且 characterStates/openThreads 会拼进 storyBible。修复 = 解析前
 * [StoryTextCleaning.cleanContentThinkingTags]。
 */
class StoryPayloadResolverMetadataThinkTest {

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.test",
        modelName = "m",
    )

    /** 必填齐（title/mood/正文）、质量字段缺 → resolvePayload 必走 L2 轻量补全。 */
    private val rawOutput = "正文开始，主角推开了门。\n---METADATA---\ntitle: 第一章\nmood: peaceful"

    @Test
    fun `L2补全响应剥净think标签_思考里的草稿字段行不被采信`() = runBlocking {
        val llmClient = mockk<LlmClient>()
        coEvery { llmClient.completion(any(), any(), any(), any(), any(), any()) } returns
            "<think>\n草稿先记：\nopenThreads: 思考草稿线头\n</think>\nsummary: 正式摘要\nhasChoice: false\nisEnding: false"
        val resolver = StoryPayloadResolver(llmClient, mockk(relaxed = true))

        val payload = resolver.resolvePayload(rawOutput, chapterNumber = 1, structConfig = config)

        assertEquals("正稿字段照常合并", "正式摘要", payload.summary)
        assertNull("思考草稿行不得混进 openThreads（会拼进 storyBible）", payload.openThreads)
    }
}
