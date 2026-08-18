package com.situ.aichat.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The in-call transcript — lifted verbatim out of [VoiceCallController] (2026-07-11 行数拆分·只搬不改).
 * Holds the capped (role, text) lines persisted into the call record on hang-up, and mirrors the last few
 * into [recent] for the live-subtitle panel (= iOS `transcript.suffix(200)` / `recentTranscriptLines`).
 * Main-thread confined like its owning controller.
 */
internal class VoiceCallTranscript {
    private val lines = ArrayList<Pair<String, String>>()

    private val _recent = MutableStateFlow<List<VoiceTranscriptLine>>(emptyList())
    val recent: StateFlow<List<VoiceTranscriptLine>> = _recent.asStateFlow()

    /** Append a line, trimming to the last [TRANSCRIPT_CAP]. */
    fun append(role: String, text: String) {
        lines.add(role to text)
        if (lines.size > TRANSCRIPT_CAP) {
            val tail = ArrayList(lines.subList(lines.size - TRANSCRIPT_CAP, lines.size))
            lines.clear()
            lines.addAll(tail)
        }
        _recent.value = lines.takeLast(RECENT_TRANSCRIPT_COUNT)
            .map { VoiceTranscriptLine(it.first, it.second) }
    }

    /** Copy for the hang-up call-record persist (safe against the later reset). */
    fun snapshot(): ArrayList<Pair<String, String>> = ArrayList(lines)

    fun clear() {
        lines.clear()
        _recent.value = emptyList()
    }

    private companion object {
        const val TRANSCRIPT_CAP = 200        // iOS VoiceCallManager.swift:52-54
        const val RECENT_TRANSCRIPT_COUNT = 4 // iOS syncRecentTranscriptLines = transcript.suffix(4)
    }
}
