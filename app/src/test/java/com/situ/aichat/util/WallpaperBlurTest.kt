package com.situ.aichat.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WallpaperBlur.boxBlur] 纯函数单测（毛玻璃模糊核·无 Android 依赖）。锁定：均匀场不变 / 单点扩散并变暗 /
 * 对称性 / radius<1 空操作 / 通道独立。打包后玻璃栏靠它出磨砂，错了质感全毁，故钉死。
 */
class WallpaperBlurTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(c: Int) = (c ushr 16) and 0xFF
    private fun alpha(c: Int) = (c ushr 24) and 0xFF

    @Test
    fun uniformFieldStaysUnchanged() {
        val w = 6
        val h = 5
        val color = argb(255, 128, 130, 132)
        val px = IntArray(w * h) { color }
        WallpaperBlur.boxBlur(px, w, h, 2, 3)
        px.forEach { assertEquals("均匀场模糊后须不变", color, it) }
    }

    @Test
    fun radiusBelowOneIsNoOp() {
        val w = 4
        val h = 4
        val px = IntArray(w * h) { if (it == 0) argb(255, 255, 255, 255) else argb(255, 0, 0, 0) }
        val before = px.copyOf()
        WallpaperBlur.boxBlur(px, w, h, 0, 3)
        assertArrayEquals("radius<1 须空操作", before, px)
    }

    @Test
    fun passesBelowOneIsNoOp() {
        val w = 4
        val h = 4
        val px = IntArray(w * h) { argb(255, it * 10, 0, 0) }
        val before = px.copyOf()
        WallpaperBlur.boxBlur(px, w, h, 2, 0)
        assertArrayEquals("passes<1 须空操作", before, px)
    }

    @Test
    fun singleBrightPointSpreadsDimsAndStaysSymmetric() {
        val w = 9
        val h = 9
        val px = IntArray(w * h) { argb(255, 0, 0, 0) }
        val center = 4 * w + 4
        px[center] = argb(255, 255, 255, 255)
        WallpaperBlur.boxBlur(px, w, h, 2, 1)

        // 中心被摊薄（< 255）但仍 > 0；相邻被点亮（> 0）。
        assertTrue("中心应变暗", red(px[center]) in 1..254)
        assertTrue("右邻应被点亮", red(px[4 * w + 5]) > 0)

        // 可分离对称核 + 对称输入 → 输出关于中心对称（水平 & 垂直，整数除法对称位等价）。
        assertEquals(red(px[4 * w + 3]), red(px[4 * w + 5]))
        assertEquals(red(px[3 * w + 4]), red(px[5 * w + 4]))

        // alpha 全程 255（不透明照片不该被模糊出半透明）。
        px.forEach { assertEquals(255, alpha(it)) }
    }

    @Test
    fun channelsAreIndependent() {
        // 纯红场模糊后仍纯红（绿/蓝恒 0），证明通道不串色。
        val w = 5
        val h = 5
        val px = IntArray(w * h) { argb(255, 200, 0, 0) }
        px[2 * w + 2] = argb(255, 0, 0, 0)
        WallpaperBlur.boxBlur(px, w, h, 1, 2)
        px.forEach { c ->
            assertEquals("绿通道须恒 0", 0, (c ushr 8) and 0xFF)
            assertEquals("蓝通道须恒 0", 0, c and 0xFF)
        }
    }
}
