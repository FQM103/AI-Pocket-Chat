package com.situ.aichat.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OfflineMeetingAction` text-marker parsing tests (P10.2c-1) — reverse-derived from iOS
 * `OfflineMeetingActionTests` (parseFromResponse 4-level degradation) plus Android-specific coverage
 * of the [系统记录] re-echo fallback and the snake_case JSON fallback (the path iOS decodes via
 * convertFromSnakeCase). Structured tool-arg parsing is not ported (Android sends no `tools`).
 */
class OfflineMeetingActionTest {

    // ── isOfflineMeetingTool ──

    @Test fun is_offline_meeting_tool_only_matches_offline_tools() {
        assertTrue(OfflineMeetingAction.isOfflineMeetingTool("suggest_offline_meeting"))
        assertTrue(OfflineMeetingAction.isOfflineMeetingTool("end_offline_meeting"))
        assertFalse(OfflineMeetingAction.isOfflineMeetingTool("create_calendar_event"))
    }

    // ── parseFromResponse: 文本标记提取 + 清理 ──

    @Test fun parse_extracts_markers_and_cleans_text() {
        val response = "我想和你多待一会儿。[offline_invite|中央公园|散步|走吧，去吹吹晚风]\n\n那我们晚点再回去。[offline_end]"
        val (cleanText, actions) = OfflineMeetingAction.parseFromResponse(response)

        assertEquals("我想和你多待一会儿。\n\n那我们晚点再回去。", cleanText)
        assertEquals(2, actions.size)
        assertEquals(OfflineMeetingActionType.SUGGEST_MEETING, actions[0].action)
        assertEquals("中央公园", actions[0].location)
        assertEquals("散步", actions[0].activity)
        assertEquals("走吧，去吹吹晚风", actions[0].invitation)
        assertEquals(OfflineMeetingActionType.END_MEETING, actions[1].action)
    }

    @Test fun parse_multiple_end_markers_all_become_end_actions() {
        val response = "我们先走到路口。[offline_end]\n\n到站后再认真道别。[offline_end]"
        val (cleanText, actions) = OfflineMeetingAction.parseFromResponse(response)

        assertEquals("我们先走到路口。\n\n到站后再认真道别。", cleanText)
        assertEquals(2, actions.size)
        assertTrue(actions.all { it.action == OfflineMeetingActionType.END_MEETING })
    }

    @Test fun parse_mixed_markers_ordered_by_original_position() {
        val response = "[offline_end]\n她想了想，又改了主意。\n[offline_invite|书店|逛逛|还是陪我去书店吧]\n[offline_end]"
        val (cleanText, actions) = OfflineMeetingAction.parseFromResponse(response)

        assertEquals("她想了想，又改了主意。", cleanText)
        assertEquals(3, actions.size)
        assertEquals(OfflineMeetingActionType.END_MEETING, actions[0].action)
        assertEquals(OfflineMeetingActionType.SUGGEST_MEETING, actions[1].action)
        assertEquals("书店", actions[1].location)
        assertEquals("逛逛", actions[1].activity)
        assertEquals("还是陪我去书店吧", actions[1].invitation)
        assertEquals(OfflineMeetingActionType.END_MEETING, actions[2].action)
    }

    @Test fun parse_only_markers_yields_empty_text_but_keeps_actions() {
        val response = "[offline_invite|美术馆|看展|陪我一起去逛逛吧]\n[offline_end]"
        val (cleanText, actions) = OfflineMeetingAction.parseFromResponse(response)

        assertTrue(cleanText.isEmpty())
        assertEquals(2, actions.size)
        assertEquals(OfflineMeetingActionType.SUGGEST_MEETING, actions[0].action)
        assertEquals("美术馆", actions[0].location)
        assertEquals("看展", actions[0].activity)
        assertEquals("陪我一起去逛逛吧", actions[0].invitation)
        assertEquals(OfflineMeetingActionType.END_MEETING, actions[1].action)
    }

    @Test fun parse_pseudo_markers_stay_in_text_no_actions() {
        val response = "这不是合法标记：[offline_invite|只写到这里]\n这个也不是：[offline_ending]"
        val (cleanText, actions) = OfflineMeetingAction.parseFromResponse(response)

        assertEquals(response, cleanText)
        assertTrue(actions.isEmpty())
    }

    // ── parseFromResponse: [系统记录] 复读邀约兜底 ──

    @Test fun parse_sysrecord_invite_echo_extracts_location_activity_and_cleans() {
        val response = "[系统记录：小琳的线下见面邀约卡片 | 地点=公园 | 活动=散步 | 台词=走吧]"
        val (cleanText, actions) = OfflineMeetingAction.parseFromResponse(response)

        assertEquals("", cleanText) // sysRecordAny 清除整段标签
        assertEquals(1, actions.size)
        assertEquals(OfflineMeetingActionType.SUGGEST_MEETING, actions[0].action)
        assertEquals("公园", actions[0].location)
        assertEquals("散步", actions[0].activity)
    }

    @Test fun parse_strips_residual_sysrecord_tags() {
        // 普通正文夹带一个被复读的系统记录标签（非邀约卡片）→ 标签被清除，正文保留，无动作。
        val response = "今天聊得很开心。[系统记录：进入线下见面模式]"
        val (cleanText, actions) = OfflineMeetingAction.parseFromResponse(response)

        assertEquals("今天聊得很开心。", cleanText)
        assertTrue(actions.isEmpty())
    }

    // ── parseFromResponse: JSON 兜底（snake_case，= iOS convertFromSnakeCase） ──

    @Test fun parse_json_fallback_invite_decodes_snake_case_and_removes_json() {
        val response =
            """好呀，那我们走吧 {"type":"offline_invite","location":"咖啡馆","activity":"喝咖啡","invitation":"走吧","hidden_tension":"她其实有点心事","tension_hint":"今天有点安静"}"""
        val (cleanText, actions) = OfflineMeetingAction.parseFromResponse(response)

        assertEquals("好呀，那我们走吧", cleanText)
        assertEquals(1, actions.size)
        val a = actions[0]
        assertEquals(OfflineMeetingActionType.SUGGEST_MEETING, a.action)
        assertEquals("咖啡馆", a.location)
        assertEquals("喝咖啡", a.activity)
        assertEquals("走吧", a.invitation)
        assertEquals("她其实有点心事", a.hiddenTension)   // snake_case hidden_tension
        assertEquals("今天有点安静", a.tensionHint)        // snake_case tension_hint
    }

    @Test fun parse_json_fallback_end_decodes_final_mood() {
        val response = """该走了。{"type":"offline_end","final_mood":"warm","farewell":"路口再见"}"""
        val (cleanText, actions) = OfflineMeetingAction.parseFromResponse(response)

        assertEquals("该走了。", cleanText)
        assertEquals(1, actions.size)
        assertEquals(OfflineMeetingActionType.END_MEETING, actions[0].action)
        assertEquals("warm", actions[0].finalMood)        // snake_case final_mood
        assertEquals("路口再见", actions[0].farewell)
    }

    @Test fun parse_json_fallback_ignored_when_text_markers_present() {
        // 已有 [offline_invite|…] 命中 → JSON 兜底不触发（即便正文里还有 offline_invite JSON）。
        val response =
            """[offline_invite|公园|散步|走吧] 另外 {"type":"offline_end","final_mood":"sweet"}"""
        val (_, actions) = OfflineMeetingAction.parseFromResponse(response)

        assertEquals(1, actions.size)
        assertEquals(OfflineMeetingActionType.SUGGEST_MEETING, actions[0].action)
    }

    @Test fun parse_plain_text_no_markers_returns_unchanged_no_actions() {
        val response = "就是普通聊天，没有任何线下标记。"
        val (cleanText, actions) = OfflineMeetingAction.parseFromResponse(response)

        assertEquals(response, cleanText)
        assertTrue(actions.isEmpty())
    }
}
