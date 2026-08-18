package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 变更确认卡脱敏表示单测（图纸一 R1 承接·补覆盖）：改期/取消 → LLM 系统记录「[系统记录：和<用户名>确认…]」，
 * 称呼用真实用户名（不传回退「用户」·人称=你+用户名·与红包/礼物卡/确认卡同口径）。
 */
class FutureMeetingChangeDataTest {

    private fun change(kind: String, from: String? = null, to: String? = null) = FutureMeetingChangeData(
        appointmentUuid = "a", changeKind = kind, oldWhenDisplay = from, newWhenDisplay = to,
    )

    @Test fun reschedule_default_fallsBackToUser() {
        assertEquals(
            "[系统记录：和用户确认是否把约定从 周六15点 改到 周日10点]",
            change(FutureMeetingChangeData.KIND_RESCHEDULE, from = "周六15点", to = "周日10点").llmRepresentation(),
        )
    }

    @Test fun reschedule_usesUserName() {
        assertEquals(
            "[系统记录：和小明确认是否把约定改到 周日10点]",
            change(FutureMeetingChangeData.KIND_RESCHEDULE, to = "周日10点").llmRepresentation("小明"),
        )
    }

    @Test fun cancel_usesUserName() {
        assertEquals(
            "[系统记录：和小明确认是否取消 周六15点 的约定]",
            change(FutureMeetingChangeData.KIND_CANCEL, from = "周六15点").llmRepresentation("小明"),
        )
    }
}
