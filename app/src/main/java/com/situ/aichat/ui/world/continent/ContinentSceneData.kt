package com.situ.aichat.ui.world.continent

import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.atlas.CityTier
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.atlas.WorldLoreSkeleton
import com.situ.aichat.world.atlas.WorldWonders

/**
 * 一个站位（城市金点 / 奇观✦·W9b 图纸 §3.2）。[x]/[z] = 盒景坐标（§3.3 映射）；[markerTop] = pad 顶
 * （城 `padH+1.6`、奇观 `padH+6.2`·demo:L229,236）；[body] = 站点卡正文（§4.6 组装后传入·UI 不再拼）。
 */
internal data class ContinentSite(
    val id: String,
    val name: String,
    val isWonder: Boolean,
    val isHome: Boolean,
    val curated: Boolean,
    val x: Float,
    val z: Float,
    val markerTop: Float,
    /** 城市微簇楼数（§4.1 城簇规则·家乡/CITY=3·其余城=2·奇观=0）。图纸 §3.2 未列此字段但 §4.1 楼数依 tier——
     *  规则值全定、仅需把 tier 从图集塞进渲染层，故在此(有 tier 处)算好塞入·见图纸 §11 施工日志。 */
    val buildingCount: Int,
    val body: String,
)

/** 一个大区的盒景装载数据（W9b 图纸 §3.2·[Companion.fromAtlas] 产出·纯派生不入库）。 */
internal data class ContinentSceneData(
    val regionId: String,
    val regionName: String,
    val isHome: Boolean,
    val flavor: String,
    val style: RegionStyle,
    val sites: List<ContinentSite>,
) {
    companion object {

        /** 站位标记抬高（demo:L229/L236）。 */
        private const val CITY_MARKER_LIFT = 1.6
        private const val WONDER_MARKER_LIFT = 6.2

        /**
         * 图集 → 某大区盒景数据（W9b 图纸 §3.3 映射 + §4.6 文案）。站点 = `citiesIn(regionId)` 全部 +
         * 该区 `WorldWonders` 全部（顺序：城在前、奇观在后·= heightAt pad 迭代序）。零 LLM / 零 DB。
         */
        fun fromAtlas(
            atlas: WorldAtlas.Atlas,
            regionId: String,
            strings: ContinentStrings,
            canonLore: Map<String, String> = emptyMap(), // W12 C4：城 id → 首访点亮 canon 正文（存在则优先·§3）
        ): ContinentSceneData {
            val region = requireNotNull(atlas.regionById(regionId)) { "unknown region: $regionId" }
            val style = requireNotNull(ContinentStyle.STYLES[region.styleKey]) { "no style: ${region.styleKey}" }
            val spread = ContinentMath.SPREAD
            val padH = style.padH

            fun boxX(atlasX: Int) = (atlasX - region.centerX).toFloat() / region.radiusLi * spread
            fun boxZ(atlasY: Int) = (atlasY - region.centerY).toFloat() / region.radiusLi * spread

            val citySites = atlas.citiesIn(regionId).map { city ->
                // 优先级（§3·C4）：首访点亮 canon lore > 精修城手写 body > 生成城确定性拼句。
                val body = canonLore[city.id] ?: strings.curatedBodies[city.id] ?: run {
                    val tier = when (city.tier) {
                        CityTier.SMALL -> strings.tierSmall
                        CityTier.TOWN -> strings.tierTown
                        CityTier.CITY -> strings.tierCity
                    }
                    val landmark = WorldLoreSkeleton.skeletonOf(atlas.seed, city, region).landmarkHint
                    strings.cityBodyTemplate.format(city.specialty, tier, landmark)
                }
                val isHome = city.id == WorldIds.HOME_CITY_ID
                val buildings = when {
                    isHome -> 3
                    city.tier == CityTier.CITY -> 3
                    else -> 2
                }
                ContinentSite(
                    id = city.id,
                    name = city.name,
                    isWonder = false,
                    isHome = isHome,
                    curated = city.curated,
                    x = boxX(city.x),
                    z = boxZ(city.y),
                    markerTop = (padH + CITY_MARKER_LIFT).toFloat(),
                    buildingCount = buildings,
                    body = body,
                )
            }

            val wonderSites = WorldWonders.ALL.filter { it.regionId == regionId }.map { wonder ->
                ContinentSite(
                    id = wonder.id,
                    name = wonder.name,
                    isWonder = true,
                    isHome = false,
                    curated = false,
                    x = boxX(wonder.x),
                    z = boxZ(wonder.y),
                    markerTop = (padH + WONDER_MARKER_LIFT).toFloat(),
                    buildingCount = 0,
                    body = strings.wonderBodyTemplate.format(wonder.hint),
                )
            }

            return ContinentSceneData(
                regionId = regionId,
                regionName = region.name,
                isHome = regionId == "yunze",
                flavor = region.flavor,
                style = style,
                sites = citySites + wonderSites,
            )
        }
    }
}

/**
 * 站点卡文案模板（由 Compose 层解析 `R.string.*` 后传入·令 [ContinentSceneData] 保持纯逻辑可 T1 直测）。
 * 全串锁死见图纸 §4.7/§9。[curatedBodies] 键 = cityId（city_yunye/city_taoqiu/city_xiyu·精确匹配）。
 */
internal data class ContinentStrings(
    val cityBodyTemplate: String,
    val tierSmall: String,
    val tierTown: String,
    val tierCity: String,
    val wonderBodyTemplate: String,
    val curatedBodies: Map<String, String>,
)
