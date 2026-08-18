package com.situ.aichat.world.atlas

import com.situ.aichat.world.WorldIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * `WorldAtlas` T1（W3 图纸 §3.6 / E1–E6·E10）：确定性 / 城数 / 坐标域 / 间距 / 唯一性 / 距离尺度 / 金标。
 *
 * 断言从图纸 §3.2/§3.6 规格独立反推：城数区间用 §3.2 的 min–max 手算（3+7×8+4 生成 +3 精修）；距离区间用
 * §3.2 的中心坐标手算；金标值实跑固化（kotlin.random.Random 序列无法离线复算·E10 授权「实跑取值后固化」）。
 *
 * 注：E9（今日一句 / `dailyLine`）依赖 `WorldResidents`（chunk 3），本 chunk 尚无该文件，故 E9 挪到
 * `WorldResidentsLoreTest`（见图纸 §11 施工日志）；E7/E8 亦在 chunk 3 的居民/风物志测试里。
 */
class WorldAtlasTest {

    private val SEED = 42L

    // ---- E1 同 seed 构建两次逐字段完全相同 ----
    @Test
    fun sameSeedRebuildIsIdentical() {
        val a = WorldAtlas.of(SEED)
        WorldAtlas.of(SEED + 1) // 挤掉单座缓存，逼下一次 of(SEED) 真重建
        val b = WorldAtlas.of(SEED)
        assertEquals("城逐字段应完全相同", a.cities, b.cities) // WorldCity 是 data class → 深比
        assertEquals(a.regions, b.regions)
        assertEquals(a.wonders, b.wonders)
        assertEquals(a.places, b.places)
    }

    // ---- E2 城市总数 ∈ [66,78] ----
    @Test
    fun totalCityCountInRange() {
        // 生成 min=3+7×8+4=63 / max=5+8×8+6=75；+3 精修 → [66,78]。
        assertTrue(WorldAtlas.of(SEED).cities.size in 66..78)
    }

    // ---- E3 全城坐标在平面内、且距大区中心 ≤ 半径（+1 整数取整容差·实测最大越界 0.37 里）----
    @Test
    fun everyCityInPlaneAndWithinRegionRadius() {
        val atlas = WorldAtlas.of(SEED)
        for (c in atlas.cities) {
            assertTrue("x 越界: $c", c.x in 0..WorldAtlas.PLANE_WIDTH)
            assertTrue("y 越界: $c", c.y in 0..WorldAtlas.PLANE_HEIGHT)
            val r = atlas.regionById(c.regionId)!!
            val d = hypot((c.x - r.centerX).toDouble(), (c.y - r.centerY).toDouble())
            assertTrue("城 $c 距中心 $d 超半径 ${r.radiusLi}", d <= r.radiusLi + 1.0)
        }
    }

    // ---- E4 同大区任两城间距 ≥ 30 里（守卫目标 40·兜底 30）----
    @Test
    fun sameRegionCitiesRespectMinSpacing() {
        val atlas = WorldAtlas.of(SEED)
        for (r in atlas.regions) {
            val cs = atlas.citiesIn(r.id)
            for (i in cs.indices) for (j in i + 1 until cs.size) {
                val d = atlas.distanceLi(cs[i].id, cs[j].id)
                assertTrue("${cs[i].id}↔${cs[j].id} 间距 $d < 30", d >= 30)
            }
        }
    }

    // ---- E5 城 id/城名全球唯一；无城名撞奇观名 ----
    @Test
    fun idsAndNamesAreGloballyUnique() {
        val atlas = WorldAtlas.of(SEED)
        val ids = atlas.cities.map { it.id }
        val names = atlas.cities.map { it.name }
        assertEquals("城 id 有重复", ids.size, ids.toSet().size)
        assertEquals("城名有重复", names.size, names.toSet().size)
        val wonderNames = atlas.wonders.map { it.name }.toSet()
        assertTrue("生成城撞奇观名", names.none { it in wonderNames })
    }

    // ---- E6 距离尺度：家乡→荒角大区 ∈ [3300,4400]；同大区任两城 ≤ 220 ----
    @Test
    fun distanceScaleMatchesContract() {
        val atlas = WorldAtlas.of(SEED)
        for (c in atlas.citiesIn("huangjiao")) {
            val d = atlas.distanceLi("city_yunye", c.id)
            assertTrue("家乡→荒角 $d 越界 [3300,4400]", d in 3300..4400)
        }
        for (r in atlas.regions) {
            val cs = atlas.citiesIn(r.id)
            for (i in cs.indices) for (j in i + 1 until cs.size) {
                val d = atlas.distanceLi(cs[i].id, cs[j].id)
                assertTrue("同大区 ${cs[i].id}↔${cs[j].id} 间距 $d > 220", d <= 220)
            }
        }
    }

    // ---- E10 金标钉死（seed=42L·实跑固化·绝不许改）----
    @Test
    fun goldValuesAreFrozen() {
        val atlas = WorldAtlas.of(42L)
        assertEquals("总城数金标", 74, atlas.cities.size)
        val g0 = atlas.cityById("city_g_yunze_0")!! // yunze index0 的首个生成城
        assertEquals("沙驿", g0.name)
        assertEquals(560, g0.x)
        assertEquals(1354, g0.y)
        assertEquals(CityTier.SMALL, g0.tier)
        assertEquals("莲塘", g0.specialty)
        assertEquals(810, atlas.distanceLi("city_yunye", "city_taoqiu"))
        // 家乡城 id 与 WorldIds 常量钉死。
        assertEquals(WorldIds.HOME_CITY_ID, atlas.cityById("city_yunye")!!.id)
    }
}
