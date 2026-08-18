package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「私下反差」AI 起草 T2（卷二 §7 T2-4）：物料 P / user message 模板**逐字锁**（测试里重新打字，
 * 绝不引用实现常量拼装）+ 四条失败路（E7：无配置 / 调用抛异常 / 返回空 / 只返回思考标签）+ 调用参数锁。
 */
class StoryPersonaDrafterTest {

    private val storyRepository = mockk<StoryRepository>()
    private val collector = mockk<StoryCharacterDataCollector>()
    private val apiConfigs = mockk<ApiConfigRepository>()
    private val contextLog = mockk<ContextLogService>()

    private val drafter = StoryPersonaDrafter(storyRepository, collector, apiConfigs, contextLog)

    private fun config(thinking: Boolean = false) = ApiConfigValues(
        providerType = ApiProviderType.OPENAI_COMPATIBLE,
        apiKey = "k",
        baseUrl = "https://example.com",
        modelName = "m",
        isThinkingModel = thinking,
    )

    private fun givenConfig(thinking: Boolean = false) {
        coEvery { apiConfigs.resolveConfigValues(any()) } returns config(thinking)
        coEvery { storyRepository.getStory("s1") } returns StoryEntity(id = "s1", genre = "都市")
        coEvery { collector.collectCharacterData(any(), any()) } returns emptyMap()
    }

    private fun givenResponse(text: String): CapturedCall {
        val messages = slot<List<ChatMessageDto>>()
        val source = slot<String>()
        val temperature = slot<Double>()
        val maxTokens = slot<Int>()
        coEvery {
            contextLog.completion(
                source = capture(source),
                characterName = any(),
                config = any(),
                messages = capture(messages),
                temperature = capture(temperature),
                maxTokens = capture(maxTokens),
                responseFormat = any(),
                segments = any(),
                onFinishReason = any(),
            )
        } returns text
        return CapturedCall(messages, source, temperature, maxTokens)
    }

    private class CapturedCall(
        val messages: io.mockk.CapturingSlot<List<ChatMessageDto>>,
        val source: io.mockk.CapturingSlot<String>,
        val temperature: io.mockk.CapturingSlot<Double>,
        val maxTokens: io.mockk.CapturingSlot<Int>,
    )

    private fun draft(name: String = "林晚晴", description: String = "部门主管，清冷疏离", characterId: String? = null) =
        runBlocking { drafter.draft("s1", characterId, name, description, 1_000L) }

    // ── 物料逐字锁 ──

    @Test fun 物料P_system提示词逐字() {
        assertEquals(
            "你是角色设定助手。根据这个故事角色的已有人设，起草一段「私下反差」设定：" +
                "写TA在私下场合与公开形象的反差——反应模式、语言习惯、行事方式。" +
                "80–150 字，只输出设定本身，不要解释、不要引号。",
            StoryPersonaDrafter.SYSTEM_PROMPT,
        )
        // 双保险：破折号与 en dash 都是全角/非 ASCII，别被编辑器悄悄换成 ASCII
        assertTrue(StoryPersonaDrafter.SYSTEM_PROMPT.contains("80–150 字"))
        assertFalse(StoryPersonaDrafter.SYSTEM_PROMPT.contains("80-150"))
    }

    @Test fun user模板_纯故事角色_三行() {
        assertEquals(
            "角色名：林晚晴\n已有人设：部门主管，清冷疏离\n本书题材：都市",
            StoryPersonaDrafter.buildUserMessage("林晚晴", "部门主管，清冷疏离", null, null, "都市"),
        )
    }

    @Test fun user模板_人设为空_写占位让模型合理想象() {
        assertEquals(
            "角色名：林晚晴\n已有人设：（未填写，按角色名与故事类型合理想象）\n本书题材：都市",
            StoryPersonaDrafter.buildUserMessage("林晚晴", "   ", null, null, "都市"),
        )
    }

    @Test fun user模板_关联聊天角色_追性格与背景各钳两百字() {
        val msg = StoryPersonaDrafter.buildUserMessage(
            roleName = "林晚晴",
            roleDescription = "主管",
            personality = "性".repeat(250),
            backstory = "背".repeat(250),
            genre = "都市",
        )
        val lines = msg.lines()
        assertEquals(5, lines.size)
        assertEquals("角色名：林晚晴", lines[0])
        assertEquals("已有人设：主管", lines[1])
        assertEquals("性格：" + "性".repeat(200), lines[2])
        assertEquals("背景：" + "背".repeat(200), lines[3])
        assertEquals("本书题材：都市", lines[4])
    }

    @Test fun user模板_性格背景为空白时整行不出() {
        assertEquals(
            "角色名：A\n已有人设：B\n本书题材：C",
            StoryPersonaDrafter.buildUserMessage("A", "B", "  ", "", "C"),
        )
    }

    // ── 调用参数锁（温度 / 额度 / 日志来源）──

    @Test fun 调用参数_温度零点七_额度五百_日志来源为故事生成() {
        givenConfig()
        val call = givenResponse("她私下只对他黏人")

        assertEquals("她私下只对他黏人", draft())

        assertEquals(0.7, call.temperature.captured, 0.0)
        assertEquals(500, call.maxTokens.captured)
        assertEquals(LogSource.STORY_GENERATION, call.source.captured)
        assertEquals(2, call.messages.captured.size)
        assertEquals("system", call.messages.captured[0].role)
        assertEquals(StoryPersonaDrafter.SYSTEM_PROMPT, call.messages.captured[0].content)
        assertEquals("user", call.messages.captured[1].role)
        assertEquals(
            "角色名：林晚晴\n已有人设：部门主管，清冷疏离\n本书题材：都市",
            call.messages.captured[1].content,
        )
    }

    @Test fun 调用参数_思考模型额度三倍() {
        givenConfig(thinking = true)
        val call = givenResponse("草稿")
        draft()
        assertEquals(1500, call.maxTokens.captured)
    }

    @Test fun 清洗_剥思考标签并trim() {
        givenConfig()
        givenResponse("<think>先想想</think>\n  她人前清冷，私下黏人  \n")
        assertEquals("她人前清冷，私下黏人", draft())
    }

    // ── E7 四条失败路 ──

    @Test fun e7_无API配置_返回null且一次都不调模型() {
        coEvery { apiConfigs.resolveConfigValues(any()) } returns null
        assertNull(draft())
        coVerify(exactly = 0) {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test fun e7_调用抛异常_吞掉返回null不崩() {
        givenConfig()
        coEvery {
            contextLog.completion(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("网络炸了")
        assertNull(draft())
    }

    @Test fun e7_模型返回空_返回null() {
        givenConfig()
        givenResponse("   \n ")
        assertNull(draft())
    }

    @Test fun e7_只返回思考标签_清洗后为空同样算失败() {
        givenConfig()
        givenResponse("<think>我在想</think>")
        assertNull(draft())
    }

    // ── 关联聊天角色：真去采集那张卡 ──

    @Test fun 关联聊天角色_采到性格背景后注进user消息() {
        givenConfig()
        coEvery { collector.collectCharacterData(any(), any()) } returns mapOf(
            "c1" to StoryCharacterSectionData(
                gender = "女", age = 27, occupation = "主管",
                appearanceDescription = "长发", personalityDescription = "外冷内热", backstory = "海归",
            ),
        )
        val call = givenResponse("草稿")

        draft(characterId = "c1")

        assertEquals(
            "角色名：林晚晴\n已有人设：部门主管，清冷疏离\n性格：外冷内热\n背景：海归\n本书题材：都市",
            call.messages.captured[1].content,
        )
    }

    @Test fun 纯故事角色_不去查聊天角色卡() {
        givenConfig()
        givenResponse("草稿")
        draft(characterId = null)
        coVerify(exactly = 0) { collector.collectCharacterData(any(), any()) }
    }
}
