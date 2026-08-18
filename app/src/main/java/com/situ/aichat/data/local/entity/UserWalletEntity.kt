package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 用户钱包（1:1 iOS `UserWallet` @Model, Models/UserWallet.swift）。单例
 * （[com.situ.aichat.economy.CurrencyService.ensureUserWallet] 保证唯一），起始 **100** 金币。
 * 独立成表（非 AppSettings）以便进备份、恢复后金币不丢。totalEarned/totalSpent 只增不减（成就展示）。
 */
@Entity(tableName = "user_wallet")
data class UserWalletEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(),
    val coinBalance: Int = 100,
    val totalEarned: Int = 0,
    val totalSpent: Int = 0,
)
