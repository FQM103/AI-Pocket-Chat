package com.situ.aichat.ui.world.town

import com.situ.aichat.ui.world.continent.ContinentStyle
import com.situ.aichat.ui.world.continent.RegionStyle
import com.situ.aichat.ui.world.continent.SkyStop
import com.situ.aichat.ui.world.continent.rgb
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.atlas.WorldClimate
import com.situ.aichat.world.atlas.WorldRegion

/** 一处小镇地点（精修城非空·程序城恒空·§3.2）：[top] = 标签/pick 锚高（§4.1）·[body] = 站点卡正文（§4.5）。 */
internal data class TownPlace(
    val id: String, val name: String, val x: Float, val z: Float, val top: Float, val body: String,
)

/**
 * 一座小镇的装载数据（W9c 图纸 §3.2·[TownSceneData.of] 产出·纯派生不入库）。[sky] = 7 停靠（§4.1E·云野镇
 * demo 原 7 / 其余城大区 5 停靠共线 pad 到 7·渲染等价）·[glowA] = 大区辉光强度。
 */
internal data class TownData(
    val cityId: String,
    val cityName: String,
    val curated: Boolean,
    val regionId: String,
    val subtitle: String,       // 「{大区名} · {specialty}」
    val sky: List<SkyStop>,     // 7 停靠
    val glowA: Float,
    val layout: TownLayoutSpec,
    val places: List<TownPlace>,
)

/** 小镇文案（Compose 层解析 `R.string.*` 后传入·令 [TownSceneData] 保持纯逻辑可 T1 直测·§4.7 全串锁死）。 */
internal data class TownStrings(
    val subtitleTemplate: String,           // world_town_subtitle 「%1$s · %2$s」
    val placeBodies: Map<String, String>,   // placeId → 正文（17 键·§4.5）
)

/**
 * 图集 + 城内里网格 → 某城小镇盒景数据（W9c 图纸 §3.2/§3.3/§4.1）。精修三城查 [TownLayout] 手写表 + 地点坐标经
 * 图集 `PLACES` 经同一 G 映射复算；程序城走种子布局规则（§4.1D·地点恒空 = 纯氛围沙盘）。零 LLM / 零 DB。
 */
internal object TownSceneData {

    fun of(atlas: WorldAtlas.Atlas, cityId: String, strings: TownStrings): TownData {
        val city = requireNotNull(atlas.cityById(cityId)) { "unknown city: $cityId" }
        val region = requireNotNull(atlas.regionById(city.regionId)) { "unknown region: ${city.regionId}" }
        val style = requireNotNull(ContinentStyle.STYLES[region.styleKey]) { "no style: ${region.styleKey}" }
        val subtitle = strings.subtitleTemplate.format(region.name, city.specialty)
        val sky = if (cityId == "city_yunye") YUNYE_SKY else padTo7(style.sky)

        val table = TownLayout.tableOf(cityId)
        return if (table != null) {
            val places = atlas.placesOf(cityId).map { p ->
                TownPlace(
                    id = p.id,
                    name = p.name,
                    x = ((p.x - table.centerGx) * 3.6).toFloat(),
                    z = ((p.y - table.centerGy) * 3.6).toFloat(),
                    top = requireNotNull(table.placeTops[p.id]) { "no top for place: ${p.id}" }.toFloat(),
                    body = strings.placeBodies[p.id].orEmpty(),
                )
            }
            TownData(cityId, city.name, true, region.id, subtitle, sky, style.glowA, table.spec, places)
        } else {
            val layout = proceduralLayout(atlas.seed, cityId, region, style)
            TownData(cityId, city.name, false, region.id, subtitle, sky, style.glowA, layout, emptyList())
        }
    }

    // ─────────────────────────── 程序城街区网络（§3.2·[TownBlockPlan] 生成·取代旧「一条街」）───────────────────────────

    /** 地面色（十大区·§4.1D 锁死·keyed by regionId）。 */
    private val GROUND_COLORS = mapOf(
        "yunze" to 0xC7A987, "xiyulin" to 0xA9A186, "huangsha" to 0xCBA379, "yingchuan" to 0xB3A583,
        "chalong" to 0xB8B089, "jibei" to 0xD8D5CC, "mushan" to 0xB0A48E, "nanyu" to 0xE2CFA0,
        "xinghai" to 0xC2B694, "huangjiao" to 0xB5AB92,
    )

    /**
     * 程序城布局（§3.2·街区网络）：水体按大区气候映射 + 地面十区表 + [TownBlockPlan] 布点（主街 / 支巷 / 广场 /
     * 地标 / 建筑三排 / 树 / 灯 / 河桥）。同 (worldSeed, cityId) 逐件确定（E1）·地点恒空（纯氛围沙盘）。
     * `warn` 传空 = [TownSceneData] 保持纯逻辑可 T1（预算削减极罕见·行为已由 [TownBlockPlan] E3 覆盖·见 §11）。
     */
    private fun proceduralLayout(
        worldSeed: Long, cityId: String, region: WorldRegion, style: RegionStyle,
    ): TownLayoutSpec {
        val water = when (region.climate) {
            WorldClimate.TEMPERATE_LAKES, WorldClimate.RAINY_COAST, WorldClimate.FOREST_VALE -> TownWater.WEST_RIVER
            WorldClimate.TROPICAL_ISLES, WorldClimate.EAST_COAST, WorldClimate.FAR_CAPE -> TownWater.EAST_SEA
            else -> TownWater.NONE
        }
        val ground = rgb(requireNotNull(GROUND_COLORS[region.id]) { "no ground color: ${region.id}" })
        val plan = TownBlockPlan.plan(worldSeed, cityId, style, water)
        return TownLayoutSpec(
            ground = ground, water = water,
            buildings = emptyList(), fillers = emptyList(),
            lanterns = plan.lanterns, trees = plan.trees,
            litBoxes = plan.litBoxes, emisBoxes = emptyList(), cones = emptyList(),
            grammar = plan.grammar,
        )
    }

    // ─────────────────────────── 天空（§4.1E·7 停靠）───────────────────────────

    /** 云野镇天空 = demo 原 7 停靠（demo:L6·`180deg` 竖向渐变·pos 顶→底 0..1）。 */
    private val YUNYE_SKY: List<SkyStop> = sky7(
        0.0 to 0x233054, 0.26 to 0x3A4874, 0.44 to 0x6A6490, 0.56 to 0xA57F8C,
        0.66 to 0xC98A76, 0.78 to 0xE8B87E, 1.0 to 0xEFC98F,
    )

    private fun sky7(vararg stops: Pair<Double, Int>): List<SkyStop> = stops.map { (pos, hex) ->
        SkyStop(pos.toFloat(), floatArrayOf(((hex shr 16) and 255) / 255f, ((hex shr 8) and 255) / 255f, (hex and 255) / 255f))
    }

    /**
     * 大区 5 停靠共线 pad 到 7（§4.1E·渲染等价）：在两个最宽区段各插一个共线中点（保留全部原折点 → 分段线性
     * 渐变逐像素不变·图纸称「表为 §11.Z 脚本输出原样」·因渲染等价故实现该规则即忠实·见 §11 施工日志）。
     */
    private fun padTo7(stops: List<SkyStop>): List<SkyStop> {
        val list = stops.toMutableList()
        repeat(2) {
            var wi = 0; var widest = -1f
            for (i in 0 until list.size - 1) {
                val gap = list[i + 1].pos - list[i].pos
                if (gap > widest) { widest = gap; wi = i }
            }
            val a = list[wi]; val b = list[wi + 1]
            val mpos = (a.pos + b.pos) / 2f
            val t = if (b.pos == a.pos) 0f else (mpos - a.pos) / (b.pos - a.pos)
            list.add(wi + 1, SkyStop(mpos, FloatArray(3) { a.color[it] + (b.color[it] - a.color[it]) * t }))
        }
        return list
    }
}
