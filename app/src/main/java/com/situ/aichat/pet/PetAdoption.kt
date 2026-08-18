package com.situ.aichat.pet

import com.situ.aichat.data.model.RelationshipQuality
import kotlin.math.max
import kotlin.math.min

/**
 * 领养解锁条件（1:1 iOS `PetCareService.canAdopt` + `AdoptionProgress`）。纯函数，便于单测。
 * 条件（全满足）：陪伴≥14 天 && trust≥40 && familiarity≥35 && closeness≥30 && 消息≥100。
 */
object PetAdoptionRules {

    /** 陪伴天数 = 24h 整段数 + 1（创建当天算第 1 天；同 [PetCareService.applyDecay] 的 24h floor 口径）。 */
    fun companionDays(creationDate: Long, now: Long): Int =
        max(((now - creationDate) / 86_400_000L).toInt() + 1, 1)

    fun evaluate(
        rq: RelationshipQuality,
        creationDate: Long,
        messageCount: Int,
        now: Long = System.currentTimeMillis(),
    ): AdoptionEligibility {
        val days = companionDays(creationDate, now)
        val progress = AdoptionProgress(days, rq.trust, rq.familiarity, rq.closeness, messageCount)
        val canAdopt = days >= 14 && rq.trust >= 40 && rq.familiarity >= 35 && rq.closeness >= 30 && messageCount >= 100
        return AdoptionEligibility(canAdopt, progress)
    }
}

data class AdoptionEligibility(val canAdopt: Boolean, val progress: AdoptionProgress)

/** 领养各项进度（当前 / 目标 14·40·35·30·100），百分比 0~1。 */
data class AdoptionProgress(
    val companionDays: Int,
    val trust: Int,
    val familiarity: Int,
    val closeness: Int,
    val messageCount: Int,
) {
    val companionDaysPercent: Float get() = min(1f, companionDays / 14f)
    val trustPercent: Float get() = min(1f, trust / 40f)
    val familiarityPercent: Float get() = min(1f, familiarity / 35f)
    val closenessPercent: Float get() = min(1f, closeness / 30f)
    val messageCountPercent: Float get() = min(1f, messageCount / 100f)

    /** 总体完成度（5 项等权平均）。 */
    val overallPercent: Float
        get() = (companionDaysPercent + trustPercent + familiarityPercent + closenessPercent + messageCountPercent) / 5f
}
