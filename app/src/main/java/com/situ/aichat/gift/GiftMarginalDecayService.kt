package com.situ.aichat.gift

import com.situ.aichat.data.local.dao.GiftDao
import kotlin.math.roundToInt

/**
 * 送礼的**边际递减**乘子（1:1 iOS `GiftMarginalDecayService`），防"同类礼物刷 N 次"的机械化刷分。
 *
 * 乘子乘到 baseline affinity 上，最后 clamp[1,20]。**按 category 不按 giftItemId**——情感上"你最近一直送花"不分
 * 玫瑰/雏菊；按 itemId 判会鼓励"换花品种继续刷"。时间窗 7 天：够长让 decay 累积，够短让情绪过期后重新满效。
 *
 * 递减曲线（不含本次）：0→1.00 / 1→0.80 / 2→0.65 / 3→0.50 / 4+→0.30（地板，再送也不归零）。
 */
object GiftMarginalDecayService {

    private const val SEVEN_DAYS_MILLIS = 7L * 24 * 3600 * 1000

    /** 同品类数量 → 乘子（纯函数，公开便于单测枚举所有档位）。 */
    fun multiplierForCount(count: Int): Double = when {
        count < 1 -> 1.00
        count == 1 -> 0.80
        count == 2 -> 0.65
        count == 3 -> 0.50
        else -> 0.30
    }

    /**
     * 应用乘子到 baseline 得最终 gain（1:1 iOS `applyMultiplier`）：round（非截断）后 clamp[1,20]。
     */
    fun applyMultiplier(baselineGain: Int, multiplier: Double): Int =
        (baselineGain * multiplier).roundToInt().coerceIn(1, 20)

    /**
     * 计算当前送礼相对 7 天同品类历史的乘子（范围 [0.30, 1.00]）。
     *
     * @param item 本次送出的礼物
     * @param receiverCharacterUuid 接收方角色
     * @param excludingRecordUuid 本次刚建的 GiftRecord uuid（必须排除，否则自己算成第 1 件；未插入可传 ""）
     * @param now 参考时间（测试可注入）
     *
     * fetch 失败兜底返回 1.0。category 是目录派生无法进 SQL，fetch 后在 Kotlin 侧按 category 过滤计数。
     */
    suspend fun multiplier(
        item: GiftItem,
        receiverCharacterUuid: String,
        excludingRecordUuid: String,
        dao: GiftDao,
        now: Long = System.currentTimeMillis(),
    ): Double {
        val sevenDaysAgo = now - SEVEN_DAYS_MILLIS
        val records = runCatching {
            dao.recentUserGiftsToCharacter(receiverCharacterUuid, sevenDaysAgo, excludingRecordUuid)
        }.getOrNull() ?: return 1.0

        val sameCategoryCount = records.count { rec ->
            GiftCatalog.find(rec.giftItemId)?.category == item.category
        }
        return multiplierForCount(sameCategoryCount)
    }
}
