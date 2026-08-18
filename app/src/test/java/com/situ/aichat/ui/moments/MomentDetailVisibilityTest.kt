package com.situ.aichat.ui.moments

import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 详情页软删收口纯函数单测（规格反推：软删帖等同「不存在」→ null，与列表/通知列表路径 `isSoftDeleted = 0`
 * 过滤行为一致）。守护系统通知深链回归：深链走未过滤的 `observePostWithRelations`，故已删帖必须在 ViewModel
 * 层映射为 null，否则被完整重渲染。见 [visiblePostOrNull]。
 */
class MomentDetailVisibilityTest {

    private fun withRelations(post: MomentPostEntity) =
        MomentPostWithRelations(post = post, comments = emptyList(), likes = emptyList())

    @Test fun `null stays null`() {
        assertNull(visiblePostOrNull(null))
    }

    @Test fun `soft-deleted post maps to null`() {
        val deleted = withRelations(MomentPostEntity(uuid = "p1", isSoftDeleted = true))
        assertNull(visiblePostOrNull(deleted))
    }

    @Test fun `live post passes through unchanged`() {
        val live = withRelations(MomentPostEntity(uuid = "p1", isSoftDeleted = false))
        assertSame(live, visiblePostOrNull(live))
    }
}
