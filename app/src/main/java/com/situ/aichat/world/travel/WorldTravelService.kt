package com.situ.aichat.world.travel

import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import com.situ.aichat.data.model.CurrencyTransactionCategory
import com.situ.aichat.economy.CurrencyService
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldClock
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.notify.WorldNotifyService
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 旅行服务（契约 §12/§13 ⚠️钱路 / W7 图纸 §3）：出发购票（世界首个真扣款口）/ 邀请来访 / 到达结算与返程生成 /
 * 位置与在途查询。**所有人（含用户）旅行都花时间，花钱只买更快**（决策 10/15）——聊天永不受限，旅行只 gate
 * 实地动作（W9/W12 消费本服务的位置查询）。
 *
 * ⚠️ **钱路铁则（§13 全项照办·勿破坏）**：扣款**只走 [CurrencyService.spendCoinsFromUser] 唯一一处**（[depart] 内）；
 * 免费模式（走 / 骑·`cost==0`）**代码路径上根本不调用** [CurrencyService]；**无任何退款 / 取消 API**（不可退·决策 24）；
 * 邀请来访**零扣款**（用户与角色钱包都分毫不动）；**扣款 + 落行同一 [AppDatabase.withTransaction]**——崩溃无
 * 「钱扣了人没走」半态。派生 uuid 禁掺 `System.currentTimeMillis`（[nowMs] 由调用方注入·便于确定性单测）。
 */
@Singleton
class WorldTravelService @Inject constructor(
    private val worldDao: WorldDao,
    private val characterDao: CharacterDao,
    private val currencyService: CurrencyService,
    private val db: AppDatabase,
    private val notifyService: WorldNotifyService,
    private val visitGreeter: com.situ.aichat.world.live.WorldVisitGreeter,
) {

    /**
     * 出发去 [destCityId]（图纸 §3.2）：世界未初始化 → [DepartResult.WorldNotReady]；用户在途 → [DepartResult.AlreadyTraveling]
     * （在途不可再出发 = 不可退的另一半）；用户已到达未结算 → 先就地结算再按新起点走；同城 → [DepartResult.SameCity]；
     * 模式不在该距离带选项 → [DepartResult.ModeUnavailable]。**单事务**扣款（`cost>0` 才碰钱路）+ 落行，余额不足 →
     * [DepartResult.InsufficientGold]（零扣款零落行）。
     */
    suspend fun depart(destCityId: String, mode: String, nowMs: Long): DepartResult {
        val state0 = worldDao.getState() ?: return DepartResult.WorldNotReady
        val existing = worldDao.getTravel(WorldIds.USER_ID)
        if (existing != null) {
            if (nowMs < existing.arriveAt) return DepartResult.AlreadyTraveling // E7 在途不可再出发
            settleUserArrivalRow(state0, existing) // E8 已到达但未结算 → 先就地结算（位置更新为 to·行删）
        }
        val state = worldDao.getState() ?: return DepartResult.WorldNotReady // 就地结算后重读起点
        val from = state.userCurrentCityId
        if (destCityId == from) return DepartResult.SameCity
        val atlas = WorldAtlas.of(state.seed)
        val distance = atlas.distanceLi(from, destCityId)
        val option = WorldTravelPlanner.optionsFor(distance).firstOrNull { it.mode == mode }
            ?: return DepartResult.ModeUnavailable
        val cost = option.costGold
        val arriveAt = nowMs + option.durationMs
        // 扣款 + 落行同事务：崩溃回滚无半态（E6）；免费模式 cost==0 → 全程不碰 CurrencyService（E5）。
        val result = db.withTransaction {
            if (cost > 0) {
                val cityName = atlas.cityById(destCityId)?.name ?: "远方"
                val newBalance = currencyService.spendCoinsFromUser(
                    amount = cost,
                    category = CurrencyTransactionCategory.WORLD_TRAVEL,
                    note = "前往$cityName",
                    relatedId = destCityId,
                    now = nowMs,
                ) ?: return@withTransaction DepartResult.InsufficientGold(
                    need = cost,
                    have = currencyService.userCoinBalance(nowMs), // 事务内查现余额填 have（零扣款）
                )
            }
            worldDao.upsertTravel(
                WorldTravelEntity(
                    ownerId = WorldIds.USER_ID,
                    fromCityId = from,
                    toCityId = destCityId,
                    departAt = nowMs,
                    arriveAt = arriveAt,
                    modeRaw = mode,
                    costGold = cost.toLong(),
                ),
            )
            DepartResult.Departed(option, arriveAt)
        }
        // W8 §3.1：排「你到达」通知——**事务外·成功才排**·排期失败仅 Log.w 不改旅行结果（钱路不受影响）。
        if (result is DepartResult.Departed) {
            try {
                notifyService.onUserDeparted(result.arriveAtMs)
            } catch (e: Exception) {
                Log.w(TAG, "排用户到达通知失败(不拦出发)", e)
            }
        }
        return result
    }

    /**
     * 邀请 [characterUuid] 跨城来看你（图纸 §3.2·决策 24 对称的另一半）：角色查无 / 未入世 → [InviteResult.NotInWorld]；
     * 角色已有行（在途或来访中）→ [InviteResult.AlreadyOnTheWay]；角色家 = 用户当前城 → [InviteResult.SameCity]。成行 →
     * **耗时最短档**（TA 想快点见你）·**零扣款**（免费只花时间·用户拍板·§13 角色经济只读——用户与角色钱包都分毫不动）·
     * 落角色行（from=家·to=用户当前城）。
     *
     * 「用户当前城」遵守 §3.1 位置三态（**行感知**·与 [depart] E8 / [userPresence] 一致·R1 🔴-1 修订）：用户自己在途
     * （`departAt ≤ now < arriveAt`）→ 目的地未定 → [InviteResult.UserTraveling] 挡（零副作用）；用户已到达但未结算 →
     * 先就地结算（[settleUserArrivalRow]），再以**新城**为「这里」邀请。
     */
    suspend fun invite(characterUuid: String, nowMs: Long): InviteResult {
        val character = characterDao.getByUuid(characterUuid)
        if (character == null || !character.joinedWorld) return InviteResult.NotInWorld
        if (worldDao.getTravel(characterUuid) != null) return InviteResult.AlreadyOnTheWay
        val state = worldDao.getState() ?: return InviteResult.NotInWorld // 世界不存在 = 无处可来（防御·§11 记）
        // 用户自己的行感知（R1 🔴-1·镜像 depart E8）：在途 → 挡；已到达未结算 → 先就地结算再取新城。
        val userTravel = worldDao.getTravel(WorldIds.USER_ID)
        if (userTravel != null) {
            if (nowMs < userTravel.arriveAt) return InviteResult.UserTraveling // 在途邀请 = 目的地未定 → 挡（零副作用）
            settleUserArrivalRow(state, userTravel) // 已到达未结算 → 先就地结算（E8 同款）
        }
        val userCity = (worldDao.getState() ?: return InviteResult.NotInWorld).userCurrentCityId // 结算后重读（不用旧引用）
        if (character.worldHomeCityId == userCity) return InviteResult.SameCity
        val distance = WorldAtlas.of(state.seed).distanceLi(character.worldHomeCityId, userCity)
        val fastest = WorldTravelPlanner.optionsFor(distance).minByOrNull { it.durationMs }!! // 耗时最短档
        val arriveAt = nowMs + fastest.durationMs
        worldDao.upsertTravel(
            WorldTravelEntity(
                ownerId = characterUuid,
                fromCityId = character.worldHomeCityId,
                toCityId = userCity,
                departAt = nowMs,
                arriveAt = arriveAt,
                modeRaw = fastest.mode,
                costGold = 0L, // 零扣款（免费只花时间·钱路铁则）
            ),
        )
        // W8 §3.1：排「TA 到达你的城」通知——**事务外·成功才排**·排期失败仅 Log.w 不改邀请结果。
        try {
            notifyService.onVisitInvited(characterUuid, arriveAt)
        } catch (e: Exception) {
            Log.w(TAG, "排来访到达通知失败(不拦邀请)", e)
        }
        return InviteResult.Invited(arriveAt)
    }

    /**
     * 懒结算所有到达（图纸 §3.2·Runner 每次前台通行证调·幂等）：遍历在途行——
     * - **用户行**到达 → 当前城 = to·行删（[settleUserArrivalRow]）；
     * - **角色来访腿**（to ≠ 家）到达 → 落 visit 世界事件（开机小报播报）；`本地日(now) > 本地日(arriveAt)` 时生成返程
     *   （替换行为 from=访问城·to=家·departAt=到达次日本地 09:00·同模式·costGold=0·PK 替换天然幂等）；
     * - **角色返程腿**（to == 家）到达 → 行删（到家）。
     */
    suspend fun settleArrivals(nowMs: Long) {
        val state = worldDao.getState() ?: return
        val zone = WorldClock.resolveZone(state.userTimezoneId)
        val atlas = WorldAtlas.of(state.seed)
        for (travel in worldDao.getAllTravels()) {
            if (nowMs < travel.arriveAt) continue // 未到达（在途 / 即将启程）
            if (travel.ownerId == WorldIds.USER_ID) {
                settleUserArrivalRow(state, travel)
                continue
            }
            val character = characterDao.getByUuid(travel.ownerId) ?: continue // 角色已删（清理链已删行·防御跳过）
            if (travel.toCityId != character.worldHomeCityId) {
                landVisitEvent(travel, character, atlas)
                // W12 C5：到达开场一句落进真实会话（零 LLM·幂等·同 landVisitEvent 派生分量 ownerId:departAt）。
                visitGreeter.greetArrival(travel.ownerId, character.name, "${travel.ownerId}:${travel.departAt}", travel.arriveAt)
                if (WorldClock.localDateOf(nowMs, zone).isAfter(WorldClock.localDateOf(travel.arriveAt, zone))) {
                    generateReturnTrip(travel, character.worldHomeCityId, zone, atlas)
                }
            } else {
                worldDao.deleteTravel(travel.ownerId) // 返程到家
            }
        }
    }

    /** 用户当前位置（图纸 §3.1 三态·W9/W12 消费）。 */
    suspend fun userPresence(nowMs: Long): WorldPresence {
        val travel = worldDao.getTravel(WorldIds.USER_ID)
        val fallback = worldDao.getState()?.userCurrentCityId ?: WorldIds.HOME_CITY_ID
        return presenceOf(travel, fallback, nowMs)
    }

    /** 角色当前位置（图纸 §3.1 三态·W9/W12 消费）。 */
    suspend fun characterPresence(characterUuid: String, nowMs: Long): WorldPresence {
        val travel = worldDao.getTravel(characterUuid)
        val fallback = characterDao.getByUuid(characterUuid)?.worldHomeCityId ?: WorldIds.HOME_CITY_ID
        return presenceOf(travel, fallback, nowMs)
    }

    /** 用户是否在途（`departAt ≤ now < arriveAt`·gate 实地动作用·图纸 §3.1）。 */
    suspend fun isUserTraveling(nowMs: Long): Boolean {
        val travel = worldDao.getTravel(WorldIds.USER_ID) ?: return false
        return nowMs >= travel.departAt && nowMs < travel.arriveAt
    }

    /**
     * 用户到达就地结算（位置更新为 `to`·清在途行·幂等）：[depart] 的 E8 前置与 [settleArrivals] 用户支共用这一处逻辑。
     */
    private suspend fun settleUserArrivalRow(state: WorldStateEntity, travel: WorldTravelEntity) {
        worldDao.upsertState(state.copy(userCurrentCityId = travel.toCityId))
        worldDao.deleteTravel(WorldIds.USER_ID)
    }

    /**
     * 落来访到达 world_event（图纸 §3.2 ①/§4.3/§3.3·uuid 派生 `world:visit:{charUuid}:{departAt}`·kind=visit）。
     * **存在即跳过**（R1 🟡-1·§3.3 修订）：世界事件是历史记录，第一次写下后永不重写——避免多次结算窗 / 生成返程那趟重复
     * `@Upsert` 整行清掉 `notifiedAt`（W8 通知去重地雷）/ `seenAt`。角色中途改名也保留当时文案，语义更正。
     */
    private suspend fun landVisitEvent(travel: WorldTravelEntity, character: CharacterEntity, atlas: WorldAtlas.Atlas) {
        val uuid = UUID.nameUUIDFromBytes("world:visit:${travel.ownerId}:${travel.departAt}".toByteArray()).toString()
        if (worldDao.getEvent(uuid) != null) return // 存在即跳过（永不重写·防清 notifiedAt/seenAt）
        val cityName = atlas.cityById(travel.toCityId)?.name ?: "远方"
        worldDao.upsertEvent(
            WorldEventEntity(
                uuid = uuid,
                kindRaw = VISIT_KIND,
                involvedIdsJson = StringListJson.encode(listOf(WorldIds.USER_ID, travel.ownerId)),
                cityId = travel.toCityId,
                summary = "${character.name}到了$cityName——TA 说，就是想来看看你",
                happenedAt = travel.arriveAt,
            ),
        )
    }

    /** 生成返程腿（图纸 §3.2 ②/§4.3·替换来访腿·departAt=到达次日本地 09:00·同模式·costGold=0·PK 替换幂等）。 */
    private suspend fun generateReturnTrip(travel: WorldTravelEntity, homeCityId: String, zone: java.time.ZoneId, atlas: WorldAtlas.Atlas) {
        val distance = atlas.distanceLi(travel.toCityId, homeCityId)
        val departAt = WorldClock.localDateOf(travel.arriveAt, zone).plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        worldDao.upsertTravel(
            travel.copy(
                fromCityId = travel.toCityId,
                toCityId = homeCityId,
                departAt = departAt,
                arriveAt = departAt + WorldTravelPlanner.durationOf(travel.modeRaw, distance),
                costGold = 0L,
            ),
        )
    }

    /** 三态规则（图纸 §3.1·锁死·用户与角色两域共用）：无行 → 兜底城；未出发 → from；在途 → from + 目的地；已到 → to。 */
    private fun presenceOf(travel: WorldTravelEntity?, fallbackCity: String, nowMs: Long): WorldPresence = when {
        travel == null -> WorldPresence(fallbackCity, null, null)
        nowMs < travel.departAt -> WorldPresence(travel.fromCityId, null, null) // 即将启程
        nowMs < travel.arriveAt -> WorldPresence(travel.fromCityId, travel.toCityId, travel.arriveAt) // 在途
        else -> WorldPresence(travel.toCityId, null, null) // 已到达
    }

    companion object {
        private const val TAG = "WorldTravel"

        /** 来访到达世界事件 kind（`world_event.kindRaw`·§9 锁死）。 */
        const val VISIT_KIND = "visit"
    }
}

/** [WorldTravelService.depart] 结果（sealed·图纸 §3.2 语义锁死）。 */
sealed interface DepartResult {
    /** 成行：[option] = 选定档（模式 / 耗时 / 票价）·[arriveAtMs] = 预计到达时刻。 */
    data class Departed(val option: TravelOption, val arriveAtMs: Long) : DepartResult

    /** 余额不足：需 [need] 金·现有 [have] 金·**零扣款零落行**。 */
    data class InsufficientGold(val need: Int, val have: Int) : DepartResult

    /** 世界未初始化（无 world_state）。 */
    data object WorldNotReady : DepartResult

    /** 用户已在途（在途不可再出发）。 */
    data object AlreadyTraveling : DepartResult

    /** 目的地 = 当前城。 */
    data object SameCity : DepartResult

    /** 该模式不在此距离带的选项内。 */
    data object ModeUnavailable : DepartResult
}

/** [WorldTravelService.invite] 结果（sealed·图纸 §3.2 语义锁死·全程零扣款）。 */
sealed interface InviteResult {
    /** 成行：[arriveAtMs] = TA 预计到达时刻（耗时最短档）。 */
    data class Invited(val arriveAtMs: Long) : InviteResult

    /** 角色查无或未入世（`!joinedWorld`）。 */
    data object NotInWorld : InviteResult

    /** 角色已有在途行（在途或来访中）。 */
    data object AlreadyOnTheWay : InviteResult

    /** 角色家 = 用户当前城（无需赶来）。 */
    data object SameCity : InviteResult

    /** 用户自己在途（`departAt ≤ now < arriveAt`）——「这里」尚无定所，先到再邀。 */
    data object UserTraveling : InviteResult
}

/**
 * 位置快照（图纸 §3.1/§3.2·W9/W12 消费）：[cityId] = 当前所在城（在途时 = 出发城）；[inTransitToCityId] 非空 =
 * 在途中且目的地；[arriveAtMs] 非空 = 在途预计到达时刻。三态由 [WorldTravelService] 按 `now` 判。
 */
data class WorldPresence(
    val cityId: String,
    val inTransitToCityId: String?,
    val arriveAtMs: Long?,
)
