package com.situ.aichat.world.atlas

import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.WorldSeeds

/**
 * 环境居民生成（契约 §4「每城若干环境居民」/ W3 图纸 §3.7·数量·职业池·今日一句模板逐字锁死·图纸 §9 禁改）。
 *
 * 「环境居民」= 不可招募的烟火气填充（≠ W6 的 20 位官方原住民）。数量 / 身份 / 今日一句全部确定性派生（纯派生
 * 不入库）——一切随机只来自 [WorldSeeds]。今日一句按**日**粒度：同（居民, epochDay）恒同句（同 W2 契约③）。
 */
object WorldResidents {

    /** 职业池（20·锁死）。 */
    val OCCUPATIONS: List<String> = listOf(
        "渔夫", "船娘", "陶匠", "茶农", "灯塔守", "邮差", "木匠", "面点师", "药师", "教书先生",
        "花农", "客栈老板", "说书人", "织工", "铁匠", "果农", "猎户", "画师", "摆渡人", "蜂农",
    )

    /** 今日一句地点槽池（10·锁死）。 */
    val SPOTS: List<String> = listOf(
        "码头", "市集", "巷口", "河边", "场院", "檐下", "田埂", "坡上", "井台", "渡口",
    )

    /** 今日一句模板池（12·锁死·`{name}` / `{spot}` / `{dish}` 槽位）。 */
    val DAILY_TEMPLATES: List<String> = listOf(
        "{name}在{spot}晒新收的干货",
        "{name}说今天的{dish}格外好",
        "{name}蹲在{spot}逗一只路过的猫",
        "{name}把{spot}扫得干干净净",
        "{name}在{spot}和人下棋，输了不肯走",
        "{name}哼着歌往{spot}去了",
        "{name}在{spot}修东西，敲敲打打一上午",
        "{name}给路过的孩子分了点{dish}",
        "{name}倚在{spot}看云，看了很久",
        "{name}在{spot}讲昨晚的梦，讲得眉飞色舞",
        "{name}沏了壶茶坐在{spot}，谁路过都招呼一声",
        "{name}收摊早，说要去{spot}看日落",
    )

    /** 该城居民数：生成城按 tier（SMALL 3 / TOWN 5 / CITY 7）；精修城手写（云野镇 7 / 陶丘 8 / 汐屿 6）。 */
    private fun residentCount(city: WorldCity): Int = if (city.curated) {
        when (city.id) {
            WorldIds.HOME_CITY_ID -> 7 // 云野镇
            "city_taoqiu" -> 8         // 陶丘
            "city_xiyu" -> 6           // 汐屿
            else -> error("未登记的精修城居民数: ${city.id}")
        }
    } else {
        when (city.tier) {
            CityTier.SMALL -> 3
            CityTier.TOWN -> 5
            CityTier.CITY -> 7
        }
    }

    /**
     * 该城全部环境居民（确定性·同城全名唯一）。
     * `rand = randomOf(derive(seed, "residents", fnv1a64(cityId)))`；每位：全名（取名器·同城唯一）→ 职业（[OCCUPATIONS]）。
     */
    fun residentsOf(seed: Long, city: WorldCity): List<WorldResident> {
        val count = residentCount(city)
        val rand = WorldSeeds.randomOf(WorldSeeds.derive(seed, "residents", WorldSeeds.fnv1a64(city.id)))
        val usedNames = HashSet<String>()
        return (0 until count).map { i ->
            val name = WorldNameForge.personName(rand, usedNames)
            val occupation = OCCUPATIONS[rand.nextInt(OCCUPATIONS.size)]
            WorldResident(id = "res_${city.id}_$i", name = name, cityId = city.id, occupation = occupation)
        }
    }

    /**
     * 该居民某日的「今日一句」（日粒度·同输入恒同句）。
     * `rand = randomOf(derive(seed, "resline", fnv1a64(resident.id) xor epochDay))`；抽模板 → spot（[SPOTS]）→ dish（所在大区 [WorldRegion.dishes]），
     * 三槽全抽后代入（模板未用到的槽为无操作替换·输出无 `{}` 残留）。
     */
    fun dailyLine(seed: Long, resident: WorldResident, region: WorldRegion, epochDay: Long): String {
        val rand = WorldSeeds.randomOf(
            WorldSeeds.derive(seed, "resline", WorldSeeds.fnv1a64(resident.id) xor epochDay)
        )
        val template = DAILY_TEMPLATES[rand.nextInt(DAILY_TEMPLATES.size)]
        val spot = SPOTS[rand.nextInt(SPOTS.size)]
        val dish = region.dishes[rand.nextInt(region.dishes.size)]
        return template.replace("{name}", resident.name).replace("{spot}", spot).replace("{dish}", dish)
    }
}
