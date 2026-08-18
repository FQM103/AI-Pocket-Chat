package com.situ.aichat.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [WorldSeeds] T1 确定性 + **金标向量**测试（W2 图纸 §7 T1-4·断言从图纸 §3.2 独立反推）。
 *
 * 金标向量 = 世界种子算法的「物理常数」看门：下方字面量由独立实现（同算法 Python 复算）钉死、
 * 再由本测试 Kotlin 侧交叉验证。**绝不许改**——一改即红 = 防未来重构悄悄改世界（图纸 §9/E10）。
 */
class WorldSeedsTest {

    // ---- 确定性：同输入同输出（重复 3 次） ----

    @Test
    fun `确定性_同输入重复三次同输出`() {
        val a1 = WorldSeeds.derive(0L, "a")
        val a2 = WorldSeeds.derive(0L, "a")
        val a3 = WorldSeeds.derive(0L, "a")
        assertEquals(a1, a2)
        assertEquals(a2, a3)

        assertEquals(WorldSeeds.splitmix64(123L), WorldSeeds.splitmix64(123L))
        assertEquals(WorldSeeds.fnv1a64("云野镇"), WorldSeeds.fnv1a64("云野镇"))

        // 种子 Random：同 seed 同序列。
        val r1 = WorldSeeds.randomOf(777L)
        val r2 = WorldSeeds.randomOf(777L)
        repeat(3) { assertEquals(r1.nextLong(), r2.nextLong()) }
    }

    // ---- 不同盐 / 不同 n 输出不同 ----

    @Test
    fun `不同盐或不同n_输出不同`() {
        assertNotEquals(WorldSeeds.derive(0L, "a"), WorldSeeds.derive(0L, "b"))
        assertNotEquals(WorldSeeds.derive(42L, "day", 1L), WorldSeeds.derive(42L, "day", 2L))
        // 盐链顺序敏感：单盐 ["a"] ≠ 双盐 ["a","b"]。
        assertNotEquals(WorldSeeds.derive(0L, "a"), WorldSeeds.derive(0L, "a", "b"))
        // 不同 root 也不同。
        assertNotEquals(WorldSeeds.derive(0L, "day", 1L), WorldSeeds.derive(1L, "day", 1L))
    }

    // ---- 金标向量：钉死字面量（稳定性金标·绝不许改·图纸 §9/E10） ----

    @Test
    fun `金标向量_钉死字面量`() {
        assertEquals(-2152535657050944081L, WorldSeeds.splitmix64(0L))
        assertEquals(-7995527694508729151L, WorldSeeds.splitmix64(1L))
        assertEquals(5717881983045765875L, WorldSeeds.fnv1a64("world"))
        assertEquals(-3851359326990528739L, WorldSeeds.fnv1a64("day"))
        assertEquals(6857225946766476583L, WorldSeeds.derive(0L, "a"))
        assertEquals(1207992707679807405L, WorldSeeds.derive(0L, "a", "b"))
        assertEquals(-826965648923912673L, WorldSeeds.derive(42L, "day", 20000L))
    }
}
