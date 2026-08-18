package com.situ.aichat.diagnostics.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * T1-2（图纸 2026-07-30 性能采集与量尺 §7）：[PerfRetention.filesToDelete] 容量轮转数学。
 *
 * 断言从图纸 §3.3 与 §5 E4/E5/E8 的规格**独立反推**（不照抄实现）：
 * - 没超帽 → 一个都不删；
 * - 超帽 → 按日期升序删到不超为止（删够即停，不多删）；
 * - 当天文件永不入删除列表，哪怕它自己就撑爆帽；
 * - 跨天新增文件不误删。
 */
class PerfRetentionTest {

    private val cap = 1000L
    private val today = "perf-20260730.jsonl"

    private fun f(day: String, bytes: Long) = PerfFileInfo("perf-$day.jsonl", bytes)

    @Test
    fun `未超帽时一个都不删`() {
        val files = listOf(f("20260728", 400), f("20260729", 300), f("20260730", 200))
        assertEquals(emptyList<String>(), PerfRetention.filesToDelete(files, cap, today))
    }

    @Test
    fun `恰好等于帽仍不删（边界闭区间）`() {
        val files = listOf(f("20260728", 500), f("20260730", 500))
        assertEquals(emptyList<String>(), PerfRetention.filesToDelete(files, cap, today))
    }

    @Test
    fun `超帽时按日期升序删最旧的直到不超`() {
        // 总 1300 > 1000：删最旧 20260726(200) → 1100 仍超；再删 20260727(300) → 800 达标即停。
        val files = listOf(
            f("20260729", 400),
            f("20260726", 200),
            f("20260730", 400),
            f("20260727", 300),
        )
        assertEquals(
            listOf("perf-20260726.jsonl", "perf-20260727.jsonl"),
            PerfRetention.filesToDelete(files, cap, today),
        )
    }

    @Test
    fun `入参乱序也按文件名日期升序删（不依赖调用方排序）`() {
        // 总 1300 > 1000：删 20260705(200) → 1100 仍超；再删 20260710(200) → 900 达标。入参故意乱序。
        val files = listOf(f("20260710", 200), f("20260730", 900), f("20260705", 200))
        assertEquals(
            listOf("perf-20260705.jsonl", "perf-20260710.jsonl"),
            PerfRetention.filesToDelete(files, cap, today),
        )
    }

    @Test
    fun `当天文件自己就撑爆帽也不删它（只删得掉的旧文件）`() {
        val files = listOf(f("20260729", 300), f("20260730", 5_000))
        val victims = PerfRetention.filesToDelete(files, cap, today)
        assertFalse("当天文件绝不许出现在删除列表", victims.contains(today))
        assertEquals(listOf("perf-20260729.jsonl"), victims)
    }

    @Test
    fun `只有当天一个文件且超帽时删除列表为空`() {
        val files = listOf(f("20260730", 9_999))
        assertEquals(emptyList<String>(), PerfRetention.filesToDelete(files, cap, today))
    }

    @Test
    fun `跨天新增文件后旧文件按需删除且新的当天文件不受影响`() {
        // 跨天：昨天的 perf-20260729 变成普通旧文件，今天是 20260730。
        val files = listOf(f("20260728", 600), f("20260729", 600), f("20260730", 50))
        assertEquals(listOf("perf-20260728.jsonl"), PerfRetention.filesToDelete(files, cap, today))
    }

    @Test
    fun `空目录返回空列表`() {
        assertEquals(emptyList<String>(), PerfRetention.filesToDelete(emptyList(), cap, today))
    }
}
