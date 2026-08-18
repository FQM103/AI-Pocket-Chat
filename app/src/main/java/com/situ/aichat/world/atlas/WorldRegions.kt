package com.situ.aichat.world.atlas

/**
 * 十个手写大区（契约 §4 / W3 图纸 §3.2·逐字照抄·图纸 §9 禁改）。
 *
 * [ALL] 的顺序 = 索引 0..9，直接喂 [WorldAtlas] 的构建循环（`WorldSeeds.derive(seed, "cities", index)`）。
 * 中心坐标在世界平面 4800 × 2600 内，尺度自检见图纸 §3.2 脚注（家乡→荒角 ≈ 3905 里 · 同大区两城 ≤ 2×半径）。
 * `styleKey` = W9 气候视觉变体的调色钩子（本块只带钥匙不带颜色——颜色在设计语言 v2 §7 / W9 落）。
 */
object WorldRegions {

    val ALL: List<WorldRegion> = listOf(
        WorldRegion(
            id = "yunze", name = "云泽大区", climate = WorldClimate.TEMPERATE_LAKES,
            centerX = 600, centerY = 1300, radiusLi = 90, genCityMin = 3, genCityMax = 5,
            flavor = "水网纵横的温带水乡，芦苇、渡船与晨雾",
            specialties = listOf("渡口水集", "苇编坊", "莲塘"),
            dishes = listOf("莲藕炖汤", "菱角糕", "芦芽鲜"),
            styleKey = "willow_mist",
        ),
        WorldRegion(
            id = "xiyulin", name = "西雨林道", climate = WorldClimate.RAINY_COAST,
            centerX = 350, centerY = 1900, radiusLi = 100, genCityMin = 7, genCityMax = 8,
            flavor = "常年细雨的西海岸，苔绿爬满木屋",
            specialties = listOf("木作坊", "菌市", "伞铺"),
            dishes = listOf("苔纹糕", "菌菇煲", "雨前笋"),
            styleKey = "fern_rain",
        ),
        WorldRegion(
            id = "huangsha", name = "黄砂高原", climate = WorldClimate.ARID_PLATEAU,
            centerX = 1100, centerY = 600, radiusLi = 110, genCityMin = 7, genCityMax = 8,
            flavor = "干燥的赭黄高原，窑火与风蚀岩",
            specialties = listOf("陶窑", "驼队集市", "盐井"),
            dishes = listOf("窑烤饼", "沙棘茶", "暖锅羊汤"),
            styleKey = "ochre_dry",
        ),
        WorldRegion(
            id = "yingchuan", name = "萤川谷地", climate = WorldClimate.FOREST_VALE,
            centerX = 1400, centerY = 1650, radiusLi = 90, genCityMin = 7, genCityMax = 8,
            flavor = "森林河谷，夏夜萤火成川",
            specialties = listOf("蜜坊", "河灯铺", "草药圃"),
            dishes = listOf("蜜渍梅", "河鲜粥", "山菌饼"),
            styleKey = "broadleaf_glow",
        ),
        WorldRegion(
            id = "chalong", name = "茶陇丘野", climate = WorldClimate.TEA_HILLS,
            centerX = 1900, centerY = 1100, radiusLi = 90, genCityMin = 7, genCityMax = 8,
            flavor = "层层茶田铺满缓丘，清明前最忙",
            specialties = listOf("茶市", "茶油坊", "竹编铺"),
            dishes = listOf("明前茶", "茶油拌饭", "茶香蛋"),
            styleKey = "tea_terrace",
        ),
        WorldRegion(
            id = "jibei", name = "极北雪原", climate = WorldClimate.POLAR_SNOW,
            centerX = 2300, centerY = 250, radiusLi = 100, genCityMin = 7, genCityMax = 8,
            flavor = "长冬的雪原，夜空偶有极光",
            specialties = listOf("皮毛集", "温泉驿", "雪橇队"),
            dishes = listOf("奶皮子茶", "雪水锅", "松子糖"),
            styleKey = "pine_snow",
        ),
        WorldRegion(
            id = "mushan", name = "暮山连峰", climate = WorldClimate.MOUNTAIN,
            centerX = 2700, centerY = 800, radiusLi = 110, genCityMin = 7, genCityMax = 8,
            flavor = "日落时被染金的连绵山脊",
            specialties = listOf("石刻坊", "云雾茶寮", "索道驿"),
            dishes = listOf("石板豆腐", "山楂糕", "云雾茶"),
            styleKey = "slate_peak",
        ),
        WorldRegion(
            id = "nanyu", name = "南屿群岛", climate = WorldClimate.TROPICAL_ISLES,
            centerX = 2500, centerY = 2300, radiusLi = 110, genCityMin = 7, genCityMax = 8,
            flavor = "常夏的群岛，白沙与椰影",
            specialties = listOf("渔歌市", "贝雕铺", "椰园"),
            dishes = listOf("椰香鱼饭", "海盐冰", "炭烤青蟹"),
            styleKey = "palm_sand",
        ),
        WorldRegion(
            id = "xinghai", name = "星海东岸", climate = WorldClimate.EAST_COAST,
            centerX = 3600, centerY = 1400, radiusLi = 100, genCityMin = 7, genCityMax = 8,
            flavor = "面向大洋的东岸，夜里星海相接",
            specialties = listOf("观星台", "灯影渔市", "造船坞"),
            dishes = listOf("星贝汤", "海苔卷", "灯影鱼干"),
            styleKey = "cypress_star",
        ),
        WorldRegion(
            id = "huangjiao", name = "荒角天涯", climate = WorldClimate.FAR_CAPE,
            centerX = 4400, centerY = 2200, radiusLi = 80, genCityMin = 4, genCityMax = 6,
            flavor = "大洋尽头的荒岬，风大人少，灯塔长明",
            specialties = listOf("灯塔驿", "风干鱼寮", "浪木作坊"),
            dishes = listOf("风干鱼", "黑麦饼", "灯塔咸茶"),
            styleKey = "storm_cape",
        ),
    )
}
