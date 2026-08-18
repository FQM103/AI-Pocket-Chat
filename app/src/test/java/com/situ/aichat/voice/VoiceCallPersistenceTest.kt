package com.situ.aichat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Pure `buildCallRecord` tests (P10.1i) — 1:1 iOS `saveCallRecord`: keep only non-blank transcript lines,
 * `duration = max(0, (now − start)/1000)`, `startTime` = ISO-8601 seconds of the start; empty → null.
 */
class VoiceCallPersistenceTest {

    private val start = 1_700_000_000_000L // fixed epoch ms

    @Test fun keeps_only_non_blank_lines_in_order() {
        val record = VoiceCallPersistence.buildCallRecord(
            transcript = listOf(
                "user" to "在吗",
                "assistant" to "   ", // blank → dropped
                "assistant" to "在的",
                "user" to "", // empty → dropped
            ),
            callStartWallMillis = start,
            nowWallMillis = start + 5_000,
        )
        assertEquals(2, record?.transcript?.size)
        assertEquals("user", record?.transcript?.get(0)?.role)
        assertEquals("在吗", record?.transcript?.get(0)?.text)
        assertEquals("在的", record?.transcript?.get(1)?.text)
    }

    @Test fun duration_is_whole_seconds() {
        val record = VoiceCallPersistence.buildCallRecord(
            transcript = listOf("user" to "hi"),
            callStartWallMillis = start,
            nowWallMillis = start + 125_900, // 125.9 s → floor 125
        )
        assertEquals(125, record?.duration)
        assertEquals("call_record", record?.type)
    }

    @Test fun negative_elapsed_clamps_duration_to_zero() {
        // Wall clock moved backwards mid-call → duration must not go negative (= iOS max(duration, 0)).
        val record = VoiceCallPersistence.buildCallRecord(
            transcript = listOf("user" to "hi"),
            callStartWallMillis = start,
            nowWallMillis = start - 10_000,
        )
        assertEquals(0, record?.duration)
    }

    @Test fun start_time_is_iso8601_seconds_of_start() {
        val record = VoiceCallPersistence.buildCallRecord(
            transcript = listOf("user" to "hi"),
            callStartWallMillis = start + 456, // sub-second part must be truncated
            nowWallMillis = start + 5_000,
        )
        val expected = Instant.ofEpochMilli(start).truncatedTo(ChronoUnit.SECONDS)
        assertEquals(expected, Instant.parse(record?.startTime))
    }

    @Test fun all_blank_transcript_returns_null() {
        assertNull(
            VoiceCallPersistence.buildCallRecord(
                transcript = listOf("user" to "  ", "assistant" to ""),
                callStartWallMillis = start,
                nowWallMillis = start + 1_000,
            ),
        )
        assertNull(VoiceCallPersistence.buildCallRecord(emptyList(), start, start + 1_000))
    }

    // --- VU3 hadTtsFailure (2026-07-12) ---

    @Test fun tts_failure_flag_is_carried_into_the_record() {
        val record = VoiceCallPersistence.buildCallRecord(
            transcript = listOf("user" to "hi"),
            callStartWallMillis = start,
            nowWallMillis = start + 1_000,
            hadTtsFailure = true,
        )
        assertTrue(record?.hadTtsFailure == true)
    }

    /** B2 回归钉: the default-arg call path (existing callers) yields a false flag, output unchanged. */
    @Test fun default_call_has_no_tts_failure() {
        val record = VoiceCallPersistence.buildCallRecord(
            transcript = listOf("user" to "hi"),
            callStartWallMillis = start,
            nowWallMillis = start + 1_000,
        )
        assertEquals(false, record?.hadTtsFailure)
    }
}
