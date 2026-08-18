package com.situ.aichat.story

import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * 截断续写剥净内联思考（T2·MockK 假 LLM）。关键点：剥标签必须在**拼接前**对续写响应单独做——
 * 孤闭合 `</think>` 按新规则「连前文一起删」，若拼接后再剥会把前面的真正文一并误删；
 * 纯思考续写剥空 = 失败，按既有约定返回原内容不阻断保存。
 */
class StoryPayloadResolverContinuationThinkTest {

    private val config = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.test",
        modelName = "m",
    )

    private fun resolver(continuationResponse: String): StoryPayloadResolver {
        val contextLog = mockk<ContextLogService>()
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns continuationResponse
        return StoryPayloadResolver(mockk(), contextLog)
    }

    @Test
    fun 续写带孤闭合_只删续写里的思考_原正文完好() = runBlocking {
        val result = resolver("续写前的推理过程</think>，她终于开口了。")
            .requestContinuation("夜色渐深，他望着窗外", config, temperature = 1.0)
        assertEquals("夜色渐深，他望着窗外，她终于开口了。", result)
    }

    @Test
    fun 续写为纯思考_剥空视同失败_返回原内容() = runBlocking {
        val result = resolver("<think>想了半天没写出正文就被截断")
            .requestContinuation("夜色渐深，他望着窗外", config, temperature = 1.0)
        assertEquals("夜色渐深，他望着窗外", result)
    }

    /**
     * CE 放行（卷一 chunk 3）：续写那次调用抛协程取消 → **如实重抛**，不再当成「续写失败返回原内容」
     * （吞掉的话，已取消的生成会带着半截正文继续走到落库）。
     */
    @Test
    fun 续写遇协程取消_如实重抛不当成失败() {
        val contextLog = mockk<ContextLogService>()
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws CancellationException("取消")

        assertThrows(CancellationException::class.java) {
            runBlocking {
                StoryPayloadResolver(mockk(), contextLog)
                    .requestContinuation("夜色渐深，他望着窗外", config, temperature = 1.0)
            }
        }
    }
}
