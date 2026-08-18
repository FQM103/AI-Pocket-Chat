package com.situ.aichat.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调度器纯函数单测：状态快照重建判定、跨角色 reservedDates、每日上限、删角色撤已弹通知的 id 枚举。
 * 活跃时段桶已随实现迁往 [ActivityBucketAnalyzerTest]（R1 🟡-1 只搬不改）。
 */
class NotificationSchedulerLogicTest {

    // MARK: - shouldRebuild

    @Test fun shouldRebuild_nullSnapshot_true() {
        assertTrue(NotificationSchedulerStore.shouldRebuild(null, "2026-01-15", "active", 3))
    }

    @Test fun shouldRebuild_allSame_false() {
        val snap = NotificationSchedulerStore.Snapshot("2026-01-15", "active", 3)
        assertFalse(NotificationSchedulerStore.shouldRebuild(snap, "2026-01-15", "active", 3))
    }

    @Test fun shouldRebuild_dateOrLabelOrCountChange_true() {
        val snap = NotificationSchedulerStore.Snapshot("2026-01-15", "active", 3)
        assertTrue(NotificationSchedulerStore.shouldRebuild(snap, "2026-01-16", "active", 3))
        assertTrue(NotificationSchedulerStore.shouldRebuild(snap, "2026-01-15", "broken", 3))
        assertTrue(NotificationSchedulerStore.shouldRebuild(snap, "2026-01-15", "active", 5))
    }

    // MARK: - reservedFireTimesExcluding

    @Test fun reservedFireTimes_excludesGivenCharacter() {
        val registry = mapOf(
            "a" to listOf(
                NotificationSchedulerStore.ScheduledRef("k1", 100L, "morning"),
                NotificationSchedulerStore.ScheduledRef("k2", 200L, "evening"),
            ),
            "b" to listOf(NotificationSchedulerStore.ScheduledRef("k3", 300L, "random")),
        )
        assertEquals(listOf(300L), NotificationSchedulerStore.reservedFireTimesExcluding(registry, "a"))
        assertEquals(listOf(100L, 200L), NotificationSchedulerStore.reservedFireTimesExcluding(registry, "b"))
        assertEquals(listOf(100L, 200L, 300L).sorted(),
            NotificationSchedulerStore.reservedFireTimesExcluding(registry, "x").sorted())
    }

    // MARK: - 每日上限（V4：5→3）

    /**
     * 每角色每日上限锁定为 3（图纸 §9）。**注意**：本常量与 [NotificationScheduler] 的
     * PURGE 类别闭集已解耦（后者用 LEGACY_MAX_SCHEDULE_CATEGORIES=5 撑住老通知撤销覆盖面），
     * 改本值不得再连带缩小闭集——由 [purgeIds_coverEveryScheduledCategory] 钉住。
     */
    @Test fun maxDailyNotifications_isThree() {
        assertEquals(3, NotificationScheduler.MAX_DAILY_NOTIFICATIONS)
    }

    // MARK: - P1-25 删角色撤已弹通知（前向枚举候选 key → hashCode → cancel）

    @Test fun purgeIds_coverEveryScheduledCategory() {
        // 类别闭集 11 个 key（schedule_0..4 + 6 回退类）+ MERGED 兜底 characterId.hashCode = 12 个 id。
        val ids = NotificationScheduler.purgeNotificationIds("c1", emptyList(), emptyList())
        val expectedKeys = List(5) { "aichat_streak_c1_schedule_$it" } + listOf(
            "aichat_streak_c1_morning", "aichat_streak_c1_evening", "aichat_streak_c1_random",
            "aichat_streak_c1_streak_remind", "aichat_streak_c1_streak_urgent", "aichat_streak_c1_streak_broken",
        )
        expectedKeys.forEach { key -> assertTrue(key, ids.contains(key.hashCode())) }
        assertTrue(ids.contains("c1".hashCode()))
        assertEquals(12, ids.size)
    }

    @Test fun purgeIds_mergeConversationAndLedgerKeys() {
        val ids = NotificationScheduler.purgeNotificationIds("c1", listOf("v1"), listOf("aichat_calendar_42"))
        assertTrue(ids.contains("busyReply_v1_0".hashCode()))
        assertTrue(ids.contains("busyReply_v1_31".hashCode()))
        assertTrue(ids.contains("aichat_calendar_42".hashCode()))
    }

    @Test fun requestKeyFor_format() {
        assertEquals("aichat_streak_c1_morning", NotificationScheduler.requestKeyFor("c1", "morning"))
    }
}
