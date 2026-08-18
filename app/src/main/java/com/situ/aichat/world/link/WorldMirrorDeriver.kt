package com.situ.aichat.world.link

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.util.StringListJson
import com.situ.aichat.world.social.WorldRelationshipBeats as Beats
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D13 镜像重派生器（W5 图纸 §3.3 / §6）：从**已落库的关系事件**重新派生 world_event 镜像。
 *
 * W4 曾在引擎 `settleDay` 内当场返回镜像、由 Coordinator 事后 `recordEvent`——「引擎事务提交」与
 * 「recordEvent」之间进程死会丢镜像（W4 D13 挂账·极小窗）。本器把镜像变成「已提交关系事件的**纯派生**」：
 * uuid 种子派生（`world:relw:` 禁改）+ Coordinator `@Upsert` → 任意时点崩溃，下次重跑必然重派生同 uuid
 * 覆盖自身 = 天然幂等闭窗。崩溃 + 长缺席致窗口截断的极端残缝由调用方回看 7 天兜住（与 `MAX_CATCHUP_DAYS=7`
 * 同宽·超 14 天双重极端接受为界）。
 *
 * **只搬不改**：[MIRROR_KINDS] 与 [buildMirror] 字段构造逐字节承自 W4 `WorldRelationshipLedger.buildMirror`
 * ——集合成员 / `world:relw:` uuid 串 / involved 排序 / cityId 取法一字未动（图纸 §9 禁改）。贡献者契约①
 * 确定性在系统层面保持：派生输入 = 库中关系事件，而它们本身是 seed+window 的确定性产物。
 */
@Singleton
class WorldMirrorDeriver @Inject constructor(
    private val socialDao: WorldSocialDao,
    private val characterDao: CharacterDao,
) {

    /**
     * 从 [fromMs] 起（happenedAt ≥ fromMs·升序）的关系事件重派生 world_event 镜像。只镜像 [MIRROR_KINDS]
     * 里有戏剧价值的拍；actor 角色查无（已删 / 未入世）→ **跳过该条**、其余照派、不抛（图纸 E2）。
     */
    suspend fun deriveSince(fromMs: Long): List<WorldEventEntity> {
        val mirrors = mutableListOf<WorldEventEntity>()
        for (event in socialDao.eventsSince(fromMs)) {
            if (event.kindRaw !in MIRROR_KINDS) continue
            val actor = characterDao.getByUuid(event.actorId) ?: continue // 角色缺失 → 跳过该条
            mirrors += buildMirror(event, actor.worldHomeCityId)
        }
        return mirrors
    }

    /** 镜像字段构造（逐字节承自 W4 `WorldRelationshipLedger.buildMirror`·图纸 §9 禁改）。 */
    private fun buildMirror(event: WorldRelationshipEventEntity, cityId: String): WorldEventEntity =
        WorldEventEntity(
            uuid = UUID.nameUUIDFromBytes("world:relw:${event.uuid}".toByteArray()).toString(),
            kindRaw = "relationship",
            involvedIdsJson = StringListJson.encode(listOf(event.actorId, event.targetId).sorted()),
            cityId = cityId,
            summary = event.summary,
            happenedAt = event.happenedAt,
        )

    companion object {
        /** 有戏剧价值的拍才镜像到 world_event（W4 §3.4·只搬不改）。 */
        private val MIRROR_KINDS = setOf(Beats.FIRST_MEET, Beats.QUARREL_START, Beats.QUARREL_MEND, Beats.MILESTONE)
    }
}
