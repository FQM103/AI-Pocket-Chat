package com.situ.aichat.world.atlas

import com.situ.aichat.world.WorldIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WorldResidents` + `WorldLoreSkeleton` T1（W3 图纸 §3.7/§3.8 / E7·E8·E9 + 居民确定性）。
 *
 * E9（今日一句）本应在 `WorldAtlasTest`（§2 表），但其依赖 `WorldResidents` 属 chunk 3，故与居民测试同处（§11）。
 * 断言从图纸 §3.3/§3.7/§3.8 规格独立反推；居民金标（云野镇首位全名）实跑固化（E10·绝不许改）。
 */
class WorldResidentsLoreTest {

    private val SEED = 42L
    private val atlas = WorldAtlas.of(SEED)

    // ---- E7 风物志骨架：每座城九内容字段全非空（无空手而归）----
    @Test
    fun everyCityLoreSkeletonHasNoBlankField() {
        for (c in atlas.cities) {
            val region = atlas.regionById(c.regionId)!!
            val sk = WorldLoreSkeleton.skeletonOf(SEED, c, region)
            assertEquals(c.id, sk.cityId)
            for (f in listOf(sk.displayName, sk.regionName, sk.specialty, sk.oldStreet,
                    sk.signatureDish, sk.landmarkHint, sk.legendHint)) {
                assertTrue("空字段于城 ${c.id}: '$f'", f.isNotBlank())
            }
            // climate / tier 为枚举（非空类型·恒非空）——凑满九字段·且须正确挂接大区/城。
            assertEquals(region.climate, sk.climate)
            assertEquals(c.tier, sk.tier)
        }
    }

    // ---- E8 精修城逐字一致 + 云野镇地点齐全 ----
    @Test
    fun curatedCitiesMatchSpecVerbatim() {
        val yunye = atlas.cityById("city_yunye")!!
        assertEquals(WorldIds.HOME_CITY_ID, yunye.id)
        assertCity(yunye, "云野镇", "yunze", 600, 1300, CityTier.TOWN, "渡口水乡")
        assertCity(atlas.cityById("city_taoqiu")!!, "陶丘", "huangsha", 1070, 640, CityTier.CITY, "千窑陶都")
        assertCity(atlas.cityById("city_xiyu")!!, "汐屿", "nanyu", 2520, 2270, CityTier.TOWN, "潮汐渔歌")
        // 云野镇 7 地点齐全、坐标 ∈ [0,12]、逐字一致。
        val expected = listOf(
            WorldPlace("yunye_home", "你的家", "city_yunye", 6, 7),
            WorldPlace("yunye_cafe", "拾光咖啡馆", "city_yunye", 5, 6),
            WorldPlace("yunye_book", "青苔书店", "city_yunye", 7, 6),
            WorldPlace("yunye_park", "河畔公园", "city_yunye", 4, 8),
            WorldPlace("yunye_dock", "渡口码头", "city_yunye", 3, 5),
            WorldPlace("yunye_eat", "巷尾食铺", "city_yunye", 6, 5),
            WorldPlace("yunye_square", "老槐树广场", "city_yunye", 5, 7),
        )
        val places = atlas.placesOf("city_yunye")
        assertEquals(expected, places)
        for (p in places) {
            assertTrue(p.x in 0..12 && p.y in 0..12)
        }
        // 生成城无城内地点。
        assertTrue(atlas.placesOf("city_g_yunze_0").isEmpty())
    }

    private fun assertCity(c: WorldCity, name: String, region: String, x: Int, y: Int, tier: CityTier, spec: String) {
        assertEquals(name, c.name); assertEquals(region, c.regionId)
        assertEquals(x, c.x); assertEquals(y, c.y)
        assertEquals(tier, c.tier); assertEquals(spec, c.specialty)
        assertTrue(c.curated)
    }

    // ---- 居民确定性 + 数量 + 唯一性 + id/职业域 ----
    @Test
    fun residentsAreDeterministicUniqueAndWellFormed() {
        val yunye = atlas.cityById("city_yunye")!!
        assertEquals(WorldResidents.residentsOf(SEED, yunye), WorldResidents.residentsOf(SEED, yunye))
        assertEquals(7, WorldResidents.residentsOf(SEED, yunye).size)       // 精修：云野镇 7
        assertEquals(8, WorldResidents.residentsOf(SEED, atlas.cityById("city_taoqiu")!!).size) // 陶丘 8
        assertEquals(6, WorldResidents.residentsOf(SEED, atlas.cityById("city_xiyu")!!).size)   // 汐屿 6
        for (c in atlas.cities) {
            val rs = WorldResidents.residentsOf(SEED, c)
            val expectCount = if (c.curated) rs.size else when (c.tier) {
                CityTier.SMALL -> 3
                CityTier.TOWN -> 5
                CityTier.CITY -> 7
            }
            assertEquals("城 ${c.id} 居民数", expectCount, rs.size)
            assertEquals("同城全名不唯一 ${c.id}", rs.size, rs.map { it.name }.toSet().size)
            rs.forEachIndexed { i, r ->
                assertEquals("res_${c.id}_$i", r.id)
                assertTrue("职业越池: ${r.occupation}", r.occupation in WorldResidents.OCCUPATIONS)
                assertEquals(c.id, r.cityId)
            }
        }
    }

    // ---- E9 今日一句：同日恒同句 / 不同日可不同 / 无 {} 残留 ----
    @Test
    fun dailyLineIsDayStableAndSlotFree() {
        val yunye = atlas.cityById("city_yunye")!!
        val region = atlas.regionById(yunye.regionId)!!
        val r0 = WorldResidents.residentsOf(SEED, yunye)[0]
        assertEquals(
            WorldResidents.dailyLine(SEED, r0, region, 20000L),
            WorldResidents.dailyLine(SEED, r0, region, 20000L),
        ) // 同日恒同句
        assertNotEquals(
            WorldResidents.dailyLine(SEED, r0, region, 20000L),
            WorldResidents.dailyLine(SEED, r0, region, 20001L),
        ) // 不同日（此例）确实不同
        for (c in atlas.cities) {
            val rr = atlas.regionById(c.regionId)!!
            for (res in WorldResidents.residentsOf(SEED, c)) {
                val line = WorldResidents.dailyLine(SEED, res, rr, 20000L)
                assertTrue("残留槽: $line", !line.contains("{") && !line.contains("}"))
                assertTrue("未代入姓名: $line", line.contains(res.name))
            }
        }
    }

    // ---- E10 居民金标（seed=42L·实跑固化·绝不许改）----
    @Test
    fun residentGoldIsFrozen() {
        val yunye = atlas.cityById("city_yunye")!!
        val r0 = WorldResidents.residentsOf(42L, yunye)[0]
        assertEquals("池白", r0.name)          // 云野镇首位居民全名
        assertEquals("res_city_yunye_0", r0.id)
        assertEquals("陶匠", r0.occupation)
    }
}
