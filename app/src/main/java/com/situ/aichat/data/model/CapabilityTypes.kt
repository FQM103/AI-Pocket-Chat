package com.situ.aichat.data.model

/**
 * Capability detection value types — faithful port of iOS `ToolCallingSupport.swift`
 * (CapabilitySupportState / ToolSupportLevel) + the Vision/Audio mode enums from
 * `APIConfigurationTypes.swift`.
 *
 * The `raw` strings are persisted in [com.situ.aichat.data.local.entity.ApiConfigEntity]
 * and MUST stay identical to the iOS rawValues so backups stay cross-readable.
 *
 * Note: [ToolProtocolFamily] lives in [ApiProviderType.kt]; [ToolCallingMode] /
 * [ThinkingModelMode] live in [ThinkingEnums.kt].
 */

/** Tri-state capability detection result (streaming / thinking tool support). */
enum class CapabilitySupportState(val raw: String) {
    UNKNOWN("unknown"),
    UNSUPPORTED("unsupported"),
    SUPPORTED("supported");

    val isSupported: Boolean get() = this == SUPPORTED

    companion object {
        fun fromRaw(raw: String): CapabilitySupportState =
            entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/** Detected tool-calling support level (none → basic declaration → full result round-trip). */
enum class ToolSupportLevel(val raw: String) {
    UNKNOWN("unknown"),
    UNSUPPORTED("unsupported"),
    BASIC("basic"),
    FULL("full");

    /** basic = tools accepted; full = tool-result follow-up verified. */
    val enablesBasicToolCalling: Boolean get() = this == BASIC || this == FULL

    /** Only a verified full round-trip is safe for an agentic tool loop. */
    val enablesToolLoop: Boolean get() = this == FULL

    companion object {
        fun fromRaw(raw: String): ToolSupportLevel =
            entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/** Vision (image understanding) mode — auto detect / always on / off. */
enum class VisionMode(val raw: String) {
    AUTO("auto"),
    ENABLED("enabled"),
    DISABLED("disabled");

    companion object {
        fun fromRaw(raw: String): VisionMode =
            entries.firstOrNull { it.raw == raw } ?: AUTO
    }
}

/** Native audio-input mode — auto detect / always on / off. */
enum class AudioInputMode(val raw: String) {
    AUTO("auto"),
    ENABLED("enabled"),
    DISABLED("disabled");

    companion object {
        fun fromRaw(raw: String): AudioInputMode =
            entries.firstOrNull { it.raw == raw } ?: AUTO
    }
}
