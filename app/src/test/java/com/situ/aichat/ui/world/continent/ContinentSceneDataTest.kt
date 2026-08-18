package com.situ.aichat.ui.world.continent

import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.atlas.CityTier
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.atlas.WorldLoreSkeleton
import com.situ.aichat.world.atlas.WorldRegions
import com.situ.aichat.world.atlas.WorldWonders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContinentSceneData] T1（W9b 图纸 §5 E5/E6·§7 T1-3）：三种子站位映射全落盒内 + 每区奇观计数 +
 * home 标志唯一（E5）；三精修城 / 程序城模板 / 奇观线索文案逐字（E6·全串 §9 锁死）。纯逻辑 → 直接
 * 用真 [WorldAtlas]（无 Android/DB）。
 */
class ContinentSceneDataTest {

    // 资源串金标（§4.7·values-zh-rCN）——UI 侧从 R.string.* 解析后传入，测里硬编码验组装。
    private val strings = ContinentStrings(
        cityBodyTemplate = "以%1\$s闻名的%2\$s。城里有%3\$s。",
        tierSmall = "小城",
        tierTown = "镇",
        tierCity = "城",
        wonderBodyTemplate = "%1\$s。",
        curatedBodies = mapOf(
            "city_yunye" to "家乡。河边的灯一盏盏亮起来，谁家的晚饭香飘得很远。",
            "city_taoqiu" to "千窑陶都。窑火几百年没熄过，落日时整面坡像上了一层釉。",
            "city_xiyu" to "潮汐渔歌。涨潮时船屋轻轻摇，退潮后滩上会留下星星形状的小洞。",
        ),
    )

    private fun scene(seed: Long, regionId: String) =
        ContinentSceneData.fromAtlas(WorldAtlas.of(seed), regionId, strings)

    // ─────────────────────────── E5 站位域 / 奇观计数 / home 唯一 ───────────────────────────

    @Test
    fun allSites_withinBox_acrossSeeds() {
        for (seed in listOf(1L, 42L, -7L)) {
            for (region in WorldRegions.ALL) {
                val data = scene(seed, region.id)
                for (site in data.sites) {
                    assertTrue("seed=$seed ${region.id} ${site.name} |x|=${site.x}≤14.1", kotlin.math.abs(site.x) <= 14.1f)
                    assertTrue("seed=$seed ${region.id} ${site.name} |z|=${site.z}≤14.1", kotlin.math.abs(site.z) <= 14.1f)
                }
            }
        }
    }

    @Test
    fun wonderCount_perRegion_matchesAtlas() {
        for (region in WorldRegions.ALL) {
            val expected = WorldWonders.ALL.count { it.regionId == region.id }
            val actual = scene(42L, region.id).sites.count { it.isWonder }
            assertEquals("${region.id} 奇观数", expected, actual)
            assertTrue("${region.id} 至少一处奇观", actual >= 1)
        }
    }

    @Test
    fun homeFlag_onlyOnYunyeTown() {
        val homeSites = WorldRegions.ALL.flatMap { scene(42L, it.id).sites }.filter { it.isHome }
        assertEquals("全世界唯一 home 站位", 1, homeSites.size)
        assertEquals(WorldIds.HOME_CITY_ID, homeSites.single().id)
        assertEquals("云野镇", homeSites.single().name)
        assertTrue("home 非奇观", !homeSites.single().isWonder)
    }

    @Test
    fun sceneMeta_flagsHomeRegion_andCarriesFlavor() {
        val yunze = scene(42L, "yunze")
        assertTrue("云泽=家乡区", yunze.isHome)
        assertEquals("云泽大区", yunze.regionName)
        assertEquals(WorldRegions.ALL.first { it.id == "yunze" }.flavor, yunze.flavor)
        assertTrue("非家乡区不标 home", !scene(42L, "mushan").isHome)
    }

    // ─────────────────────────── E6 文案逐字 ───────────────────────────

    @Test
    fun curatedCityBodies_verbatim() {
        val yunye = scene(42L, "yunze").sites.first { it.id == "city_yunye" }
        assertEquals("家乡。河边的灯一盏盏亮起来，谁家的晚饭香飘得很远。", yunye.body)
        val taoqiu = scene(42L, "huangsha").sites.first { it.id == "city_taoqiu" }
        assertEquals("千窑陶都。窑火几百年没熄过，落日时整面坡像上了一层釉。", taoqiu.body)
        val xiyu = scene(42L, "nanyu").sites.first { it.id == "city_xiyu" }
        assertEquals("潮汐渔歌。涨潮时船屋轻轻摇，退潮后滩上会留下星星形状的小洞。", xiyu.body)
    }

    @Test
    fun programCityBody_matchesTemplate_withTierAndLandmark() {
        // E6 模板金标（纯格式化·不依赖具体城）。
        assertEquals(
            "以陶窑闻名的小城。城里有一口从不干涸的老井。",
            strings.cityBodyTemplate.format("陶窑", "小城", "一口从不干涸的老井"),
        )
        // 真程序城：body == 模板(specialty, tier 词, skeleton.landmarkHint)。
        val atlas = WorldAtlas.of(42L)
        val region = atlas.regionById("chalong")!!
        val genCity = atlas.citiesIn("chalong").first { !it.curated }
        val site = ContinentSceneData.fromAtlas(atlas, "chalong", strings).sites.first { it.id == genCity.id }
        val tierWord = when (genCity.tier) {
            CityTier.SMALL -> "小城"; CityTier.TOWN -> "镇"; CityTier.CITY -> "城"
        }
        val landmark = WorldLoreSkeleton.skeletonOf(atlas.seed, genCity, region).landmarkHint
        assertEquals(strings.cityBodyTemplate.format(genCity.specialty, tierWord, landmark), site.body)
        assertTrue("含特产", site.body.contains(genCity.specialty))
        assertTrue("含地标线索", site.body.contains(landmark))
    }

    @Test
    fun wonderBody_isHintPlusPeriod_andAllBodiesNonEmpty() {
        for (region in WorldRegions.ALL) {
            val data = scene(42L, region.id)
            for (site in data.sites) assertTrue("${region.id} ${site.name} body 非空", site.body.isNotBlank())
            for (wonder in WorldWonders.ALL.filter { it.regionId == region.id }) {
                val site = data.sites.first { it.id == wonder.id }
                assertEquals("${wonder.name} 线索+。", "${wonder.hint}。", site.body)
            }
        }
    }

    @Test
    fun markerTop_cityVsWonder_liftDiffers() {
        val yunze = scene(42L, "yunze")
        val city = yunze.sites.first { !it.isWonder }
        val wonder = yunze.sites.first { it.isWonder }
        // 城 = padH + 1.6；奇观 = padH + 6.2（padH=1.5）。
        assertEquals(1.5f + 1.6f, city.markerTop, 1e-4f)
        assertEquals(1.5f + 6.2f, wonder.markerTop, 1e-4f)
    }
}
