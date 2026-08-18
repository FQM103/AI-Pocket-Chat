package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Call-record card JSON codec tests (P10.1i). Asserts the persisted shape is byte-compatible with iOS
 * `CallRecordData` (`type`/`duration`/`startTime`/`transcript:[{role,text}]`) and that [CallRecordJson.parse]
 * guards exactly like iOS `parseCallRecord` (must start with `{`, `type == "call_record"`).
 */
class CallRecordDataTest {

    private fun sample() = CallRecordData(
        type = "call_record",
        duration = 125,
        startTime = "2026-06-03T12:34:56Z",
        transcript = listOf(
            CallRecordTranscriptEntry(role = "user", text = "在吗"),
            CallRecordTranscriptEntry(role = "assistant", text = "在的，怎么了"),
        ),
    )

    @Test fun round_trip_preserves_all_fields() {
        val decoded = CallRecordJson.parse(CallRecordJson.encode(sample()))
        assertEquals(sample(), decoded)
    }

    @Test fun encoded_json_matches_ios_field_names() {
        val json = CallRecordJson.encode(sample())
        // iOS field names → byte-compatible with iOS backups.
        assertTrue(json.contains("\"type\":\"call_record\""))
        assertTrue(json.contains("\"duration\":125"))
        assertTrue(json.contains("\"startTime\":\"2026-06-03T12:34:56Z\""))
        assertTrue(json.contains("\"role\":\"user\""))
        assertTrue(json.contains("\"text\":\"在吗\""))
    }

    @Test fun parse_rejects_non_object() {
        assertNull(CallRecordJson.parse("not json"))
        assertNull(CallRecordJson.parse(""))
        assertNull(CallRecordJson.parse("plain message text"))
    }

    @Test fun parse_rejects_wrong_type() {
        // A red-packet JSON (different discriminator) must NOT parse as a call record.
        assertNull(CallRecordJson.parse("""{"type":"red_packet","recordUUID":"x","amount":5,"blessingText":""}"""))
        assertNull(CallRecordJson.parse("""{"type":"gift_card","giftItemId":"x"}"""))
    }

    @Test fun parse_tolerates_unknown_keys_and_empty_transcript() {
        val decoded = CallRecordJson.parse(
            """{"type":"call_record","duration":0,"startTime":"2026-01-01T00:00:00Z","transcript":[],"extra":"ignored"}""",
        )
        assertEquals(0, decoded?.duration)
        assertTrue(decoded?.transcript?.isEmpty() == true)
    }

    // --- VU3 hadTtsFailure (2026-07-12): safe additive field, byte-compatible with old cards ---

    /**
     * E14 字节回归钉: a normal call (hadTtsFailure=false) must encode BYTE-IDENTICAL to before the field
     * existed (encodeDefaults=false omits it). Golden is a hand-typed literal, NOT encode-derived.
     */
    @Test fun default_no_failure_encodes_byte_identical_and_omits_key() {
        val golden =
            """{"type":"call_record","duration":125,"startTime":"2026-06-03T12:34:56Z","transcript":[{"role":"user","text":"在吗"},{"role":"assistant","text":"在的，怎么了"}]}"""
        val encoded = CallRecordJson.encode(sample())
        assertEquals(golden, encoded)
        assertFalse(encoded.contains("hadTtsFailure"))
    }

    /** E15: an old card (no key) parses with hadTtsFailure defaulting to false. */
    @Test fun old_card_without_key_parses_as_no_failure() {
        val decoded = CallRecordJson.parse(
            """{"type":"call_record","duration":0,"startTime":"2026-01-01T00:00:00Z","transcript":[{"role":"user","text":"hi"}]}""",
        )
        assertEquals(false, decoded?.hadTtsFailure)
    }

    /** E13 (encode side): a failed call carries the key as true and round-trips. */
    @Test fun failure_card_round_trips_and_encodes_the_key() {
        val card = sample().copy(hadTtsFailure = true)
        val json = CallRecordJson.encode(card)
        assertTrue(json.contains("\"hadTtsFailure\":true"))
        assertEquals(card, CallRecordJson.parse(json))
    }
}
