package com.situ.aichat.widget

import com.situ.aichat.data.local.entity.MomentPostEntity
import com.situ.aichat.data.model.MomentAuthorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 最新动态（朋友圈）小组件「选最新角色帖」纯逻辑单测（13.9b）。
 * 断言反推：仅取 authorType=character、未软删，按 timestamp 取最新；用户帖与已删帖排除。
 */
class MomentWidgetDataTest {

    private fun post(
        uuid: String,
        timestamp: Long,
        author: MomentAuthorType = MomentAuthorType.CHARACTER,
        isSoftDeleted: Boolean = false,
    ) = MomentPostEntity(
        uuid = uuid,
        content = uuid,
        timestamp = timestamp,
        authorTypeRaw = author.raw,
        characterUuid = if (author == MomentAuthorType.CHARACTER) "char-$uuid" else null,
        isSoftDeleted = isSoftDeleted,
    )

    @Test
    fun `empty list yields null`() {
        assertNull(MomentWidgetData.pickLatestCharacterPost(emptyList()))
    }

    @Test
    fun `picks the newest character post`() {
        val posts = listOf(
            post("old", timestamp = 100L),
            post("new", timestamp = 300L),
            post("mid", timestamp = 200L),
        )
        assertEquals("new", MomentWidgetData.pickLatestCharacterPost(posts)?.uuid)
    }

    @Test
    fun `user posts are excluded even if newer`() {
        val posts = listOf(
            post("userNewest", timestamp = 999L, author = MomentAuthorType.USER),
            post("charOlder", timestamp = 100L, author = MomentAuthorType.CHARACTER),
        )
        assertEquals("charOlder", MomentWidgetData.pickLatestCharacterPost(posts)?.uuid)
    }

    @Test
    fun `soft-deleted posts are excluded even if newest`() {
        val posts = listOf(
            post("deletedNewest", timestamp = 999L, isSoftDeleted = true),
            post("liveOlder", timestamp = 100L),
        )
        assertEquals("liveOlder", MomentWidgetData.pickLatestCharacterPost(posts)?.uuid)
    }

    @Test
    fun `only user posts yields null`() {
        val posts = listOf(
            post("u1", timestamp = 100L, author = MomentAuthorType.USER),
            post("u2", timestamp = 200L, author = MomentAuthorType.USER),
        )
        assertNull(MomentWidgetData.pickLatestCharacterPost(posts))
    }

    @Test
    fun `result is independent of input order`() {
        val a = post("a", timestamp = 100L)
        val b = post("b", timestamp = 300L)
        val c = post("c", timestamp = 200L)
        assertEquals("b", MomentWidgetData.pickLatestCharacterPost(listOf(a, b, c))?.uuid)
        assertEquals("b", MomentWidgetData.pickLatestCharacterPost(listOf(c, a, b))?.uuid)
        assertEquals("b", MomentWidgetData.pickLatestCharacterPost(listOf(b, c, a))?.uuid)
    }
}
