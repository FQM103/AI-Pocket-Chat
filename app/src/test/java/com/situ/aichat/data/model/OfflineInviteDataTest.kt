package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline invite/end card JSON codec tests (P10.2a). Asserts byte-compatibility with iOS `OfflineInviteData`
 * (camelCase keys: tensionHint/hiddenTension/finalMood) and that [OfflineInviteJson.parse] guards like iOS
 * `parseOfflineInvite` (starts with `{`, type ∈ {offline_invite, offline_end}).
 */
class OfflineInviteDataTest {

    @Test fun invite_round_trip_and_camelcase_keys() {
        val json = OfflineInviteJson.makeInvite(
            location = "公园",
            activity = "散步",
            invitation = "走吧~",
            tensionHint = "她今天有点心事",
            hiddenTension = "她其实在意你昨天没回消息",
        )
        // iOS persisted-card keys are camelCase → byte-compatible.
        assertTrue(json.contains("\"type\":\"offline_invite\""))
        assertTrue(json.contains("\"tensionHint\":\"她今天有点心事\""))
        assertTrue(json.contains("\"hiddenTension\":"))
        val parsed = OfflineInviteJson.parse(json)
        assertEquals("公园", parsed?.location)
        assertEquals("散步", parsed?.activity)
        assertEquals("她今天有点心事", parsed?.tensionHint)
        assertNull(parsed?.responded)
    }

    @Test fun blank_hint_is_omitted() {
        val parsed = OfflineInviteJson.parse(OfflineInviteJson.makeInvite("咖啡馆", "喝咖啡", "走~", tensionHint = "  "))
        assertNull(parsed?.tensionHint)
    }

    @Test fun end_card_round_trip() {
        val parsed = OfflineInviteJson.parse(OfflineInviteJson.makeEnd("warm"))
        assertEquals("offline_end", parsed?.type)
        assertEquals("warm", parsed?.finalMood)
    }

    @Test fun parse_rejects_non_object_and_wrong_type() {
        assertNull(OfflineInviteJson.parse("not json"))
        assertNull(OfflineInviteJson.parse(""))
        assertNull(OfflineInviteJson.parse("""{"type":"call_record","duration":1,"startTime":"x","transcript":[]}"""))
        assertNull(OfflineInviteJson.parse("""{"type":"red_packet","recordUUID":"x","amount":5,"blessingText":""}"""))
    }

    @Test fun parse_tolerates_unknown_keys_and_responded() {
        val parsed = OfflineInviteJson.parse(
            """{"type":"offline_invite","location":"江边","responded":"accepted","extra":"ignore"}""",
        )
        assertEquals("江边", parsed?.location)
        assertEquals("accepted", parsed?.responded)
    }
}
