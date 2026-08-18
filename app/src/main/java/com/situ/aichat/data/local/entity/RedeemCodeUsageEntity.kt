package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 兑换码使用记录（14.6c·1:1 iOS `RedeemCodeUsage`）。设备本地·**纳入备份**——流水(CurrencyTransaction)不进备份，
 * 若只靠流水做去重，恢复备份后已用过的码会「回炉」再用一次；本表进备份故恢复后仍记得用过哪些码（一次性语义）。
 *
 * 只存 [codeHash]（SHA-256 前 32 hex）不存原码：即便备份文件外泄也拿不出能用的码（仍需 secret+HMAC 验证）。
 * [codeHash] 唯一索引 = 并发/重入下的防御性兜底，杜绝同码重复入账。
 */
@Entity(tableName = "redeem_code_usage", indices = [Index(value = ["codeHash"], unique = true)])
data class RedeemCodeUsageEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    val codeHash: String = "",
    val redeemedAt: Long = System.currentTimeMillis(),
    val amount: Int = 0,
)
