package com.situ.aichat.data.remote.llm

import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.ThinkingBudgetLevel
import com.situ.aichat.data.model.ThinkingBudgetSupport
import com.situ.aichat.data.model.ThinkingBudgetTransport
import java.net.URI

/** Resolved thinking-budget request fields (mirrors iOS `ReasoningBudgetPayload`). */
data class ReasoningBudgetPayload(
    val reasoning: ReasoningParamDto? = null,
    val reasoningEffort: String? = null,
    val thinking: ThinkingParamDto? = null,
)

/**
 * Faithful port of iOS `LLMService+ReasoningPayload`. Maps (provider, thinking level) to the
 * provider-specific request fields, gated by the [ThinkingBudgetSupport] transport decision
 * (mirrors iOS `reasoningPayload(for:)`).
 */
object ReasoningPayloadMapper {

    fun payload(config: ApiConfigValues): ReasoningBudgetPayload {
        // 1) Non-thinking model → omit all thinking fields.
        if (!config.isThinkingModel) return ReasoningBudgetPayload()
        // 2) Transport gate: this provider/baseURL/model combo can't take a budget parameter
        //    (OpenRouter auto routing, relays that don't advertise reasoning, non-reasoning OpenAI
        //    models detected as thinking, MiniMax, …) → omit, avoiding 400s from rejected fields.
        val support = ThinkingBudgetSupport.resolve(config.providerType, config.baseUrl, config.modelName)
        if (support.transport == ThinkingBudgetTransport.NONE) return ReasoningBudgetPayload()

        val level = config.thinkingBudgetLevel
        return when (config.providerType) {
            ApiProviderType.ANTHROPIC -> anthropic(level)
            ApiProviderType.GEMINI -> gemini(level)
            ApiProviderType.OPENROUTER -> openRouter(level)
            ApiProviderType.DEEPSEEK -> deepSeek(level)
            ApiProviderType.MINIMAX -> ReasoningBudgetPayload() // built-in interleaved thinking
            ApiProviderType.OPENAI_COMPATIBLE -> openAiCompat(level, config.baseUrl, config.modelName)
        }
    }

    private fun anthropic(level: ThinkingBudgetLevel): ReasoningBudgetPayload {
        if (!level.isEnabled || level == ThinkingBudgetLevel.AUTO) return ReasoningBudgetPayload()
        return ReasoningBudgetPayload(
            thinking = ThinkingParamDto(type = "enabled", budgetTokens = level.budgetTokens),
        )
    }

    private fun gemini(level: ThinkingBudgetLevel): ReasoningBudgetPayload {
        if (!level.isEnabled || level == ThinkingBudgetLevel.AUTO) return ReasoningBudgetPayload()
        return ReasoningBudgetPayload(reasoningEffort = level.effort)
    }

    private fun openRouter(level: ThinkingBudgetLevel): ReasoningBudgetPayload {
        if (!level.isEnabled || level == ThinkingBudgetLevel.AUTO) return ReasoningBudgetPayload()
        return ReasoningBudgetPayload(reasoning = ReasoningParamDto(effort = level.effort))
    }

    private fun deepSeek(level: ThinkingBudgetLevel): ReasoningBudgetPayload = when (level) {
        ThinkingBudgetLevel.OFF ->
            ReasoningBudgetPayload(thinking = ThinkingParamDto(type = "disabled"))
        ThinkingBudgetLevel.AUTO ->
            ReasoningBudgetPayload(thinking = ThinkingParamDto(type = "enabled"))
        ThinkingBudgetLevel.LOW, ThinkingBudgetLevel.MEDIUM ->
            ReasoningBudgetPayload(thinking = ThinkingParamDto(type = "enabled", reasoningEffort = "high"))
        ThinkingBudgetLevel.HIGH ->
            ReasoningBudgetPayload(thinking = ThinkingParamDto(type = "enabled", reasoningEffort = "max"))
    }

    private fun openAiCompat(
        level: ThinkingBudgetLevel,
        baseUrl: String,
        modelName: String,
    ): ReasoningBudgetPayload {
        val host = runCatching { URI(baseUrl.trim()).host?.lowercase() }.getOrNull().orEmpty()
        return when (host) {
            "api.deepseek.com" -> deepSeek(level)
            "ark.cn-beijing.volces.com", "open.bigmodel.cn", "api.moonshot.cn" -> {
                val type = if (level.isEnabled) "enabled" else "disabled"
                ReasoningBudgetPayload(thinking = ThinkingParamDto(type = type))
            }
            "api.x.ai" -> xai(level, modelName)
            "dashscope.aliyuncs.com", "api.siliconflow.cn" ->
                ReasoningBudgetPayload() // needs extra_body, not yet supported
            else -> {
                if (!level.isEnabled || level == ThinkingBudgetLevel.AUTO) ReasoningBudgetPayload()
                else ReasoningBudgetPayload(reasoningEffort = level.effort)
            }
        }
    }

    /** xAI Grok: only grok-3-mini accepts reasoning_effort (low/high). */
    private fun xai(level: ThinkingBudgetLevel, modelName: String): ReasoningBudgetPayload {
        val supportsEffort = modelName.trim().lowercase().contains("grok-3-mini")
        if (!supportsEffort || !level.isEnabled || level == ThinkingBudgetLevel.AUTO) {
            return ReasoningBudgetPayload()
        }
        val effort = if (level == ThinkingBudgetLevel.LOW) "low" else "high"
        return ReasoningBudgetPayload(reasoningEffort = effort)
    }
}
