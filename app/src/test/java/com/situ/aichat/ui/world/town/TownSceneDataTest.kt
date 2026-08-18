package com.situ.aichat.ui.world.town

import com.situ.aichat.ui.world.continent.rgb
import com.situ.aichat.world.atlas.CityTier
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.atlas.WorldClimate
import com.situ.aichat.world.atlas.WorldRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TownSceneData] T1（W9c 图纸 §5 E3/E9/E16·§7 T1-2）：程序城确定性 + 楼数 by tier + Chebyshev 间距 + 水体
 * 十气候映射 + 地面色十区表（E3）；17 句地点正文逐字 + 程序城无地点（E9）；海域安全（E16）。纯逻辑 → 真 [WorldAtlas]。
 */
class TownSceneDataTest {

    // §4.5 全串锁死（UI 侧从 R.string.* 解析后传入·测里硬编码验组装）。
    private val strings = TownStrings(
        subtitleTemplate = "%1\$s · %2\$s",
        placeBodies = mapOf(
            "yunye_home" to "窗台的灯留着一盏，桌上的杯子还温着。",
            "yunye_cafe" to "靠窗的老位置空着，咖啡机咕嘟咕嘟冒着热气。",
            "yunye_book" to "书架间很安静，最上层有本书被抽走了一半。",
            "yunye_eat" to "灶上炖着莲藕汤，香味飘到了巷口。",
            "yunye_square" to "老槐树下石桌上有人落了一副棋。傍晚风很软。",
            "yunye_park" to "河边的长椅上落着几片叶子，鸭子排成一队游过。",
            "yunye_dock" to "末班渡船还有一会儿才开，船家在收缆绳。",
            "taoqiu_kiln" to "窑火从没熄过，坡上一排窑口像一串小太阳。",
            "taoqiu_market" to "摊子上釉色深深浅浅，挑一只碗要蹲很久。",
            "taoqiu_shop" to "转盘还在转，湿陶土的味道混着茶香。",
            "taoqiu_tea" to "火塘边的位置最抢手，茶是滚烫的，话是慢慢说的。",
            "taoqiu_view" to "站上望原台，整片高原的落日都是你的。",
            "xiyu_beach" to "潮水刚退，沙滩上留着一整片星星形状的小洞。",
            "xiyu_market" to "灯塔下的渔市收摊了，只剩海风和咸味。",
            "xiyu_walk" to "栈道在椰影里拐了个弯，木板被晒得暖暖的。",
            "xiyu_hall" to "贝壳做的风铃在门口响，馆里放着潮声的录音。",
            "xiyu_cove" to "星沙湾的沙子在暮色里真的会闪。",
        ),
    )

    private fun atlas(seed: Long) = WorldAtlas.of(seed)
    private fun of(seed: Long, cityId: String) = TownSceneData.of(atlas(seed), cityId, strings)

    /** 程序城：某区某档一座生成城 id（`!curated` 且 tier 匹配·seed 42 确定性）。 */
    private fun genCity(seed: Long, tier: CityTier): String =
        atlas(seed).cities.first { !it.curated && it.tier == tier }.id

    // ─────────────────────────── E1 程序城确定性（§3.2·街区网络·取代旧「一条街」栅格）───────────────────────────

    @Test
    fun procedural_deterministic_sameSeedSameCity() {
        val id = genCity(42L, CityTier.TOWN)
        val a = of(42L, id).layout; val b = of(42L, id).layout
        // 程序城改走 TownBlockPlan：楼=语法件(grammar)·填充恒空·街/广场/桥=litBoxes·树/灯直用。
        assertTrue("填充恒空（楼已改语法件）", a.fillers.isEmpty())
        assertEquals(a.grammar.size, b.grammar.size); assertEquals(a.trees.size, b.trees.size)
        assertEquals(a.lanterns.size, b.lanterns.size); assertEquals(a.litBoxes.size, b.litBoxes.size)
        a.litBoxes.forEachIndexed { i, box -> assertEquals(box.cx, b.litBoxes[i].cx, 0.0); assertEquals(box.sx, b.litBoxes[i].sx, 0.0) }
        a.trees.forEachIndexed { i, t -> assertEquals(t.cx, b.trees[i].cx, 0.0); assertEquals(t.cz, b.trees[i].cz, 0.0); assertEquals(t.s, b.trees[i].s, 0.0) }
    }

    @Test
    fun procedural_hasStreetGrammarTreesLanterns() {
        val layout = of(42L, genCity(42L, CityTier.TOWN)).layout
        assertTrue("主街 sx=23", layout.litBoxes.any { kotlin.math.abs(it.sx - 23.0) < 1e-9 })
        assertTrue("有语法建筑墙", layout.grammar.any { it is GrammarPart.LitBox && it.role == GrammarPart.BoxRole.WALL })
        assertTrue("树 12–18", layout.trees.size in 12..18)
        assertTrue("灯 5–6", layout.lanterns.size in 5..6)
    }

    @Test
    fun procedural_water_climateMapping() {
        fun waterOf(regionId: String): TownWater =
            of(42L, atlas(42L).citiesIn(regionId).first { !it.curated }.id).layout.water
        // 西河：TEMPERATE_LAKES/RAINY_COAST/FOREST_VALE。
        assertEquals(TownWater.WEST_RIVER, waterOf("yunze"))
        assertEquals(TownWater.WEST_RIVER, waterOf("xiyulin"))
        assertEquals(TownWater.WEST_RIVER, waterOf("yingchuan"))
        // 东海：TROPICAL_ISLES/EAST_COAST/FAR_CAPE。
        assertEquals(TownWater.EAST_SEA, waterOf("nanyu"))
        assertEquals(TownWater.EAST_SEA, waterOf("xinghai"))
        assertEquals(TownWater.EAST_SEA, waterOf("huangjiao"))
        // 无水：其余四气候。
        assertEquals(TownWater.NONE, waterOf("huangsha"))
        assertEquals(TownWater.NONE, waterOf("chalong"))
        assertEquals(TownWater.NONE, waterOf("jibei"))
        assertEquals(TownWater.NONE, waterOf("mushan"))
    }

    @Test
    fun procedural_groundColor_perRegionTable() {
        val expected = mapOf(
            "yunze" to 0xC7A987, "xiyulin" to 0xA9A186, "huangsha" to 0xCBA379, "yingchuan" to 0xB3A583,
            "chalong" to 0xB8B089, "jibei" to 0xD8D5CC, "mushan" to 0xB0A48E, "nanyu" to 0xE2CFA0,
            "xinghai" to 0xC2B694, "huangjiao" to 0xB5AB92,
        )
        for (region in WorldRegions.ALL) {
            val gen = atlas(42L).citiesIn(region.id).first { !it.curated }
            val ground = of(42L, gen.id).layout.ground
            val e = rgb(expected[region.id]!!)
            assertEquals("${region.id} 地面 r", e[0], ground[0], 1e-9); assertEquals(e[1], ground[1], 1e-9); assertEquals(e[2], ground[2], 1e-9)
        }
    }

    // ─────────────────────────── E9 地点正文逐字 / 程序城无地点 ───────────────────────────

    @Test
    fun curated_placeBodies_verbatim_allSeventeen() {
        val cities = listOf("city_yunye" to "yunze", "city_taoqiu" to "huangsha", "city_xiyu" to "nanyu")
        var count = 0
        for ((cityId, regionId) in cities) {
            val data = of(42L, cityId)
            assertTrue("$cityId 是精修城", data.curated)
            assertEquals(regionId, data.regionId)
            for (place in data.places) {
                assertEquals("${place.id} 正文逐字", strings.placeBodies[place.id], place.body)
                count++
            }
        }
        assertEquals("共 17 句", 17, count)
    }

    @Test
    fun procedural_hasNoPlaces() {
        for (tier in CityTier.entries) {
            assertTrue("程序城无地点标签", of(42L, genCity(42L, tier)).places.isEmpty())
        }
    }

    @Test
    fun subtitle_isRegionNameAndSpecialty() {
        // 云野镇 = 「云泽大区 · 渡口水乡」。
        assertEquals("云泽大区 · 渡口水乡", of(42L, "city_yunye").subtitle)
        // 程序城：「{大区名} · {specialty}」·specialty 来自大区特产。
        val gen = atlas(42L).citiesIn("chalong").first { !it.curated }
        assertEquals("茶陇丘野 · ${gen.specialty}", of(42L, gen.id).subtitle)
    }

    // ─────────────────────────── E16 海域安全（gx≤9 → x≤10.8 < 13）───────────────────────────

    @Test
    fun procedural_seaSafe_noBuildingReachesSeaX() {
        // 东海 x≥13·程序城网格 gx∈[3,9]·中心 6 → x_max=(9−6)×3.6=10.8 < 13（天然不相交）。
        for (region in WorldRegions.ALL.filter {
            it.climate in setOf(WorldClimate.TROPICAL_ISLES, WorldClimate.EAST_COAST, WorldClimate.FAR_CAPE)
        }) {
            val gen = atlas(42L).citiesIn(region.id).first { !it.curated }
            val layout = of(42L, gen.id).layout
            assertEquals(TownWater.EAST_SEA, layout.water)
            // 海域 x≥13：语法建筑墙右缘（corner x+sx）与街 / 广场盒右缘（cx+sx/2）均 < 13。
            for (w in layout.grammar.filterIsInstance<GrammarPart.LitBox>()) {
                assertTrue("${region.id} 语法件右缘 ${w.x + w.sx} < 13", w.x + w.sx < 13.0)
            }
            for (b in layout.litBoxes) assertTrue("${region.id} 盒右缘 ${b.cx + b.sx / 2} < 13", b.cx + b.sx / 2 < 13.0)
        }
    }

    @Test
    fun sky_isSevenStops_ascending() {
        for (cityId in listOf("city_yunye", "city_taoqiu", "city_xiyu")) {
            val sky = of(42L, cityId).sky
            assertEquals("$cityId 7 停靠", 7, sky.size)
            for (i in 1 until sky.size) assertTrue("$cityId 停靠升序", sky[i].pos >= sky[i - 1].pos)
        }
        // 程序城同为 7 停靠。
        assertEquals(7, of(42L, genCity(42L, CityTier.TOWN)).sky.size)
    }
}
