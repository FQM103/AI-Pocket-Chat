package com.situ.aichat.world.social

import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.social.WorldRelationshipBeats as Beats
import com.situ.aichat.world.social.WorldRelationshipTypes as Types
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 关系事件的**落轴簇**（W4 图纸 §3.3–3.4·由 [WorldRelationshipEngine] 于 R1 返工 🟡-2「只搬不改」抽出）。
 *
 * 一处事件命中后共用的收尾链：双向不对称落轴（[applyPrimary]/[edgeAfter]/[fold]）→ 记事 → 里程碑
 * （[checkMilestones]/[addTypeBoth]）+ 文案填充/uuid 派生纯函数（[fill]/[relEventUuid]）。**行为字节级
 * 等同抽出前**——数值/uuid 串/不对称公式一字未动（图纸 §9 禁改）。
 *
 * W5 起 world_event 镜像不再由本类构造：改由 [com.situ.aichat.world.link.WorldMirrorDeriver] 从**已落库的
 * 关系事件重派生**（D13 崩溃窗闭窗），`buildMirror`/`MIRROR_KINDS` 已「只搬不改」移去那里；故 [applyPrimary]/
 * [checkMilestones] 只落轴/记事、不再返回镜像（改返 Unit）。
 *
 * 调用方（引擎）负责把 [applyPrimary] 包进 `db.withTransaction`（原子·防进程死半写）；本类只经
 * [WorldSocialDao] 落库，不开事务、不调时钟、不接 UI。
 */
class WorldRelationshipLedger(
    private val dao: WorldSocialDao,
) {

    // MARK: - 落轴 + 记事 + 里程碑（出事与弧线共用·镜像已移交 WorldMirrorDeriver）

    suspend fun applyPrimary(
        kind: String,
        primaryUuid: String,
        actorChar: CharacterEntity,
        targetChar: CharacterEntity,
        colorMain: String,
        colorReverse: String,
        u: Double,
        spot: String,
        summary: String,
        arcId: String?,
        beat: Beats.BeatAxes,
        pairKey: String,
        daySeed: Long,
        happenedAt: Long,
        rand: Random,
        romance: Boolean,
    ) {
        val actorId = actorChar.uuid
        val targetId = targetChar.uuid
        val isFirstMeet = kind == Beats.FIRST_MEET
        val f = 0.5 + 0.5 * u
        val mainEdge = edgeAfter(dao.getEdge(actorId, targetId), actorId, targetId, beat.closeness, beat.trust, beat.tension, colorMain, isFirstMeet, spot, happenedAt)
        val revEdge = edgeAfter(dao.getEdge(targetId, actorId), targetId, actorId, fold(beat.closeness, f), fold(beat.trust, f), fold(beat.tension, f), colorReverse, isFirstMeet, spot, happenedAt)
        dao.upsertEdge(mainEdge)
        dao.upsertEdge(revEdge)
        dao.upsertEvent(WorldRelationshipEventEntity(primaryUuid, pairKey, actorId, targetId, kind, arcId, summary, happenedAt, happenedAt))

        checkMilestones(mainEdge, revEdge, actorChar, targetChar, pairKey, daySeed, happenedAt, romance, rand)
    }

    /** 一条边的新态：主方向传满额增减、反方向传折减后增减；first_meet 重置类型/渊源，否则沿用。 */
    private fun edgeAfter(
        existing: WorldRelationshipEntity?,
        fromId: String,
        toId: String,
        closenessDelta: Int,
        trustDelta: Int,
        tensionDelta: Int,
        color: String,
        isFirstMeet: Boolean,
        spot: String,
        happenedAt: Long,
    ): WorldRelationshipEntity {
        val types = if (isFirstMeet) listOf(Types.TYPE_ACQUAINTED) else StringListJson.decode(existing?.typesJson ?: "")
        val origin = if (isFirstMeet) "相识于$spot" else (existing?.origin ?: "")
        return WorldRelationshipEntity(
            fromId = fromId,
            toId = toId,
            typesJson = StringListJson.encode(types),
            closeness = Types.clampAxis((existing?.closeness ?: 0) + closenessDelta),
            trust = Types.clampAxis((existing?.trust ?: 0) + trustDelta),
            tension = Types.clampAxis((existing?.tension ?: 0) + tensionDelta),
            colorRaw = color,
            trajectoryRaw = Types.trajectoryFor(closenessDelta, tensionDelta, existing?.trajectoryRaw ?: Types.TRAJ_STABLE),
            bond = existing?.bond ?: "",
            origin = origin,
            dormant = existing?.dormant ?: false,
            updatedAt = happenedAt,
        )
    }

    /**
     * 里程碑（主方向 closeness 首过 35→朋友、70→密友·各一次·以 types 已含为幂等判据·类型追加双向边）。
     *
     * 尾步追加**恋爱里程碑**（W10 决策 39）：romance 门开 + 主向色彩=心动 + 双向 closeness≥[Types.ROMANCE_CLOSENESS]
     * + 未曾是恋人 → types 双向 +恋人 + 一条 MILESTONE 事件。**romance=false 时首项短路——零额外 rand 抽取、
     * 与旧行为字节级等同**（E13 金标不动·图纸 §3.6/§9）。恋爱门事后关闭：已成恋人保留类型、不再新产出。
     */
    private suspend fun checkMilestones(
        mainEdge: WorldRelationshipEntity,
        revEdge: WorldRelationshipEntity,
        actorChar: CharacterEntity,
        targetChar: CharacterEntity,
        pairKey: String,
        daySeed: Long,
        happenedAt: Long,
        romance: Boolean,
        rand: Random,
    ) {
        var seq = 1
        var types = StringListJson.decode(mainEdge.typesJson)
        val steps = listOf(
            Triple(Types.MILESTONE_FRIEND_CLOSENESS, Types.TYPE_FRIEND, Beats.MILESTONE_FRIEND_TEMPLATES),
            Triple(Types.MILESTONE_CLOSE_CLOSENESS, Types.TYPE_CLOSE, Beats.MILESTONE_CLOSE_TEMPLATES),
        )
        for ((threshold, type, templates) in steps) {
            if (mainEdge.closeness < threshold || types.contains(type)) continue
            addTypeBoth(actorChar.uuid, targetChar.uuid, type)
            types = types + type
            val s = fill(templates[rand.nextInt(templates.size)], actorChar.name, targetChar.name, "")
            val uuid = relEventUuid(pairKey, daySeed, seq++)
            dao.upsertEvent(WorldRelationshipEventEntity(uuid, pairKey, actorChar.uuid, targetChar.uuid, Beats.MILESTONE, null, s, happenedAt, happenedAt))
        }
        if (romance && mainEdge.colorRaw == Types.COLOR_HEARTBEAT &&
            mainEdge.closeness >= Types.ROMANCE_CLOSENESS && revEdge.closeness >= Types.ROMANCE_CLOSENESS &&
            !types.contains(Types.TYPE_ROMANCE)
        ) {
            addTypeBoth(actorChar.uuid, targetChar.uuid, Types.TYPE_ROMANCE)
            val s = fill(Beats.MILESTONE_ROMANCE_TEMPLATES[rand.nextInt(Beats.MILESTONE_ROMANCE_TEMPLATES.size)], actorChar.name, targetChar.name, "")
            dao.upsertEvent(WorldRelationshipEventEntity(relEventUuid(pairKey, daySeed, seq), pairKey, actorChar.uuid, targetChar.uuid, Beats.MILESTONE, null, s, happenedAt, happenedAt))
        }
    }

    private suspend fun addTypeBoth(actorId: String, targetId: String, type: String) {
        listOf(actorId to targetId, targetId to actorId).forEach { (f, t) ->
            val e = dao.getEdge(f, t) ?: return@forEach
            val ts = StringListJson.decode(e.typesJson)
            if (!ts.contains(type)) dao.upsertEdge(e.copy(typesJson = StringListJson.encode(ts + type)))
        }
    }

    // MARK: - 纯辅助

    fun fill(template: String, a: String, b: String, spot: String): String =
        template.replace("{a}", a).replace("{b}", b).replace(SPOT_SLOT, spot)

    private fun fold(delta: Int, f: Double): Int = (delta * f).roundToInt()

    fun relEventUuid(pairKey: String, daySeed: Long, seq: Int): String =
        UUID.nameUUIDFromBytes("world:rel:$pairKey:$daySeed:$seq".toByteArray()).toString()

    companion object {
        const val SPOT_SLOT = "{spot}"
    }
}
