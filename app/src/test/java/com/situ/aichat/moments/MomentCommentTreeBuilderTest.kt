package com.situ.aichat.moments

import com.situ.aichat.data.local.entity.MomentCommentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with iOS `MomentCommentTreeBuilder` (Services/MomentCommentTreeBuilder.swift). Assertions
 * encode the iOS contract directly: DFS order, level 0 at top with +1 per depth, same-parent sorted
 * by ascending timestamp, orphan downgrade, cycle fallback, and the **input count == output count**
 * invariant.
 */
class MomentCommentTreeBuilderTest {

    private fun comment(uuid: String, parent: String? = null, ts: Long): MomentCommentEntity =
        MomentCommentEntity(uuid = uuid, content = uuid, timestamp = ts, parentCommentUuid = parent)

    private fun uuids(nodes: List<MomentCommentNode>): List<String> = nodes.map { it.comment.uuid }
    private fun levels(nodes: List<MomentCommentNode>): List<Int> = nodes.map { it.level }

    @Test fun `empty input yields empty output`() {
        assertEquals(emptyList<MomentCommentNode>(), MomentCommentTreeBuilder.flatten(emptyList()))
        assertEquals(emptyList<MomentCommentEntity>(), MomentCommentTreeBuilder.topLevelOrOrphaned(emptyList()))
    }

    @Test fun `all top-level comments are level 0 and sorted by ascending timestamp`() {
        val input = listOf(comment("C", ts = 3), comment("A", ts = 1), comment("B", ts = 2))
        val flat = MomentCommentTreeBuilder.flatten(input)
        assertEquals(listOf("A", "B", "C"), uuids(flat))
        assertEquals(listOf(0, 0, 0), levels(flat))
    }

    @Test fun `nested replies are depth-first with increasing levels`() {
        // A -> B -> C (a chain three deep)
        val input = listOf(
            comment("A", ts = 1),
            comment("B", parent = "A", ts = 2),
            comment("C", parent = "B", ts = 3),
        )
        val flat = MomentCommentTreeBuilder.flatten(input)
        assertEquals(listOf("A", "B", "C"), uuids(flat))
        assertEquals(listOf(0, 1, 2), levels(flat))
    }

    @Test fun `same-parent siblings are ordered by ascending timestamp`() {
        val input = listOf(
            comment("A", ts = 1),
            comment("late", parent = "A", ts = 5),
            comment("early", parent = "A", ts = 2),
        )
        val flat = MomentCommentTreeBuilder.flatten(input)
        assertEquals(listOf("A", "early", "late"), uuids(flat))
        assertEquals(listOf(0, 1, 1), levels(flat))
    }

    @Test fun `whole subtree emitted before the next top-level comment`() {
        // Two top-level comments each with one reply; DFS keeps each subtree contiguous.
        val input = listOf(
            comment("B", ts = 3),
            comment("B1", parent = "B", ts = 4),
            comment("A", ts = 1),
            comment("A1", parent = "A", ts = 2),
        )
        val flat = MomentCommentTreeBuilder.flatten(input)
        assertEquals(listOf("A", "A1", "B", "B1"), uuids(flat))
        assertEquals(listOf(0, 1, 0, 1), levels(flat))
    }

    @Test fun `orphan whose parent is absent is downgraded to top level`() {
        val input = listOf(
            comment("orphan", parent = "missing", ts = 1),
            comment("A", ts = 2),
        )
        val flat = MomentCommentTreeBuilder.flatten(input)
        // Both top-level, sorted by ts (orphan ts=1 first).
        assertEquals(listOf("orphan", "A"), uuids(flat))
        assertEquals(listOf(0, 0), levels(flat))
        // topLevelOrOrphaned agrees and excludes nothing here.
        assertEquals(listOf("orphan", "A"), MomentCommentTreeBuilder.topLevelOrOrphaned(input).map { it.uuid })
    }

    @Test fun `two-node cycle still emits every comment once (count invariant)`() {
        // A.parent = B, B.parent = A → no top level; fallback walks leftovers from input order.
        val input = listOf(comment("A", parent = "B", ts = 1), comment("B", parent = "A", ts = 2))
        val flat = MomentCommentTreeBuilder.flatten(input)
        assertEquals(input.size, flat.size)                 // input count == output count
        assertEquals(setOf("A", "B"), uuids(flat).toSet())  // nothing lost
        assertEquals(uuids(flat).size, uuids(flat).toSet().size) // nothing duplicated
        assertEquals("A", flat.first().comment.uuid)        // fallback starts from input order
        assertEquals(0, flat.first().level)
    }

    @Test fun `self-referencing comment does not loop and appears once`() {
        val input = listOf(comment("S", parent = "S", ts = 1))
        val flat = MomentCommentTreeBuilder.flatten(input)
        assertEquals(1, flat.size)
        assertEquals("S", flat.first().comment.uuid)
        assertEquals(0, flat.first().level)
    }

    @Test fun `topLevelOrOrphaned returns only top level and orphans, sorted, no recursion`() {
        val input = listOf(
            comment("A", ts = 2),
            comment("A1", parent = "A", ts = 3),   // reply — must NOT appear
            comment("orphan", parent = "gone", ts = 1),
        )
        val top = MomentCommentTreeBuilder.topLevelOrOrphaned(input).map { it.uuid }
        assertEquals(listOf("orphan", "A"), top)       // sorted by ts; reply excluded
        assertTrue("reply must not be top-level", "A1" !in top)
    }

    @Test fun `count invariant holds for a mixed graph`() {
        val input = listOf(
            comment("A", ts = 1),
            comment("A1", parent = "A", ts = 2),
            comment("A1a", parent = "A1", ts = 3),
            comment("orphan", parent = "nope", ts = 4),
            comment("B", ts = 5),
        )
        val flat = MomentCommentTreeBuilder.flatten(input)
        assertEquals(input.size, flat.size)
        assertEquals(input.map { it.uuid }.toSet(), uuids(flat).toSet())
        assertEquals(uuids(flat).size, uuids(flat).toSet().size)
    }
}
