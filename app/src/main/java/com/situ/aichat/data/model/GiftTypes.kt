package com.situ.aichat.data.model

/**
 * 礼物系统枚举（1:1 iOS `Models/GiftTypes.swift`）。rawValue 与 iOS 字面一致（camelCase），持久化进
 * `gift_records` 的 `*Raw` 列；`fromRaw` 未知值保守回退（与项目其它枚举一致）。
 *
 * 钱包/货币相关枚举（WalletOwnerType / CurrencyTransactionKind / CurrencyTransactionCategory）已在 P9.1 的
 * [CurrencyTypes] 落地，本文件只放礼物专属枚举。
 */

/** 礼物品类（1:1 iOS `GiftCategory`，7 类）。`iconSymbol` 为 SF Symbol 名，9.2d UI 映射到 Material Icon。 */
enum class GiftCategory(val raw: String, val displayName: String, val iconSymbol: String) {
    FOOD("food", "食物", "fork.knife"),
    FLOWER("flower", "花束", "camera.macro"),
    ACCESSORY("accessory", "饰品", "sparkles"),
    DAILY("daily", "日用品", "house"),
    LUXURY("luxury", "奢侈品", "crown"),
    EXPERIENCE("experience", "体验", "ticket"),
    HANDMADE("handmade", "手作", "paintbrush");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): GiftCategory = byRaw[raw] ?: FOOD
    }
}

/** 情感标签（1:1 iOS `GiftEmotionalTag`，10 个）。8 维关系映射权重表见 9.2b。 */
enum class GiftEmotionalTag(val raw: String, val displayName: String) {
    ROMANTIC("romantic", "浪漫"),
    PRACTICAL("practical", "实用"),
    HUMOROUS("humorous", "幽默"),
    NOSTALGIC("nostalgic", "怀旧"),
    ADVENTUROUS("adventurous", "冒险"),
    CUTE("cute", "可爱"),
    WARM("warm", "温暖"),
    THOUGHTFUL("thoughtful", "贴心"),
    LUXURIOUS("luxurious", "奢华"),
    REFINED("refined", "精致");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): GiftEmotionalTag? = byRaw[raw]
    }
}

/** 送礼上下文（1:1 iOS `GiftContext`，9 类）。 */
enum class GiftContext(val raw: String, val displayName: String) {
    RANDOM("random", "随手一份"),
    BIRTHDAY("birthday", "生日礼物"),
    FESTIVAL("festival", "节日礼物"),
    ANNIVERSARY("anniversary", "纪念日礼物"),
    RECONCILE("reconcile", "和好礼物"),
    COMFORT("comfort", "安慰礼物"),
    SICK_VISIT("sickVisit", "探病礼物"),
    APOLOGY("apology", "道歉礼物"),
    CONGRATS("congrats", "庆祝礼物");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): GiftContext = byRaw[raw] ?: RANDOM
    }
}

/** 送礼双方类型（1:1 iOS `GiftPartyType`）。GiftRecord 的 sender/receiver 用它。 */
enum class GiftPartyType(val raw: String) {
    USER("user"),
    CHARACTER("character");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): GiftPartyType = byRaw[raw] ?: USER
    }
}
