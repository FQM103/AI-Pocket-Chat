package com.situ.aichat.meeting

import com.situ.aichat.data.model.MeetingCandidateIntent
import com.situ.aichat.data.model.MeetingConfidence
import com.situ.aichat.data.model.MeetingProposedBy
import com.situ.aichat.data.model.MeetingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具快路引擎单测：工具名判定 / 工具定义 schema / tool_call 参数解析 / 文本暗号提取与擦除。
 */
class FutureMeetingToolTest {

    @Test fun toolName_matches() {
        assertTrue(FutureMeetingTool.isFutureMeetingTool("propose_future_meeting"))
        assertFalse(FutureMeetingTool.isFutureMeetingTool("suggest_offline_meeting"))
        assertFalse(FutureMeetingTool.isFutureMeetingTool("calendar_action"))
    }

    @Test fun toolDefinition_shape() {
        val def = FutureMeetingTool.toolDefinition
        assertEquals("function", def.type)
        assertEquals("propose_future_meeting", def.function.name)
        // required 锁定 when_text + activity（至少时间与内容）
        assertEquals(listOf("when_text", "activity"), def.function.parameters.required)
        assertTrue(def.function.parameters.properties.containsKey("hidden_tension"))
    }

    // ── 工具路：tool_call 参数 ──

    @Test fun toolCall_validArgs_buildsToolCandidate() {
        val c = FutureMeetingTool.candidateFromToolCall(
            """{"when_text":"周六下午","location":"美术馆","activity":"看展","proposed_by":"user","hidden_tension":"她有点紧张"}""",
        )!!
        assertEquals(MeetingCandidateIntent.NEW, c.intent)
        assertEquals(MeetingSource.TOOL, c.source)
        assertEquals(MeetingConfidence.HIGH, c.confidence)
        assertEquals("周六下午", c.rawWhen)
        assertEquals("美术馆", c.location)
        assertEquals("看展", c.activity)
        assertEquals(MeetingProposedBy.USER, c.proposedBy)
        assertEquals("她有点紧张", c.hiddenTensionSeed)
    }

    @Test fun toolCall_emptyShell_null() {
        // 无时间无活动无 iso → 空壳
        assertNull(FutureMeetingTool.candidateFromToolCall("""{"location":"公园"}"""))
    }

    @Test fun toolCall_isoOnly_isAccepted() {
        val c = FutureMeetingTool.candidateFromToolCall("""{"iso_datetime":"2026-06-27T15:00:00+08:00"}""")!!
        assertEquals("2026-06-27T15:00:00+08:00", c.isoDateTime)
    }

    @Test fun toolCall_garbageJson_null() {
        assertNull(FutureMeetingTool.candidateFromToolCall("not json"))
    }

    // ── 文本暗号路：[future_meeting]{...} ──

    @Test fun marker_extractedAndErasedFromText() {
        val reply = "好呀，那就这么说定啦～\n[future_meeting]{\"when_text\":\"周六下午\",\"activity\":\"看电影\"}"
        val (clean, candidates) = FutureMeetingTool.parseProposalMarkers(reply)
        assertEquals(1, candidates.size)
        assertEquals(MeetingSource.FALLBACK, candidates[0].source)
        assertEquals("看电影", candidates[0].activity)
        // 标记必须从正文擦干净（用户不可见）
        assertFalse(clean.contains("future_meeting"))
        assertFalse(clean.contains("{"))
        assertEquals("好呀，那就这么说定啦～", clean)
    }

    @Test fun marker_absent_returnsOriginalAndEmpty() {
        val reply = "今天天气真好啊"
        val (clean, candidates) = FutureMeetingTool.parseProposalMarkers(reply)
        assertEquals("今天天气真好啊", clean)
        assertTrue(candidates.isEmpty())
    }

    @Test fun marker_emptyShell_noCandidateButStillErased() {
        // 暗号在但 JSON 空壳（无时间无活动）→ 不建候选，但标记仍须擦除（绝不泄露给用户）
        val reply = "嗯[future_meeting]{\"location\":\"公园\"}"
        val (clean, candidates) = FutureMeetingTool.parseProposalMarkers(reply)
        assertTrue(candidates.isEmpty())
        assertFalse(clean.contains("future_meeting"))
        assertEquals("嗯", clean)
    }

    @Test fun marker_bareWithoutJson_stillErased() {
        // 只有裸标记没 JSON → 也擦掉，不泄露
        val (clean, candidates) = FutureMeetingTool.parseProposalMarkers("好的[future_meeting] 再见")
        assertTrue(candidates.isEmpty())
        assertFalse(clean.contains("future_meeting"))
    }

    @Test fun marker_embeddedBraceInValue_fullyParsedAndErased() {
        // 值里含 } 的合法 JSON：配平扫描须完整提取（不在串内 } 处截断）→ 候选成 + 正文零 JSON 残留。
        // 旧「首个 } 截断」实现会:① 截出非法 JSON 建不成候选 ② 把尾巴「…然后看电影"}」漏给用户。
        val reply = "好的[future_meeting]{\"when_text\":\"周六\",\"activity\":\"吃饭}然后看电影\"}"
        val (clean, candidates) = FutureMeetingTool.parseProposalMarkers(reply)
        assertEquals(1, candidates.size)
        assertEquals("吃饭}然后看电影", candidates[0].activity)
        assertEquals("周六", candidates[0].rawWhen)
        assertEquals("好的", clean)
        assertFalse(clean.contains("future_meeting"))
        assertFalse(clean.contains("然后看电影")) // 尾巴不得泄露
    }
}
