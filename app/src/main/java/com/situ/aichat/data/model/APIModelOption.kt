package com.situ.aichat.data.model

/**
 * One selectable model in the model-catalog picker — faithful port of iOS `APIModelOption`.
 *
 * `id` is the wire model name; `name` is the display label (defaults to id); `subtitle` is an
 * optional hint (owner / context length / deprecation notice); `supportedParameters` is the
 * OpenRouter capability list (drives the thinking-budget UI in 3.3c).
 */
data class APIModelOption(
    val id: String,
    val name: String = id,
    val subtitle: String? = null,
    val supportedParameters: List<String>? = null,
) {
    /** Vendor group inferred from the model id, for grouped display. */
    val vendorGroup: String
        get() {
            id.indexOf('/').let { slash ->
                if (slash > 0) return capitalizeVendor(id.substring(0, slash))
            }
            val l = id.lowercase()
            return when {
                l.startsWith("claude") -> "Anthropic"
                l.startsWith("gpt") || l.startsWith("o1") || l.startsWith("o3") || l.startsWith("o4") -> "OpenAI"
                l.startsWith("gemini") -> "Google"
                l.startsWith("deepseek") -> "DeepSeek"
                l.startsWith("llama") -> "Meta"
                l.startsWith("qwen") || l.startsWith("qwq") -> "Qwen"
                l.startsWith("mistral") || l.startsWith("mixtral") || l.startsWith("pixtral") || l.startsWith("codestral") -> "Mistral"
                l.startsWith("grok") -> "xAI"
                l.startsWith("command") -> "Cohere"
                l.startsWith("minimax") -> "MiniMax"
                else -> "其他"
            }
        }

    private fun capitalizeVendor(raw: String): String = when (raw.lowercase()) {
        "anthropic" -> "Anthropic"
        "openai" -> "OpenAI"
        "google" -> "Google"
        "deepseek" -> "DeepSeek"
        "meta-llama", "meta" -> "Meta"
        "qwen" -> "Qwen"
        "mistralai", "mistral" -> "Mistral"
        "x-ai" -> "xAI"
        "cohere" -> "Cohere"
        "minimax" -> "MiniMax"
        else -> raw.replaceFirstChar { it.uppercase() }
    }
}
