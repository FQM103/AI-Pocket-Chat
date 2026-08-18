package com.situ.aichat.world.social

import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 关系事件流水的**结痂压缩**（契约 §5 结痂 / §8.B / W4 图纸 §3.6·参数逐字锁死·图纸 §9 禁改）。
 *
 * 超量事件流水折叠进「渊源」——角色像真人一样「细节模糊、结论记得」：某对事件 > [COMPACT_THRESHOLD] 条时，
 * 把最老的一批压成一句结痂追加到两方向边的 origin，删掉被压流水，落一条 [WorldRelationshipBeats.COMPACT]
 * 标记事件（幂等门保护）。走向由被压事件的种类比近似判（因被压区间首尾 closeness 不可得·§3.6 简化锁死）。
 *
 * 调用方（[WorldRelationshipEngine]）负责把本方法包进 `db.withTransaction`（原子·防进程死半写）。
 */
@Singleton
class WorldRelationshipCompactor @Inject constructor(
    private val socialDao: WorldSocialDao,
) {

    /**
     * 若该对事件超量则压缩一次。[aId]/[bId] = pairKey 两端（uuid 字典序小→大）、[aName]/[bName] 对应名。
     * @return 是否发生了压缩。
     */
    suspend fun compactIfNeeded(
        pairKey: String,
        aId: String,
        bId: String,
        aName: String,
        bName: String,
        daySeed: Long,
        happenedAt: Long,
    ): Boolean {
        val count = socialDao.countEventsForPair(pairKey)
        if (count <= COMPACT_THRESHOLD) return false
        val n = count - KEEP_NEWEST
        val toCompact = socialDao.oldestEventsForPair(pairKey, n)
        if (toCompact.isEmpty()) return false

        val uuid = compactUuid(pairKey, daySeed)
        if (socialDao.eventByUuid(uuid) != null) return false // 幂等门：今日已压过不重压

        val trend = trendOf(toCompact)
        val sentence = WorldRelationshipBeats.COMPACT_TEMPLATE
            .replace("{a}", aName)
            .replace("{b}", bName)
            .replace("{n}", n.toString())
            .replace("{trend}", trend)

        appendOrigin(aId, bId, sentence)
        appendOrigin(bId, aId, sentence)
        socialDao.deleteEventsByUuids(toCompact.map { it.uuid })
        socialDao.upsertEvent(
            WorldRelationshipEventEntity(
                uuid = uuid,
                pairKey = pairKey,
                actorId = aId,
                targetId = bId,
                kindRaw = WorldRelationshipBeats.COMPACT,
                arcId = null,
                summary = sentence,
                happenedAt = happenedAt,
                settledAt = happenedAt,
            ),
        )
        return true
    }

    /**
     * 走向（§3.6 锁死）：drift 占比 >50% → 淡了些；否则 正向(outing/help)多 → 更近了；否则(quarrel 系不少于正向) → 起起伏伏。
     */
    private fun trendOf(events: List<WorldRelationshipEventEntity>): String {
        val drift = events.count { it.kindRaw == WorldRelationshipBeats.DRIFT }
        if (drift * 2 > events.size) return WorldRelationshipBeats.TREND_FADED
        val positive = events.count {
            it.kindRaw == WorldRelationshipBeats.OUTING || it.kindRaw == WorldRelationshipBeats.HELP
        }
        val quarrel = events.count {
            it.kindRaw == WorldRelationshipBeats.QUARREL_START ||
                it.kindRaw == WorldRelationshipBeats.QUARREL_COLD ||
                it.kindRaw == WorldRelationshipBeats.QUARREL_MEND
        }
        return if (positive > quarrel) WorldRelationshipBeats.TREND_CLOSER else WorldRelationshipBeats.TREND_UPS_AND_DOWNS
    }

    /** 结痂句追加到该向边 origin 尾部（原文 + ；+ 结痂句·超 [ORIGIN_MAX] 字只留最新 [ORIGIN_MAX]）。 */
    private suspend fun appendOrigin(fromId: String, toId: String, sentence: String) {
        val edge = socialDao.getEdge(fromId, toId) ?: return
        val merged = if (edge.origin.isEmpty()) sentence else "${edge.origin}；$sentence"
        val trimmed = if (merged.length > ORIGIN_MAX) merged.takeLast(ORIGIN_MAX) else merged
        socialDao.upsertEdge(edge.copy(origin = trimmed))
    }

    private fun compactUuid(pairKey: String, daySeed: Long): String =
        UUID.nameUUIDFromBytes("world:rel:compact:$pairKey:$daySeed".toByteArray()).toString()

    companion object {
        /** 触发阈值：某对事件 > 40 条时压缩（§3.6）。 */
        const val COMPACT_THRESHOLD = 40

        /** 保留最新条数（其余最老的被压）（§3.6）。 */
        const val KEEP_NEWEST = 24

        /** origin 上限字数（超出只留最新此数）（§3.6）。 */
        const val ORIGIN_MAX = 500
    }
}
