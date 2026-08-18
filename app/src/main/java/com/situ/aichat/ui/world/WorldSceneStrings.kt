package com.situ.aichat.ui.world

import android.content.Context
import com.situ.aichat.R
import com.situ.aichat.ui.world.continent.ContinentStrings
import com.situ.aichat.ui.world.town.TownStrings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界屏文案装配单源（W9d·从 [WorldViewModel] **只搬不改**抽出·§2）：大陆城市/奇观文案 + 小镇地点正文 17 键 +
 * 小镇副标模板。令 SceneData 保持纯逻辑可 T1 直测（Compose 层解析 R.string 后传入）。既有映射一字不改。
 */
@Singleton
class WorldSceneStrings @Inject constructor(@ApplicationContext private val ctx: Context) {

    internal val continent: ContinentStrings by lazy {
        ContinentStrings(
            cityBodyTemplate = ctx.getString(R.string.world_city_body_template),
            tierSmall = ctx.getString(R.string.world_city_tier_small),
            tierTown = ctx.getString(R.string.world_city_tier_town),
            tierCity = ctx.getString(R.string.world_city_tier_city),
            wonderBodyTemplate = ctx.getString(R.string.world_wonder_body_template),
            curatedBodies = mapOf(
                "city_yunye" to ctx.getString(R.string.world_city_body_yunye),
                "city_taoqiu" to ctx.getString(R.string.world_city_body_taoqiu),
                "city_xiyu" to ctx.getString(R.string.world_city_body_xiyu),
            ),
        )
    }

    internal val town: TownStrings by lazy {
        TownStrings(
            subtitleTemplate = ctx.getString(R.string.world_town_subtitle),
            placeBodies = mapOf(
                "yunye_home" to ctx.getString(R.string.world_place_body_yunye_home),
                "yunye_cafe" to ctx.getString(R.string.world_place_body_yunye_cafe),
                "yunye_book" to ctx.getString(R.string.world_place_body_yunye_book),
                "yunye_eat" to ctx.getString(R.string.world_place_body_yunye_eat),
                "yunye_square" to ctx.getString(R.string.world_place_body_yunye_square),
                "yunye_park" to ctx.getString(R.string.world_place_body_yunye_park),
                "yunye_dock" to ctx.getString(R.string.world_place_body_yunye_dock),
                "taoqiu_kiln" to ctx.getString(R.string.world_place_body_taoqiu_kiln),
                "taoqiu_market" to ctx.getString(R.string.world_place_body_taoqiu_market),
                "taoqiu_shop" to ctx.getString(R.string.world_place_body_taoqiu_shop),
                "taoqiu_tea" to ctx.getString(R.string.world_place_body_taoqiu_tea),
                "taoqiu_view" to ctx.getString(R.string.world_place_body_taoqiu_view),
                "xiyu_beach" to ctx.getString(R.string.world_place_body_xiyu_beach),
                "xiyu_market" to ctx.getString(R.string.world_place_body_xiyu_market),
                "xiyu_walk" to ctx.getString(R.string.world_place_body_xiyu_walk),
                "xiyu_hall" to ctx.getString(R.string.world_place_body_xiyu_hall),
                "xiyu_cove" to ctx.getString(R.string.world_place_body_xiyu_cove),
            ),
        )
    }

    /** 小镇副标「{大区名} · {specialty}」（§4.3）。 */
    fun townSubtitle(regionName: String, specialty: String): String =
        ctx.getString(R.string.world_town_subtitle, regionName, specialty)
}
