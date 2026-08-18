package com.situ.aichat.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `OfflineMeetingAction.fromToolCallArguments` / `lenientParseToolCallArguments` tests (S2) —
 * reverse-derived from iOS `OfflineMeetingActionTests` (strict snake_case decode, unknown-name throws,
 * missing-field nulls, old-data farewell) + the lenient path's snake/camel acceptance.
 */
class OfflineToolCallArgsTest {

    // ── fromToolCallArguments (strict, snake_case) ──

    @Test fun strict_parses_suggest_args() {
        val a = OfflineMeetingAction.fromToolCallArguments(
            "suggest_offline_meeting",
            """{"location":"滨江","activity":"散步","invitation":"一起去吹吹风吧","hidden_tension":"她今天心里藏着一件事","tension_hint":"今天的她比平时安静"}""",
        )
        assertEquals(OfflineMeetingActionType.SUGGEST_MEETING, a.action)
        assertEquals("滨江", a.location)
        assertEquals("散步", a.activity)
        assertEquals("一起去吹吹风吧", a.invitation)
        assertEquals("她今天心里藏着一件事", a.hiddenTension)
        assertEquals("今天的她比平时安静", a.tensionHint)
        assertNull(a.farewell)
    }

    @Test fun strict_parses_end_final_mood() {
        val a = OfflineMeetingAction.fromToolCallArguments("end_offline_meeting", """{"final_mood":"warm"}""")
        assertEquals(OfflineMeetingActionType.END_MEETING, a.action)
        assertEquals("warm", a.finalMood)
        assertNull(a.location)
        assertNull(a.farewell)
    }

    @Test fun strict_parses_legacy_end_with_farewell() {
        val a = OfflineMeetingAction.fromToolCallArguments("end_offline_meeting", """{"farewell":"夜色渐深，我们在路口道别。"}""")
        assertEquals(OfflineMeetingActionType.END_MEETING, a.action)
        assertEquals("夜色渐深，我们在路口道别。", a.farewell)
        assertNull(a.finalMood)
    }

    @Test fun strict_unknown_tool_name_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            OfflineMeetingAction.fromToolCallArguments("offline_meeting_unknown", """{"farewell":"bye"}""")
        }
    }

    @Test fun strict_missing_fields_stay_null() {
        val suggest = OfflineMeetingAction.fromToolCallArguments("suggest_offline_meeting", """{"invitation":"跟我一起走吧"}""")
        assertEquals(OfflineMeetingActionType.SUGGEST_MEETING, suggest.action)
        assertNull(suggest.location)
        assertNull(suggest.activity)
        assertEquals("跟我一起走吧", suggest.invitation)
        assertNull(suggest.hiddenTension)
        assertNull(suggest.tensionHint)

        val end = OfflineMeetingAction.fromToolCallArguments("end_offline_meeting", "{}")
        assertEquals(OfflineMeetingActionType.END_MEETING, end.action)
        assertNull(end.farewell)
    }

    // ── lenientParseToolCallArguments (snake + camel) ──

    @Test fun lenient_accepts_snake_case() {
        val a = OfflineMeetingAction.lenientParseToolCallArguments(
            "suggest_offline_meeting",
            """{"location":"公园","activity":"散步","invitation":"走吧","hidden_tension":"她有心事","tension_hint":"今天安静"}""",
        )!!
        assertEquals("公园", a.location)
        assertEquals("她有心事", a.hiddenTension)
        assertEquals("今天安静", a.tensionHint)
    }

    @Test fun lenient_also_accepts_camel_case() {
        val a = OfflineMeetingAction.lenientParseToolCallArguments(
            "suggest_offline_meeting",
            """{"location":"公园","hiddenTension":"她有心事","tensionHint":"今天安静"}""",
        )!!
        assertEquals("她有心事", a.hiddenTension) // camelCase 也接受
        assertEquals("今天安静", a.tensionHint)

        val end = OfflineMeetingAction.lenientParseToolCallArguments("end_offline_meeting", """{"finalMood":"sweet"}""")!!
        assertEquals("sweet", end.finalMood)
    }

    @Test fun lenient_returns_null_on_bad_json_or_unknown_name() {
        assertNull(OfflineMeetingAction.lenientParseToolCallArguments("suggest_offline_meeting", "not json"))
        assertNull(OfflineMeetingAction.lenientParseToolCallArguments("offline_meeting_unknown", "{}"))
    }
}
