package com.situ.aichat.ui.world.continent

import com.situ.aichat.world.atlas.WorldAtlas
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContinentGeometry] / [TriStream] 轻量健康测（W9b 验证铁律补覆盖·非 §7 强制项）：三角流 9 分量对齐、
 * 全值有限、水面单 quad、城/塔发光非空、大陆星点 14 颗落屏上 26% 带且确定性。
 */
class ContinentGeometryTest {

    private val strings = ContinentStrings(
        cityBodyTemplate = "以%1\$s闻名的%2\$s。城里有%3\$s。",
        tierSmall = "小城", tierTown = "镇", tierCity = "城",
        wonderBodyTemplate = "%1\$s。",
        curatedBodies = mapOf("city_yunye" to "家乡。", "city_taoqiu" to "陶都。", "city_xiyu" to "渔歌。"),
    )

    private fun sites(regionId: String) =
        ContinentSceneData.fromAtlas(WorldAtlas.of(42L), regionId, strings).sites

    @Test
    fun streams_are9Stride_finite_waterIsOneQuad_emisNonEmpty() {
        // 代表三区：yunze(温带·雪线)/huangsha(台地)/jibei(雪原)。
        for (regionId in listOf("yunze", "huangsha", "jibei")) {
            val style = ContinentStyle.STYLES.getValue(
                when (regionId) { "yunze" -> "willow_mist"; "huangsha" -> "ochre_dry"; else -> "pine_snow" },
            )
            val g = ContinentGeometry.buildRegion(style, sites(regionId))
            for ((name, stream) in listOf("lit" to g.lit, "water" to g.water, "emis" to g.emis)) {
                assertEquals("$regionId $name 9 分量对齐", 0, stream.size % 9)
                for (f in stream) assertTrue("$regionId $name 有限值", f.isFinite())
            }
            assertTrue("$regionId lit 非空(台座+地形)", g.lit.isNotEmpty())
            assertEquals("$regionId 水面 = 1 quad = 6 顶点 × 9 = 54", 54, g.water.size)
            assertTrue("$regionId emis 非空(城窗/塔灯)", g.emis.isNotEmpty())
        }
    }

    @Test
    fun terrainGrid_hasExpectedTriangleCount() {
        // 地形 = 台座 4 壁(各 2 tri=8) + RES×RES×2 网格 tri。lit 至少含这些（+树+城+塔）。
        val g = ContinentGeometry.buildRegion(ContinentStyle.STYLES.getValue("willow_mist"), sites("yunze"))
        val minTerrainVerts = (8 + 64 * 64 * 2) * 3 // tri×3 顶点
        assertTrue("lit 顶点 ≥ 台座+地形", g.lit.size / 9 >= minTerrainVerts)
    }

    @Test
    fun continentStars_14_inTopBand_deterministic() {
        val s = ContinentGeometry.buildContinentStars(42L)
        assertEquals(ContinentGeometry.STAR_COUNT * 4, s.size)
        for (i in 0 until ContinentGeometry.STAR_COUNT) {
            val y = s[i * 4 + 1] // y_ndc = 1 − 2·topFrac·topFrac∈[0,0.26) → y∈(0.48,1]
            assertTrue("星 $i 落屏上 26% 带", y > 0.47f && y <= 1f)
            val size = s[i * 4 + 2]
            assertTrue("星 $i 尺寸 2 或 1.4", size == 2f || size == 1.4f)
            val x = s[i * 4] // x_ndc ∈ [-1,1)
            assertTrue("星 $i x 在 NDC", x >= -1f && x < 1f)
        }
        // 同世界种子恒同一片。
        assertArrayEquals(s, ContinentGeometry.buildContinentStars(42L), 0f)
    }
}
