package com.situ.aichat.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure post-reply trigger-predicate tests (P10.1i), reverse-derived from iOS values:
 * - `shouldTriggerRelationshipFallback`: ① ≥7 days since reference → true; ② ≥100 rounds AND ≥24h → true.
 * - `crossesRelationshipBand`: non-uniform band edges `[10,20,30,50,70,85,95,100]` (`value <= edge`).
 */
class VoiceCallPostReplyRoundsTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    // ── shouldTriggerRelationshipFallback ──

    @Test fun fallback_fires_after_seven_days() {
        assertTrue(
            VoiceCallPostReplyRounds.shouldTriggerRelationshipFallback(
                messageCount = 3, lastAnalysisDate = now - 7 * day, creationDate = 0, now = now,
            ),
        )
    }

    @Test fun fallback_does_not_fire_just_before_seven_days() {
        assertFalse(
            VoiceCallPostReplyRounds.shouldTriggerRelationshipFallback(
                messageCount = 3, lastAnalysisDate = now - (7 * day - 1000), creationDate = 0, now = now,
            ),
        )
    }

    @Test fun fallback_fires_at_100_rounds_and_24h() {
        assertTrue(
            VoiceCallPostReplyRounds.shouldTriggerRelationshipFallback(
                messageCount = 100, lastAnalysisDate = now - day, creationDate = 0, now = now,
            ),
        )
    }

    @Test fun fallback_blocked_at_100_rounds_under_24h() {
        // The 24h minimum cooldown prevents the "-15 count" semantics from re-triggering every ~15 rounds.
        assertFalse(
            VoiceCallPostReplyRounds.shouldTriggerRelationshipFallback(
                messageCount = 100, lastAnalysisDate = now - (day - 1000), creationDate = 0, now = now,
            ),
        )
    }

    @Test fun fallback_blocked_under_100_rounds_and_under_7_days() {
        assertFalse(
            VoiceCallPostReplyRounds.shouldTriggerRelationshipFallback(
                messageCount = 99, lastAnalysisDate = now - 2 * day, creationDate = 0, now = now,
            ),
        )
    }

    @Test fun fallback_uses_creation_date_when_no_prior_analysis() {
        // No prior analysis → reference is creationDate; 8 days since creation → fire.
        assertTrue(
            VoiceCallPostReplyRounds.shouldTriggerRelationshipFallback(
                messageCount = 1, lastAnalysisDate = null, creationDate = now - 8 * day, now = now,
            ),
        )
    }

    // ── crossesRelationshipBand (edges 10,20,30,50,70,85,95,100) ──

    @Test fun same_band_does_not_cross() {
        assertFalse(VoiceCallPostReplyRounds.crossesRelationshipBand(5, 9)) // both ≤10 → band 0
        assertFalse(VoiceCallPostReplyRounds.crossesRelationshipBand(71, 84)) // both in (70,85] → band 5
    }

    @Test fun crossing_an_edge_is_detected() {
        assertTrue(VoiceCallPostReplyRounds.crossesRelationshipBand(10, 11)) // band 0 → band 1
        assertTrue(VoiceCallPostReplyRounds.crossesRelationshipBand(50, 51)) // band 3 → band 4
        assertTrue(VoiceCallPostReplyRounds.crossesRelationshipBand(95, 96)) // band 6 → band 7 (breaks 96+ deadlock)
    }

    @Test fun edge_value_belongs_to_lower_band() {
        // value <= edge → that band; so 30 is band 2, 31 is band 3.
        assertTrue(VoiceCallPostReplyRounds.crossesRelationshipBand(30, 31))
        assertFalse(VoiceCallPostReplyRounds.crossesRelationshipBand(21, 30)) // both in (20,30] → band 2
    }
}
