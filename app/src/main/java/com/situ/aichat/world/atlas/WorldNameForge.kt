package com.situ.aichat.world.atlas

import kotlin.random.Random

/**
 * 取名器（契约 §4「程序化生成地名」/ W3 图纸 §3.5·字库与规则逐字锁死·图纸 §9 禁改）。
 *
 * 城名 / 街名 / 人名 / 地标 hint / 传说 hint 全部从传入的**同一随机流**（`WorldSeeds.randomOf(...)` 派生）
 * 确定性抽取——同流同序列必同结果。字库改一个字 = 金标测试红灯（`WorldNameForgeTest`）。
 *
 * 唯一性（城名全球唯一 / 居民全名同城唯一）由调用方传入的 `used` 可变集合承载：抽中即**占名**（加入集合），
 * 冲突则用**同一随机流**重抽（≤50 次·上限后果见各方法 KDoc·实际不可达）。
 */
object WorldNameForge {

    /** 城名前缀（36 字·锁死）。 */
    const val CITY_PREFIXES = "云溪沙雾星苇岚汐茶陶松雪浦荻蓝月栖枫盐晚荷澄泉石岸霜芦渡萤竹夕潮杏山海洲"

    /** 城名后缀（14 字·锁死）。 */
    const val CITY_SUFFIXES = "镇城港湾集坞关洲里屿坪桥驿崖"

    /** 街名前缀（10 字·锁死）。 */
    const val STREET_PREFIXES = "老长青杏灯石南拾竹雨"

    /** 街名后缀（5 字·锁死）。 */
    const val STREET_SUFFIXES = "街巷堤坡市"

    /** 人名·姓（24 字·锁死）。 */
    const val SURNAMES = "林沈苏顾温陆江叶程韩白秦许孟段柳邵阮岑傅桑池蓝闻"

    /** 人名·名字库（36 字·1–2 字名·锁死）。 */
    const val GIVEN_NAMES = "之晚青禾舟宁昭澄知屿亭桉洛川苒星野树汀卉明泽苇湾一安然声白卯池予帆灯未归"

    /** 地标 hint 池（14·锁死·供 [WorldLoreSkeleton] landmarkHint）。 */
    val LANDMARK_HINTS: List<String> = listOf(
        "一座吱呀作响的老水车",
        "半截爬满藤的旧城墙",
        "一口从不干涸的老井",
        "巷口那棵比城还老的树",
        "一间只在雨天开门的杂货铺",
        "桥洞下会回声的石阶",
        "一面贴满旧船票的告示墙",
        "屋顶上永远蹲着猫的钟楼",
        "一条傍晚会亮灯笼的窄巷",
        "城门口磨得发亮的石狮子",
        "一架还能转的旧风车",
        "码头边褪色的许愿牌",
        "后山半路的无名凉亭",
        "集市中央的老戏台",
    )

    /** 建城传说 hint 池（10·锁死·供 [WorldLoreSkeleton] legendHint）。 */
    val LEGEND_HINTS: List<String> = listOf(
        "传说建城的是一位退休的航海家",
        "最早只是三户人家的渡口",
        "城名来自一场没人记得的约定",
        "第一代城主是位女铁匠",
        "据说全城的路加起来正好绕星球千分之一",
        "老人说城址是跟着一群候鸟选的",
        "建城那天下了三天的雨",
        "城里每口井都通向同一条暗河（未证实）",
        "第一间铺子卖的是伞",
        "城徽上的动物没人见过活的",
    )

    /**
     * 造一个全球唯一的城名并占名（加入 [used]）。规则（§3.5）：
     * `prefix + suffix`（默认）；`rand.nextDouble() < 0.3` → 双前缀 `p1 + p2 + suffix`（p1 ≠ p2）。
     * 冲突用同一随机流重抽（≤50 次）；超限（实际不可达）→ **后缀前插「新」**。
     */
    fun cityName(rand: Random, used: MutableSet<String>): String {
        repeat(50) {
            val name = drawCity(rand, insertNew = false)
            if (name !in used) { used.add(name); return name }
        }
        val name = drawCity(rand, insertNew = true)
        used.add(name)
        return name
    }

    private fun drawCity(rand: Random, insertNew: Boolean): String {
        val mid = if (insertNew) "新" else ""
        return if (rand.nextDouble() < 0.3) {
            val p1 = CITY_PREFIXES[rand.nextInt(CITY_PREFIXES.length)]
            var p2 = CITY_PREFIXES[rand.nextInt(CITY_PREFIXES.length)]
            while (p2 == p1) p2 = CITY_PREFIXES[rand.nextInt(CITY_PREFIXES.length)]
            val suffix = CITY_SUFFIXES[rand.nextInt(CITY_SUFFIXES.length)]
            "$p1$p2$mid$suffix"
        } else {
            val prefix = CITY_PREFIXES[rand.nextInt(CITY_PREFIXES.length)]
            val suffix = CITY_SUFFIXES[rand.nextInt(CITY_SUFFIXES.length)]
            "$prefix$mid$suffix"
        }
    }

    /** 街名：`前缀 + 后缀`（各一抽·无唯一性约束·每城一条供风物志）。 */
    fun streetName(rand: Random): String {
        val prefix = STREET_PREFIXES[rand.nextInt(STREET_PREFIXES.length)]
        val suffix = STREET_SUFFIXES[rand.nextInt(STREET_SUFFIXES.length)]
        return "$prefix$suffix"
    }

    /**
     * 造一个同城唯一的居民全名并占名（加入 [used]）。规则（§3.5）：
     * `姓 + 名字`；名字 `rand.nextDouble() < 0.6` → 双字（叠字允许·仅城名双前缀要求 p1≠p2）。
     * 冲突用同一随机流重抽（≤50 次）；超限（实际不可达·同城 ≤8 人 vs 3 万余组合）→ 收下末次抽取（见 §11 施工日志）。
     */
    fun personName(rand: Random, used: MutableSet<String>): String {
        repeat(50) {
            val name = drawPerson(rand)
            if (name !in used) { used.add(name); return name }
        }
        val name = drawPerson(rand)
        used.add(name)
        return name
    }

    private fun drawPerson(rand: Random): String {
        val surname = SURNAMES[rand.nextInt(SURNAMES.length)]
        return if (rand.nextDouble() < 0.6) {
            val g1 = GIVEN_NAMES[rand.nextInt(GIVEN_NAMES.length)]
            val g2 = GIVEN_NAMES[rand.nextInt(GIVEN_NAMES.length)]
            "$surname$g1$g2"
        } else {
            val g = GIVEN_NAMES[rand.nextInt(GIVEN_NAMES.length)]
            "$surname$g"
        }
    }

    /** 地标 hint（[LANDMARK_HINTS] 一抽）。 */
    fun landmarkHint(rand: Random): String = LANDMARK_HINTS[rand.nextInt(LANDMARK_HINTS.size)]

    /** 建城传说 hint（[LEGEND_HINTS] 一抽）。 */
    fun legendHint(rand: Random): String = LEGEND_HINTS[rand.nextInt(LEGEND_HINTS.size)]
}
