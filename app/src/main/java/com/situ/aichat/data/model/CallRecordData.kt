package com.situ.aichat.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One transcript line in a call record (1:1 iOS `CallRecordTranscriptEntry`: `role`/`text`).
 * `role` is `"user"` or `"assistant"` (matches the chat role raw strings).
 */
@Serializable
data class CallRecordTranscriptEntry(
    val role: String,
    val text: String,
)

/**
 * Structured data for a voice-call record card (1:1 iOS `CallRecordData`, the [MessageKind.CALL_RECORD_CARD]
 * associated value). When a call ends, its non-empty transcript is aggregated into ONE assistant message
 * whose `content` is this JSON (conversation preview `📞 语音通话`).
 *
 * Field names match iOS exactly (`type`/`duration`/`startTime`/`transcript`) so the persisted JSON is
 * byte-compatible with iOS backups (transcript entries serialize as `{role,text}`).
 *
 * @param type discriminator, always `"call_record"` (validated on parse).
 * @param duration call length in seconds (already `max(0, …)` when built).
 * @param startTime ISO-8601 string of the call start (= iOS `ISO8601DateFormatter`).
 * @param transcript ordered, non-empty lines.
 * @param hadTtsFailure VU3: this call had ≥1 turn fall back to text (no voice). Safe additive field —
 *   `encodeDefaults=false` means false cards encode byte-identically to before; `ignoreUnknownKeys=true`
 *   means old cards parse to false. Only true cards carry the extra key.
 */
@Serializable
data class CallRecordData(
    val type: String,
    val duration: Int,
    val startTime: String,
    val transcript: List<CallRecordTranscriptEntry>,
    val hadTtsFailure: Boolean = false,
)

/** Call-record card JSON codec (1:1 iOS `parseCallRecord`; `encodeDefaults=false`, order-independent). */
object CallRecordJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(data: CallRecordData): String = json.encodeToString(CallRecordData.serializer(), data)

    /**
     * Parse a message's content into a call record (= iOS `parseCallRecord`):
     * - content must start with `{` (excludes plainText),
     * - `type` must equal `"call_record"` (excludes other JSON cards),
     * - any failure → null (the caller falls back to plainText).
     */
    fun parse(content: String): CallRecordData? {
        if (!content.startsWith("{")) return null
        val data = runCatching { json.decodeFromString<CallRecordData>(content) }.getOrNull() ?: return null
        return if (data.type == "call_record") data else null
    }
}
