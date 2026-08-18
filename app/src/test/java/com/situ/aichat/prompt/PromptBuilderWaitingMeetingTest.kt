package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import io.mockk.every
import io.mockk.mockk
import com.situ.aichat.data.local.entity.UserProfileEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 等待期【待见约定】prompt 段纯函数测（Phase 9）：仅 confirmed + 未来才注入；段标题避开 DirtyMessageDetector 保留标题。
 * 断言从规格独立反推（不照搬实现）。
 */
class PromptBuilderWaitingMeetingTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-06-25T10:00:00Z")

    private fun appt(status: String = "confirmed", daysAhead: Long = 2, activity: String = "看展", location: String = "美术馆") =
        MeetingAppointmentEntity(
            uuid = "a1", characterUuid = "c1", conversationUuid = "conv1", status = status,
            scheduledAt = now.plusSeconds(daysAhead * 86_400).toEpochMilli(),
            timeGranularity = "dayOnly", activity = activity, location = location,
        )

    @Test fun confirmedFuture_injectsSection_withActivityAndLocation() {
        val out = PromptBuilder.buildWaitingMeetingPrompt(appt(), userProfile = null, now = now, zone = zone)
        assertTrue(out != null)
        assertTrue("应含段标题【待见约定】", out!!.contains("【待见约定】"))
        assertTrue("应含活动", out.contains("看展"))
        assertTrue("应含地点", out.contains("美术馆"))
    }

    @Test fun sectionTitle_avoidsDirtyMessageDetectorReservedTitles() {
        val out = PromptBuilder.buildWaitingMeetingPrompt(appt(), userProfile = null, now = now, zone = zone)!!
        // 强耦合铁律：绝不复用这些 DirtyMessageDetector 强依赖的段标题（否则破坏脏消息检测）。
        assertFalse(out.contains("【长期事实】"))
        assertFalse(out.contains("【见面 ·"))
        assertFalse(out.contains("【见面·"))
        assertFalse(out.contains("【你今天完整的日程】"))
    }

    @Test fun usesUserNickname_whenPresent() {
        val profile = mockk<UserProfileEntity> { every { nickname } returns "小明" }
        val out = PromptBuilder.buildWaitingMeetingPrompt(appt(), userProfile = profile, now = now, zone = zone)!!
        assertTrue(out.contains("小明"))
    }

    @Test fun proposed_notInjected() {
        // 未确认（proposed）= 还在确认卡阶段，不算「待见约定」。
        assertNull(PromptBuilder.buildWaitingMeetingPrompt(appt(status = "proposed"), userProfile = null, now = now, zone = zone))
    }

    @Test fun pastAppointment_notInjected() {
        // 已过点 → 交 Phase 10/11，不再「待见」。
        assertNull(PromptBuilder.buildWaitingMeetingPrompt(appt(daysAhead = -1), userProfile = null, now = now, zone = zone))
    }
}
