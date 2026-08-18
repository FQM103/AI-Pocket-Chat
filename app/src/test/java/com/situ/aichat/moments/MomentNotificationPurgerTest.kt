package com.situ.aichat.moments

import com.situ.aichat.data.model.MomentNotificationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-44 朋友圈族已弹通知撤销的候选 id 枚举：格式与发出处单源（singleNotificationId/
 * interactionNotificationId），断言用手拼串 hashCode 锁拼法防静默漂移。
 */
class MomentNotificationPurgerTest {

    @Test
    fun `enumerates own posts and interacted posts across all types`() {
        val ids = MomentNotificationPurger.purgeNotificationIds("c1", listOf("p1", "p2"), listOf("q1"))
        assertTrue(ids.contains("moment_newpost:p1".hashCode()))
        assertTrue(ids.contains("moment_newpost:p2".hashCode()))
        listOf("commentOnUserPost", "replyToUserComment", "likeOnUserPost", "coLike").forEach { raw ->
            assertTrue(raw, ids.contains("moment:$raw:q1:c1".hashCode()))
        }
        // 2 新帖 + 1 帖 × 4 类型闭集 = 6（新增 MomentNotificationType 会破此断言=提醒同步审视）。
        assertEquals(6, ids.size)
    }

    @Test
    fun `merged notification id is exempt`() {
        val ids = MomentNotificationPurger.purgeNotificationIds("c1", listOf("p1"), listOf("q1"))
        assertFalse(ids.contains("moment_newpost_merged".hashCode()))
    }

    @Test
    fun `empty inputs yield empty set`() {
        assertTrue(MomentNotificationPurger.purgeNotificationIds("c1", emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `id constructors are single-source with emitters`() {
        assertEquals("moment_newpost:p".hashCode(), MomentNewPostNotifier.singleNotificationId("p"))
        assertEquals(
            "moment:likeOnUserPost:p:c".hashCode(),
            MomentInteractionService.interactionNotificationId(MomentNotificationType.LIKE_ON_USER_POST, "p", "c"),
        )
    }
}
