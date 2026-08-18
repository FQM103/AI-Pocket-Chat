package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.offline.OfflineMarkerStartPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线下见面 prompt 历史过滤纯逻辑测试（10.2c-3c）：断言反推 iOS PromptBuilder
 * `filteredMessages` 的 `.offlineMarkerStart` 分支 + `extractTensionSeedFromSessionMessages`。
 */
class PromptBuilderOfflineFilterTest {

    private fun markerStart(sessionId: String, tensionSeed: String?, ts: Long): MessageEntity =
        MessageEntity(
            messageUUID = "m$ts",
            conversationUuid = "c1",
            roleRaw = "assistant",
            content = OfflineMarkerStartPayload("公园", "散步", "下午3:30", tensionSeed).makeContent(),
            timestamp = ts,
            isOfflineMode = true,
            offlineSessionId = sessionId,
            messageKindRaw = MessageKind.OFFLINE_MARKER_START.raw,
        )

    private fun narrative(sessionId: String, text: String, ts: Long): MessageEntity =
        MessageEntity(
            messageUUID = "n$ts",
            conversationUuid = "c1",
            roleRaw = "assistant",
            content = text,
            timestamp = ts,
            isOfflineMode = true,
            offlineSessionId = sessionId,
        )

    // ── shouldKeepOfflineMarkerStart ──

    @Test fun marker_kept_only_when_offline_and_session_matches() {
        assertTrue(PromptBuilder.shouldKeepOfflineMarkerStart(true, "s1", "s1"))
    }

    @Test fun marker_dropped_when_not_in_offline_mode() {
        assertFalse(PromptBuilder.shouldKeepOfflineMarkerStart(false, "s1", "s1"))
    }

    @Test fun marker_dropped_when_session_differs() {
        assertFalse(PromptBuilder.shouldKeepOfflineMarkerStart(true, "s1", "s2"))
    }

    @Test fun marker_dropped_on_null_or_blank_session() {
        assertFalse(PromptBuilder.shouldKeepOfflineMarkerStart(true, null, "s1"))
        assertFalse(PromptBuilder.shouldKeepOfflineMarkerStart(true, "", "s1"))
        assertFalse(PromptBuilder.shouldKeepOfflineMarkerStart(true, "s1", null))
        assertFalse(PromptBuilder.shouldKeepOfflineMarkerStart(true, "s1", ""))
    }

    // ── extractTensionSeedFromSessionMessages ──

    @Test fun tension_seed_extracted_from_marker_payload() {
        val msgs = listOf(
            markerStart("s1", "她今天其实有点心事没说", 1),
            narrative("s1", "[环境]咖啡馆很安静[/环境]", 2),
        )
        assertEquals("她今天其实有点心事没说", PromptBuilder.extractTensionSeedFromSessionMessages(msgs))
    }

    @Test fun tension_seed_null_when_marker_has_no_seed() {
        val msgs = listOf(markerStart("s1", null, 1), narrative("s1", "走着走着", 2))
        assertNull(PromptBuilder.extractTensionSeedFromSessionMessages(msgs))
    }

    @Test fun tension_seed_null_when_no_marker() {
        assertNull(PromptBuilder.extractTensionSeedFromSessionMessages(listOf(narrative("s1", "随便聊聊", 1))))
    }

    @Test fun tension_seed_takes_latest_marker_when_multiple() {
        // 倒序取首个非空（= 最新一张入场标记，1:1 iOS reversed）。
        val msgs = listOf(
            markerStart("s0", "旧种子", 1),
            markerStart("s1", "新种子", 2),
        )
        assertEquals("新种子", PromptBuilder.extractTensionSeedFromSessionMessages(msgs))
    }
}
