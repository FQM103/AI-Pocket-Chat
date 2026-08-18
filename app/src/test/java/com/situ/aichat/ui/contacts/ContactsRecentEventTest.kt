package com.situ.aichat.ui.contacts

import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [pickRecentEvent] 纯函数边界单测（图纸一 §7 T1·断言从 §3.2 算法独立反推，非照搬实现）。
 * 覆盖 T1-1 窗口边界 / T1-2 初始行排除 / T1-3 legacy 排除 / T1-4 空链 trim / T1-5 两源竞争。
 */
class ContactsRecentEventTest {

    private val now = 1_700_000_000_000L
    private val window = RECENT_EVENT_WINDOW_MILLIS // 14 天

    private fun milestone(establishedAt: Long, reason: String = "关系调整", name: String = "朋友") =
        MilestoneEntity(
            uuid = "m", characterUuid = "c", relationshipName = name,
            establishedDate = establishedAt, reason = reason,
        )

    private fun meeting(startedAt: Long, kind: String = "meeting", activity: String = "", location: String = "") =
        OfflineMeetingMemoryEntity(
            uuid = "o", characterUuid = "c", startedAtMillis = startedAt,
            kindRaw = kind, activity = activity, location = location,
            createdAtMillis = 0L, updatedAtMillis = 0L,
        )

    // ── T1-1 窗口边界（±1 精度）──

    @Test fun `恰14天整_含`() {
        assertTrue(pickRecentEvent(milestone(now - window), null, now) is RecentEvent.Milestone)
    }

    @Test fun `14天加1ms_不含`() {
        assertNull(pickRecentEvent(milestone(now - window - 1), null, now))
    }

    @Test fun `未来时间戳_不含`() {
        assertNull(pickRecentEvent(milestone(now + 1000), null, now))
    }

    // ── T1-2 初始行排除 ──

    @Test fun `初始设定_永不作纪事`() {
        assertNull(pickRecentEvent(milestone(now - 1000, reason = "初始设定"), null, now))
    }

    @Test fun `关系调整_正常入选`() {
        val ev = pickRecentEvent(milestone(now - 1000, reason = "关系调整", name = "恋人"), null, now)
        assertEquals("恋人", (ev as RecentEvent.Milestone).name)
    }

    // ── T1-3 legacy 排除 ──

    @Test fun `legacy行_不入选`() {
        assertNull(pickRecentEvent(null, meeting(now - 1000, kind = "legacy", activity = "散步"), now))
    }

    @Test fun `startedAt为0_窗天然排除`() {
        // now-0 = now ≫ window → 不入选（legacy 行 startedAt=0 的双保险）。
        assertNull(pickRecentEvent(null, meeting(0, kind = "meeting", activity = "散步"), now))
    }

    @Test fun `meeting行窗内_入选`() {
        assertTrue(pickRecentEvent(null, meeting(now - 1000, activity = "散步"), now) is RecentEvent.Meeting)
    }

    // ── T1-4 空链 trim（选取层只 trim；activity/location 空→格式降级由 UI 消费）──

    @Test fun `activity与location各自trim`() {
        val ev = pickRecentEvent(null, meeting(now - 1000, activity = "  散步 ", location = " 公园 "), now) as RecentEvent.Meeting
        assertEquals("散步", ev.activity)
        assertEquals("公园", ev.location)
    }

    @Test fun `纯空格activity_trim后按空处理`() {
        val ev = pickRecentEvent(null, meeting(now - 1000, activity = "   ", location = "公园"), now) as RecentEvent.Meeting
        assertEquals("", ev.activity)
        assertEquals("公园", ev.location)
    }

    @Test fun `activity与location都空_保持空串`() {
        val ev = pickRecentEvent(null, meeting(now - 1000, activity = "", location = ""), now) as RecentEvent.Meeting
        assertEquals("", ev.activity)
        assertEquals("", ev.location)
    }

    // ── T1-5 两源竞争 ──

    @Test fun `里程碑更新_取里程碑`() {
        // 里程碑 1 天前、见面 2 天前 → 里程碑 atMillis 更大 → 取里程碑。
        val ev = pickRecentEvent(milestone(now - oneDay), meeting(now - 2 * oneDay, activity = "散步"), now)
        assertTrue(ev is RecentEvent.Milestone)
    }

    @Test fun `见面更新_取见面`() {
        val ev = pickRecentEvent(milestone(now - 2 * oneDay), meeting(now - oneDay, activity = "散步"), now)
        assertTrue(ev is RecentEvent.Meeting)
    }

    @Test fun `时间戳相等_取见面`() {
        val ev = pickRecentEvent(milestone(now - oneDay), meeting(now - oneDay, activity = "散步"), now)
        assertTrue("相等取见面（信息更具体）", ev is RecentEvent.Meeting)
    }

    private companion object {
        const val oneDay = 24L * 60 * 60 * 1000
    }
}
