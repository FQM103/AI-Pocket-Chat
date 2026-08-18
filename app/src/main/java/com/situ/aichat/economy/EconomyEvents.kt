package com.situ.aichat.economy

import com.situ.aichat.notification.EconomyNotificationTier

/**
 * 经济可见性（P1-40·拍板 A+三档·安卓超越 iOS：iOS 对经济事件零通知）：维护循环在调用点配对收集的
 * 「真发生事件」摘要 + 纯函数通知决策。**只读展示层**——不碰金额计算/写路径/幂等 key。
 */
enum class EconomyEventKind {
    /** 发薪（payoutIfDue 入账 > 0）。 */
    SALARY,

    /** 入职储蓄（首次推断完月薪的一次性 0.5 月薪）。 */
    ONBOARDING,

    /** 季度奖金。 */
    BONUS,

    /** 房租足额扣款。 */
    RENT,

    /** 欠租（部分扣款或 0 元留痕；[EconomyEvent.amount] 存「本应扣额」=叙事值，实扣在账本可查）。 */
    RENT_ARREARS,

    /** 昨日日程消费合计。 */
    SCHEDULE_SPEND,
}

/**
 * 单条经济事件摘要（kind 在调用点已知，amount=入/扣账额，欠租为本应额）。
 * 身份键 = [characterUuid]（批2 复核修 LOW#6：角色名无唯一约束，重名角色按名分组会被错误合并）；
 * [characterName] 仅展示。
 */
data class EconomyEvent(
    val kind: EconomyEventKind,
    val characterUuid: String,
    val characterName: String,
    val amount: Int,
    val timestamp: Long,
)

/**
 * 通知决策**纯函数**（仿 MomentNewPostNotificationPlanner）：events+tier → 0/简要/详细。
 * 文案本地化与实际发出在 [EconomySummaryNotifier]。
 */
object EconomyNotificationPlanner {

    sealed interface Plan {
        /** 不发（无事件 / 档位关）。 */
        data object None : Plan

        /** 简要（默认档）：不含金额，报有变动的角色数。 */
        data class Brief(val characterCount: Int) : Plan

        /** 详细：每角色一行、含金额。 */
        data class Detailed(val lines: List<CharacterLine>) : Plan
    }

    /** 详细档单行：一个角色的全部事件（保持发生序）。 */
    data class CharacterLine(val characterName: String, val events: List<EconomyEvent>)

    fun plan(events: List<EconomyEvent>, tier: EconomyNotificationTier): Plan {
        if (events.isEmpty() || tier == EconomyNotificationTier.OFF) return Plan.None
        return when (tier) {
            // 计数/分组按 uuid（复核修 LOW#6：重名角色不可合并），展示用 name。
            EconomyNotificationTier.BRIEF -> Plan.Brief(events.map { it.characterUuid }.distinct().size)
            EconomyNotificationTier.DETAILED -> Plan.Detailed(
                events.groupBy { it.characterUuid }.map { (_, list) -> CharacterLine(list.first().characterName, list) },
            )
            EconomyNotificationTier.OFF -> Plan.None
        }
    }
}

/** 钱包卡「新变动」高亮决策（纯函数）：最新流水时刻晚于上次浏览才亮（相等不亮；无流水不亮）。 */
internal fun hasEconomyNews(latestTxMillis: Long?, lastViewedMillis: Long): Boolean =
    latestTxMillis != null && latestTxMillis > lastViewedMillis
