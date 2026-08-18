package com.situ.aichat.world.stage

/**
 * 日程站位兜底解析（W9d 图纸 §4.3 / 契约 §14.A-2·§9 锁死）。
 *
 * 纯函数：把角色当前日程事件的 `location` 文本落到本城的一个 placeId（或「在城中」/「回家」）。
 * 三步（顺序锁死）：**名称直中 → 关键词映射地点型 → 本城该型地点**；都不中 = [Resolution.InTown]。
 * 型表 [PLACE_TYPES] 与关键词表 [KEYWORDS] = 世界物理常数（图纸 §4.3.2/§4.3.3·改一位金标测试变红）。
 *
 * 「你的家」（`yunye_home`）与「星沙湾」（`xiyu_cove`）**不入型表** = 不接日程站位（用户家不解析给角色·
 * 星沙湾留给风景）；HOME 关键词落 [Resolution.AtHomeHouse]（认领民居·非 `yunye_home`）。程序城（无地点表·
 * [places] 空）恒 [Resolution.InTown]。
 */
object WorldStageResolver {

    /** 地点型（§4.3.3·每城每型至多一个地点）。 */
    enum class PlaceType {
        CAFE, RESTAURANT, BOOKSTORE, PARK, SQUARE, DOCK,
        MARKET, TEAHOUSE, KILN, WORKSHOP, LOOKOUT, BEACH, BOARDWALK, HALL,
    }

    /** 解析结果（§4.3）。 */
    sealed interface Resolution {
        /** 命中本城某具体地点。 */
        data class AtPlace(val placeId: String) : Resolution

        /** 落回自家民居（HOME 关键词·非用户家 placeId）。 */
        data object AtHomeHouse : Resolution

        /** 在城中转悠（无命中 / 程序城）。 */
        data object InTown : Resolution
    }

    /** 城内地点（placeId + 全名·名称直中用·由调用方喂本城真实地点清单）。 */
    data class CityPlace(val placeId: String, val name: String)

    /** placeId → 地点型（§4.3.3 锁死·home/cove 有意不入表 = 不接站位）。 */
    private val PLACE_TYPES: Map<String, PlaceType> = mapOf(
        // 云野镇
        "yunye_cafe" to PlaceType.CAFE,
        "yunye_book" to PlaceType.BOOKSTORE,
        "yunye_eat" to PlaceType.RESTAURANT,
        "yunye_park" to PlaceType.PARK,
        "yunye_square" to PlaceType.SQUARE,
        "yunye_dock" to PlaceType.DOCK,
        // 陶丘
        "taoqiu_kiln" to PlaceType.KILN,
        "taoqiu_market" to PlaceType.MARKET,
        "taoqiu_shop" to PlaceType.WORKSHOP,
        "taoqiu_tea" to PlaceType.TEAHOUSE,
        "taoqiu_view" to PlaceType.LOOKOUT,
        // 汐屿（cove 不入表）
        "xiyu_beach" to PlaceType.BEACH,
        "xiyu_market" to PlaceType.MARKET,
        "xiyu_walk" to PlaceType.BOARDWALK,
        "xiyu_hall" to PlaceType.HALL,
    )

    /** placeId → 地点型只读查（W13 聊天状态行 emoji 用·§9「PLACE_TYPES 只读引用」——纯读访问器·不改型表内容/解析算法）。 */
    fun placeTypeOf(placeId: String): PlaceType? = PLACE_TYPES[placeId]

    /** 关键词组：[type] 为 null = HOME（落 AT_HOME_HOUSE）。匹配序 = 本表声明序（§4.3.2 锁死）。 */
    private data class KeywordGroup(val type: PlaceType?, val words: List<String>)

    private val KEYWORDS: List<KeywordGroup> = listOf(
        KeywordGroup(PlaceType.CAFE, listOf("咖啡", "咖啡馆", "coffee", "cafe", "星巴克")),
        KeywordGroup(PlaceType.RESTAURANT, listOf("餐厅", "饭店", "食堂", "小吃", "面馆", "饭馆", "食铺", "馆子", "火锅")),
        KeywordGroup(PlaceType.BOOKSTORE, listOf("书店", "书吧", "图书馆", "书屋")),
        KeywordGroup(PlaceType.PARK, listOf("公园", "河边", "河畔", "湖边", "绿地", "树林")),
        KeywordGroup(PlaceType.SQUARE, listOf("广场", "街心")),
        KeywordGroup(PlaceType.DOCK, listOf("码头", "渡口", "港口")),
        KeywordGroup(PlaceType.MARKET, listOf("市集", "市场", "集市", "商场", "商店", "超市")),
        KeywordGroup(PlaceType.TEAHOUSE, listOf("茶馆", "茶肆", "茶楼", "茶室")),
        KeywordGroup(PlaceType.KILN, listOf("窑")),
        KeywordGroup(PlaceType.WORKSHOP, listOf("工坊", "工作室", "作坊", "画室")),
        KeywordGroup(PlaceType.LOOKOUT, listOf("山顶", "观景", "高台", "山上")),
        KeywordGroup(PlaceType.BEACH, listOf("海边", "沙滩", "海滩", "赶海")),
        KeywordGroup(PlaceType.BOARDWALK, listOf("栈道", "海岸")),
        KeywordGroup(PlaceType.HALL, listOf("展馆", "博物馆", "美术馆", "展览")),
        KeywordGroup(null, listOf("家", "家里", "公寓", "宿舍", "卧室", "客厅", "厨房", "阳台")), // HOME → AT_HOME_HOUSE
    )

    /**
     * 解析 [location] → 站位（§4.3·锁死三步）。[places] = 本城真实地点清单（含 home/cove 亦无妨·内部按型表滤）；
     * 空清单（程序城·无地点表）→ 恒 [Resolution.InTown]。
     */
    fun resolve(location: String, places: List<CityPlace>): Resolution {
        // 程序城（无地点表）恒 IN_TOWN（§4.3.4）。
        if (places.isEmpty()) return Resolution.InTown

        // 仅「入型表」的地点参与站位（滤掉 yunye_home / xiyu_cove）。
        val typed = places.filter { PLACE_TYPES.containsKey(it.placeId) }

        // 1. 名称直中（原文含地点全名·顺序 = places 序·首中即返）。
        for (p in typed) {
            if (location.contains(p.name)) return Resolution.AtPlace(p.placeId)
        }

        // 2. 关键词 → 型 → 本城该型地点（声明序·首中即定型）。
        val lower = location.lowercase()
        for (group in KEYWORDS) {
            if (group.words.none { lower.contains(it) }) continue
            val type = group.type ?: return Resolution.AtHomeHouse // HOME
            val place = typed.firstOrNull { PLACE_TYPES[it.placeId] == type }
            return if (place != null) Resolution.AtPlace(place.placeId) else Resolution.InTown
        }

        // 3. 无命中。
        return Resolution.InTown
    }
}
