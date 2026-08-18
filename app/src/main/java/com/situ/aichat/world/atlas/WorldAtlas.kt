package com.situ.aichat.world.atlas

import com.situ.aichat.world.WorldSeeds
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 星球图集构建 + 查询（契约 §4 / W3 图纸 §3.6·生成算法逐字锁死·图纸 §9 禁改）。
 *
 * 从世界种子确定性长出整颗星球：10 手写大区（[WorldRegions]）+ 3 精修城（[WorldCuratedCities]）+
 * 逐大区程序生成城 + 12 奇观（[WorldWonders]）。**图集 = 纯派生数据，不入库**（契约 §5「能派生就不存」）——
 * 一切随机只来自 [WorldSeeds]（禁无种 `Random()` / 时钟）。世界只有一颗种子，故只缓存最近一次构建。
 *
 * ### 升级契约（[ATLAS_VERSION] 锁死·图纸 §3.6）
 * 未来扩充（新大区 / 新城 / 新奇观）**只许追加**——加新大区到 [WorldRegions.ALL] 末尾（新索引）、
 * 加新奇观到 [WorldWonders.ALL] 末尾（新 id）；**已有大区 / 城 / 奇观的 id、名、坐标永不改动**，
 * 生成算法（随机流用法 / 间距守卫 / tier 分档 / id 格式）亦不改。因为玩家的发现记录与风物志定稿都钉在
 * 这些 id / 坐标上——动一处 = 老玩家的世界地图错位。破坏性变更须提 [ATLAS_VERSION] 并写迁移。
 */
object WorldAtlas {

    /** 图集契约版本（追加式升级·见类 KDoc）。 */
    const val ATLAS_VERSION = 1

    /** 世界平面尺寸（单位「里」·直连契约 §12 距离尺度）。 */
    const val PLANE_WIDTH = 4800
    const val PLANE_HEIGHT = 2600

    /** 间距守卫：生成城距同大区已有城的目标间距（里）·未达则同流重抽。 */
    private const val MIN_SPACING = 40.0

    /** 间距守卫最大重抽次数（仍未达标则照收末次·金标钉住实际结果）。 */
    private const val SPACING_TRIES = 20

    /**
     * 一次构建出的整颗星球图集（不可变快照）。查询 API 见各方法。
     */
    class Atlas internal constructor(
        val seed: Long,
        val regions: List<WorldRegion>,
        val cities: List<WorldCity>,
        val wonders: List<WorldWonder>,
        val places: List<WorldPlace>,
    ) {
        fun cityById(id: String): WorldCity? = cities.firstOrNull { it.id == id }
        fun regionById(id: String): WorldRegion? = regions.firstOrNull { it.id == id }
        fun citiesIn(regionId: String): List<WorldCity> = cities.filter { it.regionId == regionId }

        /** 城内地点（仅精修城非空·生成城恒空列表）。 */
        fun placesOf(cityId: String): List<WorldPlace> = places.filter { it.cityId == cityId }

        /** 两城直线距离（里·`round(hypot(dx,dy))`）。城 id 不存在 = 编程错误，快速失败。 */
        fun distanceLi(cityIdA: String, cityIdB: String): Int {
            val a = requireNotNull(cityById(cityIdA)) { "unknown city: $cityIdA" }
            val b = requireNotNull(cityById(cityIdB)) { "unknown city: $cityIdB" }
            return hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).roundToInt()
        }
    }

    @Volatile
    private var cached: Pair<Long, Atlas>? = null

    /** 取（或构建）该种子的图集·单座缓存（世界只有一颗种子）。 */
    fun of(seed: Long): Atlas {
        cached?.let { if (it.first == seed) return it.second }
        val atlas = build(seed)
        cached = seed to atlas
        return atlas
    }

    private fun build(seed: Long): Atlas {
        val regions = WorldRegions.ALL
        val cities = ArrayList<WorldCity>()
        val usedNames = HashSet<String>()

        // 步骤 2：3 精修城先入表（占名、占 id）。
        for (c in WorldCuratedCities.CITIES) {
            cities.add(c)
            usedNames.add(c.name)
        }
        // 奇观名一并占用（§3.5：生成城名不撞精修城 / 奇观名）。
        for (w in WorldWonders.ALL) usedNames.add(w.name)

        // 步骤 3：逐大区（按索引）生成城。
        for ((index, region) in regions.withIndex()) {
            val count = WorldSeeds.randomOf(WorldSeeds.derive(seed, "cities", index.toLong()))
                .nextInt(region.genCityMin, region.genCityMax + 1)
            for (i in 0 until count) {
                val rand = WorldSeeds.randomOf(WorldSeeds.derive(seed, "city", index * 1000L + i))
                // 位置 + 间距守卫（同一 rand 流·≤SPACING_TRIES 次；仍未达标照收末次）。
                var x = 0
                var y = 0
                for (attempt in 0 until SPACING_TRIES) {
                    val rr = region.radiusLi * sqrt(rand.nextDouble())
                    val angle = rand.nextDouble() * 2.0 * PI
                    x = (region.centerX + rr * cos(angle)).roundToInt()
                    y = (region.centerY + rr * sin(angle)).roundToInt()
                    val minDist = cities.asSequence()
                        .filter { it.regionId == region.id }
                        .minOfOrNull { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) }
                    if (minDist == null || minDist >= MIN_SPACING) break
                }
                val d = rand.nextDouble()
                val tier = when {
                    d < 0.4 -> CityTier.SMALL
                    d < 0.8 -> CityTier.TOWN
                    else -> CityTier.CITY
                }
                val specialty = region.specialties[rand.nextInt(3)]
                val name = WorldNameForge.cityName(rand, usedNames)
                cities.add(
                    WorldCity(
                        id = "city_g_${region.id}_$i",
                        name = name,
                        regionId = region.id,
                        x = x,
                        y = y,
                        tier = tier,
                        specialty = specialty,
                        curated = false,
                    )
                )
            }
        }

        return Atlas(seed, regions, cities.toList(), WorldWonders.ALL, WorldCuratedCities.PLACES)
    }
}
