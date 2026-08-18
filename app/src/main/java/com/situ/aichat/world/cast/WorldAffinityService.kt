package com.situ.aichat.world.cast

import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.WorldIds
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 眼缘服务（W6 图纸 §3.2/§3.3·契约 §11·决策 24）：把「认识一个原住民」的运行态——发现 / 双燃料眼缘 / 偶遇 /
 * 引荐——落到 `world_native_state`。合计 = `round(narrativeFuel×nw + giftFuel×gw)`（下限 0）；**不衰减铁则**：
 * 全类**不存在任何衰减代码路径**（缺席多久眼缘一分不掉·测试反证）。朦胧节点只吐短语、绝不吐数字/百分比
 * （决策 24：数值条会把结识一个人变成刷副本）——[WorldAffinityStage] 承载四档短语。
 *
 * 纯函数 [affinityOf]/[stageOf] 在 companion（`internal`·招募服务与 T1 共用·无需注入）；实例方法改运行态、经
 * [WorldNativeDao] 落库（偶遇的本地日判定取 [WorldDao] 世界态时区）。触发时机（发现/偶遇/引荐）归 W9/W12，本类只提供幂等 API。
 */
@Singleton
class WorldAffinityService @Inject constructor(
    private val nativeDao: WorldNativeDao,
    private val worldDao: WorldDao,
) {

    /**
     * 播种（§3.3）：为 [WorldNativeRoster.ALL] 中**尚无状态行**的 slug 插初始行（未发现·燃料 0·
     * `currentCityId = def.cityId`）。**已有行绝不动**（幂等·绝不重置燃料）；新版本追加 def 时只补新行。
     */
    suspend fun ensureSeeded() {
        val existing = nativeDao.getAll().mapTo(HashSet()) { it.nativeId }
        val missing = WorldNativeRoster.ALL.mapNotNull { def ->
            val id = WorldIds.nativeId(def.slug)
            if (id in existing) null
            else WorldNativeStateEntity(nativeId = id, currentCityId = def.cityId)
        }
        if (missing.isNotEmpty()) nativeDao.upsertAll(missing)
    }

    /** 发现（§3.3）：置 `discovered=true, discoveredAt=atMs`（已发现 = no-op·状态缺失 = no-op）。 */
    suspend fun discover(nativeId: String, atMs: Long) {
        val s = nativeDao.get(nativeId) ?: return
        if (s.discovered) return
        nativeDao.upsert(s.copy(discovered = true, discoveredAt = atMs))
    }

    /**
     * 偶遇（§3.3）：未发现先 `discover`；**同一本地日重复偶遇不重计**（`lastEncounterAt` 与本次同本地日 →
     * 只更时间戳、不加燃料不加计数）；跨日正常累计 `encounterCount+1`、`narrativeFuel += ENCOUNTER_FUEL`。
     * 本地日用世界态时区判（[WorldClock.resolveZone]）。
     */
    suspend fun recordEncounter(nativeId: String, atMs: Long) {
        val world = worldDao.getState() ?: return
        val s = nativeDao.get(nativeId) ?: return
        val zone = WorldClock.resolveZone(world.userTimezoneId)
        val sameLocalDay = s.lastEncounterAt?.let {
            WorldClock.localDateOf(it, zone) == WorldClock.localDateOf(atMs, zone)
        } ?: false
        val updated = if (sameLocalDay) {
            s.copy(lastEncounterAt = atMs) // 同本地日：只更时间戳
        } else {
            s.copy(
                discovered = true,
                discoveredAt = s.discoveredAt ?: atMs,
                encounterCount = s.encounterCount + 1,
                narrativeFuel = s.narrativeFuel + ENCOUNTER_FUEL,
                lastEncounterAt = atMs,
            )
        }
        nativeDao.upsert(updated)
    }

    /** 加叙事燃料（§3.3）：`points ≤ 0` no-op；未发现 no-op（还没见过谈不上眼缘）。 */
    suspend fun addNarrativeFuel(nativeId: String, points: Int) {
        if (points <= 0) return
        val s = nativeDao.get(nativeId) ?: return
        if (!s.discovered) return
        nativeDao.upsert(s.copy(narrativeFuel = s.narrativeFuel + points))
    }

    /** 加心意燃料（§3.3）：`points ≤ 0` no-op；未发现 no-op。**只记点数**——兑换率/扣 gold 不在本块（钱路零碰）。 */
    suspend fun addGiftFuel(nativeId: String, points: Int) {
        if (points <= 0) return
        val s = nativeDao.get(nativeId) ?: return
        if (!s.discovered) return
        nativeDao.upsert(s.copy(giftFuel = s.giftFuel + points))
    }

    /**
     * 引荐（§3.3）：未发现时 = `discover` + `narrativeFuel += REFERRAL_FUEL`（一次性）；**已发现 no-op**
     * （同一原住民只可被引荐一次）。
     */
    suspend fun introduce(nativeId: String, atMs: Long) {
        val s = nativeDao.get(nativeId) ?: return
        if (s.discovered) return
        nativeDao.upsert(
            s.copy(discovered = true, discoveredAt = s.discoveredAt ?: atMs, narrativeFuel = s.narrativeFuel + REFERRAL_FUEL),
        )
    }

    /**
     * 引荐候选（§3.3）：查 [recruitedCharacterUuid] 反查到的原住民 → 其出厂边对端里**未发现且未招募**的 def
     * （顺序 = 边表声明序）。非招募角色 / 查无 → 空表。
     */
    suspend fun referralCandidates(recruitedCharacterUuid: String): List<WorldNativeDef> {
        val source = nativeDao.getAll().firstOrNull { it.recruitedCharacterUuid == recruitedCharacterUuid }
            ?: return emptyList()
        val sourceDef = WorldNativeRoster.byNativeId(source.nativeId) ?: return emptyList()
        return WorldNativeRoster.factoryNeighborsOf(sourceDef.slug).filter { neighbor ->
            val st = nativeDao.get(WorldIds.nativeId(neighbor.slug))
            st == null || (!st.discovered && st.recruitedCharacterUuid == null)
        }
    }

    companion object {
        /** 偶遇一次的叙事燃料（§3.2 锁死）。 */
        const val ENCOUNTER_FUEL = 6

        /** 被引荐一次性的叙事燃料（§3.2 锁死）。 */
        const val REFERRAL_FUEL = 18

        /** 眼缘合计（§3.2 锁死）：`round(nf×nw + gf×gw)`·下限 0（燃料原始值照存·改权重不丢历史）。 */
        internal fun affinityOf(state: WorldNativeStateEntity, def: WorldNativeDef): Int =
            (state.narrativeFuel * def.narrativeWeight + state.giftFuel * def.giftWeight).roundToInt().coerceAtLeast(0)

        /** 朦胧节点四档（§4.1 锁死）：`fraction = affinity / recruitThreshold` → 短语档（绝不吐数字）。 */
        internal fun stageOf(state: WorldNativeStateEntity, def: WorldNativeDef): WorldAffinityStage =
            WorldAffinityStage.of(affinityOf(state, def).toDouble() / def.recruitThreshold)
    }
}

/**
 * 朦胧节点四档（W6 图纸 §4.1 逐字锁死·图纸 §9 禁改）：眼缘只以**文字温度**露给玩家、绝不给数字/百分比
 * （决策 24）。[phrase] 是唯一对外出口；W9/W12 呈现读 [phrase]，绝不读 `affinityOf` 的原始数。
 */
enum class WorldAffinityStage(val phrase: String) {
    /** fraction < 0.25。 */
    STRANGER("你们还只是打过照面"),

    /** 0.25 ≤ fraction < 0.60。 */
    WARMING("TA 见到你会笑了"),

    /** 0.60 ≤ fraction < 1.0。 */
    EXPECTING("TA 好像在等你来"),

    /** fraction ≥ 1.0（= 愿意被招募）。 */
    WILLING("TA 愿意认识你了");

    companion object {
        /** 四档边界（§4.1 锁死）：0.25 / 0.60 / 1.0。 */
        fun of(fraction: Double): WorldAffinityStage = when {
            fraction < 0.25 -> STRANGER
            fraction < 0.60 -> WARMING
            fraction < 1.0 -> EXPECTING
            else -> WILLING
        }
    }
}
