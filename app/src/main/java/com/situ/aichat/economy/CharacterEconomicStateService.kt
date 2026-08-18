package com.situ.aichat.economy

import com.situ.aichat.data.local.dao.CurrencyDao
import com.situ.aichat.data.model.CharacterEconomicChatState
import com.situ.aichat.data.model.ChatEconomicPressureLevel
import com.situ.aichat.data.model.EconomicStatusTier
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 角色经济状况判定（1:1 iOS `Services/CharacterEconomicStateService.swift` 的**聊天侧**）。把角色余额/月薪/近 30 天
 * 欠租聚合成 4 档「压力档位」+ 近 7 天大额扣款摘要，让聊天 LLM 自然流露肉痛感（不暴露数字）。
 *
 * 月薪 ≤ 0（乞丐/流浪）或无钱包 → null → CHARACTER_ECONOMIC_STATE 模块跳过——**这正是当前「月薪 0 跳过」的来由**，
 * 9.1b 月薪推断落地、钱包有月薪后自动生效。iOS 是 nonisolated enum 接 ModelContext；安卓改 @Singleton + CurrencyDao，
 * 阈值判定 [chatPressureLevel] 是注入 hasArrears 的纯函数便于单测。**日程侧 3 档 `EconomicStatusTier` 随 9.1b 日程消费一起接。**
 */
@Singleton
class CharacterEconomicStateService @Inject constructor(
    private val dao: CurrencyDao,
) {

    /** 聊天经济状态（档位 + 近 7 天扣款摘要）；月薪 ≤ 0 / 无钱包返回 null。供 ChatViewModel/BusyReplyService 装进 BuildContext。 */
    suspend fun resolveChatState(characterUuid: String, now: Long = System.currentTimeMillis()): CharacterEconomicChatState? {
        val wallet = dao.getCharacterWallet(characterUuid) ?: return null
        val level = chatPressureLevel(wallet.monthlySalary, wallet.coinBalance, hasRecentArrears(characterUuid, now)) ?: return null
        return CharacterEconomicChatState(level = level, recentEventSummaries = recentEventSummaries(characterUuid, now))
    }

    /**
     * 日程侧 3 档 [EconomicStatusTier]（1:1 iOS `CharacterEconomicStateService.tier`）：月薪 ≤ 0 → null；近 30 天欠租
     * 一票否决 → tight；否则按 `max(0,余额)/月薪` 比例 `<0.5` tight / `≥1.5` comfortable / 其余 normal。供主动送礼候选
     * 过滤（预算比例表）+ 日程经济档位注入（9.1b-5）用。纯阈值判定走 [economicTier]（注入 hasArrears 便于单测）。
     */
    suspend fun tier(
        characterUuid: String,
        monthlySalary: Int,
        coinBalance: Int,
        now: Long = System.currentTimeMillis(),
    ): EconomicStatusTier? {
        if (monthlySalary <= 0) return null
        return economicTier(monthlySalary, coinBalance, hasRecentArrears(characterUuid, now))
    }

    /** 近 30 天是否有「欠租」流水（房租 service 在余额不足时写，note 含「欠租」二字；SQL 过 owner+时间，Kotlin 过文案）。 */
    suspend fun hasRecentArrears(characterUuid: String, now: Long = System.currentTimeMillis()): Boolean {
        val cutoff = now - ARREARS_WINDOW_DAYS * DAY_MILLIS
        return dao.characterTransactionsSince(characterUuid, cutoff).any { it.note.contains("欠租") }
    }

    /** 近 7 天经济事件摘要（`"{相对时间}:{note 截 20→18+…}"`，倒序最多 [limit] 条，跳过空 note）。 */
    suspend fun recentEventSummaries(
        characterUuid: String,
        now: Long = System.currentTimeMillis(),
        limit: Int = DEFAULT_RECENT_EVENT_LIMIT,
    ): List<String> {
        val cutoff = now - RECENT_EVENT_WINDOW_DAYS * DAY_MILLIS
        val txs = dao.characterTransactionsSince(characterUuid, cutoff).sortedByDescending { it.timestamp }
        val out = ArrayList<String>()
        for (tx in txs) {
            val note = trimEventNote(tx.note)
            if (note.isEmpty()) continue
            out.add("${relativeDayText(tx.timestamp, now)}:$note")
            if (out.size >= limit) break
        }
        return out
    }

    companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val ARREARS_WINDOW_DAYS = 30
        const val RECENT_EVENT_WINDOW_DAYS = 7
        const val DEFAULT_RECENT_EVENT_LIMIT = 5
    }
}

// ── 纯函数（internal，确定性单测，断言反推 iOS 阈值/边界/文案） ──────────────

private const val STRUGGLING_THRESHOLD = 0.30
private const val FRUGAL_THRESHOLD = 0.60
private const val COMFORTABLE_CHAT_THRESHOLD = 1.00

// 日程侧 3 档阈值（1:1 iOS tightThreshold=0.5 / comfortableThreshold=1.5）
private const val TIER_TIGHT_THRESHOLD = 0.5
private const val TIER_COMFORTABLE_THRESHOLD = 1.5

/**
 * 日程侧 3 档判定（1:1 iOS `tier`）：调用方已保证 `monthlySalary > 0` + 已查 [hasArrears]；
 * 欠租 → tight；否则按 `max(0,余额)/月薪` 比例 `<0.5` tight / `≥1.5` comfortable / 其余 normal。
 */
internal fun economicTier(monthlySalary: Int, coinBalance: Int, hasArrears: Boolean): EconomicStatusTier? {
    if (monthlySalary <= 0) return null
    if (hasArrears) return EconomicStatusTier.TIGHT
    val ratio = maxOf(0, coinBalance).toDouble() / monthlySalary
    return when {
        ratio < TIER_TIGHT_THRESHOLD -> EconomicStatusTier.TIGHT
        ratio >= TIER_COMFORTABLE_THRESHOLD -> EconomicStatusTier.COMFORTABLE
        else -> EconomicStatusTier.NORMAL
    }
}

/**
 * 聊天压力档位（1:1 iOS `chatPressureLevel`）：月薪 ≤ 0 → null；欠租一票否决 → struggling；
 * 否则按 `max(0, balance) / salary` 比例分 4 档（<0.30 / <0.60 / <1.00 / ≥1.00）。
 */
internal fun chatPressureLevel(monthlySalary: Int, coinBalance: Int, hasArrears: Boolean): ChatEconomicPressureLevel? {
    if (monthlySalary <= 0) return null
    if (hasArrears) return ChatEconomicPressureLevel.STRUGGLING
    val ratio = maxOf(0, coinBalance).toDouble() / monthlySalary
    return when {
        ratio < STRUGGLING_THRESHOLD -> ChatEconomicPressureLevel.STRUGGLING
        ratio < FRUGAL_THRESHOLD -> ChatEconomicPressureLevel.FRUGAL
        ratio < COMFORTABLE_CHAT_THRESHOLD -> ChatEconomicPressureLevel.COMFORTABLE
        else -> ChatEconomicPressureLevel.ABUNDANT
    }
}

/** note 截断（1:1 iOS：trim 后字数 >20 → 前 18 字 + "…"）。 */
internal fun trimEventNote(note: String): String {
    val t = note.trim()
    return if (t.length > 20) t.take(18) + "…" else t
}

/** "今天 / 昨天 / N 天前"（1:1 iOS `relativeDayText`，只覆盖近 7 天；按自然日 startOfDay 之差）。 */
internal fun relativeDayText(timestamp: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val d1 = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    val d2 = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(d1, d2).toInt()
    return when {
        days < 0 -> "未来"
        days == 0 -> "今天"
        days == 1 -> "昨天"
        else -> "$days 天前"
    }
}
