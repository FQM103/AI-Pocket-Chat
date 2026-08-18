package com.situ.aichat.offline

import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OfflineStateGuard` tests (P10.2c-3b): the 4-case dirty-state repair decision + the >10min recovery
 * prompt decision, reverse-derived from iOS `ensureOfflineStateConsistency` / `shouldShowOfflineRecoveryPrompt`.
 */
class OfflineStateGuardTest {

    private val start = MessageKind.OFFLINE_MARKER_START
    private val end = MessageKind.OFFLINE_MARKER_END
    private val text = MessageKind.PLAIN_TEXT

    // ── decide: 4 dirty cases + healthy ──

    @Test fun decide_healthy_session_returns_none() {
        assertEquals(
            OfflineStateRepair.NONE,
            OfflineStateGuard.decide(true, "abc", listOf(start, text, text)),
        )
    }

    @Test fun decide_case1_flag_without_session_full_reset() {
        assertEquals(OfflineStateRepair.FULL_RESET, OfflineStateGuard.decide(true, "", emptyList()))
        assertEquals(OfflineStateRepair.FULL_RESET, OfflineStateGuard.decide(true, null, emptyList()))
        // 仅空白也算空 → 整体重置
        assertEquals(OfflineStateRepair.FULL_RESET, OfflineStateGuard.decide(true, "   ", emptyList()))
    }

    @Test fun decide_case2_orphan_session_clears_session_id() {
        assertEquals(OfflineStateRepair.CLEAR_SESSION_ID, OfflineStateGuard.decide(false, "abc", emptyList()))
    }

    @Test fun decide_case3_missing_start_marker_full_reset() {
        assertEquals(OfflineStateRepair.FULL_RESET, OfflineStateGuard.decide(true, "abc", listOf(text, text)))
    }

    @Test fun decide_case4_trailing_end_marker_full_reset() {
        assertEquals(OfflineStateRepair.FULL_RESET, OfflineStateGuard.decide(true, "abc", listOf(start, text, end)))
    }

    @Test fun decide_fully_clean_returns_none() {
        assertEquals(OfflineStateRepair.NONE, OfflineStateGuard.decide(false, null, emptyList()))
        assertEquals(OfflineStateRepair.NONE, OfflineStateGuard.decide(false, "  ", emptyList()))
    }

    // ── shouldShowRecoveryPrompt ──

    @Test fun recovery_false_when_not_offline_or_blank_session() {
        assertFalse(OfflineStateGuard.shouldShowRecoveryPrompt(false, "abc", 1_000L, 2_000L))
        assertFalse(OfflineStateGuard.shouldShowRecoveryPrompt(true, "   ", 1_000L, 2_000L))
        assertFalse(OfflineStateGuard.shouldShowRecoveryPrompt(true, null, 1_000L, 2_000L))
    }

    @Test fun recovery_true_when_offline_but_no_messages() {
        assertTrue(OfflineStateGuard.shouldShowRecoveryPrompt(true, "abc", null, 2_000L))
    }

    @Test fun recovery_depends_on_last_message_age() {
        // 5 分钟前 → 未超 10 分钟 → false
        assertFalse(OfflineStateGuard.shouldShowRecoveryPrompt(true, "abc", 300_000L, 600_000L))
        // 刚好 10 分钟 → 不算超过（> 严格） → false
        assertFalse(OfflineStateGuard.shouldShowRecoveryPrompt(true, "abc", 0L, 600_000L))
        // 超过 10 分钟 → true
        assertTrue(OfflineStateGuard.shouldShowRecoveryPrompt(true, "abc", 0L, 600_001L))
    }
}
