package com.situ.aichat.ui.world.continent

import com.situ.aichat.world.atlas.WorldRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContinentStyle] T1（W9b 图纸 §7 T1-2·§9 锁死）：STYLES 键集与图集 styleKey 双射；四样例区
 * （yunze/nanyu/huangsha/jibei = demo valley/isles/plateau/tundra）逐字段 == demo 金标（硬编码于测里）；
 * 天空恒 5 停靠且位置升序 0→1；leafs 非空；treeShape/padH/amp 全正；snowLine 无雪区 = 99。
 */
class ContinentStyleTest {

    private val cEps = 1e-12

    private fun assertColor(name: String, hex: Int, a: DoubleArray) {
        assertEquals("$name.r", ((hex shr 16) and 255) / 255.0, a[0], cEps)
        assertEquals("$name.g", ((hex shr 8) and 255) / 255.0, a[1], cEps)
        assertEquals("$name.b", (hex and 255) / 255.0, a[2], cEps)
    }

    @Test
    fun styleKeys_bijectWithAtlasRegions() {
        val atlasKeys = WorldRegions.ALL.map { it.styleKey }.toSet()
        assertEquals("每区 styleKey 都有样式、无多余样式", atlasKeys, ContinentStyle.STYLES.keys)
        assertEquals("十大区", 10, ContinentStyle.STYLES.size)
    }

    @Test
    fun valley_yunze_matchesDemo() {
        val s = ContinentStyle.STYLES.getValue("willow_mist")
        assertEquals(11.7, s.seed, 0.0); assertEquals(0.46, s.sea, 0.0); assertEquals(5.2, s.amp, 0.0)
        assertEquals(0.60, s.coast, 0.0); assertEquals(1.5, s.padH, 0.0); assertEquals(false, s.terrace)
        assertEquals(4.4, s.snowLine, 0.0); assertEquals(60, s.treeN)
        assertEquals(0.7, s.trunk, 0.0); assertEquals(0.8, s.treeR, 0.0); assertEquals(1.5, s.treeH, 0.0)
        assertArrayEquals3(floatArrayOf(1.0f, 0.86f, 0.70f), s.warm); assertArrayEquals3(floatArrayOf(0.79f, 0.54f, 0.46f), s.haze)
        assertColor("water", 0x3E5C6E, s.water); assertColor("bed", 0x51606A, s.bed); assertColor("beach", 0xD9C3A3, s.beach)
        assertColor("g1", 0x8FA37E, s.g1); assertColor("g2", 0x7E926E, s.g2); assertColor("cliff", 0xC4A484, s.cliff)
        assertColor("snow", 0xEFEDE9, s.snow); assertColor("rock", 0x9A8B7C, s.rock); assertColor("earth", 0x6B5A48, s.earth)
        assertEquals(2, s.leafs.size); assertColor("leaf0", 0x7E926E, s.leafs[0]); assertColor("leaf1", 0x8FA37E, s.leafs[1])
        assertSky(s, listOf(0.0f to 0x16203A, 0.34f to 0x3A4874, 0.58f to 0x8A6E86, 0.74f to 0xC98A76, 1.0f to 0xE8B87E))
        assertEquals(1.0f, s.glowA, 0f)
    }

    @Test
    fun isles_nanyu_matchesDemo() {
        val s = ContinentStyle.STYLES.getValue("palm_sand")
        assertEquals(23.4, s.seed, 0.0); assertEquals(0.54, s.sea, 0.0); assertEquals(4.2, s.amp, 0.0)
        assertEquals(0.86, s.coast, 0.0); assertEquals(1.2, s.padH, 0.0); assertEquals(false, s.terrace)
        assertEquals(99.0, s.snowLine, 0.0); assertEquals(18, s.treeN)
        assertEquals(1.3, s.trunk, 0.0); assertEquals(0.9, s.treeR, 0.0); assertEquals(0.8, s.treeH, 0.0)
        assertColor("water", 0x2E6E72, s.water); assertColor("beach", 0xEDD9AC, s.beach); assertColor("snow", 0xFFFFFF, s.snow)
        assertEquals(2, s.leafs.size); assertColor("leaf0", 0x5F9E6E, s.leafs[0]); assertColor("leaf1", 0x74B07A, s.leafs[1])
        assertSky(s, listOf(0.0f to 0x1B2B44, 0.38f to 0x3E6C86, 0.66f to 0x7FBFB0, 0.83f to 0xB8CCA8, 1.0f to 0xF2D9A0))
        assertEquals(0.9f, s.glowA, 0f)
    }

    @Test
    fun plateau_huangsha_matchesDemo() {
        val s = ContinentStyle.STYLES.getValue("ochre_dry")
        assertEquals(41.2, s.seed, 0.0); assertEquals(0.38, s.sea, 0.0); assertEquals(6.0, s.amp, 0.0)
        assertEquals(0.58, s.coast, 0.0); assertEquals(2.4, s.padH, 0.0); assertEquals(true, s.terrace)
        assertEquals(99.0, s.snowLine, 0.0); assertEquals(10, s.treeN)
        assertColor("g1", 0xC9A46B, s.g1); assertColor("cliff", 0x8E6B4E, s.cliff); assertColor("snow", 0xFFFFFF, s.snow)
        assertEquals(1, s.leafs.size); assertColor("leaf0", 0x6B7A52, s.leafs[0])
        assertSky(s, listOf(0.0f to 0x2A2438, 0.42f to 0x7A5A56, 0.72f to 0xC98A5E, 0.86f to 0xDCAA76, 1.0f to 0xEFC98F))
        assertEquals(1.0f, s.glowA, 0f)
    }

    @Test
    fun tundra_jibei_matchesDemo() {
        val s = ContinentStyle.STYLES.getValue("pine_snow")
        assertEquals(57.9, s.seed, 0.0); assertEquals(0.50, s.sea, 0.0); assertEquals(5.0, s.amp, 0.0)
        assertEquals(0.62, s.coast, 0.0); assertEquals(1.5, s.padH, 0.0); assertEquals(false, s.terrace)
        assertEquals(1.2, s.snowLine, 0.0); assertEquals(24, s.treeN)
        assertEquals(0.5, s.trunk, 0.0); assertEquals(0.5, s.treeR, 0.0); assertEquals(1.9, s.treeH, 0.0)
        assertColor("g1", 0xE2E4E4, s.g1); assertColor("rock", 0x6E7686, s.rock); assertColor("snow", 0xEDEBE6, s.snow)
        assertEquals(1, s.leafs.size); assertColor("leaf0", 0x46604E, s.leafs[0])
        assertSky(s, listOf(0.0f to 0x101828, 0.40f to 0x2E4258, 0.72f to 0x8FA6B8, 0.86f to 0xB4C4D0, 1.0f to 0xD9E2E8))
        assertEquals(0.35f, s.glowA, 0f)
    }

    @Test
    fun allRegions_invariants() {
        for ((key, s) in ContinentStyle.STYLES) {
            assertEquals("$key styleKey 自洽", key, s.styleKey)
            assertTrue("$key amp>0", s.amp > 0); assertTrue("$key padH>0", s.padH > 0)
            assertTrue("$key coast>0", s.coast > 0); assertTrue("$key sea>0", s.sea > 0)
            assertTrue("$key trunk>0", s.trunk > 0); assertTrue("$key treeR>0", s.treeR > 0); assertTrue("$key treeH>0", s.treeH > 0)
            assertTrue("$key treeN≥0", s.treeN >= 0)
            assertTrue("$key snowLine>0", s.snowLine > 0)
            assertTrue("$key leafs 非空", s.leafs.isNotEmpty())
            assertEquals("$key warm 三分量", 3, s.warm.size); assertEquals("$key haze 三分量", 3, s.haze.size)
            // 天空恒 5 停靠·位置升序·首 0 末 1。
            assertEquals("$key 5 停靠", 5, s.sky.size)
            assertEquals("$key 首停靠=0", 0f, s.sky.first().pos, 0f)
            assertEquals("$key 末停靠=1", 1f, s.sky.last().pos, 0f)
            for (i in 1 until s.sky.size) assertTrue("$key 停靠升序", s.sky[i].pos > s.sky[i - 1].pos)
        }
    }

    private fun assertArrayEquals3(exp: FloatArray, act: FloatArray) {
        for (i in 0..2) assertEquals(exp[i], act[i], 1e-6f)
    }

    private fun assertSky(s: RegionStyle, stops: List<Pair<Float, Int>>) {
        assertEquals(stops.size, s.sky.size)
        stops.forEachIndexed { i, (pos, hex) ->
            assertEquals("sky[$i].pos", pos, s.sky[i].pos, 1e-6f)
            assertEquals("sky[$i].r", ((hex shr 16) and 255) / 255f, s.sky[i].color[0], 1e-6f)
            assertEquals("sky[$i].g", ((hex shr 8) and 255) / 255f, s.sky[i].color[1], 1e-6f)
            assertEquals("sky[$i].b", (hex and 255) / 255f, s.sky[i].color[2], 1e-6f)
        }
    }
}
