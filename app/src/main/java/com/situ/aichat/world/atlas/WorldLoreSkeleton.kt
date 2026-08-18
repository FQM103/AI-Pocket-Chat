package com.situ.aichat.world.atlas

import com.situ.aichat.world.WorldSeeds

/**
 * 风物志骨架组装（契约 §4「无空手而归」硬规 / W3 图纸 §3.8）。
 *
 * 九内容字段全部从图集 / 字库确定性组装 → **全非空 = 「无空手而归」的数据保证**（空城 = bug）。生成城的风物志
 * 首访由 LLM 就此骨架润色定稿（W5/W12），精修城骨架即 canon。一切随机只来自 [WorldSeeds]（纯派生不入库）。
 *
 * 派生式：`rand = randomOf(derive(seed, "lore", fnv1a64(cityId)))`，顺序抽 oldStreet → signatureDish →
 * landmarkHint → legendHint。（图纸 §3.8 只规定「seed 派生」、§9 只要求「随机走 WorldSeeds.derive」，未锁具体盐串；
 * 此处取 `"lore"` + `fnv1a64(cityId)` 与居民派生同构——见图纸 §11 施工裁量记录。）
 */
object WorldLoreSkeleton {

    /** 某城的风物志骨架（九内容字段全非空）。 */
    fun skeletonOf(seed: Long, city: WorldCity, region: WorldRegion): CityLoreSkeleton {
        val rand = WorldSeeds.randomOf(WorldSeeds.derive(seed, "lore", WorldSeeds.fnv1a64(city.id)))
        val oldStreet = WorldNameForge.streetName(rand)
        val signatureDish = region.dishes[rand.nextInt(region.dishes.size)]
        val landmarkHint = WorldNameForge.landmarkHint(rand)
        val legendHint = WorldNameForge.legendHint(rand)
        return CityLoreSkeleton(
            cityId = city.id,
            displayName = city.name,
            regionName = region.name,
            climate = region.climate,
            tier = city.tier,
            specialty = city.specialty,
            oldStreet = oldStreet,
            signatureDish = signatureDish,
            landmarkHint = landmarkHint,
            legendHint = legendHint,
        )
    }
}
