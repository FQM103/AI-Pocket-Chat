package com.situ.aichat.gift

import com.situ.aichat.data.local.dao.GiftDao
import com.situ.aichat.data.local.entity.GiftRecordEntity
import com.situ.aichat.data.model.GiftCategory
import com.situ.aichat.data.model.GiftImpressionTag
import com.situ.aichat.data.model.MoodHistoryEntry
import java.time.Instant
import java.time.MonthDay
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 送礼印象标签计算（1:1 iOS `GiftImpressionTagService`）。行为模式驱动、不依赖总心意值（天然反刷分）。
 *
 * 算法：查角色收到的用户送礼 → 逐标签 [triggers] 评估 → 同组取最高 priority + 独立全留 → priority DESC top limit。
 * 纯函数 [selectTags]/[triggers] 与 DB 查询分离，便于单测枚举边界。"近 N 天"是滚动 24h×N（非日历日），跨度/生日用日历。
 */
object GiftImpressionTagService {

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** 计算角色应显示的标签（DB 查询 + 选择），最多 [limit] 个（UI 默认 3）。 */
    suspend fun tags(
        characterUuid: String,
        dao: GiftDao,
        moodHistory: List<MoodHistoryEntry>,
        birthday: Long?,
        limit: Int = 3,
        now: Long = System.currentTimeMillis(),
    ): List<GiftImpressionTag> {
        val records = runCatching { dao.userGiftsToCharacterDesc(characterUuid) }.getOrDefault(emptyList())
        return selectTags(records, moodHistory, birthday, limit, now)
    }

    /** 纯选择：逐标签评估 → 同组去重（keep 最高 priority）+ 独立全留 → priority DESC → top limit。 */
    internal fun selectTags(
        records: List<GiftRecordEntity>,
        moodHistory: List<MoodHistoryEntry>,
        birthday: Long?,
        limit: Int = 3,
        now: Long,
    ): List<GiftImpressionTag> {
        if (records.isEmpty()) return emptyList()

        val triggered = GiftImpressionTag.entries.filter { triggers(it, records, moodHistory, birthday, now) }

        val bestByGroup = LinkedHashMap<String, GiftImpressionTag>()
        val standalones = mutableListOf<GiftImpressionTag>()
        for (tag in triggered) {
            if (tag.group.isEmpty()) {
                standalones += tag
            } else {
                val existing = bestByGroup[tag.group]
                if (existing == null || tag.priority > existing.priority) bestByGroup[tag.group] = tag
            }
        }

        val merged = bestByGroup.values + standalones
        return merged.sortedByDescending { it.priority }.take(limit)
    }

    /** 单标签触发判定（纯函数，便于单测枚举边界）。 */
    internal fun triggers(
        tag: GiftImpressionTag,
        records: List<GiftRecordEntity>,
        moodHistory: List<MoodHistoryEntry>,
        birthday: Long?,
        now: Long,
    ): Boolean = when (tag) {
        // 频次
        GiftImpressionTag.CONSTANT_PRESENCE -> recordsInLast(7, records, now).size >= 2
        GiftImpressionTag.THOUGHTFUL_FREQUENCY -> recordsInLast(30, records, now).size >= 3
        GiftImpressionTag.PERSISTENT -> sendDateSpan(records) >= 30
        GiftImpressionTag.INDULGENT -> recordsInLast(7, records, now).size >= 3

        // 品类偏好（近 30 天）
        GiftImpressionTag.ROMANTIC -> categoryShare(GiftCategory.FLOWER, 30, records, now) >= 0.40
        GiftImpressionTag.PRACTICAL -> categoryShare(GiftCategory.FOOD, 30, records, now) >= 0.40
        GiftImpressionTag.REFINED -> categoryShare(GiftCategory.ACCESSORY, 30, records, now) >= 0.40
        GiftImpressionTag.VERSATILE -> distinctCategoryCount(30, records, now) >= 5

        // 价值高
        GiftImpressionTag.GENEROUS -> records.any { it.pricePaid > 500 }
        GiftImpressionTag.DEVOTED -> records.any { it.pricePaid > 1000 }

        // 工艺
        GiftImpressionTag.ARTFUL -> records.any { isHandmade(it) }
        GiftImpressionTag.METICULOUS -> {
            val total = records.size
            if (total == 0) false else records.count { isHandmade(it) }.toDouble() / total >= 0.30
        }

        // 独立
        GiftImpressionTag.LITTLE_BUT_OFTEN ->
            recordsInLast(30, records, now).count { it.pricePaid <= 50 } >= 5

        GiftImpressionTag.ATTUNED ->
            records.any { isDuringMoodLow(it.timestamp, moodHistory) }

        GiftImpressionTag.REMEMBERING -> {
            if (birthday == null) {
                false
            } else {
                val bdayMD = MonthDay.from(Instant.ofEpochMilli(birthday).atZone(zone))
                records.any { MonthDay.from(Instant.ofEpochMilli(it.timestamp).atZone(zone)) == bdayMD }
            }
        }

        GiftImpressionTag.OBSESSED -> {
            if (records.size < 5) {
                false
            } else {
                val byCategory = records.groupingBy {
                    GiftCatalog.find(it.giftItemId)?.category ?: GiftCategory.FOOD
                }.eachCount()
                val maxCount = byCategory.values.maxOrNull() ?: 0
                val share = maxCount.toDouble() / records.size
                share >= 0.60 && maxCount >= 5
            }
        }
    }

    // MARK: - 辅助纯函数

    /** 近 [days] 天（滚动 24h×days，非日历日）。 */
    private fun recordsInLast(days: Int, records: List<GiftRecordEntity>, now: Long): List<GiftRecordEntity> {
        val cutoff = now - days.toLong() * 24 * 3600 * 1000
        return records.filter { it.timestamp >= cutoff }
    }

    /** 首末送礼跨度（日历日，startOfDay 差）。 */
    private fun sendDateSpan(records: List<GiftRecordEntity>): Int {
        val minTs = records.minOfOrNull { it.timestamp } ?: return 0
        val maxTs = records.maxOfOrNull { it.timestamp } ?: return 0
        val firstDay = Instant.ofEpochMilli(minTs).atZone(zone).toLocalDate()
        val lastDay = Instant.ofEpochMilli(maxTs).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(firstDay, lastDay).toInt()
    }

    /** 某品类在近 [days] 天的占比（recent 空 → 0）。 */
    private fun categoryShare(category: GiftCategory, days: Int, records: List<GiftRecordEntity>, now: Long): Double {
        val recent = recordsInLast(days, records, now)
        if (recent.isEmpty()) return 0.0
        val matching = recent.count { GiftCatalog.find(it.giftItemId)?.category == category }
        return matching.toDouble() / recent.size
    }

    /** 近 [days] 天不同品类数。 */
    private fun distinctCategoryCount(days: Int, records: List<GiftRecordEntity>, now: Long): Int =
        recordsInLast(days, records, now).mapNotNull { GiftCatalog.find(it.giftItemId)?.category }.toSet().size

    /** 手作判定：DIY 或目录 isHandmade（与 GiftHistoryPromptService 一致）。 */
    private fun isHandmade(record: GiftRecordEntity): Boolean {
        if (record.isDIY) return true
        return GiftCatalog.find(record.giftItemId)?.isHandmade == true
    }

    /** 送礼时刻是否处于情绪低落期（与 GiftTimingBonusService 一致：礼物前 24h 内最近 5 条 mood ≥3 红）。 */
    private fun isDuringMoodLow(giftTimestamp: Long, moodHistory: List<MoodHistoryEntry>): Boolean {
        val oneDayBefore = giftTimestamp - 24L * 3600 * 1000
        val redCount = moodHistory
            .filter { it.timestamp in oneDayBefore..giftTimestamp }
            .sortedByDescending { it.timestamp }
            .take(5)
            .count { it.colorName == "red" }
        return redCount >= 3
    }
}
