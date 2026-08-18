package com.situ.aichat.world.cast

import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.dao.WorldNativeDao
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.dao.WorldUserResidentDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.WorldIds
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 招募服务（W6 图纸 §3.3/§3.4·契约 §11）：眼缘过线 → 愿意 → 把原住民**实例化成正式角色**，让 W4 引擎、W5 联动无缝接管。
 *
 * [recruit] 全程**单 `db.withTransaction`**（进程死 = 全回滚·无「角色建了但指针没记」半态）：建角色 + 写招募指针 +
 * **出厂边落地**（对端也已招募的双向边 + 一条 [REL_ORIGIN_KIND] 种子事件——让 W4 引擎「事件流水非空 = hasEdge」
 * 认账·老同学绝不被重发「初识」）+ recruit 世界事件（开机小报播报）。`rel_origin` 是**新 kind**、不进 W4 Beats
 * taxonomy（引擎对未知 kind 既有行为 = hasEdge 计入、其余忽略）；W5 镜像/记忆 kind 集天然不含它 → 零派生。
 *
 * **钱路零碰**（不触 `CurrencyService`/economy/wallet/gift 包）；派生 uuid 禁掺 `System.currentTimeMillis`
 * （[nowMs] 由调用方传入·事件 uuid 只含 slug/charUuid/pairKey）。
 */
@Singleton
class WorldRecruitService @Inject constructor(
    private val nativeDao: WorldNativeDao,
    private val socialDao: WorldSocialDao,
    private val worldDao: WorldDao,
    private val characterRepository: CharacterRepository,
    private val db: AppDatabase,
    private val affinityService: WorldAffinityService,
    // 战役 B（图纸 §2）：招募用户自建居民时取其表单头像带入 CharacterEntity（官方原住民 slug 恒查无 → null 走字母彩圈）。
    private val userResidentDao: WorldUserResidentDao,
) {

    /** 愿意判定（§3.3）：`discovered && recruitedCharacterUuid == null && affinity ≥ threshold`。 */
    suspend fun isWilling(nativeId: String): Boolean {
        val state = nativeDao.get(nativeId) ?: return false
        val def = WorldNativeRoster.byNativeId(nativeId) ?: return false
        return isWilling(state, def)
    }

    private fun isWilling(state: WorldNativeStateEntity, def: WorldNativeDef): Boolean =
        state.discovered && state.recruitedCharacterUuid == null &&
            WorldAffinityService.affinityOf(state, def) >= def.recruitThreshold

    /**
     * 招募（§3.3）：不愿意 → null·零副作用。愿意 → 单事务内建角色 + 写指针 + 落出厂边 + recruit 事件，返回新 uuid。
     * 事务内**再验一次愿意**（防并发双招）；[nowMs] = 调用方时刻（事件 happenedAt·禁进派生 uuid）。
     */
    suspend fun recruit(nativeId: String, nowMs: Long): String? {
        val def = WorldNativeRoster.byNativeId(nativeId) ?: return null
        val newUuid = UUID.randomUUID().toString()
        return db.withTransaction {
            val state = nativeDao.get(nativeId) ?: return@withTransaction null
            if (!isWilling(state, def)) return@withTransaction null // 事务内复核·不满足即回滚前返 null
            // 战役 B（图纸 §2）：用户自建居民带表单头像入库；官方原住民 slug 非 resident_ → null（字母彩圈·§4.5 不变）。
            val avatarPath = if (def.slug.startsWith(WorldResidentService.RESIDENT_SLUG_PREFIX)) {
                userResidentDao.get(def.slug)?.avatarPath
            } else {
                null
            }
            characterRepository.insert(buildCharacter(def, newUuid, nowMs, avatarPath), initialRelationshipName = "新朋友")
            nativeDao.upsert(state.copy(recruitedCharacterUuid = newUuid))
            landFactoryEdges(def, newUuid, nowMs)
            recordRecruitEvent(def, newUuid, nowMs)
            consumeReferral(def, newUuid, nowMs) // W6 §8.E 接线（W12 C5·销「referralCandidates 无人调用」）
            newUuid
        }
    }

    /**
     * 第五步·引荐消费（W12 图纸 §2/§9·契约 §8.E）：招募成功后，取该原住民**首位未发现邻居**（[WorldAffinityService.referralCandidates]
     * 声明序）→ `introduce`（discovered + 叙事燃料 +18·一次性）+ 一条 `referral` 世界事件（开机小报播报·**不进记忆/通知**·E12）。
     * 无候选（邻居皆已发现/已招募）→ no-op。事件 uuid `world:referral:<recruiterSlug>:<candidateSlug>` 幂等。
     */
    private suspend fun consumeReferral(recruiterDef: WorldNativeDef, newUuid: String, nowMs: Long) {
        val candidate = affinityService.referralCandidates(newUuid).firstOrNull() ?: return
        affinityService.introduce(WorldIds.nativeId(candidate.slug), nowMs) // discovered + REFERRAL_FUEL(+18)
        val seed = worldDao.getState()?.seed
        val candidateCity = if (seed != null) WorldNativeRoster.cityNameOf(candidate, seed) else "远方"
        worldDao.upsertEvent(
            WorldEventEntity(
                uuid = UUID.nameUUIDFromBytes("world:referral:${recruiterDef.slug}:${candidate.slug}".toByteArray()).toString(),
                kindRaw = REFERRAL_KIND,
                involvedIdsJson = StringListJson.encode(listOf(WorldIds.USER_ID, WorldIds.nativeId(candidate.slug))),
                cityId = candidate.cityId,
                // §9 只锁 kind/uuid·此 summary 图纸未给逐字（§11 记·仿 recruit 文案风格）。
                summary = "你从「${recruiterDef.name}」那儿，听说了${candidateCity}的「${candidate.name}」",
                happenedAt = nowMs,
            ),
        )
    }

    /**
     * 由 def 构建角色实体（§4.5 字段映射锁死·`ageModeRaw="fixed"`·`joinedWorld=true`·余列默认）。
     * [avatarPath]：官方原住民恒 null（字母彩圈）；用户自建居民带入表单头像（战役 B·图纸 §2）。
     */
    private fun buildCharacter(def: WorldNativeDef, uuid: String, nowMs: Long, avatarPath: String? = null): CharacterEntity =
        CharacterEntity(
            uuid = uuid,
            name = def.name,
            creationDate = nowMs,
            gender = def.gender,
            occupation = def.occupation,
            ageModeRaw = "fixed",
            fixedAge = def.fixedAge,
            personalityDescription = def.personality,
            appearanceDescription = def.appearance,
            backstory = def.backstory,
            speakingStyle = def.speakingStyle,
            catchphrases = def.catchphrases, // 原样含「｜」分隔
            initialInterests = def.interests,
            exampleDialogues = "",
            systemPrompt = "",
            avatarPath = avatarPath, // 官方 null 走字母彩圈；用户居民带表单头像（战役 B）
            joinedWorld = true,
            worldHomeCityId = def.cityId,
            worldJoinedAt = nowMs,
        )

    /**
     * 出厂边落地（§3.3 step3）：遍历该 slug 的出厂边——对端**也已招募**的，按 §4.3 双向落边 + 一条 `rel_origin`
     * 种子事件。对端未招募 → 什么都不落（他日对端招募时由其流程补齐·双向遍历天然覆盖）。
     */
    private suspend fun landFactoryEdges(def: WorldNativeDef, newUuid: String, nowMs: Long) {
        for (edge in WorldNativeRoster.factoryEdgesOf(def.slug)) {
            val otherSlug = if (edge.slugA == def.slug) edge.slugB else edge.slugA
            val otherUuid = nativeDao.get(WorldIds.nativeId(otherSlug))?.recruitedCharacterUuid ?: continue
            // 按边声明的 A/B 朝向映射到角色 uuid（本次招募者 = def.slug 那端）。
            val charA = if (edge.slugA == def.slug) newUuid else otherUuid
            val charB = if (edge.slugB == def.slug) newUuid else otherUuid
            landEdge(edge, charA, charB, nowMs)
        }
    }

    /** 落一条出厂边：双向 [WorldRelationshipEntity]（值照 §4.3）+ 一条 `rel_origin` 关系事件（让引擎 hasEdge 认账）。 */
    private suspend fun landEdge(edge: WorldFactoryEdge, charA: String, charB: String, nowMs: Long) {
        val typesJson = StringListJson.encode(edge.types)
        val bond = edge.types.joinToString("·")
        socialDao.upsertEdge(
            WorldRelationshipEntity(
                fromId = charA, toId = charB, typesJson = typesJson,
                closeness = edge.closenessAB, trust = edge.trustAB, colorRaw = edge.colorAB,
                bond = bond, origin = edge.origin, dormant = false, updatedAt = nowMs,
            ),
        )
        socialDao.upsertEdge(
            WorldRelationshipEntity(
                fromId = charB, toId = charA, typesJson = typesJson,
                closeness = edge.closenessBA, trust = edge.trustBA, colorRaw = edge.colorBA,
                bond = bond, origin = edge.origin, dormant = false, updatedAt = nowMs,
            ),
        )
        val pairKey = WorldIds.pairKey(charA, charB)
        socialDao.upsertEvent(
            WorldRelationshipEventEntity(
                uuid = UUID.nameUUIDFromBytes("world:rel:origin:$pairKey".toByteArray()).toString(),
                pairKey = pairKey, actorId = charA, targetId = charB,
                kindRaw = REL_ORIGIN_KIND, arcId = null, summary = edge.origin,
                happenedAt = nowMs, settledAt = nowMs,
            ),
        )
    }

    /** recruit 世界事件（§3.3 step4 / §4.6 文案）：进 `world_event` → 开机小报自然播报。 */
    private suspend fun recordRecruitEvent(def: WorldNativeDef, newUuid: String, nowMs: Long) {
        val seed = worldDao.getState()?.seed
        val cityName = if (seed != null) WorldNativeRoster.cityNameOf(def, seed) else "远方"
        worldDao.upsertEvent(
            WorldEventEntity(
                uuid = UUID.nameUUIDFromBytes("world:recruit:${def.slug}:$newUuid".toByteArray()).toString(),
                kindRaw = RECRUIT_KIND,
                involvedIdsJson = StringListJson.encode(listOf(WorldIds.USER_ID, newUuid)),
                cityId = def.cityId,
                summary = "「${def.name}」成了你的朋友——你们的缘分从${cityName}开始",
                happenedAt = nowMs,
            ),
        )
    }

    companion object {
        /** 出厂边种子关系事件 kind（新 kind·不进 W4 Beats taxonomy·§6 禁区核对）。 */
        const val REL_ORIGIN_KIND = "rel_origin"

        /** 招募世界事件 kind（`world_event.kindRaw`·§9 锁死）。 */
        const val RECRUIT_KIND = "recruit"

        /** 引荐世界事件 kind（W12·`world_event.kindRaw`·§9 锁死·不进 MEMORY_KINDS/通知·E12）。 */
        const val REFERRAL_KIND = "referral"
    }
}
