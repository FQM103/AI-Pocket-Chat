package com.situ.aichat.ui.character

import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.promise.PromiseInjectionRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资料页「我们的约定」卡状态派生纯逻辑（记忆改造三期·图纸 §3.2 / §7 T1-3）。断言从图纸 §3.2 独立反推：
 * 7 天窗边界（E9）/ open 空+窗内了结（E8）/ 全空 EMPTY（E7）/ preview 裁 3 且序=sortedOpen / totalCount 全量。
 */
class ProfilePromisesCardStateTest {

    private val now = 1_000_000_000_000L
    private val windowMs = PromiseInjectionRenderer.RESOLVED_WINDOW_MS
    private val dayMs = 24L * 60 * 60 * 1000

    private fun open(uuid: String, created: Long, due: Long? = null) = PromiseEntity(
        uuid = uuid, characterUuid = "c1", content = uuid, statusRaw = PromiseStatus.OPEN,
        dueAtMillis = due, createdAtMillis = created, updatedAtMillis = created,
    )

    private fun resolved(uuid: String, status: String, resolvedAt: Long) = PromiseEntity(
        uuid = uuid, characterUuid = "c1", content = uuid, statusRaw = status,
        resolvedAtMillis = resolvedAt, createdAtMillis = 0L, updatedAtMillis = resolvedAt,
    )

    // ① 7 天窗边界（E9）：恰在 now − RESOLVED_WINDOW_MS 的了结入选、更旧 1ms 的不入。
    @Test fun windowBoundary_exactAtCutoffIncluded_olderExcluded_e9() {
        val atCutoff = resolved("in", PromiseStatus.FULFILLED, now - windowMs)     // 恰在窗沿（>=）
        val justOlder = resolved("out", PromiseStatus.CANCELLED, now - windowMs - 1) // 更旧 1ms（<）
        val s = PromiseCardState.compute(emptyList(), listOf(justOlder, atCutoff), now)
        assertEquals("恰在窗沿入选", "in", s.latestResolved?.uuid)
    }

    // ② open 空 + 窗内有了结（E8）：openPreview 空、latestResolved=窗内最新、hasAny true。
    @Test fun emptyOpen_resolvedInWindow_showsLatest_e8() {
        val older = resolved("r1", PromiseStatus.FULFILLED, now - 6 * dayMs)
        val newer = resolved("r2", PromiseStatus.CANCELLED, now - 2 * dayMs)
        val s = PromiseCardState.compute(emptyList(), listOf(older, newer), now)
        assertTrue(s.openPreview.isEmpty())
        assertEquals(0, s.openCount)
        assertEquals("r2", s.latestResolved?.uuid) // 窗内最新（resolvedAt 最大）
        assertTrue(s.hasAny)
        assertEquals(2, s.totalCount)
    }

    // ③ 全空（E7）：等于 EMPTY、hasAny false。
    @Test fun allEmpty_isEmptyState_e7() {
        val s = PromiseCardState.compute(emptyList(), emptyList(), now)
        assertEquals(PromiseCardState.EMPTY, s)
        assertFalse(s.hasAny)
        assertNull(s.latestResolved)
    }

    // ④ preview 裁 3 且序 = sortedOpen（due 升序在前，其后 no-due 按 created 升序）。
    @Test fun openPreview_takes3_inSortedOpenOrder() {
        val opens = listOf(
            open("n1", created = 300),               // no-due
            open("d2", created = 100, due = 5_000),  // due 晚
            open("d1", created = 200, due = 1_000),  // due 早
            open("n2", created = 400),               // no-due·created 更晚
        )
        val s = PromiseCardState.compute(opens, emptyList(), now)
        assertEquals(4, s.openCount)
        // sortedOpen：d1(due1000) → d2(due5000) → n1(created300) → n2(created400)；take 3。
        assertEquals(listOf("d1", "d2", "n1"), s.openPreview.map { it.uuid })
    }

    // ⑤ totalCount = open + resolved 全量（不受 7 天窗影响；窗外了结不进微区）。
    @Test fun totalCount_isFullOpenPlusResolved_windowDoesNotClip() {
        val opens = (1..5).map { open("o$it", created = it.toLong()) }
        val resolvedOld = (1..8).map { resolved("r$it", PromiseStatus.FULFILLED, now - 100 * dayMs) } // 全在窗外
        val s = PromiseCardState.compute(opens, resolvedOld, now)
        assertEquals(5, s.openCount)
        assertEquals(13, s.totalCount) // 5 + 8 全量
        assertNull("窗外了结不进微区", s.latestResolved)
        assertTrue(s.hasAny)
    }
}
