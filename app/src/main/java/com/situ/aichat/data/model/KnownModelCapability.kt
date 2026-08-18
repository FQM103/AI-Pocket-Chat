package com.situ.aichat.data.model

/**
 * Known-model capability info (thinking / vision / tool calling / audio input).
 * Faithful port of iOS `KnownModelCapabilityTable.swift`.
 *
 * **`isThinking` semantics**: means the model *supports* thinking mode, NOT that thinking is
 * on by default. e.g. `deepseek-v4-flash` is `isThinking = true` (hybrid: can toggle), but the
 * `isLikelyThinkingModel` heuristic does not flag it (defaults to non-thinking requests so old
 * chat users don't suddenly get slower after upgrade). Two mechanisms, two jobs:
 * - capability-table `isThinking` = whether the config UI shows the "thinking depth" menu + lets
 *   the user toggle.
 * - `isLikelyThinkingModel` = whether streaming expects reasoning_content + sends thinking fields
 *   by default.
 *
 * The last 5 fields (contextWindow / maxOutputTokens / isDeprecated / deprecationDate /
 * replacementModelID) default to nil/false; older entries need not set them.
 */
data class KnownModelCapability(
    val isThinking: Boolean,
    val hasVision: Boolean,
    val hasToolCalling: Boolean,
    val hasAudioInput: Boolean,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val isDeprecated: Boolean = false,
    val deprecationDate: String? = null,
    val replacementModelID: String? = null,
)

/**
 * Static model-capability lookup table — matches a model name by longest-prefix.
 * Used to give instant hints before runtime API detection, and to prefill detection
 * results in auto mode (see `ApiConfigRepository.prefillFromKnownCapabilities`).
 *
 * Pure (no Android dependencies) so it stays unit-testable and callable from any layer.
 */
object KnownModelCapabilityTable {

    /** Look up a model's known capability; returns null if not in the table. */
    fun lookup(modelName: String): KnownModelCapability? {
        val normalized = normalize(modelName)
        if (normalized.isEmpty()) return null
        // Longest-prefix-first (entries are sorted by prefix length desc).
        for (entry in entries) {
            if (normalized.startsWith(entry.prefix)) return entry.capability
        }
        return null
    }

    /** Normalize: trim, lowercase, strip relay vendor prefixes (e.g. "anthropic/claude…" → "claude…"). */
    private fun normalize(modelName: String): String {
        var name = modelName.trim().lowercase()
        for (prefix in vendorPrefixes) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length)
                break
            }
        }
        return name
    }

    /** Common vendor prefixes used by relay model IDs. */
    private val vendorPrefixes: List<String> = listOf(
        "anthropic/",
        "openai/",
        "google/",
        "deepseek/",
        "minimax/",
        "meta-llama/",
        "meta/",
        "qwen/",
        "mistralai/",
        "mistral/",
        "cohere/",
        "perplexity/",
        "x-ai/",
        "nvidia/",
        "01-ai/",
    )

    private data class Entry(val prefix: String, val capability: KnownModelCapability)

    // Sorted by prefix length desc so the most specific pattern wins
    // (e.g. "claude-3-5-haiku" before "claude-3").
    private val entries: List<Entry> = buildSortedEntries()

    private fun buildSortedEntries(): List<Entry> {
        val raw: List<Pair<String, KnownModelCapability>> = listOf(
            // ── Anthropic Claude ──
            "claude-opus-4" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "claude-sonnet-4" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "claude-3-7-sonnet" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "claude-3-5-sonnet" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "claude-3-5-haiku" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "claude-3-opus" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "claude-3-sonnet" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "claude-3-haiku" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),

            // ── OpenAI GPT ──
            "gpt-5" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = true),
            "gpt-4.5" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = true),
            "gpt-4o-audio" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = true),
            "gpt-4o-mini-audio" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = true),
            "gpt-4o-mini" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "gpt-4o" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "gpt-4-turbo" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "gpt-4-vision" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = false, hasAudioInput = false),
            "gpt-4" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),

            // ── OpenAI Reasoning ──
            "o4-mini" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "o3-pro" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "o3-mini" to KnownModelCapability(isThinking = true, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "o3" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "o1-pro" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "o1-mini" to KnownModelCapability(isThinking = true, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "o1" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = false),

            // ── Google Gemini ──
            "gemini-3" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = true),
            "gemini-2.5-pro" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = true),
            "gemini-2.5-flash" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = true),
            "gemini-2.0-flash" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = true),
            "gemini-2.0" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "gemini-1.5-pro" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = true),
            "gemini-1.5-flash" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = true),

            // ── DeepSeek ──
            // V4 series: 1M context, 384K max output; flash/pro both support a thinking toggle
            // (hybrid mode, controlled by reasoning_effort).
            "deepseek-v4-flash" to KnownModelCapability(
                isThinking = true, hasVision = false, hasToolCalling = true, hasAudioInput = false,
                contextWindow = 1_000_000, maxOutputTokens = 384_000,
            ),
            "deepseek-v4-pro" to KnownModelCapability(
                isThinking = true, hasVision = false, hasToolCalling = true, hasAudioInput = false,
                contextWindow = 1_000_000, maxOutputTokens = 384_000,
            ),
            // ⚠️ The two legacy names below are deprecated on 2026-07-24. Official wording:
            // "they correspond to deepseek-v4-flash's non-thinking and thinking modes respectively"
            // i.e. deepseek-chat → v4-flash non-thinking; deepseek-reasoner → v4-flash thinking.
            "deepseek-reasoner" to KnownModelCapability(
                isThinking = true, hasVision = false, hasToolCalling = true, hasAudioInput = false,
                isDeprecated = true, deprecationDate = "2026-07-24", replacementModelID = "deepseek-v4-flash",
            ),
            "deepseek-chat" to KnownModelCapability(
                isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false,
                isDeprecated = true, deprecationDate = "2026-07-24", replacementModelID = "deepseek-v4-flash",
            ),
            "deepseek-r1" to KnownModelCapability(isThinking = true, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "deepseek-v3" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "deepseek-v2.5" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),

            // ── MiniMax ──
            "minimax-m2.7" to KnownModelCapability(isThinking = true, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "minimax-m2.5" to KnownModelCapability(isThinking = true, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "minimax-m2.1" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "minimax-m2" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),

            // ── Meta Llama ──
            "llama-4" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "llama-3.3" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "llama-3.2-vision" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = false, hasAudioInput = false),
            "llama-3.2" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "llama-3.1" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "llama-3" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),

            // ── Alibaba Qwen ──
            "qwen3" to KnownModelCapability(isThinking = true, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "qwen-3" to KnownModelCapability(isThinking = true, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "qwq" to KnownModelCapability(isThinking = true, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "qwen2.5-vl" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "qwen-2.5-vl" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "qwen2.5" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "qwen-2.5" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),

            // ── Mistral ──
            "mistral-large" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "mistral-medium" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "mistral-small" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "pixtral" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "codestral" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "mixtral" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),

            // ── Cohere ──
            "command-r-plus" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "command-r" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),
            "command-a" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),

            // ── xAI Grok ──
            // Grok's reasoning_effort acceptance is a provider dialect difference, not reflected here:
            //   grok-3-mini / grok-3-mini-fast: the only ones that accept reasoning_effort (low/high)
            //   grok-3 / grok-3-fast / grok-4 / grok-4-fast-reasoning: auto-think, reasoning_effort → 400
            //   grok-4-fast-non-reasoning: non-thinking
            // The field-omission decision lives in the mapper's api.x.ai branch; the table only sets
            // isThinking to drive UI show/hide of the thinking-intensity row.
            "grok-4-fast-non-reasoning" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "grok-4-fast-reasoning" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "grok-4" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "grok-3-mini-fast" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "grok-3-mini" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "grok-3-fast" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "grok-3" to KnownModelCapability(isThinking = true, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "grok-2-vision" to KnownModelCapability(isThinking = false, hasVision = true, hasToolCalling = true, hasAudioInput = false),
            "grok-2" to KnownModelCapability(isThinking = false, hasVision = false, hasToolCalling = true, hasAudioInput = false),
        )

        return raw
            .map { Entry(it.first, it.second) }
            .sortedByDescending { it.prefix.length }
    }
}

/**
 * Auto-redirect a deprecated model to its replacement at request-build time — faithful port of
 * iOS `LLMService.autoRedirectDeprecatedModel` (the third defense layer).
 *
 * Only applies to [ApiProviderType.DEEPSEEK]. For OPENAI_COMPATIBLE the user may intentionally
 * point `deepseek-chat` at a relay / mock / private fine-tune, so forcing a redirect there would
 * break intent. Only the `model` field changes — thinking fields are driven by config.isThinkingModel
 * and stay valid (reasoner→v4-flash keeps the user's thinking mode).
 */
fun redirectDeprecatedModel(modelName: String, providerType: ApiProviderType): String {
    if (providerType != ApiProviderType.DEEPSEEK) return modelName
    val cap = KnownModelCapabilityTable.lookup(modelName) ?: return modelName
    val replacement = cap.replacementModelID
    if (!cap.isDeprecated || replacement.isNullOrEmpty()) return modelName
    return replacement
}
