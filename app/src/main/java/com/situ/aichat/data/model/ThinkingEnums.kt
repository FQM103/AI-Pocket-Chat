package com.situ.aichat.data.model

/**
 * Mirrors iOS `ThinkingBudgetLevel`: one enum carrying both an integer token budget
 * (Anthropic / Gemini native) and a string effort (OpenAI / OpenRouter). Each provider's
 * mapper picks which one to use.
 */
enum class ThinkingBudgetLevel(val raw: String) {
    OFF("off"),
    AUTO("auto"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    val budgetTokens: Int
        get() = when (this) {
            OFF -> 0
            AUTO -> -1
            LOW -> 2_048
            MEDIUM -> 8_192
            HIGH -> 16_384
        }

    val effort: String
        get() = when (this) {
            OFF -> "none"
            AUTO -> "auto"
            LOW -> "low"
            MEDIUM -> "medium"
            HIGH -> "high"
        }

    val isEnabled: Boolean get() = this != OFF

    companion object {
        fun fromRaw(raw: String): ThinkingBudgetLevel =
            entries.firstOrNull { it.raw == raw } ?: AUTO
    }
}

enum class MaxOutputLength(val raw: String) {
    AUTO("auto"),
    SHORT("short"),
    MEDIUM("medium"),
    LONG("long"),
    EXTRA_LONG("extraLong");

    val tokenLimit: Int?
        get() = when (this) {
            AUTO -> null
            SHORT -> 2_000
            MEDIUM -> 4_000
            LONG -> 8_000
            EXTRA_LONG -> 16_000
        }

    companion object {
        fun fromRaw(raw: String): MaxOutputLength =
            entries.firstOrNull { it.raw == raw } ?: AUTO
    }
}

enum class ThinkingModelMode(val raw: String) {
    AUTO("auto"),
    STANDARD("standard"),
    THINKING("thinking");

    companion object {
        fun fromRaw(raw: String): ThinkingModelMode =
            entries.firstOrNull { it.raw == raw } ?: AUTO
    }
}

enum class ToolCallingMode(val raw: String) {
    AUTO("auto"),
    ENABLED("enabled"),
    DISABLED("disabled");

    companion object {
        fun fromRaw(raw: String): ToolCallingMode =
            entries.firstOrNull { it.raw == raw } ?: AUTO
    }
}
