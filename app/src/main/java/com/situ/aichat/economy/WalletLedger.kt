package com.situ.aichat.economy

import com.situ.aichat.data.local.entity.CurrencyTransactionEntity
import com.situ.aichat.data.model.CurrencyTransactionKind
import java.time.Instant
import java.time.ZoneId

/**
 * 我的钱包屏账本的纯计算（14.6a·💰只读·不碰任何钱算路径，仅聚合/格式化展示）。1:1 iOS `WalletView`：
 * 本月收/支小计（按 kind 内存分组，kind 是派生字段进不了 SQL）+ 筛选 + 日期标签。注入 zone/now 便于单测。
 */
object WalletLedger {

    enum class Filter { ALL, EARN, SPEND }

    data class MonthlyStats(val earn: Int, val spend: Int) {
        companion object {
            val ZERO = MonthlyStats(0, 0)
        }
    }

    /**
     * 本月收/支小计（1:1 iOS computeMonthlyStats）：扫一遍 timestamp >= [monthStart] 的流水，按 kind 累加。
     * [transactions] 为用户侧全部流水（顺序无关）。
     */
    fun monthlyStats(transactions: List<CurrencyTransactionEntity>, monthStart: Long): MonthlyStats {
        var earn = 0
        var spend = 0
        for (tx in transactions) {
            if (tx.timestamp < monthStart) continue
            when (CurrencyTransactionKind.fromRaw(tx.kindRaw)) {
                CurrencyTransactionKind.EARN -> earn += tx.amount
                CurrencyTransactionKind.SPEND -> spend += tx.amount
            }
        }
        return MonthlyStats(earn, spend)
    }

    /** 按筛选切片（1:1 iOS filteredTransactions·保序）。 */
    fun applyFilter(transactions: List<CurrencyTransactionEntity>, filter: Filter): List<CurrencyTransactionEntity> =
        when (filter) {
            Filter.ALL -> transactions
            Filter.EARN -> transactions.filter { CurrencyTransactionKind.fromRaw(it.kindRaw) == CurrencyTransactionKind.EARN }
            Filter.SPEND -> transactions.filter { CurrencyTransactionKind.fromRaw(it.kindRaw) == CurrencyTransactionKind.SPEND }
        }

    /** 本月起始毫秒（设备时区当月 1 号 0 点·1:1 iOS startOfThisMonth）。 */
    fun monthStartMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /**
     * 账本行日期标签（1:1 iOS dateLabel）：今天 HH:mm / 昨天 HH:mm / 本年 M月d日 HH:mm / 跨年 yyyy年M月d日（无时分）。
     */
    fun dateLabel(timestamp: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val z = Instant.ofEpochMilli(timestamp).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val date = z.toLocalDate()
        val time = "%02d:%02d".format(z.hour, z.minute)
        return when {
            date == today -> "今天 $time"
            date == today.minusDays(1) -> "昨天 $time"
            date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日 $time"
            else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        }
    }
}
