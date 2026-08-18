package com.situ.aichat.data.model

/**
 * 货币系统枚举（1:1 iOS `Models/GiftTypes.swift` 的 WalletOwnerType / CurrencyTransactionKind /
 * CurrencyTransactionCategory）。rawValue 与 iOS 字面一致（camelCase），持久化进 CurrencyTransactionEntity 的
 * `*Raw` 列；`fromRaw` 未知值保守回退（与项目其它枚举一致）。
 */

/** 钱包所有者（1:1 iOS `WalletOwnerType`）。 */
enum class WalletOwnerType(val raw: String) {
    USER("user"),
    CHARACTER("character");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): WalletOwnerType = byRaw[raw] ?: USER
    }
}

/** 交易方向（1:1 iOS `CurrencyTransactionKind`）。 */
enum class CurrencyTransactionKind(val raw: String) {
    EARN("earn"),
    SPEND("spend");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): CurrencyTransactionKind = byRaw[raw] ?: EARN
    }
}

/**
 * 交易分类（iOS `CurrencyTransactionCategory` 12 类 + 安卓独立后新增）。`displayName` 对齐 iOS 中文映射
 * （petShop="宠物商店"非"宠物用品"——信代码）。`PET_SOUVENIR_SALE` 是 iOS 预留的 dead 枚举（项目无任何变卖功能、
 * 散步只入账 PET_WALK），保留占位以备未来。`WORLD_TRAVEL` = 世界系统 W7 旅行购票支出（安卓独立项目新增·无 iOS 对应）。
 */
enum class CurrencyTransactionCategory(val raw: String, val displayName: String) {
    PET_WALK("petWalk", "宠物散步"),
    PET_SOUVENIR_SALE("petSouvenirSale", "纪念品变卖"),
    PET_SHOP("petShop", "宠物商店"),
    MILESTONE("milestone", "里程碑奖励"),
    RED_PACKET("redPacket", "红包"),
    SALARY("salary", "工资"),
    GIFT("gift", "礼物"),
    INITIAL("initial", "初始金币"),
    UNEXPECTED_INCOME("unexpectedIncome", "意外收入"),
    UNEXPECTED_EXPENSE("unexpectedExpense", "意外支出"),
    REDEEM_CODE("redeemCode", "兑换码"),
    WORLD_TRAVEL("worldTravel", "世界旅行"),
    OTHER("other", "其他");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): CurrencyTransactionCategory = byRaw[raw] ?: OTHER
    }
}
