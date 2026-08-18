package com.situ.aichat.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NotificationTemplateWorker companion 纯函数单测（主动通知真实感改造 T1-4）。
 *
 * 只测 [NotificationTemplateWorker.shouldRefreshByAge]——worker 本体的接线由编译担保（本项目无
 * work-testing 依赖，F28/§9 不得新增）。断言从图纸 D-8 规格独立反推：池龄 **>** 30 天才翻新。
 */
class NotificationTemplateWorkerTest {

    private val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    /** 恰 30 天不触发（严格大于号：边界相等不翻新）。 */
    @Test fun exactlyThirtyDays_doesNotRefresh() {
        assertFalse(NotificationTemplateWorker.shouldRefreshByAge(now - thirtyDaysMs, now))
    }

    /** 30 天 + 1ms 触发（±1 精度）。 */
    @Test fun thirtyDaysPlusOneMilli_refreshes() {
        assertTrue(NotificationTemplateWorker.shouldRefreshByAge(now - thirtyDaysMs - 1, now))
    }

    /** 池空（null）不触发——该路交给「仍用默认文案」那一支处理。 */
    @Test fun nullOldest_doesNotRefresh() {
        assertFalse(NotificationTemplateWorker.shouldRefreshByAge(null, now))
    }

    /** 新鲜池（刚烤好 / 一周前）不触发。 */
    @Test fun freshPool_doesNotRefresh() {
        assertFalse(NotificationTemplateWorker.shouldRefreshByAge(now, now))
        assertFalse(NotificationTemplateWorker.shouldRefreshByAge(now - 7L * 24 * 60 * 60 * 1000, now))
    }

    /** 远超期（半年）触发。 */
    @Test fun veryOldPool_refreshes() {
        assertTrue(NotificationTemplateWorker.shouldRefreshByAge(now - 180L * 24 * 60 * 60 * 1000, now))
    }
}
