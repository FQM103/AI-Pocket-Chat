package com.situ.aichat.data.model

/**
 * Outcome of a tool-calling capability probe — faithful port of iOS
 * `ToolCallingSupport.swift` `ToolDetectionResult`.
 *
 * `checkedAt` is epoch millis (iOS used `Date`); persisted into
 * `ApiConfigEntity.toolDetectionCheckedAt`.
 */
data class ToolDetectionResult(
    val level: ToolSupportLevel,
    val protocolFamily: ToolProtocolFamily,
    val streamingState: CapabilitySupportState,
    val thinkingState: CapabilitySupportState,
    val summary: String,
    val checkedAt: Long,
) {
    companion object {
        fun unsupported(
            protocolFamily: ToolProtocolFamily,
            summary: String,
            checkedAt: Long = System.currentTimeMillis(),
        ) = ToolDetectionResult(
            level = ToolSupportLevel.UNSUPPORTED,
            protocolFamily = protocolFamily,
            streamingState = CapabilitySupportState.UNSUPPORTED,
            thinkingState = CapabilitySupportState.UNSUPPORTED,
            summary = summary,
            checkedAt = checkedAt,
        )

        fun unknown(
            protocolFamily: ToolProtocolFamily,
            summary: String,
            checkedAt: Long = System.currentTimeMillis(),
        ) = ToolDetectionResult(
            level = ToolSupportLevel.UNKNOWN,
            protocolFamily = protocolFamily,
            streamingState = CapabilitySupportState.UNKNOWN,
            thinkingState = CapabilitySupportState.UNKNOWN,
            summary = summary,
            checkedAt = checkedAt,
        )
    }
}
