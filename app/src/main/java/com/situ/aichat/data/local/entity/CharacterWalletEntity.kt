package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 角色钱包（1:1 iOS `CharacterWallet` @Model）。1 角色 : 1 钱包，角色删除级联删（FK CASCADE + characterUuid
 * 唯一索引，同 [CharacterPetEntity]）。角色起始 **0** 金币；月薪 0 且 [salaryInferred]=false（区分「未推断」与
 * 「推断结果就是 0」=乞丐/流浪汉）；[salaryDay] 1-28（避 29/30/31 月末边界）。
 * 三个 `last*Date` 是「只记录成功行为」的幂等戳。心意值 [affinityFromUser]/[affinityToUser] 不可消费。
 */
@Entity(
    tableName = "character_wallet",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["characterUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("characterUuid", unique = true)],
)
data class CharacterWalletEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    val characterUuid: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val coinBalance: Int = 0,
    val totalEarned: Int = 0,
    val totalSpent: Int = 0,
    val monthlySalary: Int = 0,
    val salaryInferred: Boolean = false,
    val salaryDay: Int = 15,
    val lastSalaryDate: Long? = null,
    val lastEconomicScanDate: Long? = null,
    val lastProactiveGiftDate: Long? = null,
    val affinityFromUser: Int = 0,
    val affinityToUser: Int = 0,
)
