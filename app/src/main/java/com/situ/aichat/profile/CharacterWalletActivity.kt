package com.situ.aichat.profile

import com.situ.aichat.data.local.entity.CurrencyTransactionEntity
import com.situ.aichat.data.model.CurrencyTransactionKind

/**
 * 角色钱包卡「最近动态 + 本月汇总」的纯计算（💰只读·不碰任何钱算路径，仅聚合展示）。
 * 1:1 iOS `CharacterWalletCard.refreshData`：近 7 天流水（≤50 条）+ 本月按 kind 内存分组求收入/支出。
 *
 * 入参 [transactions] 须为「自 min(monthStart, sevenDaysAgo) 起、timestamp 降序」的角色流水。
 */
object CharacterWalletActivity {

    data class Summary(
        val recent: List<CurrencyTransactionEntity>,
        val monthlyEarn: Int,
        val monthlySpend: Int,
    )

    val EMPTY = Summary(emptyList(), 0, 0)

    fun compute(
        transactions: List<CurrencyTransactionEntity>,
        sevenDaysAgo: Long,
        monthStart: Long,
        recentLimit: Int = 50,
    ): Summary {
        // 近 7 天列表（已降序，取前 50；amount==0 的欠租流水也保留，UI 显灰「0」）。
        val recent = transactions.filter { it.timestamp >= sevenDaysAgo }.take(recentLimit)
        // 本月汇总：kind 是派生字段不能进 SQL，内存分组（1:1 iOS @Transient kind 分组）。
        var earn = 0
        var spend = 0
        for (tx in transactions) {
            if (tx.timestamp < monthStart) continue
            when (CurrencyTransactionKind.fromRaw(tx.kindRaw)) {
                CurrencyTransactionKind.EARN -> earn += tx.amount
                CurrencyTransactionKind.SPEND -> spend += tx.amount
            }
        }
        return Summary(recent, earn, spend)
    }
}
