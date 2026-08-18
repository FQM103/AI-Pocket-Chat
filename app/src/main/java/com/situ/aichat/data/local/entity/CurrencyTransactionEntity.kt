package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 金币交易流水（1:1 iOS `CurrencyTransaction` @Model）。每笔进出一条；[amount] **总是正数**（方向由 [kindRaw]
 * 决定，构造时由 CurrencyService coerceAtLeast(0)），[balanceAfter] 是交易后余额快照（账本 UI 免累加历史）。
 * [relatedEntityId] 是幂等 key（发薪/房租/送礼/兑换/进化等去重，建索引）。单表 + [ownerTypeRaw] 判别（不按 owner
 * 拆表）。
 *
 * **进备份**（R2 修：随余额一起恢复）：发薪/房租/季度奖金/入职储蓄的「是否已发」全靠 [relatedEntityId] 的幂等台账判定
 * （[com.situ.aichat.data.local.dao.CurrencyDao.transactionExists]），余额快照本身不带这些幂等信息。若流水不进备份，
 * 换机/重装恢复后台账为空 → 下次回前台维护把当月工资/入职储蓄/季度奖金**重发**、房租**重扣**。故备份必须随 wallet 一起
 * 整表搬运流水（原 uuid/relatedEntityId 保真），作为顶层全局段恢复（与礼物/红包记录同档）。
 */
@Entity(
    tableName = "currency_transaction",
    indices = [Index("relatedEntityId"), Index("characterUuid"), Index("timestamp")],
)
data class CurrencyTransactionEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val ownerTypeRaw: String = "user",
    val characterUuid: String = "",
    val kindRaw: String = "earn",
    val categoryRaw: String = "other",
    val amount: Int = 0,
    val balanceAfter: Int = 0,
    val relatedEntityId: String? = null,
    val note: String = "",
)
