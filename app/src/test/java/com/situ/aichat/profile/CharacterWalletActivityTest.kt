package com.situ.aichat.profile

import com.situ.aichat.data.local.entity.CurrencyTransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CharacterWalletActivity] 单测——断言反推 iOS `CharacterWalletCard.refreshData`：
 * 近 7 天流水(≤limit·降序保留·含 0 元)；本月按 kind 内存分组求收入/支出，且只计 >= monthStart。
 * 💰 只读聚合，不涉任何钱算路径。
 */
class CharacterWalletActivityTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L
    private val sevenDaysAgo = now - 7 * day
    private val monthStart = now - 10 * day // 月初早于 7 天前（月中后段）

    private fun tx(offsetDays: Long, kind: String, amount: Int) = CurrencyTransactionEntity(
        timestamp = now - offsetDays * day,
        ownerTypeRaw = "character",
        characterUuid = "c",
        kindRaw = kind,
        amount = amount,
    )

    @Test fun recentWithin7Days_descPreserved_andMonthlyGrouped() {
        val txns = listOf(
            tx(1, "earn", 100),
            tx(2, "spend", 50),
            tx(3, "earn", 0), // 0 元流水也进近 7 天列表（UI 显灰 0）
            tx(6, "spend", 30),
            tx(9, "earn", 200), // 9 天前：进本月汇总，不进近 7 天
        )
        val s = CharacterWalletActivity.compute(txns, sevenDaysAgo, monthStart)
        assertEquals(listOf(100, 50, 0, 30), s.recent.map { it.amount }) // 排除 9d、顺序保持
        assertEquals(300, s.monthlyEarn) // 100 + 0 + 200
        assertEquals(80, s.monthlySpend) // 50 + 30
    }

    @Test fun recentLimit_caps() {
        val txns = (1..5).map { tx(it.toLong(), "earn", it * 10) }
        val s = CharacterWalletActivity.compute(txns, sevenDaysAgo, monthStart, recentLimit = 2)
        assertEquals(listOf(10, 20), s.recent.map { it.amount })
    }

    @Test fun monthly_excludesBeforeMonthStart() {
        val txns = listOf(
            tx(1, "earn", 100),
            tx(15, "earn", 999), // 15 天前 < monthStart(10d)：既不计本月也不进近 7 天
        )
        val s = CharacterWalletActivity.compute(txns, sevenDaysAgo, monthStart)
        assertEquals(100, s.monthlyEarn)
        assertEquals(listOf(100), s.recent.map { it.amount })
    }

    @Test fun empty_returnsZeros() {
        val s = CharacterWalletActivity.compute(emptyList(), sevenDaysAgo, monthStart)
        assertEquals(0, s.monthlyEarn)
        assertEquals(0, s.monthlySpend)
        assertTrue(s.recent.isEmpty())
    }
}
