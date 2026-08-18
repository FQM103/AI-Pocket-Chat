package com.situ.aichat.ui.diary

import com.situ.aichat.data.local.entity.DiaryCommentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1：评论一层线程分组 + 「还能回复吗」门控（R3·契约 §2 F3）。断言从规格独立反推：
 * 根/回复各按时间升序、孤儿回复降级为根不丢数据、每根限 1 轮（用户回复过即关闸）。
 */
class DiaryCommentThreadTest {

    private fun c(
        id: String,
        ts: Long,
        characterUuid: String? = "char1",
        parent: String? = null,
        fromUser: Boolean = false,
    ) = DiaryCommentEntity(
        id = id, entryUuid = "e1", content = id, timestamp = ts,
        characterUuid = characterUuid, parentCommentId = parent, isFromUser = fromUser,
    )

    @Test fun `roots ascend by time, replies attach ascending`() {
        val threads = groupDiaryCommentThreads(
            listOf(
                c("rootB", ts = 200),
                c("rootA", ts = 100),
                c("replyB2", ts = 400, characterUuid = "char1", parent = "rootB"),
                c("replyB1", ts = 300, characterUuid = null, parent = "rootB", fromUser = true),
            ),
        )
        assertEquals(listOf("rootA", "rootB"), threads.map { it.root.id })
        assertEquals(emptyList<String>(), threads[0].replies.map { it.id })
        assertEquals(listOf("replyB1", "replyB2"), threads[1].replies.map { it.id })
    }

    @Test fun `orphan reply (root deleted) degrades to a root — data never hidden`() {
        val threads = groupDiaryCommentThreads(
            listOf(c("orphan", ts = 100, characterUuid = null, parent = "gone", fromUser = true)),
        )
        assertEquals(1, threads.size)
        assertEquals("orphan", threads[0].root.id)
        assertTrue(threads[0].replies.isEmpty())
    }

    @Test fun `canReply true only for character root with no user reply yet`() {
        val fresh = DiaryCommentThread(c("root", 100), emptyList())
        assertTrue("角色根评论且无用户回复 → 可回复", fresh.canReply())

        val replied = DiaryCommentThread(
            c("root", 100),
            listOf(c("u1", 200, characterUuid = null, parent = "root", fromUser = true)),
        )
        assertFalse("用户已回复过 → 关闸（每根限 1 轮）", replied.canReply())

        val fullRound = DiaryCommentThread(
            c("root", 100),
            listOf(
                c("u1", 200, characterUuid = null, parent = "root", fromUser = true),
                c("a1", 300, parent = "root"),
            ),
        )
        assertFalse("一轮已完整 → 仍关闸", fullRound.canReply())

        val userRoot = DiaryCommentThread(c("root", 100, characterUuid = null, fromUser = true), emptyList())
        assertFalse("用户自己的（孤儿降级）根 → 不可回复", userRoot.canReply())

        val anonymousRoot = DiaryCommentThread(c("root", 100, characterUuid = null), emptyList())
        assertFalse("无角色归属的根 → 不可回复（无人可回应）", anonymousRoot.canReply())
    }

    // MARK: - R6-1 交换日记「给 TA 留言」门控（一封信限一条顶层留言）

    @Test fun `canLeaveExchangeNote - open until the user leaves a top-level note`() {
        assertTrue("无任何评论 → 可留言", canLeaveExchangeNote(emptyList()))
        assertTrue(
            "只有角色评论 → 仍可留言",
            canLeaveExchangeNote(listOf(c("r1", 100))),
        )
        assertFalse(
            "用户已有顶层留言 → 关闸",
            canLeaveExchangeNote(listOf(c("n1", 100, characterUuid = null, fromUser = true))),
        )
    }

    @Test fun `canLeaveExchangeNote - user replies inside threads do not close the gate`() {
        // 用户在某角色评论下的回复（R3 一轮）不是顶层留言，不挡「给 TA 留言」。
        val comments = listOf(
            c("r1", 100),
            c("u1", 200, characterUuid = null, parent = "r1", fromUser = true),
        )
        assertTrue(canLeaveExchangeNote(comments))
    }

    @Test fun `character response alone does not block reply`() {
        // 边界：根下只有角色自己的补充（无用户回复）→ 仍可回复。
        val thread = DiaryCommentThread(
            c("root", 100),
            listOf(c("a1", 200, parent = "root")),
        )
        assertTrue(thread.canReply())
    }
}
