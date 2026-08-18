package com.situ.aichat.moments

import com.situ.aichat.data.local.entity.MomentCommentEntity

/** A comment paired with its indent level for the detail view (iOS `(comment, level)` tuple). */
data class MomentCommentNode(val comment: MomentCommentEntity, val level: Int)

/**
 * 1:1 port of iOS `MomentCommentTreeBuilder` (Services/MomentCommentTreeBuilder.swift).
 *
 * Builds a hierarchical comment view from a flat `List<MomentCommentEntity>` (typically
 * `MomentPostWithRelations.comments`) using only [MomentCommentEntity.parentCommentUuid] — never an
 * inverse `replies` array. iOS hit a refresh-ordering bug doing the latter (AI comments are written
 * in a background context; the main context's reverse relationship lags), so building from the flat
 * list keyed by uuid is the only safe approach.
 *
 * Fault tolerance, matching iOS exactly:
 * - **Orphan downgrade**: a comment whose parent isn't in the list (deleted / cross-post pollution)
 *   is shown at the top level rather than silently dropped.
 * - **Cycle fallback**: if every comment points into a cycle (so `topLevel` is empty), the leftover
 *   unvisited comments are appended at the top level, preserving the invariant **input count ==
 *   output count**. Normal data never reaches this.
 *
 * Unlike iOS this needs no `@MainActor` — it is a pure function over immutable data classes.
 */
object MomentCommentTreeBuilder {

    /**
     * Flatten into a depth-first `(comment, level)` sequence for indented rendering. Top-level
     * comments are level 0, each reply one deeper. Same-parent comments are ordered by ascending
     * timestamp; every comment appears exactly once.
     */
    fun flatten(comments: List<MomentCommentEntity>): List<MomentCommentNode> {
        val (topLevel, childrenByParent) = groupByParent(comments)

        val result = ArrayList<MomentCommentNode>(comments.size)
        val visited = HashSet<String>()

        fun walk(comment: MomentCommentEntity, level: Int) {
            if (!visited.add(comment.uuid)) return
            result.add(MomentCommentNode(comment, level))
            for (reply in childrenByParent[comment.uuid].orEmpty()) {
                walk(reply, level + 1)
            }
        }

        for (comment in topLevel) {
            walk(comment, 0)
        }

        // Fallback: if a full cycle left topLevel empty, append the leftover comments as top-level so
        // nothing is lost. Normal data never gets here.
        for (comment in comments) {
            if (comment.uuid !in visited) walk(comment, 0)
        }

        return result
    }

    /**
     * Top-level comments only (orphans included), for list-page previews. Equivalent to the
     * `level == 0` entries of [flatten] but lighter — it doesn't recurse into replies.
     */
    fun topLevelOrOrphaned(comments: List<MomentCommentEntity>): List<MomentCommentEntity> =
        groupByParent(comments).first

    // ---- Helpers ----

    private fun groupByParent(
        comments: List<MomentCommentEntity>,
    ): Pair<List<MomentCommentEntity>, Map<String, List<MomentCommentEntity>>> {
        val allUuids = comments.mapTo(HashSet()) { it.uuid }

        val childrenByParent = HashMap<String, MutableList<MomentCommentEntity>>()
        val topLevel = ArrayList<MomentCommentEntity>()
        for (comment in comments) {
            val parentUuid = comment.parentCommentUuid
            if (parentUuid != null && parentUuid in allUuids) {
                childrenByParent.getOrPut(parentUuid) { ArrayList() }.add(comment)
            } else {
                topLevel.add(comment)
            }
        }

        topLevel.sortBy { it.timestamp }
        for (list in childrenByParent.values) {
            list.sortBy { it.timestamp }
        }

        return topLevel to childrenByParent
    }
}
