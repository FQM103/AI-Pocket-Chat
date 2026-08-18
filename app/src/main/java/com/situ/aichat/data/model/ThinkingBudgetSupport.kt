package com.situ.aichat.data.model

import java.net.URI

/**
 * Thinking-budget "transport gate" — faithful port of iOS `APIConfigurationTypes.swift`
 * (`ThinkingBudgetTransport` / `ThinkingBudgetPrecision` / `ThinkingBudgetSupport`).
 *
 * Resolves, per (provider, baseURL, model), HOW a thinking budget should be sent (or that it
 * must NOT be sent). Two consumers:
 * - [com.situ.aichat.data.remote.llm.ReasoningPayloadMapper] — `transport == NONE` ⇒ omit all
 *   thinking fields (covers e.g. OpenRouter auto-routed models, relay models that don't advertise
 *   reasoning, non-reasoning OpenAI models that were detected as thinking).
 * - the thinking-intensity UI row (visibility via [showsControl] + [allowedLevels]) — lands with
 *   the capability UI in P3.3; `userFacingHint` (the localized footer) is deferred until then.
 */

enum class ThinkingBudgetTransport {
    NONE,
    OPENAI_REASONING_EFFORT,
    OPENROUTER_REASONING,
    ANTHROPIC_THINKING,
    DEEPSEEK_THINKING, // DeepSeek thinking: {"type":"enabled"}, no budget control
}

enum class ThinkingBudgetPrecision {
    UNSUPPORTED,
    EFFORT_HINT,
    TOKEN_BUDGET,
}

/**
 * The resolved capability. `note` is a dev/diagnostic English string (iOS keeps it but no longer
 * shows it in the UI), so it is intentionally not localized.
 */
data class ThinkingBudgetSupport(
    val transport: ThinkingBudgetTransport,
    val precision: ThinkingBudgetPrecision,
    val showsControl: Boolean,
    val allowedLevels: List<ThinkingBudgetLevel>,
    val note: String,
) {
    val sendsBudget: Boolean get() = transport != ThinkingBudgetTransport.NONE

    /** Clamp a level to [allowedLevels]: prefer the level itself, else AUTO, else OFF, else first. */
    fun normalized(level: ThinkingBudgetLevel): ThinkingBudgetLevel {
        if (allowedLevels.contains(level)) return level
        if (allowedLevels.contains(ThinkingBudgetLevel.AUTO)) return ThinkingBudgetLevel.AUTO
        if (allowedLevels.contains(ThinkingBudgetLevel.OFF)) return ThinkingBudgetLevel.OFF
        return allowedLevels.firstOrNull() ?: ThinkingBudgetLevel.AUTO
    }

    companion object {
        private fun none(note: String) = ThinkingBudgetSupport(
            transport = ThinkingBudgetTransport.NONE,
            precision = ThinkingBudgetPrecision.UNSUPPORTED,
            showsControl = false,
            allowedLevels = listOf(ThinkingBudgetLevel.AUTO),
            note = note,
        )

        fun resolve(
            providerType: ApiProviderType,
            baseUrl: String,
            modelName: String,
            supportedParameters: List<String>? = null,
        ): ThinkingBudgetSupport {
            val model = modelName.trim().lowercase()

            return when (providerType) {
                ApiProviderType.ANTHROPIC -> {
                    if (!supportsAnthropicThinkingModel(model)) {
                        none("This Claude model does not clearly advertise extended thinking on the current compatibility path, so thinking budget control stays off.")
                    } else {
                        ThinkingBudgetSupport(
                            transport = ThinkingBudgetTransport.ANTHROPIC_THINKING,
                            precision = ThinkingBudgetPrecision.TOKEN_BUDGET,
                            showsControl = true,
                            // Anthropic has no "auto" (omitting thinking == off).
                            allowedLevels = listOf(
                                ThinkingBudgetLevel.OFF, ThinkingBudgetLevel.LOW,
                                ThinkingBudgetLevel.MEDIUM, ThinkingBudgetLevel.HIGH,
                            ),
                            note = "Sent as Anthropic-compatible thinking.budget_tokens.",
                        )
                    }
                }

                ApiProviderType.GEMINI -> {
                    if (!supportsGeminiOpenAIThinking(baseUrl, model)) {
                        none("Gemini thinking control is only enabled on Google's OpenAI-compatible path for supported Gemini thinking models.")
                    } else {
                        ThinkingBudgetSupport(
                            transport = ThinkingBudgetTransport.OPENAI_REASONING_EFFORT,
                            precision = ThinkingBudgetPrecision.EFFORT_HINT,
                            showsControl = true,
                            allowedLevels = geminiLevels(),
                            note = "Sent through Gemini's OpenAI-compatible reasoning_effort mapping.",
                        )
                    }
                }

                ApiProviderType.DEEPSEEK -> ThinkingBudgetSupport(
                    transport = ThinkingBudgetTransport.DEEPSEEK_THINKING,
                    precision = ThinkingBudgetPrecision.EFFORT_HINT,
                    showsControl = true,
                    allowedLevels = listOf(
                        ThinkingBudgetLevel.OFF, ThinkingBudgetLevel.AUTO, ThinkingBudgetLevel.LOW,
                        ThinkingBudgetLevel.MEDIUM, ThinkingBudgetLevel.HIGH,
                    ),
                    note = "Sent as DeepSeek's thinking + reasoning_effort. low/medium map to high, high maps to max.",
                )

                ApiProviderType.OPENROUTER -> when {
                    isOpenRouterAutoModel(modelName) -> none(
                        "OpenRouter auto routing can switch providers between requests, so no explicit thinking budget is sent.",
                    )
                    supportedParameters != null && !supportsOpenRouterReasoningControl(supportedParameters) -> none(
                        "The selected OpenRouter model does not advertise reasoning parameters, so budget control is hidden.",
                    )
                    else -> ThinkingBudgetSupport(
                        transport = ThinkingBudgetTransport.OPENROUTER_REASONING,
                        precision = ThinkingBudgetPrecision.EFFORT_HINT,
                        showsControl = true,
                        allowedLevels = listOf(
                            ThinkingBudgetLevel.OFF, ThinkingBudgetLevel.AUTO, ThinkingBudgetLevel.LOW,
                            ThinkingBudgetLevel.MEDIUM, ThinkingBudgetLevel.HIGH,
                        ),
                        note = "Sent as OpenRouter's reasoning object (a routing hint, not a token-accurate budget).",
                    )
                }

                ApiProviderType.MINIMAX -> none(
                    "MiniMax models decide thinking depth automatically. No external budget control is available.",
                )

                ApiProviderType.OPENAI_COMPATIBLE -> when {
                    // 1) Official OpenAI endpoint + reasoning model → per-model levels.
                    isOfficialOpenAIHost(baseUrl) && isOfficialOpenAIReasoningModel(model) ->
                        ThinkingBudgetSupport(
                            transport = ThinkingBudgetTransport.OPENAI_REASONING_EFFORT,
                            precision = ThinkingBudgetPrecision.EFFORT_HINT,
                            showsControl = true,
                            allowedLevels = openAiLevels(model),
                            note = "Sent as OpenAI's reasoning_effort.",
                        )
                    // 2) Non-official endpoint (relay): decide by model name — relays forward reasoning_effort.
                    isKnownThinkingModelWithBudgetControl(model) ->
                        ThinkingBudgetSupport(
                            transport = ThinkingBudgetTransport.OPENAI_REASONING_EFFORT,
                            precision = ThinkingBudgetPrecision.EFFORT_HINT,
                            showsControl = true,
                            allowedLevels = listOf(
                                ThinkingBudgetLevel.OFF, ThinkingBudgetLevel.AUTO, ThinkingBudgetLevel.LOW,
                                ThinkingBudgetLevel.MEDIUM, ThinkingBudgetLevel.HIGH,
                            ),
                            note = "Forwarded as reasoning_effort via the relay service.",
                        )
                    // 3) Official endpoint but non-reasoning model / unknown model → disabled.
                    else -> none(
                        when {
                            isOfficialOpenAIHost(baseUrl) ->
                                "This model does not clearly expose OpenAI reasoning controls on Chat Completions, so no budget parameter is sent."
                            model.startsWith("deepseek") ->
                                "DeepSeek does not provide a dedicated reasoning budget parameter; the model decides thinking depth automatically."
                            else ->
                                "This model is not recognized as a thinking model, so no reasoning budget parameter is sent."
                        },
                    )
                }
            }
        }

        // MARK: - Heuristics (mirror iOS private statics)

        private fun isOpenRouterAutoModel(modelName: String): Boolean {
            val n = modelName.trim().lowercase()
            return n == "openrouter/auto" || n == "auto" || n.endsWith("/auto")
        }

        /** Known thinking models that accept a budget-control parameter (relay scenario). */
        private fun isKnownThinkingModelWithBudgetControl(model: String): Boolean {
            if (supportsAnthropicThinkingModel(model)) return true
            if (isOfficialOpenAIReasoningModel(model)) return true
            if (model.startsWith("gemini-2.5") || model.startsWith("gemini-3")) return true
            if (model.startsWith("grok-3")) return true
            if (model.startsWith("qwq") || model.startsWith("qwen3") || model.startsWith("qwen-3")) return true
            // DeepSeek does not support budget control here.
            return false
        }

        private fun isOfficialOpenAIHost(baseUrl: String): Boolean =
            hostOf(baseUrl) == "api.openai.com"

        private fun isOfficialOpenAIReasoningModel(model: String): Boolean =
            model.startsWith("gpt-5") || model.startsWith("o1") ||
                model.startsWith("o3") || model.startsWith("o4")

        private fun openAiLevels(model: String): List<ThinkingBudgetLevel> =
            if (model.startsWith("gpt-5")) {
                listOf(
                    ThinkingBudgetLevel.OFF, ThinkingBudgetLevel.AUTO, ThinkingBudgetLevel.LOW,
                    ThinkingBudgetLevel.MEDIUM, ThinkingBudgetLevel.HIGH,
                )
            } else {
                // o1/o3/o4 can't fully disable thinking, but allow OFF for a uniform UI (mapper sends "none").
                listOf(
                    ThinkingBudgetLevel.OFF, ThinkingBudgetLevel.AUTO,
                    ThinkingBudgetLevel.MEDIUM, ThinkingBudgetLevel.HIGH,
                )
            }

        private fun supportsAnthropicThinkingModel(model: String): Boolean =
            model.startsWith("claude-3-7") || model.startsWith("claude-sonnet-4") ||
                model.startsWith("claude-opus-4")

        private fun supportsGeminiOpenAIThinking(baseUrl: String, model: String): Boolean =
            isGeminiOpenAICompatibleHost(baseUrl) &&
                (model.startsWith("gemini-2.5") || model.startsWith("gemini-3"))

        /** Gemini 2.5 Pro / 3 can't fully disable thinking, but UI still allows OFF (mapper omits the field). */
        private fun geminiLevels(): List<ThinkingBudgetLevel> = listOf(
            ThinkingBudgetLevel.OFF, ThinkingBudgetLevel.AUTO, ThinkingBudgetLevel.LOW,
            ThinkingBudgetLevel.MEDIUM, ThinkingBudgetLevel.HIGH,
        )

        private fun isGeminiOpenAICompatibleHost(baseUrl: String): Boolean {
            val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return false
            if (uri.host?.lowercase() != "generativelanguage.googleapis.com") return false
            val path = (uri.path ?: "").lowercase()
            return path.contains("/openai") || path.endsWith("/chat/completions")
        }

        private fun supportsOpenRouterReasoningControl(supportedParameters: List<String>): Boolean {
            val normalized = supportedParameters.map { it.lowercase() }.toSet()
            return normalized.contains("reasoning") ||
                normalized.contains("reasoning.effort") ||
                normalized.contains("reasoning.max_tokens")
        }

        private fun hostOf(baseUrl: String): String? =
            runCatching { URI(baseUrl.trim()).host?.lowercase() }.getOrNull()
    }
}

/** Convenience mirror of iOS `APIProviderType.thinkingBudgetSupport(baseURL:modelName:)`. */
fun ApiProviderType.thinkingBudgetSupport(
    baseUrl: String,
    modelName: String,
    supportedParameters: List<String>? = null,
): ThinkingBudgetSupport =
    ThinkingBudgetSupport.resolve(this, baseUrl, modelName, supportedParameters)
