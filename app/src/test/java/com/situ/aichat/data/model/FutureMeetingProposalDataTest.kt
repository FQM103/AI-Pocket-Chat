package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认卡数据快照单测：JSON 往返 / 拒绝非本类型 / 省略 null / llmRepresentation 脱敏。
 */
class FutureMeetingProposalDataTest {

    @Test fun encode_parse_roundTrip() {
        val data = FutureMeetingProposalData(
            appointmentUuid = "appt-1",
            whenDisplay = "6月27日 周六 15:00",
            location = "猫咖",
            activity = "撸猫",
            invitation = "周六一起去猫咖呀～",
            tensionHint = "她有点累",
            responded = null,
        )
        val encoded = FutureMeetingProposalJson.encode(data)
        assertEquals(data, FutureMeetingProposalJson.parse(encoded))
    }

    @Test fun parse_rejectsWrongType() {
        assertNull(FutureMeetingProposalJson.parse("""{"type":"red_packet","appointmentUuid":"x"}"""))
    }

    @Test fun parse_garbage_null() {
        assertNull(FutureMeetingProposalJson.parse("not json"))
    }

    @Test fun encode_omitsNullFields() {
        val data = FutureMeetingProposalData(appointmentUuid = "a", activity = "看展")
        val encoded = FutureMeetingProposalJson.encode(data)
        assertFalse("null location 不应写出", encoded.contains("location"))
        assertTrue(encoded.contains("future_meeting_proposal"))
        assertTrue(encoded.contains("看展"))
    }

    @Test fun llm_representation_full() {
        val data = FutureMeetingProposalData(
            appointmentUuid = "a", whenDisplay = "6月27日 周六", location = "猫咖", activity = "撸猫",
        )
        assertEquals("[系统记录：向用户提出了未来见面的约定 | 时间=6月27日 周六 | 地点=猫咖 | 活动=撸猫]", data.llmRepresentation())
    }

    @Test fun llm_representation_empty() {
        assertEquals("[系统记录：向用户提出了未来见面的约定]", FutureMeetingProposalData(appointmentUuid = "a").llmRepresentation())
    }

    /** 图纸一 R1 承接·指名：传用户名 → 称呼用真名（不传回退「用户」·上面两例已证）。 */
    @Test fun llm_representation_usesUserName() {
        assertEquals(
            "[系统记录：向小明提出了未来见面的约定 | 时间=6月27日 周六 | 地点=猫咖 | 活动=撸猫]",
            FutureMeetingProposalData(appointmentUuid = "a", whenDisplay = "6月27日 周六", location = "猫咖", activity = "撸猫").llmRepresentation("小明"),
        )
    }

    @Test fun responded_constants() {
        assertEquals("accepted", FutureMeetingProposalData.RESPONDED_ACCEPTED)
        assertEquals("declined", FutureMeetingProposalData.RESPONDED_DECLINED)
    }
}
