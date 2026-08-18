package com.situ.aichat.economy

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 从日程事件 activity + location 文本识别经济影响（1:1 iOS `Services/ScheduleEconomicEventExtractor.swift`）。
 * 纯函数（无注入）：① 先筛免费场景（在家/上班/学习/睡觉，**消费 location 优先于免费 activity**——在餐厅谈事照样扣吃饭钱）；
 * ② 类别识别（**location 优先于 activity**）；③ 按月薪比例 + 周末1.3 / 同行1.5(仅 dining|entertainment) / 品牌1.2 /
 * 职业 bias(学生0.7/富豪1.3) 算金额，minAmount 兜底；④ 组 note。匹配大小写无关（text 与 keyword 都 lowercase）。
 * 金额确定性走 [stableRate]（eventId 哈希），同事件多次扫描金额相同。
 */
object ScheduleEconomicEventExtractor {

    // ── 关键词词典（逐字 iOS；匹配时大小写无关） ──

    private val activityKeywords: List<Pair<ScheduleEconomicCategory, List<String>>> = listOf(
        ScheduleEconomicCategory.DINING to listOf("吃饭", "聚餐", "早餐", "午餐", "晚餐", "宵夜", "约饭", "聚会", "宴会", "饭局", "用餐", "brunch"),
        ScheduleEconomicCategory.DRINKS to listOf("喝咖啡", "下午茶", "奶茶", "果汁", "喝酒", "品茶", "茶歇", "喝茶"),
        ScheduleEconomicCategory.ENTERTAINMENT to listOf("看电影", "唱歌", "KTV", "玩游戏", "看展", "演唱会", "话剧", "音乐会", "看演出", "游乐"),
        ScheduleEconomicCategory.MEDICAL to listOf("看病", "就医", "挂号", "输液", "复查", "体检", "洗牙", "打针"),
        ScheduleEconomicCategory.SHOPPING to listOf("购物", "逛街", "shopping", "买衣服", "买菜", "采购", "血拼"),
        ScheduleEconomicCategory.TRANSPORT to listOf("出差", "旅行", "出游", "赶飞机", "赶火车", "候机", "度假", "远行"),
        ScheduleEconomicCategory.FITNESS to listOf("健身", "瑜伽", "游泳", "打球", "网球", "羽毛球", "篮球", "足球", "撸铁"),
    )

    private val locationKeywords: List<Pair<ScheduleEconomicCategory, List<String>>> = listOf(
        ScheduleEconomicCategory.DINING to listOf("餐厅", "饭店", "饭馆", "餐馆", "火锅", "烧烤", "寿司", "日料", "西餐", "自助", "大排档", "食府", "海底捞", "麦当劳", "肯德基", "必胜客", "外婆家", "星期五"),
        ScheduleEconomicCategory.DRINKS to listOf("咖啡店", "咖啡厅", "星巴克", "奶茶店", "瑞幸", "tims", "costa", "茶馆", "酒吧", "酒馆", "bar"),
        ScheduleEconomicCategory.ENTERTAINMENT to listOf("电影院", "影院", "影城", "ktv", "网吧", "游戏厅", "游乐园", "剧院", "音乐厅", "万达", "博物馆"),
        ScheduleEconomicCategory.MEDICAL to listOf("医院", "诊所", "药店", "牙科", "口腔", "体检中心"),
        ScheduleEconomicCategory.SHOPPING to listOf("商场", "超市", "购物中心", "百货", "市场", "商业街", "万象", "大悦城", "盒马", "沃尔玛", "山姆"),
        ScheduleEconomicCategory.TRANSPORT to listOf("机场", "火车站", "高铁站", "地铁站", "车站", "码头", "港口", "高速"),
        ScheduleEconomicCategory.FITNESS to listOf("健身房", "瑜伽馆", "泳池", "游泳馆", "球馆", "健身中心"),
    )

    /** 免费场景 location（双字以上明确词，避免单字「家」误中「一家餐厅/家乐福」）。 */
    private val freeLocationKeywords = listOf(
        "家里", "家中", "在家", "我家", "卧室", "客厅", "厨房", "书房", "阳台",
        "宿舍", "公寓",
        "公司", "办公室", "工作室", "会议室",
        "学校", "教室", "课堂", "图书馆", "自习室",
        "公园", "小区",
    )

    /** 免费 activity（明确的被动/工作/学习；消费场景由 location 主导）。 */
    private val freeActivityKeywords = listOf(
        "睡觉", "睡眠", "午睡", "休息", "发呆", "冥想", "起床",
        "上班", "开会", "写代码", "编程", "改bug",
        "上课", "复习", "写作业", "做题",
        "散步", "晨跑", "夜跑",
    )

    /** 具体品牌（location 精确度 ×1.2）。 */
    private val specificBrandKeywords = listOf(
        "星巴克", "瑞幸", "tims", "costa",
        "海底捞", "外婆家", "麦当劳", "肯德基", "必胜客", "星期五",
        "万达", "大悦城", "万象", "盒马", "沃尔玛", "山姆",
    )

    private val studentOccupationKeywords = listOf("学生", "student", "实习", "intern", "高中", "大学")
    private val luxuryOccupationKeywords = listOf("ceo", "总裁", "创始人", "亿万富翁", "富豪", "企业家", "老板", "董事")

    // ── 公开 API ──

    /** 提取经济影响；免费场景/未识别返回 null。 */
    fun extract(
        activity: String,
        location: String,
        relatedCharacterNames: String?,
        startTimeMillis: Long,
        eventId: String,
        monthlySalary: Int,
        occupation: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ScheduleEconomicImpact? {
        if (isFreeScenario(location, activity)) return null
        val category = matchCategory(location, activity) ?: return null

        val isWeekend = Instant.ofEpochMilli(startTimeMillis).atZone(zone).dayOfWeek.let {
            it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY
        }
        val hasCompanion = !relatedCharacterNames?.trim().isNullOrEmpty()
        val isSpecificBrand = containsAny(location.lowercase(), specificBrandKeywords)

        val amount = computeAmount(category, monthlySalary, isWeekend, hasCompanion, isSpecificBrand, occupation, eventId)
        return ScheduleEconomicImpact(category, amount, composeNote(category, location, activity), eventId)
    }

    // ── 免费场景判定（消费 location 优先于免费 activity） ──
    internal fun isFreeScenario(location: String, activity: String): Boolean {
        val loc = location.lowercase()
        val act = activity.lowercase()
        for ((_, keywords) in locationKeywords) if (containsAny(loc, keywords)) return false
        if (containsAny(loc, freeLocationKeywords)) return true
        if (containsAny(act, freeActivityKeywords)) return true
        return false
    }

    // ── 类别识别（location 优先，activity 次之） ──
    internal fun matchCategory(location: String, activity: String): ScheduleEconomicCategory? {
        val loc = location.lowercase()
        val act = activity.lowercase()
        for ((category, keywords) in locationKeywords) if (containsAny(loc, keywords)) return category
        for ((category, keywords) in activityKeywords) if (containsAny(act, keywords)) return category
        return null
    }

    // ── 金额计算（确定性） ──
    internal fun computeAmount(
        category: ScheduleEconomicCategory,
        monthlySalary: Int,
        isWeekend: Boolean,
        hasCompanion: Boolean,
        isSpecificBrand: Boolean,
        occupation: String,
        eventId: String,
    ): Int {
        val rate = stableRate(category.baseRateMin, category.baseRateMax, eventId)
        var amount = maxOf(0, monthlySalary) * rate
        if (isWeekend) amount *= 1.3
        if (hasCompanion && (category == ScheduleEconomicCategory.DINING || category == ScheduleEconomicCategory.ENTERTAINMENT)) amount *= 1.5
        if (isSpecificBrand) amount *= 1.2
        amount *= occupationBias(occupation)
        return maxOf(category.minAmount, amount.roundToInt())
    }

    /** 职业 bias：学生 ×0.7 / 富豪 ×1.3 / 其他 ×1.0。 */
    internal fun occupationBias(occupation: String): Double {
        val lower = occupation.lowercase()
        if (containsAny(lower, studentOccupationKeywords)) return 0.7
        if (containsAny(lower, luxuryOccupationKeywords)) return 1.3
        return 1.0
    }

    // ── note 组装：「{emoji} {shortName} · {detail}」，detail 优先 location，>20 截 18+… ──
    internal fun composeNote(category: ScheduleEconomicCategory, location: String, activity: String): String {
        val detail = location.trim().ifEmpty { activity.trim() }
        if (detail.isEmpty()) return "${category.emoji} ${category.shortName}"
        val trimmedDetail = if (detail.length > 20) detail.take(18) + "…" else detail
        return "${category.emoji} ${category.shortName} · $trimmedDetail"
    }

    /** 字符串包含任一关键词（text 已 lowercase；keyword 在此 lowercase，匹配大小写无关）。 */
    internal fun containsAny(text: String, keywords: List<String>): Boolean = keywords.any { text.contains(it.lowercase()) }
}
