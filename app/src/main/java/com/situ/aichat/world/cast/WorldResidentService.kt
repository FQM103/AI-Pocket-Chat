package com.situ.aichat.world.cast

import android.util.Log
import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.dao.WorldUserResidentDao
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldUserResidentEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.atlas.WorldRegions
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户自建居民生命周期**唯一写入口**（世界二期战役 B·契约 §3·图纸 §3.3）：创建（校验+落库+播种眼缘行+落户事件+
 * 注册花名册）/ 未招募删除（送 TA 离开）/ 已招募被删连坐（O2 彻底消失）/ 启动装载花名册。UI/VM 绝不直碰 DAO。
 *
 * **双源合流**：把 [WorldUserResidentEntity] 映射成 [WorldNativeDef]（[defOf]·§3.1 逐字锁死），经
 * [WorldNativeRoster.registerUserDefs] 并入官方花名册——此后眼缘/招募/偶遇/星图/偷听全链**零文件改动**自动生效。
 *
 * 写路径全程单 `db.withTransaction`（进程死 = 全回滚·无半态）；派生 uuid（落户事件）只含 slug、**禁掺时钟**
 * （沿用 [WorldRecruitService] 纪律）；钱路零碰。
 */
@Singleton
class WorldResidentService @Inject constructor(
    private val userResidentDao: WorldUserResidentDao,
    private val nativeDao: WorldNativeDao,
    private val worldDao: WorldDao,
    private val db: AppDatabase,
) {

    /**
     * 创建居民（§3.3 create）：校验（空名 → [CreateResult.InvalidName]·满 [MAX_RESIDENTS] → [CreateResult.CapReached]）→
     * 单事务落三件（def 行 + 眼缘播种行 discovered=false 燃料全 0 + `resident_arrive` 落户事件）→ 事务后刷花名册。
     *
     * [nowMs] 仅作 createdAt / 事件 happenedAt（**不进任何派生 uuid**）；默认取墙钟，测试传固定值。
     */
    suspend fun create(draft: ResidentDraft, nowMs: Long = System.currentTimeMillis()): CreateResult {
        val name = draft.name.trim()
        if (name.isEmpty()) return CreateResult.InvalidName
        if (userResidentDao.count() >= MAX_RESIDENTS) return CreateResult.CapReached

        val slug = RESIDENT_SLUG_PREFIX + UUID.randomUUID().toString().replace("-", "").take(8)
        val nativeId = WorldIds.nativeId(slug)
        val entity = WorldUserResidentEntity(
            slug = slug,
            name = name,
            gender = draft.gender,
            age = clampAge(draft.ageText),
            cityId = draft.cityId,
            occupation = draft.occupation,
            personaBrief = draft.personaBrief,
            traitsJson = StringListJson.encode(draft.traits),
            freeformLore = draft.freeformLore,
            initialRelationText = draft.initialRelationText,
            fuelBias = draft.fuelBias,
            avatarPath = draft.avatarPath,
            createdAt = nowMs,
        )
        val seed = worldDao.getState()?.seed
        val cityName = if (seed != null) WorldNativeDef.cityNameOf(draft.cityId, seed) else "远方"
        db.withTransaction {
            userResidentDao.upsert(entity)
            // 眼缘播种行：与官方 ensureSeeded 行等价（未发现·双燃料 0·currentCityId=出生城）。
            nativeDao.upsert(WorldNativeStateEntity(nativeId = nativeId, currentCityId = draft.cityId))
            worldDao.upsertEvent(
                WorldEventEntity(
                    uuid = UUID.nameUUIDFromBytes("world:resident:arrive:$slug".toByteArray()).toString(),
                    kindRaw = ARRIVE_KIND,
                    // involvedIdsJson = [nativeId]：令 deleteUnrecruited 的 deleteEventsInvolving(nativeId) 能连坐清此事件
                    // （图纸未逐字给 involvedIdsJson·§11 记）。
                    involvedIdsJson = StringListJson.encode(listOf(nativeId)),
                    cityId = draft.cityId,
                    summary = "$name 搬来了$cityName，住进了新家",
                    happenedAt = nowMs,
                ),
            )
        }
        refreshRoster()
        return CreateResult.Ok(slug)
    }

    /**
     * 送 TA 离开（§3.3 deleteUnrecruited·仅未招募）：已招募（指针非空）→ 返 false 零删除（只能走删联系人·E3）；
     * 否则单事务删 def 行 + state 行 + 连坐清落户事件，后刷花名册 + 删头像文件（best-effort）。
     */
    suspend fun deleteUnrecruited(slug: String): Boolean {
        val nativeId = WorldIds.nativeId(slug)
        if (nativeDao.get(nativeId)?.recruitedCharacterUuid != null) return false // 已招募 → 拒删（E3）
        val avatarPath = userResidentDao.get(slug)?.avatarPath
        db.withTransaction {
            userResidentDao.delete(slug)
            nativeDao.deleteByNativeId(nativeId)
            worldDao.deleteEventsInvolving(nativeId)
        }
        deleteAvatarFile(avatarPath)
        refreshRoster()
        return true
    }

    /**
     * 已招募居民的联系人被删（§3.3 onCharacterDeleted·O2 彻底消失）：由 [com.situ.aichat.data.repository.CharacterRepository.delete]
     * **事务内**调。招募指针命中的 state 行其 slug 若为用户居民 → 删 def 行 + 删 state 行（**取代**官方的 resetRecruitment
     * 「回城缘分归零」·官方行为仅对官方保留）；返回 [ResidentEviction]（含头像路径）供事务提交后收尾。非用户居民 / 无命中 → null。
     */
    suspend fun onCharacterDeleted(recruitedUuid: String): ResidentEviction? {
        val state = nativeDao.getByRecruitedUuid(recruitedUuid) ?: return null
        val slug = state.nativeId.removePrefix(WorldIds.NATIVE_PREFIX)
        if (!slug.startsWith(RESIDENT_SLUG_PREFIX)) return null // 官方原住民 → 交回 resetRecruitment
        val avatarPath = userResidentDao.get(slug)?.avatarPath
        userResidentDao.delete(slug)
        nativeDao.deleteByNativeId(state.nativeId)
        // 🟡-1（复核 R1·O2 彻底消失）：连坐清落户事件（involvedIdsJson=[nativeId]）——CharacterRepository 的
        // deleteEventsInvolving 按角色 uuid 匹配、命不中含 nativeId 的落户事件，故在此按 nativeId 补一刀（与 deleteUnrecruited 一致）。
        worldDao.deleteEventsInvolving(state.nativeId)
        return ResidentEviction(avatarPath)
    }

    /**
     * [onCharacterDeleted] 命中后的事务外收尾（O2）：删头像文件（best-effort）+ 刷花名册（地图/星图/设置计数即少一位）。
     * 由 CharacterRepository 在删角色事务**提交后**调。
     */
    suspend fun finalizeEviction(eviction: ResidentEviction) {
        deleteAvatarFile(eviction.avatarPath)
        refreshRoster()
    }

    /**
     * 启动装载（§3.3 loadIntoRoster）：把库里全部用户居民映射入花名册——由 [com.situ.aichat.world.WorldBootstrap]
     * 在 `ensureSeeded()` 之前调，确保播种与全链消费覆盖用户居民。
     */
    suspend fun loadIntoRoster() = refreshRoster()

    /** 重查全部用户居民 → [defOf] → 注册进花名册（create/delete 后 + 启动装载）。世界未建（seed 缺）则注册空表。 */
    private suspend fun refreshRoster() {
        val entities = userResidentDao.getAll()
        val seed = worldDao.getState()?.seed
        val defs = if (entities.isEmpty() || seed == null) emptyList() else entities.map { defOf(it, seed) }
        WorldNativeRoster.registerUserDefs(defs)
    }

    /** 删头像文件（best-effort·失败仅日志·图纸 §3.3）。 */
    private fun deleteAvatarFile(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }.onFailure { Log.w(TAG, "删居民头像失败", it) }
    }

    companion object {
        private const val TAG = "WorldResident"

        /** 用户居民上限（O1·2026-07-07 拍板·图纸 §9 锁死）。 */
        const val MAX_RESIDENTS = 50

        /** 用户居民 slug 前缀（图纸 §9 锁死·`resident_` 恒不撞官方人名拼音）。 */
        const val RESIDENT_SLUG_PREFIX = "resident_"

        /** 落户世界事件 kind（`world_event.kindRaw`·图纸 §9 锁死）。 */
        const val ARRIVE_KIND = "resident_arrive"

        /** 招募门槛（官方域 85–140 中位·图纸 §3.1 §9 锁死）。 */
        const val RESIDENT_RECRUIT_THRESHOLD = 105

        /** 年龄钳位（§3.1/E5）：仅收数字·钳 [1,999]·空 / 非数字 → 26。纯函数便于 T1-2 反推。 */
        internal fun clampAge(raw: String): Int = raw.trim().toIntOrNull()?.coerceIn(1, 999) ?: 26

        /**
         * def 映射（§3.1 逐字锁死·图纸 §9 禁改·T1-1 反推）：把用户居民静态人设映射成 [WorldNativeDef]。
         * regionId 查图集（查无 → 回退家乡城所在区·E4）；人设简介+性格底色拼进 personality；自由设定+初始关系拼进 backstory。
         */
        internal fun defOf(entity: WorldUserResidentEntity, seed: Long): WorldNativeDef {
            val atlas = WorldAtlas.of(seed)
            val homeRegion = atlas.cityById(WorldIds.HOME_CITY_ID)?.regionId ?: WorldRegions.ALL.first().id
            val regionId = atlas.cityById(entity.cityId)?.regionId ?: homeRegion // 查无回退家乡城区（E4）
            val traits = StringListJson.decode(entity.traitsJson).joinToString("、")
            val backstory = listOf(entity.freeformLore, entity.initialRelationText)
                .filter { it.isNotBlank() }.joinToString("\n")
            val (nw, gw) = fuelWeights(entity.fuelBias)
            return WorldNativeDef(
                slug = entity.slug,
                name = entity.name,
                gender = entity.gender,
                fixedAge = entity.age,
                regionId = regionId,
                cityId = entity.cityId,
                placeId = null,
                occupation = entity.occupation,
                oneLiner = entity.occupation,
                personality = entity.personaBrief + "。性格底色：" + traits,
                appearance = "",
                backstory = backstory,
                speakingStyle = "说话贴合性格底色：" + traits,
                catchphrases = "",
                interests = "",
                greeting = "你好呀，我是${entity.name}。之前就见过你几次，今天总算说上话了。",
                recruitThreshold = RESIDENT_RECRUIT_THRESHOLD,
                narrativeWeight = nw,
                giftWeight = gw,
            )
        }

        /** 眼缘倾向 → 双燃料权重（§3.1·全落官方真值域：narrative 1.0–1.3 / gift 0.2–1.2）。 */
        private fun fuelWeights(bias: String): Pair<Double, Double> = when (bias) {
            "narrative" -> 1.2 to 0.6
            "gift" -> 1.0 to 1.2
            else -> 1.0 to 1.0 // balanced（含兜底）
        }
    }
}

/** 创建居民表单入参（VM 已把性别/性格底色解析完毕·年龄仍为原文由服务钳位）。 */
data class ResidentDraft(
    val name: String,
    /** 已解析：男→"male"、女→"female"、自定义→原文（§3.1）。 */
    val gender: String,
    /** 原文（服务层 [WorldResidentService.clampAge] 钳位·E5）。 */
    val ageText: String,
    val cityId: String,
    val occupation: String,
    val personaBrief: String,
    val traits: List<String>,
    val freeformLore: String,
    val initialRelationText: String,
    /** "balanced" / "narrative" / "gift"。 */
    val fuelBias: String,
    val avatarPath: String?,
)

/** create 结果（§3.3）。 */
sealed interface CreateResult {
    data class Ok(val slug: String) : CreateResult
    data object CapReached : CreateResult
    data object InvalidName : CreateResult
}

/** [WorldResidentService.onCharacterDeleted] 命中回执（供事务提交后删头像+刷花名册·O2）。 */
data class ResidentEviction(val avatarPath: String?)
