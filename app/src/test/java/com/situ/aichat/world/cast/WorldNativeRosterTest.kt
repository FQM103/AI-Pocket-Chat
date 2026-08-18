package com.situ.aichat.world.cast

import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.atlas.WorldRegions
import com.situ.aichat.world.social.WorldRelationshipTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldNativeRoster] T1-1（W6 图纸 §7·E1 全部花名册不变量·纯 JVM 断言·[WorldAtlas] 无 Android 依赖）。
 *
 * 断言从图纸 §4.2/§4.3/§4.4 规格**独立反推**：人数/唯一性/城解析/权重域/边有效性/「≥35 含朋友」/「无 ≥70」/
 * 色彩域/单一连通分量全独立重算，不照搬花名册实现。三种子（图集城名随种子·city id 稳定）验 cityId 恒可解析。
 */
class WorldNativeRosterTest {

    // 战役 B 双源合流后 ALL = 官方 + 用户居民；本 20 人不变量固定跑 OFFICIAL（图纸 §3.2 允许的唯一测试适配·语义不变）。
    private val roster = WorldNativeRoster.OFFICIAL
    private val regionIds = WorldRegions.ALL.map { it.id }.toSet()
    private val curatedCities = setOf("city_yunye", "city_taoqiu", "city_xiyu")

    // MARK: - E1 人设不变量

    @Test
    fun `E1 花名册恰 20 人_slug 与 name 全唯一`() {
        assertEquals(20, roster.size)
        assertEquals("slug 应全唯一", 20, roster.map { it.slug }.toSet().size)
        assertEquals("name 应全唯一", 20, roster.map { it.name }.toSet().size)
    }

    @Test
    fun `E1 每位_regionId 属十大区_threshold 正_权重落 0 到 1_5`() {
        for (d in roster) {
            assertTrue("${d.slug} regionId=${d.regionId} 不属十大区", d.regionId in regionIds)
            assertTrue("${d.slug} threshold 应 >0", d.recruitThreshold > 0)
            assertTrue("${d.slug} narrativeWeight 越界", d.narrativeWeight in 0.0..1.5)
            assertTrue("${d.slug} giftWeight 越界", d.giftWeight in 0.0..1.5)
            assertTrue("${d.slug} gender 域", d.gender == "male" || d.gender == "female")
            assertTrue("${d.slug} fixedAge 20–71", d.fixedAge in 20..71)
        }
        // 锁死人设实为 9 女 / 11 男（§4.2 逐字·两性皆备）。育人原则 prose 写「10/10」与锁死数据差一位——
        // 非 E1 不变量、§9 锁死人设为准（见 §11 施工日志·prose nit）。此处断言锁死实况以防将来误改性别。
        assertEquals(9, roster.count { it.gender == "female" })
        assertEquals(11, roster.count { it.gender == "male" })
    }

    @Test
    fun `E1 每位 cityId 在任意种子图集均可解析_且 regionId 与城一致_placeId 仅精修城`() {
        for (seed in listOf(1L, 20260704L, -998877L)) {
            val atlas = WorldAtlas.of(seed)
            for (d in roster) {
                val city = atlas.cityById(d.cityId)
                assertNotNull("${d.slug} cityId=${d.cityId} 在 seed=$seed 图集解析不到", city)
                assertEquals("${d.slug} regionId 与城不一致", city!!.regionId, d.regionId)
            }
        }
        // placeId 仅精修城非空（生成城恒 null·§3.1）。
        for (d in roster) {
            if (d.cityId in curatedCities) assertNotNull("${d.slug} 精修城应有 placeId", d.placeId)
            else assertNull("${d.slug} 生成城应无 placeId", d.placeId)
        }
    }

    // MARK: - E1 出厂边不变量（§4.3）

    @Test
    fun `E1 出厂边恰 20 条_slug 全有效_无自环无重复`() {
        val edges = WorldNativeRoster.FACTORY_EDGES
        assertEquals(20, edges.size)
        val seenPairs = mutableSetOf<Set<String>>()
        for (e in edges) {
            assertNotNull("边 slugA=${e.slugA} 无效", WorldNativeRoster.bySlug(e.slugA))
            assertNotNull("边 slugB=${e.slugB} 无效", WorldNativeRoster.bySlug(e.slugB))
            assertTrue("自环边 ${e.slugA}", e.slugA != e.slugB)
            assertTrue("重复边 ${e.slugA}↔${e.slugB}", seenPairs.add(setOf(e.slugA, e.slugB)))
        }
    }

    @Test
    fun `E1 closeness≥35 的边 types 必含朋友_且无 ≥70 边`() {
        for (e in WorldNativeRoster.FACTORY_EDGES) {
            val maxClo = maxOf(e.closenessAB, e.closenessBA)
            if (maxClo >= 35) {
                assertTrue("${e.slugA}↔${e.slugB} closeness≥35 却缺「朋友」", e.types.contains("朋友"))
            }
            assertTrue("${e.slugA}↔${e.slugB} 出现 ≥70 边", e.closenessAB < 70 && e.closenessBA < 70)
        }
    }

    @Test
    fun `E1 出厂边色彩全落 BASE_COLORS_恋爱色不用`() {
        val base = WorldRelationshipTypes.BASE_COLORS.toSet()
        for (e in WorldNativeRoster.FACTORY_EDGES) {
            assertTrue("${e.slugA}→${e.slugB} 色 ${e.colorAB} 不在 BASE_COLORS", e.colorAB in base)
            assertTrue("${e.slugB}→${e.slugA} 色 ${e.colorBA} 不在 BASE_COLORS", e.colorBA in base)
        }
        // 恋爱色物理排除（恋爱门语义不破）。
        for (e in WorldNativeRoster.FACTORY_EDGES) {
            assertFalse(e.colorAB in WorldRelationshipTypes.ROMANCE_COLORS)
            assertFalse(e.colorBA in WorldRelationshipTypes.ROMANCE_COLORS)
        }
    }

    // MARK: - E1 单一连通分量（§4.4·决策 26）

    @Test
    fun `E1 从 su_wan 出发 BFS 可达全部 20 人_连通分量为 1`() {
        val adj = HashMap<String, MutableList<String>>()
        for (d in roster) adj[d.slug] = mutableListOf()
        for (e in WorldNativeRoster.FACTORY_EDGES) {
            adj.getValue(e.slugA).add(e.slugB)
            adj.getValue(e.slugB).add(e.slugA)
        }
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>().apply { add("su_wan") }
        while (stack.isNotEmpty()) {
            val x = stack.removeLast()
            if (!seen.add(x)) continue
            adj.getValue(x).forEach { if (it !in seen) stack.add(it) }
        }
        assertEquals("从 su_wan BFS 未覆盖全部 20 人（连通分量≠1）", 20, seen.size)
    }

    @Test
    fun `E13 前哨_su_wan 出厂邻居声明序 = 林陌屿明前严真游信`() {
        // 与 chunk 3 的 E13 引荐候选同源（此处只验边表声明序·不涉运行态过滤）。
        val neighbors = WorldNativeRoster.factoryNeighborsOf("su_wan").map { it.slug }
        assertEquals(listOf("lin_moyu", "ming_qian", "yan_zhen", "you_xin"), neighbors)
    }
}
