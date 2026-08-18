package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 礼物记录（1:1 iOS `GiftRecord` @Model，"收礼盒"数据源）。每次送/收礼一条。
 *
 * 与 [CurrencyTransactionEntity] 区别：流水记"金币进出"（审计），本表记"送礼事件本身"（礼物类型/价格/反应/关系影响快照）。
 *
 * [senderType]/[receiverType]/[context] 存枚举 raw（GiftPartyType / GiftContext）。[diyImagePath] 对应 iOS
 * `@Attribute(.externalStorage) diyImageData`——安卓存 app 私有目录路径（`filesDir/gift_diy/<uuid>.jpg`）非 BLOB，
 * 绝不把图片塞进消息 JSON。
 */
@Entity(
    tableName = "gift_records",
    indices = [Index("receiverCharacterUUID"), Index("senderCharacterUUID"), Index("timestamp")],
)
data class GiftRecordEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    // 送礼双方
    val senderType: String = "user",            // user / character
    val senderCharacterUUID: String = "",
    val receiverType: String = "character",     // user / character
    val receiverCharacterUUID: String = "",
    // 礼物内容
    val giftItemId: String = "",
    val pricePaid: Int = 0,                      // 快照
    val isDIY: Boolean = false,
    val diyTitle: String = "",
    val diyContent: String = "",
    val diyImagePath: String? = null,           // app 私有目录路径（对应 .externalStorage）
    // 上下文
    val context: String = "random",
    val senderMessage: String = "",
    // 接收方反应
    val reactionText: String = "",
    val reactionMoodEmoji: String = "",
    // 关系影响快照
    val affinityGain: Int = 0,
    val relationshipImpactJSON: String = "",    // {"familiarity":3,"closeness":5,...}
)
