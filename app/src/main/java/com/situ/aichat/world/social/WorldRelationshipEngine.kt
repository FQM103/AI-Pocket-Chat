package com.situ.aichat.world.social

import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.world.SettlementDay
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.WorldSeeds
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.atlas.WorldResidents
import com.situ.aichat.world.social.WorldRelationshipBeats as Beats
import com.situ.aichat.world.social.WorldRelationshipTypes as Types
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 逐日角色↔角色互动生成引擎（契约 §8.B / W4 图纸 §3.2–3.4·随机纪律与数值锁死·图纸 §9 禁改）。
 *
 * 每对每日一条种子流（[WorldSeeds]·`derive(seed,"rel",fnv1a64(pairKey) xor epochDay)`）——是否出事 / 选种类 /
 * 选模板 / 不对称系数全从此流按固定顺序抽。事件弧线为主形态（quarrel 三步跨日）；双向不对称落轴；里程碑
 * 幂等；渐远漂移；结痂压缩（委托 [WorldRelationshipCompactor]）。**幂等门**（[WorldSocialDao.eventByUuid]）+
 * 每对写作包进 `db.withTransaction` = 进程死重跑不双扣（图纸 §3.4·E2）。
 *
 * 落库直经 [com.situ.aichat.data.local.dao.WorldSocialDao]；返回值 = 有戏剧价值拍的 `world_event` 镜像
 * （由 Coordinator 落库）。**不写任何角色 mood / 记忆列**（联动闭环属 W5）。
 */
@Singleton
class WorldRelationshipEngine @Inject constructor(
    private val db: AppDatabase,
    private val compactor: WorldRelationshipCompactor,
) {
    private val dao = db.worldSocialDao()
    private val ledger = WorldRelationshipLedger(dao)

    /**
     * 结算一个本地日：落关系事件/边（直落 DB）。**不返回镜像**——W5 起 world_event 镜像由
     * [com.situ.aichat.world.link.WorldMirrorDeriver] 从已落库关系事件重派生（D13 崩溃窗闭窗·图纸 §3.3）。
     */
    suspend fun settleDay(
        state: WorldStateEntity,
        settings: AppSettings,
        participants: List<CharacterEntity>,
        day: SettlementDay,
    ) {
        if (participants.size < 2) return
        val zone = WorldClock.resolveZone(state.userTimezoneId)
        val createdEpochDay = WorldClock.localDateOf(state.createdAt, zone).toEpochDay()
        val newbie = (day.epochDay - createdEpochDay) < NEWBIE_WINDOW_DAYS
        val romance = Types.romanceAllowed(settings)
        val atlas = WorldAtlas.of(state.seed)
        val sorted = participants.sortedBy { it.uuid }
        val noon = noonMs(day.date, zone)

        // Phase A：只读分类——每对建流、掷签，归入弧线推进 / 命中出事 / 渐远漂移。
        // hasEdge / 弧线 / 漂移一律从「截至当日正午」的事件推（happenedAt ≤ noon）——重跑更早的日子时不被后续
        // 天的事件污染（掷签阈值/命中集/日上限稳定 → 已产出者经幂等门跳过 = 进程死重跑不双扣·E2）。
        val arcs = mutableListOf<Plan>()
        val drifts = mutableListOf<Plan>()
        val hits = mutableListOf<Plan>()
        for (i in sorted.indices) {
            for (j in i + 1 until sorted.size) {
                val a = sorted[i]
                val b = sorted[j]
                val pairKey = WorldIds.pairKey(a.uuid, b.uuid) // a.uuid|b.uuid（a<b）
                val fnv = WorldSeeds.fnv1a64(pairKey)
                val rand = WorldSeeds.randomOf(WorldSeeds.derive(state.seed, "rel", fnv xor day.epochDay))
                if (dao.getEdge(a.uuid, b.uuid)?.dormant == true || dao.getEdge(b.uuid, a.uuid)?.dormant == true) {
                    continue // 休眠边整对跳过
                }
                val asOf = dao.eventsForPair(pairKey).filter { it.happenedAt <= noon }
                val hasEdge = asOf.isNotEmpty()
                if (hasEdge && isArcInProgress(asOf)) {
                    arcs += Plan(a, b, pairKey, rand, hasEdge, fnv)
                    continue // 弧线优先·当日必推进、不掷出事签
                }
                val p = (if (hasEdge) EDGE_EVENT_PROB else MEET_PROB) * (if (newbie) NEWBIE_MULTIPLIER else 1)
                if (rand.nextDouble() < p) {
                    hits += Plan(a, b, pairKey, rand, hasEdge, fnv)
                } else if (hasEdge && isDriftDue(asOf, day.epochDay, zone)) {
                    drifts += Plan(a, b, pairKey, rand, hasEdge, fnv)
                }
            }
        }

        // Phase B：执行——弧线推进 / 漂移不计日上限；出事命中按 fnv1a64 升序取前 3。
        val touched = mutableListOf<Plan>()
        for (plan in arcs) { executeArc(plan, romance, atlas, day, zone); touched += plan }
        for (plan in drifts) { executeDrift(plan, day, zone); touched += plan }
        for (plan in hits.sortedBy { it.fnv }.take(MAX_NEW_EVENTS_PER_DAY)) {
            executeEvent(plan, romance, atlas, day, zone)
            touched += plan
        }

        // Phase C：结痂压缩（先日事件后压缩·每个动过的对查超量）。
        for (plan in touched) {
            db.withTransaction {
                compactor.compactIfNeeded(plan.pairKey, plan.a.uuid, plan.b.uuid, plan.a.name, plan.b.name, day.daySeed, noon)
            }
        }
    }

    // MARK: - 出事（first_meet / outing / help / gossip / quarrel_start）

    private suspend fun executeEvent(
        plan: Plan,
        romance: Boolean,
        atlas: WorldAtlas.Atlas,
        day: SettlementDay,
        zone: ZoneId,
    ) = db.withTransaction {
        val pairKey = plan.pairKey
        val primaryUuid = ledger.relEventUuid(pairKey, day.daySeed, 0)
        if (dao.eventByUuid(primaryUuid) != null) return@withTransaction // 幂等门
        val rand = plan.rand
        val happenedAt = noonMs(day.date, zone)

        val kind = if (!plan.hasEdge) Beats.FIRST_MEET else Beats.edgeEventKind(rand.nextDouble())
        val actorIsA = rand.nextBoolean()
        val actorChar = if (actorIsA) plan.a else plan.b
        val targetChar = if (actorIsA) plan.b else plan.a
        val u = rand.nextDouble()
        val beat = Beats.AXES.getValue(kind)
        val templates = Beats.templatesOf(kind)
        val template = templates[rand.nextInt(templates.size)]
        val spot = if (template.contains(WorldRelationshipLedger.SPOT_SLOT)) pickSpot(actorChar, atlas, rand) else ""
        var colorMain = beat.colors[rand.nextInt(beat.colors.size)]
        val colorReverse = beat.colors[rand.nextInt(beat.colors.size)]
        if (romance && kind == Beats.OUTING) colorMain = sweetSpot(colorMain, actorChar.uuid, targetChar.uuid, rand)
        val summary = ledger.fill(template, actorChar.name, targetChar.name, spot)
        val arcId = if (kind == Beats.QUARREL_START) "arc_q_${pairKey}_${day.epochDay}" else null

        ledger.applyPrimary(kind, primaryUuid, actorChar, targetChar, colorMain, colorReverse, u, spot, summary, arcId, beat, pairKey, day.daySeed, happenedAt, rand, romance)
    }

    // MARK: - 弧线推进（quarrel 三步：start→cold(1–2)→mend·复用起始朝向）

    private suspend fun executeArc(
        plan: Plan,
        romance: Boolean,
        atlas: WorldAtlas.Atlas,
        day: SettlementDay,
        zone: ZoneId,
    ) = db.withTransaction {
        val pairKey = plan.pairKey
        val stepUuid = ledger.relEventUuid(pairKey, day.daySeed, 0)
        if (dao.eventByUuid(stepUuid) != null) return@withTransaction // 幂等门
        val happenedAt = noonMs(day.date, zone)
        val asOf = dao.eventsForPair(pairKey).filter { it.happenedAt <= happenedAt } // 截至当日重建弧线（重跑安全）
        val newest = asOf.lastOrNull() ?: return@withTransaction
        val arcId = newest.arcId ?: return@withTransaction
        val arcEvents = asOf.filter { it.arcId == arcId }
        val start = arcEvents.firstOrNull { it.kindRaw == Beats.QUARREL_START } ?: return@withTransaction
        val actorChar = if (start.actorId == plan.a.uuid) plan.a else plan.b
        val targetChar = if (start.actorId == plan.a.uuid) plan.b else plan.a
        val rand = plan.rand

        val step = when {
            newest.kindRaw == Beats.QUARREL_START -> Beats.QUARREL_COLD // 首个冷战日必然
            arcEvents.count { it.kindRaw == Beats.QUARREL_COLD } >= 2 -> Beats.QUARREL_MEND // 冷战封顶 2 天
            rand.nextDouble() < 0.5 -> Beats.QUARREL_COLD // 续冷
            else -> Beats.QUARREL_MEND
        }
        val u = rand.nextDouble()
        val beat = Beats.AXES.getValue(step)
        val templates = Beats.templatesOf(step)
        val template = templates[rand.nextInt(templates.size)]
        val spot = if (template.contains(WorldRelationshipLedger.SPOT_SLOT)) pickSpot(actorChar, atlas, rand) else ""
        var colorMain = beat.colors[rand.nextInt(beat.colors.size)]
        val colorReverse = beat.colors[rand.nextInt(beat.colors.size)]
        if (romance && step == Beats.QUARREL_MEND) colorMain = sweetSpot(colorMain, actorChar.uuid, targetChar.uuid, rand)
        val summary = ledger.fill(template, actorChar.name, targetChar.name, spot)

        ledger.applyPrimary(step, stepUuid, actorChar, targetChar, colorMain, colorReverse, u, spot, summary, arcId, beat, pairKey, day.daySeed, happenedAt, rand, romance)
    }

    // MARK: - 渐远漂移（静默·双向 closeness −1 地板 10·不进镜像·不计日上限）

    private suspend fun executeDrift(plan: Plan, day: SettlementDay, zone: ZoneId) {
        db.withTransaction {
            val driftUuid = ledger.relEventUuid(plan.pairKey, day.daySeed, 0)
            if (dao.eventByUuid(driftUuid) != null) return@withTransaction // 幂等门
            val happenedAt = noonMs(day.date, zone)
            listOf(plan.a.uuid to plan.b.uuid, plan.b.uuid to plan.a.uuid).forEach { (f, t) ->
                dao.getEdge(f, t)?.let { e ->
                    dao.upsertEdge(
                        e.copy(
                            // 地板语义 = drift 只往下、走到 10 停；已低于地板的边（可达：first_meet 反向折减 u→0 初值 9）不动它。
                            closeness = if (e.closeness > Types.DRIFT_FLOOR) {
                                (e.closeness + Beats.DRIFT_CLOSENESS_DELTA).coerceAtLeast(Types.DRIFT_FLOOR)
                            } else {
                                e.closeness
                            },
                            trajectoryRaw = Types.trajectoryFor(Beats.DRIFT_CLOSENESS_DELTA, 0, e.trajectoryRaw),
                            updatedAt = happenedAt,
                        ),
                    )
                }
            }
            dao.upsertEvent(
                WorldRelationshipEventEntity(
                    uuid = driftUuid, pairKey = plan.pairKey, actorId = plan.a.uuid, targetId = plan.b.uuid,
                    kindRaw = Beats.DRIFT, arcId = null,
                    summary = ledger.fill(Beats.DRIFT_TEMPLATE, plan.a.name, plan.b.name, ""),
                    happenedAt = happenedAt, settledAt = happenedAt,
                ),
            )
        }
    }

    // MARK: - 纯辅助

    /** 恋爱色彩甜点：主方向 closeness ≥ 60 时追加一次 0.10 掷签，中 → 主方向色彩改为「心动」。 */
    private suspend fun sweetSpot(colorMain: String, actorId: String, targetId: String, rand: Random): String {
        val closeness = dao.getEdge(actorId, targetId)?.closeness ?: 0
        return if (closeness >= Beats.SWEETSPOT_CLOSENESS && rand.nextDouble() < Beats.SWEETSPOT_PROB) Types.COLOR_HEARTBEAT else colorMain
    }

    /** spot：主动方家乡为精修城 → 其地点名池一抽；否则环境居民 spot 池一抽。 */
    private fun pickSpot(actorChar: CharacterEntity, atlas: WorldAtlas.Atlas, rand: Random): String {
        val city = atlas.cityById(actorChar.worldHomeCityId)
        val places = if (city?.curated == true) atlas.placesOf(actorChar.worldHomeCityId).map { it.name } else emptyList()
        val pool = if (places.isNotEmpty()) places else WorldResidents.SPOTS
        return pool[rand.nextInt(pool.size)]
    }

    /** 进行中弧线判定：截至当日最新事件为拌嘴/冷战（mend 一落 → 最新变 mend → 弧线终结）。 */
    private fun isArcInProgress(asOf: List<WorldRelationshipEventEntity>): Boolean {
        val newest = asOf.lastOrNull() ?: return false
        return newest.kindRaw == Beats.QUARREL_START || newest.kindRaw == Beats.QUARREL_COLD
    }

    /** 渐远漂移到期：距最近**实质**事件（非 drift/compact）≥14 天且每满 7 天一次（§3.4·E9）。 */
    private fun isDriftDue(asOf: List<WorldRelationshipEventEntity>, epochDay: Long, zone: ZoneId): Boolean {
        val lastSub = asOf.lastOrNull { it.kindRaw != Beats.DRIFT && it.kindRaw != Beats.COMPACT } ?: return false
        val gap = epochDay - WorldClock.localDateOf(lastSub.happenedAt, zone).toEpochDay()
        return gap >= 14 && gap % 7 == 0L
    }

    private fun noonMs(date: LocalDate, zone: ZoneId): Long =
        date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    /** 一对当日的处理计划（携带该对流·供 Phase B 续抽）。 */
    private data class Plan(
        val a: CharacterEntity,
        val b: CharacterEntity,
        val pairKey: String,
        val rand: Random,
        val hasEdge: Boolean,
        val fnv: Long,
    )

    companion object {
        /** 无边对当日相识概率（§3.2）。 */
        const val MEET_PROB = 0.10

        /** 有边对当日出事概率（§3.2）。 */
        const val EDGE_EVENT_PROB = 0.08

        /** 新手微热窗（世界建成 < 此天数 → 概率翻倍·§3.2）。 */
        const val NEWBIE_WINDOW_DAYS = 7

        /** 新手微热倍数（§3.2）。 */
        const val NEWBIE_MULTIPLIER = 2

        /** 全局日上限：每日新「出事」对 ≤ 3（弧线推进/漂移/里程碑不计入·§3.2 护栏#6）。 */
        const val MAX_NEW_EVENTS_PER_DAY = 3
    }
}
