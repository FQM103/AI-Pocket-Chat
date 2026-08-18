package com.situ.aichat.data.model

/**
 * 1:1 port of iOS `MomentTriggerType` (Models/MomentPost.swift). Marks *why* a generated moment or
 * diary entry was created. Despite the "Moment" name it is a generic generation-trigger type shared
 * by both 朋友圈 (M06) and 日记 (M07) — iOS reuses the same enum for `DiaryEntry.triggerTypeRaw`.
 *
 * Persisted as [raw] (iOS rawValue) so backups round-trip. Unknown/legacy values fall back to
 * [AUTO_DRAFT] (iOS `?? .autoDraft`).
 *
 * - [AUTO_DRAFT]    自发随笔（兴趣/日程/成长上下文驱动），老数据默认值
 * - [GIFT_RECEIVED] 收到珍贵/手作礼物顺势而发（`relatedGiftId` 指向 GiftRecord）→ 礼物系统 P9
 * - [PET_SHOP_PURCHASE] 给宠物买贵价用品 → 宠物系统 P8
 */
enum class MomentTriggerType(val raw: String) {
    AUTO_DRAFT("auto_draft"),
    GIFT_RECEIVED("gift_received"),
    PET_SHOP_PURCHASE("pet_shop_purchase"),

    /** 交换日记（日记重设计 R4·安卓新增）：角色写的「TA 自己的日记」，authorCharacterUuid 指作者。 */
    EXCHANGE("exchange"),
    ;

    companion object {
        fun fromRaw(raw: String): MomentTriggerType =
            entries.firstOrNull { it.raw == raw } ?: AUTO_DRAFT
    }
}
