package com.situ.aichat.gift

import com.situ.aichat.data.model.MoodHistoryEntry
import java.util.Calendar

/**
 * 送礼的**时机加成**乘子（1:1 iOS `GiftTimingBonusService`）。特殊时刻收礼感受成倍放大：
 * - **生日当天** ×3.0（最强信号）
 * - **情绪低落** ×1.5（角色最近明显心情不好，用户"看出来"送礼）
 *
 * **组合取最大不叠加**（生日+不开心→×4.5 会破坏 clamp[1,20] 边界）。"和好 ×2"/"生病 ×2" 缺前置能力未实现。
 */
object GiftTimingBonusService {

    private const val ONE_DAY_MILLIS = 24L * 3600 * 1000

    /**
     * 当前时刻对指定角色的时机加成（生日 vs 情绪低落取最大，范围 [1.0, 3.0]）。
     */
    fun multiplier(birthday: Long?, moodHistory: List<MoodHistoryEntry>, now: Long): Double =
        maxOf(birthdayMultiplier(birthday, now), moodLowMultiplier(moodHistory, now))

    /**
     * 生日当天返回 3.0，否则 1.0（1:1 iOS `birthdayMultiplier`）。
     *
     * 用设备当前时区（[Calendar.getInstance] = `Calendar.current`），仅比对 month+day（不管年份）。
     */
    fun birthdayMultiplier(birthday: Long?, now: Long): Double {
        if (birthday == null) return 1.0
        val cal = Calendar.getInstance()
        cal.timeInMillis = birthday
        val bMonth = cal.get(Calendar.MONTH)
        val bDay = cal.get(Calendar.DAY_OF_MONTH)
        cal.timeInMillis = now
        val nMonth = cal.get(Calendar.MONTH)
        val nDay = cal.get(Calendar.DAY_OF_MONTH)
        return if (bMonth == nMonth && bDay == nDay) 3.0 else 1.0
    }

    /**
     * 情绪低落判定（1:1 iOS `moodLowMultiplier` 方案 B）：最近 5 条 mood 中**都在 24h 内**、red 达 3 条触发 1.5。
     *
     * 条数取 5（非 3）降低敏感度：5 条需 60% red（3/5）才触发，比 2/3 门槛更稳健。24h 内不够 3 条 → 不触发。
     */
    fun moodLowMultiplier(moodHistory: List<MoodHistoryEntry>, now: Long): Double {
        val recent5 = moodHistory.sortedByDescending { it.timestamp }.take(5)
        if (recent5.isEmpty()) return 1.0

        val oneDayAgo = now - ONE_DAY_MILLIS
        val within24h = recent5.filter { it.timestamp >= oneDayAgo }

        val redCount = within24h.count { it.colorName == "red" }
        return if (redCount >= 3) 1.5 else 1.0
    }
}
