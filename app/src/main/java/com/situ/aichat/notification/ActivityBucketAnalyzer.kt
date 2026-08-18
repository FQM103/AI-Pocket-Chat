package com.situ.aichat.notification

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户活跃时段桶分析：「这个人平时几点在玩手机」——回退支（无日程）把主动消息的候选时刻往这些桶上靠
 * （[NotificationTimePlanner] 消费）。日程支不用它：时刻挂事件本身（V3）。
 *
 * **自 [NotificationScheduler] 只搬不改迁出**（R1 🟡-1 行数治理：该文件 633 行越 🔴 硬上限 600）。
 * 行为字节级不变，既有桶单测断言零改随迁（`ActivityBucketAnalyzerTest`）。
 */
@Singleton
class ActivityBucketAnalyzer @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
) {

    /** 最近 14 天用户消息的活跃时段桶（对齐 iOS analyzeActivityBucketMinutes）。 */
    suspend fun analyzeActivityBucketMinutes(characterId: String, now: Long, zone: ZoneId): List<Int> {
        val conversations = conversationDao.getByCharacter(characterId)
        if (conversations.isEmpty()) return DEFAULT_BUCKETS
        val fourteenDaysAgo = now - 14L * 24 * 60 * 60 * 1000
        val timestamps = conversations
            .flatMap { messageDao.recentSinceForSummary(it.uuid, fourteenDaysAgo, ACTIVITY_FETCH_LIMIT) }
            .filter { it.roleRaw == ROLE_USER }
            .map { it.timestamp }
        return computeActivityBuckets(timestamps, now, zone)
    }

    companion object {
        private const val ROLE_USER = "user"
        private const val ACTIVITY_FETCH_LIMIT = 2000
        private val DEFAULT_BUCKETS = listOf(12 * 60, 21 * 60)

        /**
         * 用户消息时间戳 → 活跃 30 分钟桶（按近因加权，取前 4）。对齐 iOS analyzeActivityBucketMinutes。
         * 数据不足（总权重 < 5）或为空时回退 [12:00, 21:00]。纯函数。
         */
        internal fun computeActivityBuckets(timestamps: List<Long>, now: Long, zone: ZoneId): List<Int> {
            if (timestamps.isEmpty()) return DEFAULT_BUCKETS
            val bucketScores = mutableMapOf<Int, Double>()
            for (ts in timestamps) {
                val zdt = Instant.ofEpochMilli(ts).atZone(zone)
                val bucketMinute = zdt.hour * 60 + if (zdt.minute >= 30) 30 else 0
                val ageInDays = ((now - ts) / (24L * 60 * 60 * 1000)).coerceIn(0L, 13L)
                val recencyWeight = maxOf(0.35, 1.0 - (ageInDays * 0.05))
                bucketScores[bucketMinute] = (bucketScores[bucketMinute] ?: 0.0) + recencyWeight
            }
            if (bucketScores.values.sum() < 5.0) return DEFAULT_BUCKETS
            return bucketScores.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key })
                .take(4)
                .map { it.key }
        }
    }
}
