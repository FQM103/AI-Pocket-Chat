package com.situ.aichat.meeting

import com.situ.aichat.data.model.MeetingTimeGranularity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 宽限期判定纯函数单测。exact 过点 3h（含边界）/ dayOnly · vague 到次日 0 点。注入 Asia/Shanghai 确定性。
 */
class MeetingArrivalPolicyTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // ── exact：过点 3h ──

    @Test fun exact_withinGrace_notMissed() {
        val sched = millis(2026, 6, 27, 15, 0)
        val now = millis(2026, 6, 27, 17, 59) // 过点 2h59m
        assertTrue(MeetingArrivalPolicy.isWithinArrivalWindow(sched, MeetingTimeGranularity.EXACT, now, zone))
        assertFalse(MeetingArrivalPolicy.isMissed(sched, MeetingTimeGranularity.EXACT, now, zone))
    }

    @Test fun exact_atDeadline_inclusive_notMissed() {
        val sched = millis(2026, 6, 27, 15, 0)
        val now = millis(2026, 6, 27, 18, 0) // 正好 3h（含边界仍可赴约）
        assertTrue(MeetingArrivalPolicy.isWithinArrivalWindow(sched, MeetingTimeGranularity.EXACT, now, zone))
        assertFalse(MeetingArrivalPolicy.isMissed(sched, MeetingTimeGranularity.EXACT, now, zone))
    }

    @Test fun exact_pastGrace_missed() {
        val sched = millis(2026, 6, 27, 15, 0)
        val now = millis(2026, 6, 27, 18, 1) // 过点 3h01m
        assertFalse(MeetingArrivalPolicy.isWithinArrivalWindow(sched, MeetingTimeGranularity.EXACT, now, zone))
        assertTrue(MeetingArrivalPolicy.isMissed(sched, MeetingTimeGranularity.EXACT, now, zone))
    }

    // ── dayOnly / vague：到次日 0 点 ──

    @Test fun dayOnly_lateSameNight_notMissed() {
        val sched = millis(2026, 6, 27, 19, 0)
        val now = millis(2026, 6, 27, 23, 30) // 当晚仍可补赴
        assertFalse(MeetingArrivalPolicy.isMissed(sched, MeetingTimeGranularity.DAY_ONLY, now, zone))
    }

    @Test fun dayOnly_afterMidnight_missed() {
        val sched = millis(2026, 6, 27, 19, 0)
        val now = millis(2026, 6, 28, 0, 1) // 次日 0:01
        assertTrue(MeetingArrivalPolicy.isMissed(sched, MeetingTimeGranularity.DAY_ONLY, now, zone))
    }

    @Test fun vague_sameDeadlineAsDayOnly() {
        val sched = millis(2026, 6, 27, 19, 0)
        assertFalse(
            MeetingArrivalPolicy.isMissed(sched, MeetingTimeGranularity.VAGUE, millis(2026, 6, 27, 23, 59), zone),
        )
        assertTrue(
            MeetingArrivalPolicy.isMissed(sched, MeetingTimeGranularity.VAGUE, millis(2026, 6, 28, 0, 1), zone),
        )
    }

    // ── 倒数小条 ↔ 到点「出发赴约」按钮两态判定（10d·互斥）──

    @Test fun countdownState_confirmedFuture_true() {
        assertTrue(MeetingArrivalPolicy.isCountdownState("confirmed", millis(2026, 6, 28, 12, 0), millis(2026, 6, 27, 12, 0)))
    }

    @Test fun countdownState_confirmedAlreadyStarted_false() {
        // 已到点 → 不再是倒数态（交到点变身按钮）
        assertFalse(MeetingArrivalPolicy.isCountdownState("confirmed", millis(2026, 6, 27, 11, 0), millis(2026, 6, 27, 12, 0)))
    }

    @Test fun countdownState_proposedFuture_false() {
        // 还没确认（待确认卡阶段）→ 不上小条
        assertFalse(MeetingArrivalPolicy.isCountdownState("proposed", millis(2026, 6, 28, 12, 0), millis(2026, 6, 27, 12, 0)))
    }

    @Test fun arrivalState_startedWithinGrace_true() {
        val sched = millis(2026, 6, 27, 15, 0)
        assertTrue(MeetingArrivalPolicy.isArrivalState("confirmed", sched, MeetingTimeGranularity.EXACT, millis(2026, 6, 27, 16, 0), zone))
    }

    @Test fun arrivalState_stillFuture_false_belongsToCountdown() {
        val sched = millis(2026, 6, 27, 15, 0)
        assertFalse(MeetingArrivalPolicy.isArrivalState("confirmed", sched, MeetingTimeGranularity.EXACT, millis(2026, 6, 27, 14, 0), zone))
    }

    @Test fun arrivalState_pastGrace_false() {
        val sched = millis(2026, 6, 27, 15, 0)
        assertFalse(MeetingArrivalPolicy.isArrivalState("confirmed", sched, MeetingTimeGranularity.EXACT, millis(2026, 6, 27, 18, 1), zone))
    }

    @Test fun arrivalState_proposed_false() {
        val sched = millis(2026, 6, 27, 15, 0)
        assertFalse(MeetingArrivalPolicy.isArrivalState("proposed", sched, MeetingTimeGranularity.EXACT, millis(2026, 6, 27, 16, 0), zone))
    }
}
