package com.situ.aichat.ui.world.interior

import com.situ.aichat.world.stage.WorldWeatherKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [InteriorSceneData] T1-4（W9d 图纸 §7·E16/E17 + 咖啡馆金标）。
 *
 * 金标：§4.2A 咖啡馆转录表逐字段 vs 代码建出的 lit 顶点（box 首顶点 = (cx-sx/2, y0+h, cz+sz/2)·色）——
 * 与 demo↔转录的 verify_9d.py（chunk 5）合成 demo↔转录↔码 全链。界内/降水计数/昼夜差为 E16/E17。
 */
class InteriorLayoutTest {

    private val ALL_IDS = listOf(
        "yunye_home", "yunye_cafe", "yunye_book", "yunye_eat",
        "taoqiu_kiln", "taoqiu_market", "taoqiu_shop", "taoqiu_tea",
        "xiyu_market", "xiyu_hall",
    )

    private fun build(id: String, weather: WorldWeatherKind = WorldWeatherKind.CLEAR, night: Boolean = true) =
        InteriorSceneData.of(id, weather, night)!!

    /** lit/emis 流里是否存在 pos≈(x,y,z) 且 col≈hex 的顶点（9 分量/顶点）。 */
    private fun hasVertex(a: FloatArray, x: Double, y: Double, z: Double, hex: Int): Boolean {
        val r = (((hex shr 16) and 255) / 255.0).toFloat()
        val g = (((hex shr 8) and 255) / 255.0).toFloat()
        val b = ((hex and 255) / 255.0).toFloat()
        var i = 0
        while (i < a.size) {
            if (abs(a[i] - x) < 2e-3 && abs(a[i + 1] - y) < 2e-3 && abs(a[i + 2] - z) < 2e-3 &&
                abs(a[i + 6] - r) < 3e-3 && abs(a[i + 7] - g) < 3e-3 && abs(a[i + 8] - b) < 3e-3
            ) return true
            i += 9
        }
        return false
    }

    /** box 首顶点 = 顶面 (cx-sx/2, y0+h, cz+sz/2)（TriStream.box 顺序·golden 用）。 */
    private fun assertBox(a: FloatArray, cx: Double, y0: Double, cz: Double, sx: Double, h: Double, sz: Double, hex: Int, tag: String) {
        assertTrue("金标缺件: $tag", hasVertex(a, cx - sx / 2, y0 + h, cz + sz / 2, hex))
    }

    // ---- 咖啡馆金标（§4.2A 转录表逐字段 vs 代码常量）----

    @Test
    fun `T1-4 咖啡馆金标_关键件逐字段`() {
        val lit = build("yunye_cafe").geometry.lit
        assertBox(lit, 2.1, 0.0, -2.55, 3.8, 1.0, 1.05, 0xC99A86, "吧台台身 CLAY")
        assertBox(lit, 2.1, 1.0, -2.55, 4.1, 0.1, 1.25, 0x3A322C, "台面 DARKCLAY")
        assertBox(lit, 2.1, 0.32, -1.99, 3.8, 0.06, 0.05, 0xC2A26B, "铜踏杆 BRASS")
        assertBox(lit, 1.3, 1.1, -2.75, 0.9, 0.62, 0.55, 0xCFC6BA, "意式机")
        assertBox(lit, 2.35, 1.1, -2.8, 0.3, 0.5, 0.3, 0x8E8377, "磨豆机")
        assertBox(lit, 2.1, 2.0, -3.28, 3.4, 0.08, 0.3, 0x8A6B4E, "吊架 WOOD")
        assertBox(lit, -0.9, 1.5, -3.34, 1.7, 1.2, 0.08, 0x2F2A26, "黑板")
        assertBox(lit, 4.6, 0.0, -2.7, 0.9, 1.7, 0.4, 0x8A6B4E, "书报架 WOOD")
        // pendant 地面光池首顶点 = (cx-r, 0.016, cz+r) #AE9068
        assertTrue("金标缺件: 吊灯光池 #AE9068", hasVertex(lit, 0.9 - 1.15, 0.016, 0.85 + 1.15, 0xAE9068))
        // 房壳材质：地板 plankA=WOOD 首条 px=1 顶点存在（floor quad 首顶点 (x0,0,RZ1)）
        assertTrue("地板 plankA WOOD", hasVertex(lit, -5.2 + 1 * 0.8, 0.0, 3.4, 0x8A6B4E))
    }

    // ---- E16 界内（lit 全件·墙厚 WT 容差·窗外景除外）----

    @Test
    fun `E16 十间 lit 顶点全界内`() {
        // 容差含：墙厚 WT(0.16) + 地面光池贴墙外溢（floorLamp/hearth 的暖池会漫到墙脚下·视觉被墙遮·装机复审）。
        // 目的 = 抓「飞出房间到虚空」的坐标笔误，非像素级封闭。
        for (id in ALL_IDS) {
            val lit = build(id).geometry.lit
            var i = 0
            while (i < lit.size) {
                val x = lit[i]; val y = lit[i + 1]; val z = lit[i + 2]
                assertTrue("$id x 越界 $x", x in -5.6f..5.6f)
                assertTrue("$id z 越界 $z", z in -3.7f..3.7f)
                assertTrue("$id y 越界 $y", y in -0.05f..3.35f)
                i += 9
            }
        }
    }

    @Test
    fun `E16 锚位与场景点界内`() {
        for (id in ALL_IDS) {
            val d = build(id)
            val anchors = buildList {
                d.nativeAnchor?.let { add(it) }
                addAll(d.guestSlots); addAll(d.petSpots); addAll(d.steamSpots)
                addAll(d.flavorSpots.map { it.anchor })
            }
            for (an in anchors) {
                assertTrue("$id 锚 x=${an.x}", an.x in -5.2f..5.2f)
                assertTrue("$id 锚 z=${an.z}", an.z in -3.4f..3.4f)
                assertTrue("$id 锚 y=${an.y}", an.y in 0f..3.3f)
            }
        }
    }

    @Test
    fun `E16 id唯一_hasInterior单源`() {
        assertEquals(10, ALL_IDS.toSet().size)
        for (id in ALL_IDS) assertTrue(InteriorSceneData.hasInterior(id))
        assertFalse(InteriorSceneData.hasInterior("yunye_park"))   // 环境地点无室内
        assertFalse(InteriorSceneData.hasInterior("xiyu_cove"))    // 风景无室内
        assertFalse(InteriorSceneData.hasInterior("city_g_x_0"))   // 程序城无室内
        // 场景点 id 全局唯一（§4.10 十一条）
        val spotIds = ALL_IDS.flatMap { build(it).flavorSpots.map { s -> s.id } }
        assertEquals(11, spotIds.size)
        assertEquals(11, spotIds.toSet().size)
    }

    // ---- E17 窗景变体 + 降水 ----

    @Test
    fun `E17 降水计数_雨26 雪34 晴0`() {
        assertEquals(0, build("yunye_cafe", WorldWeatherKind.CLEAR).geometry.precip.size)
        assertEquals(26 * 6 * 9, build("yunye_cafe", WorldWeatherKind.RAIN).geometry.precip.size) // 26 quad×6 顶点×9 分量
        assertEquals(34 * 6 * 9, build("yunye_cafe", WorldWeatherKind.SNOW).geometry.precip.size) // 34 quad
    }

    @Test
    fun `E17 昼夜窗景差_夜多远窗灯18顶点`() {
        val night = build("yunye_cafe", night = true).geometry.emis
        val day = build("yunye_cafe", night = false).geometry.emis
        // 夜=夜幕(6)+屋影(90)+远窗灯(24)=120·昼=天幕(12)+屋影(90)=102·差 18 顶点（×9 分量）。
        assertEquals(18 * 9, night.size - day.size)
    }

    @Test
    fun `E17 降水确定性_同参恒同`() {
        val a = build("taoqiu_kiln", WorldWeatherKind.RAIN).geometry.precip
        val b = build("taoqiu_kiln", WorldWeatherKind.RAIN).geometry.precip
        assertTrue(a.contentEquals(b))
    }
}
