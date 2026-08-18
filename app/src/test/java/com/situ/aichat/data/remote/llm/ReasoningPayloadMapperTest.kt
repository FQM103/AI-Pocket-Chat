package com.situ.aichat.data.remote.llm

import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.ThinkingBudgetLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * H3#1 测试网 · ReasoningPayloadMapper（思考预算请求字段分流）。
 * 规格：非思考模型/传输门 NONE/MiniMax → 全空（绝不带字段防 400）；DeepSeek 五档 thinking 映射
 * （low·medium→high、high→max）；OpenRouter reasoning.effort；OPENAI_COMPATIBLE 按 host 分流
 * （api.deepseek.com→DeepSeek 表 / volces·bigmodel·moonshot→enabled-disabled / api.x.ai 仅
 * grok-3-mini / dashscope·siliconflow→留空 / 未知 host→reasoning_effort）。
 */
class ReasoningPayloadMapperTest {

    private fun config(
        provider: ApiProviderType,
        level: ThinkingBudgetLevel,
        baseUrl: String = "https://api.example.com",
        model: String = "qwen3-max", // 已知思考模型 → 过中转站传输门
        thinking: Boolean = true,
    ) = ApiConfigValues(
        providerType = provider,
        apiKey = "k",
        baseUrl = baseUrl,
        modelName = model,
        thinkingBudgetLevel = level,
        isThinkingModel = thinking,
    )

    private val empty = ReasoningBudgetPayload()

    // MARK: - 顶层闸门

    @Test
    fun nonThinkingModel_emitsNothing() {
        val c = config(ApiProviderType.DEEPSEEK, ThinkingBudgetLevel.HIGH, thinking = false)
        assertEquals(empty, ReasoningPayloadMapper.payload(c))
    }

    @Test
    fun miniMax_builtInThinking_emitsNothing() {
        // MiniMax 传输门=NONE（模型自决思考深度）→ 不带任何字段。
        val c = config(ApiProviderType.MINIMAX, ThinkingBudgetLevel.HIGH)
        assertEquals(empty, ReasoningPayloadMapper.payload(c))
    }

    @Test
    fun openRouterAutoRouting_gateBlocks() {
        // openrouter/auto 路由可能换供应商 → 传输门 NONE。
        val c = config(ApiProviderType.OPENROUTER, ThinkingBudgetLevel.HIGH, model = "openrouter/auto")
        assertEquals(empty, ReasoningPayloadMapper.payload(c))
    }

    // MARK: - DeepSeek 五档表

    @Test
    fun deepSeek_levelTable() {
        fun payloadFor(level: ThinkingBudgetLevel) =
            ReasoningPayloadMapper.payload(config(ApiProviderType.DEEPSEEK, level, model = "deepseek-chat"))

        assertEquals(ThinkingParamDto(type = "disabled"), payloadFor(ThinkingBudgetLevel.OFF).thinking)
        assertEquals(ThinkingParamDto(type = "enabled"), payloadFor(ThinkingBudgetLevel.AUTO).thinking)
        assertEquals(
            ThinkingParamDto(type = "enabled", reasoningEffort = "high"),
            payloadFor(ThinkingBudgetLevel.LOW).thinking,
        )
        assertEquals(
            ThinkingParamDto(type = "enabled", reasoningEffort = "high"),
            payloadFor(ThinkingBudgetLevel.MEDIUM).thinking,
        )
        assertEquals(
            ThinkingParamDto(type = "enabled", reasoningEffort = "max"),
            payloadFor(ThinkingBudgetLevel.HIGH).thinking,
        )
    }

    // MARK: - OpenRouter

    @Test
    fun openRouter_effortMapping_offAndAutoOmit() {
        fun payloadFor(level: ThinkingBudgetLevel) =
            ReasoningPayloadMapper.payload(config(ApiProviderType.OPENROUTER, level, model = "anthropic/claude-sonnet-4.6"))

        assertEquals(empty, payloadFor(ThinkingBudgetLevel.OFF))
        assertEquals(empty, payloadFor(ThinkingBudgetLevel.AUTO))
        assertEquals(ReasoningParamDto(effort = "medium"), payloadFor(ThinkingBudgetLevel.MEDIUM).reasoning)
        assertEquals(ReasoningParamDto(effort = "high"), payloadFor(ThinkingBudgetLevel.HIGH).reasoning)
    }

    // MARK: - OPENAI_COMPATIBLE host 分流表

    private fun compat(baseUrl: String, level: ThinkingBudgetLevel, model: String = "qwen3-max") =
        ReasoningPayloadMapper.payload(config(ApiProviderType.OPENAI_COMPATIBLE, level, baseUrl, model))

    @Test
    fun compat_deepSeekHost_usesDeepSeekTable() {
        val p = compat("https://api.deepseek.com", ThinkingBudgetLevel.HIGH)
        assertEquals(ThinkingParamDto(type = "enabled", reasoningEffort = "max"), p.thinking)
    }

    @Test
    fun compat_volcesBigmodelMoonshot_enabledDisabledOnly() {
        for (host in listOf("ark.cn-beijing.volces.com", "open.bigmodel.cn", "api.moonshot.cn")) {
            val on = compat("https://$host/v1", ThinkingBudgetLevel.MEDIUM)
            assertEquals("host=$host", ThinkingParamDto(type = "enabled"), on.thinking)
            val off = compat("https://$host/v1", ThinkingBudgetLevel.OFF)
            assertEquals("host=$host", ThinkingParamDto(type = "disabled"), off.thinking)
        }
    }

    @Test
    fun compat_xai_onlyGrok3MiniGetsEffort() {
        val mini = compat("https://api.x.ai/v1", ThinkingBudgetLevel.LOW, model = "grok-3-mini")
        assertEquals("low", mini.reasoningEffort)
        val miniHigh = compat("https://api.x.ai/v1", ThinkingBudgetLevel.MEDIUM, model = "grok-3-mini")
        assertEquals("high", miniHigh.reasoningEffort) // 仅 low/high 两档：非 LOW 一律 high
        val full = compat("https://api.x.ai/v1", ThinkingBudgetLevel.HIGH, model = "grok-3")
        assertEquals(empty, full)
    }

    @Test
    fun compat_dashscopeSiliconflow_needExtraBody_omit() {
        assertEquals(empty, compat("https://dashscope.aliyuncs.com/compatible-mode/v1", ThinkingBudgetLevel.HIGH))
        assertEquals(empty, compat("https://api.siliconflow.cn/v1", ThinkingBudgetLevel.HIGH))
    }

    @Test
    fun compat_unknownHost_plainReasoningEffort() {
        val p = compat("https://relay.example.com/v1", ThinkingBudgetLevel.HIGH)
        assertEquals("high", p.reasoningEffort)
        assertEquals(empty, compat("https://relay.example.com/v1", ThinkingBudgetLevel.AUTO))
        assertEquals(empty, compat("https://relay.example.com/v1", ThinkingBudgetLevel.OFF))
    }
}
