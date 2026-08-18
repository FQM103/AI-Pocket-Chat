package com.situ.aichat.world.atlas

/**
 * 星球图集（Atlas）的数据类型与枚举（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §4 / W3 图纸 §3.1）。
 *
 * 图集 = **纯派生数据，不入库**（契约 §5「能派生就存」的反面：能派生就不存）——整颗星球从世界种子确定性
 * 长出（构建见 [WorldAtlas]）。坐标系 = **世界平面 4800 × 2600（单位「里」）**，直连 §12 距离尺度；
 * 城内地点用 0–12 的里网格。字段一经落地即锁死（图纸 §9 禁改）——玩家的发现记录 / 风物志定稿钉在这些
 * id / 名 / 坐标上，未来只许追加（见 [WorldAtlas.ATLAS_VERSION] 升级契约）。
 */

/** 大区气候（十档·驱动 W9 气候视觉变体的调色路线·`WorldRegion.styleKey` 是其钩子）。 */
enum class WorldClimate {
    TEMPERATE_LAKES, RAINY_COAST, ARID_PLATEAU, FOREST_VALE, TEA_HILLS,
    POLAR_SNOW, MOUNTAIN, TROPICAL_ISLES, EAST_COAST, FAR_CAPE,
}

/** 城市规模档（驱动环境居民数量·§3.7：SMALL 3 / TOWN 5 / CITY 7）。 */
enum class CityTier { SMALL, TOWN, CITY }

/**
 * 一个大区（十个手写大区·§3.2）：气候 / 中心坐标 / 半径 / 生成城数量区间 / 风土文案 / 特产 / 菜 / 调色钥匙。
 *
 * @property centerX 大区中心 X（世界平面 [0,4800]）。
 * @property centerY 大区中心 Y（世界平面 [0,2600]）。
 * @property radiusLi 生成城散布半径（单位「里」）。
 * @property genCityMin 程序生成城数量下限（含）。
 * @property genCityMax 程序生成城数量上限（含）。
 * @property flavor 风土一句（供城市卡 / 风物志引用）。
 * @property specialties 特产三样（生成城 specialty 从中取）。
 * @property dishes 招牌菜三样（风物志 signatureDish / 居民今日一句 dish 槽从中取）。
 * @property styleKey W9 气候视觉变体的调色钩子（本块只带钥匙不带颜色——颜色在设计语言 v2 §7 / W9 落）。
 */
data class WorldRegion(
    val id: String,
    val name: String,
    val climate: WorldClimate,
    val centerX: Int,
    val centerY: Int,
    val radiusLi: Int,
    val genCityMin: Int,
    val genCityMax: Int,
    val flavor: String,
    val specialties: List<String>,
    val dishes: List<String>,
    val styleKey: String,
)

/**
 * 一座城（精修 3 座 + 程序生成 63+ 座）。
 *
 * @property x 世界平面 X（[0,4800]）。
 * @property y 世界平面 Y（[0,2600]）。
 * @property tier 规模档（驱动居民数）。
 * @property specialty 招牌（精修城手写 / 生成城从大区 specialties 取）。
 * @property curated 是否精修城（精修城有城内地点表 [WorldPlace]，生成城无）。
 */
data class WorldCity(
    val id: String,
    val name: String,
    val regionId: String,
    val x: Int,
    val y: Int,
    val tier: CityTier,
    val specialty: String,
    val curated: Boolean,
)

/** 精修城城内地点（仅精修城非空·坐标 = 城内里网格 0–12）。 */
data class WorldPlace(
    val id: String,
    val name: String,
    val cityId: String,
    val x: Int,
    val y: Int,
)

/**
 * 一处奇观（十二处手写·§3.4）。
 *
 * @property hint 发现前的朦胧线索（地图上「那边好像有点什么」）。
 * @property vignette 抵达时点亮的一小段场景文案（canon·无需 LLM 润色）。
 */
data class WorldWonder(
    val id: String,
    val name: String,
    val regionId: String,
    val x: Int,
    val y: Int,
    val hint: String,
    val vignette: String,
)

/** 一位环境居民（不可招募的烟火气填充·§3.7·区别于 W6 的 20 位官方原住民）。 */
data class WorldResident(
    val id: String,
    val name: String,
    val cityId: String,
    val occupation: String,
)

/**
 * 一座城的风物志骨架（§3.8·九字段全非空 = 「无空手而归」的数据保证·契约 §4 硬规）。
 *
 * 全部从图集 / 字库确定性组装；生成城的风物志首访由 LLM 就此骨架润色定稿（W5/W12），精修城骨架即 canon。
 */
data class CityLoreSkeleton(
    val cityId: String,
    val displayName: String,
    val regionName: String,
    val climate: WorldClimate,
    val tier: CityTier,
    val specialty: String,
    val oldStreet: String,
    val signatureDish: String,
    val landmarkHint: String,
    val legendHint: String,
)
