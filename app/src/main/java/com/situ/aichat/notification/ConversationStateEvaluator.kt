package com.situ.aichat.notification

import com.situ.aichat.data.local.dao.ConversationDao
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.dao.NotificationDeliveryDao
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 角色当前对话相位（主动通知到点闸门与现做 prompt 共用；判定见 [ConversationStateEvaluator.resolvePhase]）。 */
enum class ConversationPhase { HOT, AFTERGLOW, SAME_DAY, OVERNIGHT, NORMAL, DISTANT_EARLY, DISTANT_LATE, LONG_ABSENCE }

/** 某角色此刻的对话状态快照（全本地推导，不写库）。 */
data class ConversationState(
    val phase: ConversationPhase,
    /** 距最后一条消息（任意方）的分钟数；null = 该角色从未有消息。 */
    val minutesSinceLastMessage: Long?,
    /** 最后一条消息是否用户发的；null = 从未有消息。 */
    val lastMessageFromUser: Boolean?,
    /** 最后一条用户消息之后已投递的排程主动通知条数（用户一回复自然归零）。 */
    val unansweredProactiveCount: Int,
    /** 距最后一条**用户**消息的自然日差（降频闸口径）；null = 用户从未说过话（视同 ≥15 天档）。 */
    val daysSinceLastUserMessage: Int?,
    /** 竞态终查快照锚点：最后一条消息的 messageUUID。 */
    val latestMessageUuid: String?,
)

/**
 * 全本地推导「该角色当前对话状态」，供排程侧与到点侧共用（主动通知真实感改造 C2）。
 *
 * 只读库、绝不写库。相位判定用「最后一条消息（任意方）」；降频闸的天数用「最后一条**用户**消息」——
 * 二者口径不同是有意的（图纸 §3.5 步骤 5）：相位管「现在打扰合不合适」，天数管「TA 多久没理我了」。
 *
 * 连发/降频计数一律从 `notification_delivery_records` 推导，不设内存计数器——抗进程死亡（图纸 D-2）。
 */
@Singleton
class ConversationStateEvaluator @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val deliveryDao: NotificationDeliveryDao,
) {

    /**
     * 推导 [characterUuid] 在 [now] 时刻的对话状态。
     * 会话全部归档也照常纳入（归档 ≠ 失忆，图纸 E11）。
     */
    suspend fun evaluate(
        characterUuid: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ConversationState {
        val conversationUuids = conversationDao.getByCharacter(characterUuid).map { it.uuid }
        if (conversationUuids.isEmpty()) {
            // E10 首装 / 新角色：空列表不入 IN 查询，直接给「久别」空态。
            return ConversationState(
                phase = ConversationPhase.LONG_ABSENCE,
                minutesSinceLastMessage = null,
                lastMessageFromUser = null,
                unansweredProactiveCount = 0,
                daysSinceLastUserMessage = null,
                latestMessageUuid = null,
            )
        }
        val latest = messageDao.latestNonSystemAcross(conversationUuids)
        val lastUserTs = messageDao.latestUserTimestampAcross(conversationUuids)
        return ConversationState(
            phase = resolvePhase(latest?.timestamp, now, zone),
            minutesSinceLastMessage = latest?.let { (now - it.timestamp) / 60_000 },
            lastMessageFromUser = latest?.let { it.roleRaw == ROLE_USER },
            unansweredProactiveCount = deliveryDao.countDeliveredSince(characterUuid, lastUserTs ?: 0L),
            daysSinceLastUserMessage = lastUserTs?.let { daysBetween(it, now, zone) },
            latestMessageUuid = latest?.messageUUID,
        )
    }

    /**
     * 该角色最后一条非系统非空消息的 uuid（无消息/无会话 → null）。
     * 竞态终查专用轻量快照（图纸 §3.2 g）：与 [evaluate] 产出的 [ConversationState.latestMessageUuid] 前后比对，
     * 不等即说明「现做期间有人说了新话」→ 整条丢弃。
     */
    suspend fun latestMessageUuid(characterUuid: String): String? {
        val conversationUuids = conversationDao.getByCharacter(characterUuid).map { it.uuid }
        if (conversationUuids.isEmpty()) return null
        return messageDao.latestNonSystemAcross(conversationUuids)?.messageUUID
    }

    /**
     * 相位判定（纯函数，internal 便于单测）。锁定口径（图纸 §3.5）：
     * 从未有消息 → LONG_ABSENCE；≤30min → HOT；≤120min → AFTERGLOW；
     * 其余按自然日差 d：0=SAME_DAY / 1=OVERNIGHT / 2..3=NORMAL / 4..7=DISTANT_EARLY / 8..14=DISTANT_LATE / ≥15=LONG_ABSENCE。
     *
     * 相位只取 [lastMessageTs]（「最后一条消息·任意方」，图纸 §3.5 步骤 5）；「最后一条**用户**消息」的两处用途
     * （连发计数基准 / [ConversationState.daysSinceLastUserMessage]）都在 [evaluate] 里，故本函数不收该参
     * （§3.5 签名已由作者 R1 修订）。
     */
    internal fun resolvePhase(
        lastMessageTs: Long?,
        now: Long,
        zone: ZoneId,
    ): ConversationPhase {
        if (lastMessageTs == null) return ConversationPhase.LONG_ABSENCE
        val minutes = (now - lastMessageTs) / 60_000
        if (minutes <= HOT_MAX_MINUTES) return ConversationPhase.HOT
        if (minutes <= AFTERGLOW_MAX_MINUTES) return ConversationPhase.AFTERGLOW
        return when (daysBetween(lastMessageTs, now, zone)) {
            0 -> ConversationPhase.SAME_DAY
            1 -> ConversationPhase.OVERNIGHT
            in 2..3 -> ConversationPhase.NORMAL
            in 4..7 -> ConversationPhase.DISTANT_EARLY
            in 8..14 -> ConversationPhase.DISTANT_LATE
            else -> ConversationPhase.LONG_ABSENCE
        }
    }

    /** 自然日差（LocalDate 差，非 24h 整除）：昨晚 23:50 → 今早 00:10 算 1 天。 */
    private fun daysBetween(fromMillis: Long, now: Long, zone: ZoneId): Int {
        val fromDay = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(fromDay, today).toInt()
    }

    companion object {
        private const val ROLE_USER = "user"

        /** 热聊窗：距最后一条消息 ≤30min = 正聊着，绝不插话（图纸 §9 锁定）。 */
        internal const val HOT_MAX_MINUTES = 30L

        /** 余温窗：≤120min = 刚聊完，静默（图纸 §9 锁定）。 */
        internal const val AFTERGLOW_MAX_MINUTES = 120L
    }
}
