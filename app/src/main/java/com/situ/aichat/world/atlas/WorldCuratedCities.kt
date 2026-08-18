package com.situ.aichat.world.atlas

import com.situ.aichat.world.WorldIds

/**
 * 三座精修城 + 城内地点（契约 §4「精修 3：家乡云野镇 + 2 招牌城」/ W3 图纸 §3.3·逐字照抄·图纸 §9 禁改）。
 *
 * 精修城 `curated = true`、有城内地点表（[PLACES]·坐标 = 城内里网格 0–12）；生成城无地点（走城市卡 + 首访点亮）。
 * 云野镇 id = [WorldIds.HOME_CITY_ID]（= 默认住址·契约 §6），此处直接引用常量把二者钉死（E8）。
 * 三城在 [WorldAtlas] 构建时**先入表并占名占 id**（§3.6 步骤 2）。
 */
object WorldCuratedCities {

    val CITIES: List<WorldCity> = listOf(
        WorldCity(
            id = WorldIds.HOME_CITY_ID, name = "云野镇", regionId = "yunze",
            x = 600, y = 1300, tier = CityTier.TOWN, specialty = "渡口水乡", curated = true,
        ),
        WorldCity(
            id = "city_taoqiu", name = "陶丘", regionId = "huangsha",
            x = 1070, y = 640, tier = CityTier.CITY, specialty = "千窑陶都", curated = true,
        ),
        WorldCity(
            id = "city_xiyu", name = "汐屿", regionId = "nanyu",
            x = 2520, y = 2270, tier = CityTier.TOWN, specialty = "潮汐渔歌", curated = true,
        ),
    )

    val PLACES: List<WorldPlace> = listOf(
        // 云野镇（7 地点）
        WorldPlace("yunye_home", "你的家", WorldIds.HOME_CITY_ID, 6, 7),
        WorldPlace("yunye_cafe", "拾光咖啡馆", WorldIds.HOME_CITY_ID, 5, 6),
        WorldPlace("yunye_book", "青苔书店", WorldIds.HOME_CITY_ID, 7, 6),
        WorldPlace("yunye_park", "河畔公园", WorldIds.HOME_CITY_ID, 4, 8),
        WorldPlace("yunye_dock", "渡口码头", WorldIds.HOME_CITY_ID, 3, 5),
        WorldPlace("yunye_eat", "巷尾食铺", WorldIds.HOME_CITY_ID, 6, 5),
        WorldPlace("yunye_square", "老槐树广场", WorldIds.HOME_CITY_ID, 5, 7),
        // 陶丘（5 地点）
        WorldPlace("taoqiu_kiln", "千窑坡", "city_taoqiu", 5, 4),
        WorldPlace("taoqiu_market", "釉色市集", "city_taoqiu", 6, 6),
        WorldPlace("taoqiu_shop", "陶心工坊", "city_taoqiu", 4, 6),
        WorldPlace("taoqiu_tea", "火塘茶肆", "city_taoqiu", 7, 5),
        WorldPlace("taoqiu_view", "望原台", "city_taoqiu", 8, 8),
        // 汐屿（5 地点）
        WorldPlace("xiyu_beach", "落汐滩", "city_xiyu", 4, 8),
        WorldPlace("xiyu_market", "灯塔渔市", "city_xiyu", 5, 5),
        WorldPlace("xiyu_walk", "椰风栈道", "city_xiyu", 6, 7),
        WorldPlace("xiyu_hall", "潮声馆", "city_xiyu", 5, 6),
        WorldPlace("xiyu_cove", "星沙湾", "city_xiyu", 8, 9),
    )
}
