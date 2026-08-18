package com.situ.aichat.gift

import com.situ.aichat.data.model.GiftCategory
import com.situ.aichat.data.model.GiftEmotionalTag
import java.util.UUID

/**
 * 礼物目录项（1:1 iOS `GiftItem`，非 @Model 静态数据）。
 *
 * "礼物的模板"；每次实际送礼产生的记录由 `GiftRecordEntity` 存储。目录是产品决定的静态代码数据，随 app 版本更新，
 * 不进 Room（避免迁移负担）——`GiftRecordEntity` 只存 `giftItemId`，通过 [GiftCatalog.find] 查回详情。
 */
data class GiftItem(
    /** 稳定 ID（如 "gift_boba_tea"），同时是图片资源名 */
    val id: String,
    /** 显示名（中文） */
    val name: String,
    /** 一句话描述 */
    val subtitle: String,
    /** 价格（金币） */
    val price: Int,
    /** 品类 */
    val category: GiftCategory,
    /** 情感标签（1-3 个） */
    val emotionalTags: List<GiftEmotionalTag>,
    /** SF Symbol 名字（图片缺失时占位；9.2d 映射 Material Icon） */
    val fallbackSymbol: String,
    /** 是否"重磅款"（礼物店加粗，情感权重略高） */
    val isSignature: Boolean,
    /** 是否手作（价格低但情感权重最高） */
    val isHandmade: Boolean,
)

/**
 * 静态礼物目录（1:1 iOS `GiftCatalog`，46 件）。
 *
 * 纯静态查询无任何状态，可从任意线程同步调用。后续阶段从此处新增礼物不影响数据库迁移。
 */
object GiftCatalog {

    /** 通过 id 查找礼物 */
    fun find(id: String): GiftItem? = allItems.firstOrNull { it.id == id }

    /** 按品类过滤 */
    fun items(category: GiftCategory): List<GiftItem> = allItems.filter { it.category == category }

    /** 按价格排序 */
    fun sortedByPrice(ascending: Boolean = true): List<GiftItem> =
        if (ascending) allItems.sortedBy { it.price } else allItems.sortedByDescending { it.price }

    // MARK: - 用户 DIY 手作礼物

    /**
     * 用户 DIY 手作礼物的 id 约定前缀（1:1 iOS `userDIYIdPrefix`）。
     *
     * DIY 礼物不在 [allItems]，[find] 对 DIY id 必然返回 null；所有从 GiftRecord 读 GiftItem 的调用方对
     * `isDIY == true` 都已设兜底（读不到目录项时用 record 自身 diyTitle/diyContent/diyImagePath 兜底）。
     */
    const val userDIYIdPrefix = "diy_user_"

    /**
     * 构造一个用户 DIY 手作礼物的 [GiftItem] stub（1:1 iOS `makeUserDIY`）。
     *
     * 只存在于送礼流程的内存里，不写入 [allItems]。送礼完成后持久化的是 GiftRecord（isDIY=true）。
     *
     * - [cost] 内部 clamp 到 `[2, 20]`（UI 应已约束，这里双重兜底）。
     * - [title] 空 → "手作礼物"；[content] 前 15 字作 subtitle（超 15 加 "…"，空 → "亲手做的一份"）。
     */
    fun makeUserDIY(title: String, content: String, cost: Int): GiftItem {
        val cleanedTitle = title.trim()
        val cleanedContent = content.trim()
        val clampedCost = cost.coerceIn(2, 20)

        val name = cleanedTitle.ifEmpty { "手作礼物" }

        val subtitle = when {
            cleanedContent.isEmpty() -> "亲手做的一份"
            cleanedContent.length > 15 -> cleanedContent.take(15) + "…"
            else -> cleanedContent
        }

        val idSuffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val id = userDIYIdPrefix + idSuffix

        return GiftItem(
            id = id,
            name = name,
            subtitle = subtitle,
            price = clampedCost,
            category = GiftCategory.HANDMADE,
            // 默认"贴心 + 怀旧"，和预置手作 4 件主调对齐
            emotionalTags = listOf(GiftEmotionalTag.THOUGHTFUL, GiftEmotionalTag.NOSTALGIC),
            fallbackSymbol = "heart.text.square",
            isSignature = false,
            isHandmade = true,
        )
    }

    /** 全部 46 件礼物（1:1 iOS `allItems`，id/名称/价格/标签/signature/handmade 逐项核对）。 */
    val allItems: List<GiftItem> = listOf(
        // 食物（10 件）
        item("gift_oden", "关东煮", "便利店热腾腾的一份", 8, GiftCategory.FOOD, listOf(GiftEmotionalTag.HUMOROUS, GiftEmotionalTag.WARM), "bowl.of.rice"),
        item("gift_boba_tea", "珍珠奶茶", "还是经典焦糖口味", 15, GiftCategory.FOOD, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.HUMOROUS), "cup.and.saucer"),
        item("gift_coffee", "手冲咖啡", "醒一醒，别熬太晚", 20, GiftCategory.FOOD, listOf(GiftEmotionalTag.PRACTICAL, GiftEmotionalTag.WARM), "cup.and.saucer.fill"),
        item("gift_cake_slice", "小蛋糕", "草莓奶油切块", 35, GiftCategory.FOOD, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.CUTE), "birthday.cake"),
        item("gift_fruit_plate", "水果切盘", "切好的爱心", 50, GiftCategory.FOOD, listOf(GiftEmotionalTag.PRACTICAL, GiftEmotionalTag.THOUGHTFUL), "leaf"),
        item("gift_macaron", "法式马卡龙", "三种颜色三种味道", 65, GiftCategory.FOOD, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.REFINED), "circle.grid.3x3.fill", isSignature = true),
        item("gift_sushi_set", "寿司拼盘", "一起吃顿好的", 120, GiftCategory.FOOD, listOf(GiftEmotionalTag.REFINED, GiftEmotionalTag.PRACTICAL), "fish"),
        item("gift_hotpot", "火锅套餐", "两人份，涮起来", 180, GiftCategory.FOOD, listOf(GiftEmotionalTag.WARM, GiftEmotionalTag.ROMANTIC), "flame.fill", isSignature = true),
        item("gift_steak", "高级牛排", "七分熟，配红酒", 380, GiftCategory.FOOD, listOf(GiftEmotionalTag.LUXURIOUS, GiftEmotionalTag.ROMANTIC), "fork.knife"),
        item("gift_michelin", "米其林大餐", "今晚我请客", 880, GiftCategory.FOOD, listOf(GiftEmotionalTag.LUXURIOUS, GiftEmotionalTag.ROMANTIC), "star.circle.fill", isSignature = true),

        // 花束（7 件）
        item("gift_rose_single", "单枝玫瑰", "路过花店顺手带的", 20, GiftCategory.FLOWER, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.NOSTALGIC), "camera.macro"),
        item("gift_daisy", "小雏菊", "一小把，用麻绳捆着", 25, GiftCategory.FLOWER, listOf(GiftEmotionalTag.CUTE, GiftEmotionalTag.HUMOROUS), "camera.macro.circle"),
        item("gift_babybreath", "满天星", "一簇白色的小星星", 45, GiftCategory.FLOWER, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.CUTE), "sparkles"),
        item("gift_sunflower", "向日葵", "像你一样亮", 60, GiftCategory.FLOWER, listOf(GiftEmotionalTag.HUMOROUS, GiftEmotionalTag.WARM), "sun.max.fill"),
        item("gift_tulip", "郁金香束", "十一朵奶油色", 95, GiftCategory.FLOWER, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.REFINED), "camera.macro"),
        item("gift_rose_bouquet", "玫瑰花束", "粉色的，裹着牛皮纸", 150, GiftCategory.FLOWER, listOf(GiftEmotionalTag.ROMANTIC), "camera.macro", isSignature = true),
        item("gift_99_roses", "99 朵玫瑰", "长长久久", 888, GiftCategory.FLOWER, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.LUXURIOUS), "heart.circle.fill", isSignature = true),

        // 饰品（7 件）
        item("gift_hairclip", "发夹", "小珍珠配小花", 30, GiftCategory.ACCESSORY, listOf(GiftEmotionalTag.CUTE), "circle.hexagongrid"),
        item("gift_couple_bracelet", "情侣手绳", "一人一条", 68, GiftCategory.ACCESSORY, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.NOSTALGIC), "infinity", isSignature = true),
        item("gift_bracelet", "手链", "细细的金色", 120, GiftCategory.ACCESSORY, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.REFINED), "circle.circle"),
        item("gift_earrings", "耳环", "一对珍珠垂坠", 280, GiftCategory.ACCESSORY, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.REFINED), "drop"),
        item("gift_necklace", "项链", "爱心吊坠", 380, GiftCategory.ACCESSORY, listOf(GiftEmotionalTag.ROMANTIC), "heart"),
        item("gift_sunglasses", "墨镜", "复古猫眼款", 580, GiftCategory.ACCESSORY, listOf(GiftEmotionalTag.ADVENTUROUS, GiftEmotionalTag.REFINED), "sunglasses"),
        item("gift_ring", "钻戒", "答应我好不好", 1880, GiftCategory.ACCESSORY, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.LUXURIOUS), "diamond.fill", isSignature = true),

        // 日用品（8 件）
        item("gift_handwarmer", "暖手宝", "冬天别冻着了", 45, GiftCategory.DAILY, listOf(GiftEmotionalTag.PRACTICAL, GiftEmotionalTag.THOUGHTFUL), "flame"),
        item("gift_plush_slippers", "毛绒拖鞋", "兔子耳朵款", 55, GiftCategory.DAILY, listOf(GiftEmotionalTag.CUTE, GiftEmotionalTag.PRACTICAL), "shoe"),
        item("gift_teddy_bear", "小熊玩偶", "系着红色蝴蝶结", 65, GiftCategory.DAILY, listOf(GiftEmotionalTag.CUTE, GiftEmotionalTag.NOSTALGIC), "teddybear", isSignature = true),
        item("gift_candle", "香薰蜡烛", "薰衣草味，玻璃罐装", 75, GiftCategory.DAILY, listOf(GiftEmotionalTag.ROMANTIC), "flame.fill"),
        item("gift_thermos", "保温杯", "鼠尾草绿", 80, GiftCategory.DAILY, listOf(GiftEmotionalTag.PRACTICAL), "mug"),
        item("gift_couple_mugs", "情侣马克杯", "刻着两个名字", 88, GiftCategory.DAILY, listOf(GiftEmotionalTag.PRACTICAL, GiftEmotionalTag.ROMANTIC), "mug.fill"),
        item("gift_pillow", "抱枕", "绣着月亮", 90, GiftCategory.DAILY, listOf(GiftEmotionalTag.PRACTICAL, GiftEmotionalTag.CUTE), "bed.double"),
        item("gift_scarf", "围巾", "米色针织，流苏边", 120, GiftCategory.DAILY, listOf(GiftEmotionalTag.PRACTICAL, GiftEmotionalTag.NOSTALGIC), "scribble.variable", isSignature = true),

        // 奢侈品（5 件）
        item("gift_lipstick", "口红", "经典正红", 350, GiftCategory.LUXURY, listOf(GiftEmotionalTag.PRACTICAL, GiftEmotionalTag.ROMANTIC), "lips"),
        item("gift_perfume", "香水", "琥珀色液体，金色瓶盖", 680, GiftCategory.LUXURY, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.REFINED), "drop.circle.fill"),
        item("gift_silk_scarf", "真丝围巾", "花卉图案，轻柔垂顺", 880, GiftCategory.LUXURY, listOf(GiftEmotionalTag.LUXURIOUS, GiftEmotionalTag.ROMANTIC), "square.fill.on.circle.fill"),
        item("gift_skincare_set", "高端护肤套装", "奶油和金色的盒子", 1200, GiftCategory.LUXURY, listOf(GiftEmotionalTag.PRACTICAL, GiftEmotionalTag.REFINED), "sparkles.square.filled.on.square"),
        item("gift_designer_bag", "名牌手袋", "链条柔软的驼色羊皮", 1500, GiftCategory.LUXURY, listOf(GiftEmotionalTag.LUXURIOUS), "handbag.fill", isSignature = true),

        // 体验券（5 件）
        item("gift_movie_ticket", "一起看电影", "两张电影票 + 爆米花", 120, GiftCategory.EXPERIENCE, listOf(GiftEmotionalTag.ROMANTIC), "film"),
        item("gift_ktv", "KTV 包间", "开嗓三小时", 200, GiftCategory.EXPERIENCE, listOf(GiftEmotionalTag.HUMOROUS, GiftEmotionalTag.ADVENTUROUS), "mic.fill"),
        item("gift_date_dinner", "约会晚餐", "烛光 + 两杯红酒", 300, GiftCategory.EXPERIENCE, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.REFINED), "wineglass", isSignature = true),
        item("gift_spa", "按摩 SPA", "给你一个彻底的放松", 450, GiftCategory.EXPERIENCE, listOf(GiftEmotionalTag.THOUGHTFUL, GiftEmotionalTag.REFINED), "leaf.circle"),
        item("gift_weekend_trip", "周末出游", "带上小行李箱就走", 500, GiftCategory.EXPERIENCE, listOf(GiftEmotionalTag.ADVENTUROUS, GiftEmotionalTag.ROMANTIC), "airplane", isSignature = true),

        // 手作卡片（4 件 · 低价高情感）
        item("gift_note", "手写便签", "画了几颗爱心", 5, GiftCategory.HANDMADE, listOf(GiftEmotionalTag.NOSTALGIC), "note.text", isHandmade = true),
        item("gift_postcard", "手写明信片", "水彩风景 + 寥寥几笔", 10, GiftCategory.HANDMADE, listOf(GiftEmotionalTag.NOSTALGIC), "envelope", isHandmade = true),
        item("gift_origami", "纸鹤", "粉色的一只", 15, GiftCategory.HANDMADE, listOf(GiftEmotionalTag.NOSTALGIC, GiftEmotionalTag.CUTE), "signature", isHandmade = true),
        item("gift_love_letter", "手写情书", "火漆封口，夹了干花", 30, GiftCategory.HANDMADE, listOf(GiftEmotionalTag.ROMANTIC, GiftEmotionalTag.NOSTALGIC), "envelope.fill", isSignature = true, isHandmade = true),
    )

    /** 内部构造助手，减少 46 行样板。 */
    private fun item(
        id: String,
        name: String,
        subtitle: String,
        price: Int,
        category: GiftCategory,
        emotionalTags: List<GiftEmotionalTag>,
        fallbackSymbol: String,
        isSignature: Boolean = false,
        isHandmade: Boolean = false,
    ): GiftItem = GiftItem(id, name, subtitle, price, category, emotionalTags, fallbackSymbol, isSignature, isHandmade)
}
