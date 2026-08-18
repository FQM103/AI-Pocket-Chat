package com.situ.aichat.ui.world.town

import com.situ.aichat.ui.world.continent.rgb
import com.situ.aichat.world.atlas.WorldCuratedCities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TownLayout] T1（W9c 图纸 §5 E1/E2·§7 T1-1）：云野镇逐字段金标（与 §4.1A 独立对照·demo 逐值）+ 陶丘/汐屿
 * 网格↔`PLACES`（中心=各自地点质心·§3.3）+ 全件界内 ±20。金标由图纸独立反推·非照搬实现。
 */
class TownLayoutTest {

    private val eps = 1e-4

    private fun assertColor(msg: String, hex: Int, c: DoubleArray) {
        val e = rgb(hex)
        assertEquals("$msg r", e[0], c[0], eps); assertEquals("$msg g", e[1], c[1], eps); assertEquals("$msg b", e[2], c[2], eps)
    }

    // ─────────────────────────── E1 云野镇逐字段金标（demo:L141-188）───────────────────────────

    @Test
    fun yunye_center_isDemoLocked() {
        val t = TownLayout.tableOf("city_yunye")!!
        assertEquals(5.5, t.centerGx, eps); assertEquals(6.5, t.centerGy, eps)
    }

    @Test
    fun yunye_buildings_matchDemoValues() {
        val t = TownLayout.tableOf("city_yunye")!!
        val b = t.spec.buildings
        assertEquals("4 座建筑", 4, b.size)
        // home G(6,7)=(1.8,1.8)·3.2×2.4×2.8·#C99A86/#9A5B3E·3 窗。
        assertEquals(1.8, b[0].cx, eps); assertEquals(1.8, b[0].cz, eps)
        assertEquals(3.2, b[0].sx, eps); assertEquals(2.4, b[0].h, eps); assertEquals(2.8, b[0].sz, eps)
        assertColor("home 墙", 0xC99A86, b[0].wall); assertColor("home 顶", 0x9A5B3E, b[0].roof)
        assertEquals(3, b[0].windows)
        // cafe G(5,6)=(-1.8,-1.8)·3.4×2.2×2.6·#B98A6E/#8A4E33·3。
        assertEquals(-1.8, b[1].cx, eps); assertEquals(-1.8, b[1].cz, eps)
        assertEquals(3.4, b[1].sx, eps); assertColor("cafe 墙", 0xB98A6E, b[1].wall); assertEquals(3, b[1].windows)
        // book G(7,6)=(5.4,-1.8)·2.8×2.6×2.4·#8E9AA6/#5C6B7C·2。
        assertEquals(5.4, b[2].cx, eps); assertEquals(-1.8, b[2].cz, eps)
        assertEquals(2.6, b[2].h, eps); assertColor("book 墙", 0x8E9AA6, b[2].wall); assertEquals(2, b[2].windows)
        // eat G(6,5)=(1.8,-5.4)·2.6×2.0×2.2·#C4A484/#9A5B3E·2。
        assertEquals(1.8, b[3].cx, eps); assertEquals(-5.4, b[3].cz, eps)
        assertEquals(2.0, b[3].h, eps); assertColor("eat 墙", 0xC4A484, b[3].wall); assertEquals(2, b[3].windows)
    }

    @Test
    fun yunye_placeTops_matchDemo() {
        val tops = TownLayout.tableOf("city_yunye")!!.placeTops
        // 建筑 top = h+1.1；环境件 = demo:L176/L180/L182。
        assertEquals(2.4 + 1.1, tops["yunye_home"]!!, eps)
        assertEquals(2.2 + 1.1, tops["yunye_cafe"]!!, eps)
        assertEquals(2.6 + 1.1, tops["yunye_book"]!!, eps)
        assertEquals(2.0 + 1.1, tops["yunye_eat"]!!, eps)
        assertEquals(3.6, tops["yunye_square"]!!, eps)
        assertEquals(2.6, tops["yunye_park"]!!, eps)
        assertEquals(1.2, tops["yunye_dock"]!!, eps)
        assertEquals("7 地点全有锚高", 7, tops.size)
    }

    @Test
    fun yunye_env_riverGroundFillersLanterns() {
        val s = TownLayout.tableOf("city_yunye")!!.spec
        assertEquals(TownWater.WEST_RIVER, s.water)
        assertColor("地面", 0xC7A987, s.ground)
        assertEquals("填充民居 5", 5, s.fillers.size)
        assertEquals("灯柱 3", 3, s.lanterns.size)
        assertEquals("树 10（记忆点 4 + 补充 6·§3.4）", 10, s.trees.size)
        // 广场大槐树 s1.6 @ G(5,7)=(-1.8,1.8)·叶 #7E926E·普通树 0.7/1.5（记忆点 trees[0] 逐字节不变=E6）。
        val huai = s.trees[0]
        assertEquals(-1.8, huai.cx, eps); assertEquals(1.8, huai.cz, eps); assertEquals(1.6, huai.s, eps)
        assertEquals(0.7, huai.trunkH, eps); assertEquals(1.5, huai.coneH, eps); assertColor("槐树叶", 0x7E926E, huai.leaf)
        // 码头板一 @ G(3,5)=(-9,-5.4) 偏 -1.4 = (-10.4,-5.4)·4.2×0.18×1.1·#8A6B4E。
        val dock = s.litBoxes[0]
        assertEquals(-10.4, dock.cx, eps); assertEquals(0.06, dock.y0, eps); assertEquals(4.2, dock.sx, eps)
    }

    // ─────────────────────────── E2 陶丘/汐屿 网格↔PLACES·质心·界内 ───────────────────────────

    @Test
    fun taoqiu_center_equalsPlaceCentroid() {
        val places = WorldCuratedCities.PLACES.filter { it.cityId == "city_taoqiu" }
        val cx = places.map { it.x }.average(); val cy = places.map { it.y }.average()
        val t = TownLayout.tableOf("city_taoqiu")!!
        assertEquals("陶丘中心 = 地点质心 X", cx, t.centerGx, eps)
        assertEquals("陶丘中心 = 地点质心 Y", cy, t.centerGy, eps)
        assertEquals(6.0, t.centerGx, eps); assertEquals(5.8, t.centerGy, eps)
    }

    @Test
    fun xiyu_center_equalsPlaceCentroid() {
        val places = WorldCuratedCities.PLACES.filter { it.cityId == "city_xiyu" }
        val cx = places.map { it.x }.average(); val cy = places.map { it.y }.average()
        val t = TownLayout.tableOf("city_xiyu")!!
        assertEquals(cx, t.centerGx, eps); assertEquals(cy, t.centerGy, eps)
        assertEquals(5.6, t.centerGx, eps); assertEquals(7.0, t.centerGy, eps)
    }

    @Test
    fun curatedCities_placeTops_keysMatchPlaces() {
        for (cityId in listOf("city_yunye", "city_taoqiu", "city_xiyu")) {
            val expected = WorldCuratedCities.PLACES.filter { it.cityId == cityId }.map { it.id }.toSet()
            assertEquals("$cityId 锚高键集 == PLACES", expected, TownLayout.tableOf(cityId)!!.placeTops.keys)
        }
    }

    @Test
    fun allCurated_geometryWithinBounds() {
        for (cityId in listOf("city_yunye", "city_taoqiu", "city_xiyu")) {
            val s = TownLayout.tableOf(cityId)!!.spec
            val xs = buildList {
                s.buildings.forEach { add(it.cx to it.cz) }; s.fillers.forEach { add(it.cx to it.cz) }
                s.lanterns.forEach { add(it.cx to it.cz) }; s.trees.forEach { add(it.cx to it.cz) }
                s.litBoxes.forEach { add(it.cx to it.cz) }; s.emisBoxes.forEach { add(it.cx to it.cz) }
                s.cones.forEach { add(it.cx to it.cz) }
                s.grammar.forEach { p ->   // 补充段语法件（corner-anchored·§3.4）
                    when (p) {
                        is GrammarPart.LitBox -> add(p.x to p.z); is GrammarPart.Roof -> add(p.x to p.z)
                        is GrammarPart.EmisBox -> add(p.x to p.z)
                    }
                }
            }
            for ((x, z) in xs) {
                assertTrue("$cityId 件 |x|=$x ≤20", kotlin.math.abs(x) <= 20.0)
                assertTrue("$cityId 件 |z|=$z ≤20", kotlin.math.abs(z) <= 20.0)
            }
        }
    }

    // ─────────────────────────── §3.4 E6 氛围补充段（只追加·记忆点零 diff）───────────────────────────

    @Test
    fun curated_supplement_appendedAfterMemory() {
        // 补充段追加于尾·记忆点前缀逐字节不变（buildings/tops/dock 由上面金标守）；远景层由 TownRenderer 供给（spec 不携带）。
        val y = TownLayout.tableOf("city_yunye")!!.spec
        assertEquals("云野镇补充边缘民居 3 栋 = 6 件", 6, y.grammar.size)
        assertEquals("均 gable 顶", 3, y.grammar.count { it is GrammarPart.Roof && it.style == RoofStyle.GABLE })
        val alley = y.litBoxes.last()   // 支巷追加于 litBoxes 尾
        assertEquals("支巷宽 1.0", 1.0, alley.sx, eps); assertColor("支巷石板", 0xD0BA9A, alley.col)
        // 陶丘/汐屿：边缘民居3 + 树 5·无支巷（§3.4）。
        val tao = TownLayout.tableOf("city_taoqiu")!!.spec
        val xiyu = TownLayout.tableOf("city_xiyu")!!.spec
        assertEquals("陶丘 6 补充件", 6, tao.grammar.size); assertEquals("陶丘树 2+5", 7, tao.trees.size)
        assertEquals("汐屿 6 补充件", 6, xiyu.grammar.size); assertEquals("汐屿树 4+5", 9, xiyu.trees.size)
        // 汐屿补充全件 x<13（避东海）。
        xiyu.grammar.filterIsInstance<GrammarPart.LitBox>().forEach { assertTrue("汐屿补充 x<13", it.x + it.sx < 13.0) }
        xiyu.trees.forEach { assertTrue("汐屿树 x<13", it.cx < 13.0) }
    }

    @Test
    fun taoqiu_xiyu_waterAndGround() {
        val tao = TownLayout.tableOf("city_taoqiu")!!.spec
        assertEquals("陶丘无水", TownWater.NONE, tao.water); assertColor("陶丘地面", 0xCBA379, tao.ground)
        val xiyu = TownLayout.tableOf("city_xiyu")!!.spec
        assertEquals("汐屿东海", TownWater.EAST_SEA, xiyu.water); assertColor("汐屿地面", 0xE2CFA0, xiyu.ground)
    }
}
