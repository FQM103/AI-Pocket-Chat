package com.situ.aichat.data.model

/**
 * Mirrors the iOS `APIProviderType`. Note: there is no separate "openai" case —
 * OpenAI itself is served via [OPENAI_COMPATIBLE] (default base URL api.openai.com/v1).
 * MiniMax is a first-class provider (chat + TTS).
 */
enum class ApiProviderType(val raw: String) {
    ANTHROPIC("anthropic"),
    GEMINI("gemini"),
    DEEPSEEK("deepseek"),
    OPENROUTER("openrouter"),
    MINIMAX("minimax"),
    OPENAI_COMPATIBLE("openai_compatible");

    val displayName: String
        get() = when (this) {
            ANTHROPIC -> "Anthropic"
            GEMINI -> "Gemini"
            DEEPSEEK -> "DeepSeek"
            OPENROUTER -> "OpenRouter"
            MINIMAX -> "MiniMax"
            OPENAI_COMPATIBLE -> "自定义 (OpenAI 协议)"
        }

    val defaultBaseUrl: String
        get() = when (this) {
            ANTHROPIC -> "https://api.anthropic.com"
            GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
            DEEPSEEK -> "https://api.deepseek.com/v1"
            OPENROUTER -> "https://openrouter.ai/api/v1"
            OPENAI_COMPATIBLE -> "https://api.openai.com/v1"
            MINIMAX -> "https://api.minimaxi.com/v1"
        }

    val defaultModelName: String
        get() = when (this) {
            ANTHROPIC -> "claude-sonnet-4-5"
            GEMINI -> "gemini-2.5-pro"
            DEEPSEEK -> "deepseek-v4-flash"
            OPENROUTER -> "openrouter/auto"
            OPENAI_COMPATIBLE -> "gpt-4o"
            MINIMAX -> "MiniMax-M2.7"
        }

    val toolProtocolFamily: ToolProtocolFamily
        get() = when (this) {
            ANTHROPIC -> ToolProtocolFamily.ANTHROPIC
            GEMINI -> ToolProtocolFamily.GEMINI
            DEEPSEEK -> ToolProtocolFamily.DEEPSEEK
            OPENROUTER, OPENAI_COMPATIBLE, MINIMAX -> ToolProtocolFamily.OPENAI_COMPATIBLE
        }

    /** Anthropic Messages API does not accept response_format. */
    val supportsResponseFormat: Boolean get() = this != ANTHROPIC

    /** stream_options.include_usage is unsafe on the Anthropic/Gemini compat proxies. */
    val supportsStreamUsage: Boolean
        get() = when (this) {
            DEEPSEEK, OPENROUTER, OPENAI_COMPATIBLE, MINIMAX -> true
            ANTHROPIC, GEMINI -> false
        }

    companion object {
        fun fromRaw(raw: String): ApiProviderType =
            entries.firstOrNull { it.raw == raw } ?: OPENAI_COMPATIBLE

        /** Mirrors iOS APIProviderType.infer(from:). */
        fun infer(providerName: String): ApiProviderType {
            val n = providerName.trim().lowercase()
            return when {
                n.contains("anthropic") || n.contains("claude") -> ANTHROPIC
                n.contains("gemini") || n.contains("google") -> GEMINI
                n.contains("deepseek") -> DEEPSEEK
                n.contains("openrouter") || n.contains("open router") -> OPENROUTER
                n.contains("minimax") -> MINIMAX
                else -> OPENAI_COMPATIBLE
            }
        }
    }
}

enum class ToolProtocolFamily(val raw: String) {
    ANTHROPIC("anthropic"),
    GEMINI("gemini"),
    DEEPSEEK("deepseek"),
    OPENAI_COMPATIBLE("openai_compatible");

    companion object {
        fun fromRaw(raw: String): ToolProtocolFamily =
            entries.firstOrNull { it.raw == raw } ?: OPENAI_COMPATIBLE
    }
}
