package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.WorldSocialDao
import com.situ.aichat.data.local.entity.WorldRelationshipEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.world.WorldIds
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 角色↔角色关系边 / 关系事件的仓库门面 + 参与者清理/休眠（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §8.A / W1 图纸 §2）。
 *
 * 只做存储与通道：有向边 CRUD、事件流水读写、离开世界休眠、参与者移除——**不做任何数值钳位/关系推演/弧线压缩**
 * （那属 W4）。删角色的整段世界痕迹清理在 [CharacterRepository] 的单事务里（直接调 DAO），本门面供 W4+ 业务层调用。
 */
@Singleton
class WorldSocialRepository @Inject constructor(
    private val socialDao: WorldSocialDao,
) {
    // MARK: - 有向关系边

    suspend fun upsertEdge(edge: WorldRelationshipEntity) = socialDao.upsertEdge(edge)

    suspend fun getEdge(fromId: String, toId: String): WorldRelationshipEntity? = socialDao.getEdge(fromId, toId)

    /** TA 对别人的关系（出向）。 */
    suspend fun edgesFrom(id: String): List<WorldRelationshipEntity> = socialDao.edgesFrom(id)

    /** 别人对 TA 的关系（入向）。 */
    suspend fun edgesTo(id: String): List<WorldRelationshipEntity> = socialDao.edgesTo(id)

    fun observeEdges(): Flow<List<WorldRelationshipEntity>> = socialDao.observeEdges()

    // MARK: - 关系事件流水

    suspend fun recordEvent(event: WorldRelationshipEventEntity) = socialDao.upsertEvent(event)

    /** 两人之间的事件历史（无向对键·按发生先后）。 */
    suspend fun eventsForPair(a: String, b: String): List<WorldRelationshipEventEntity> =
        socialDao.eventsForPair(WorldIds.pairKey(a, b))

    // MARK: - 参与者清理 / 休眠

    /** 移除参与者的全部关系痕迹（两向边 + 关系事件）。删角色场景由 [CharacterRepository] 单事务内直接调 DAO。 */
    suspend fun removeParticipant(id: String) {
        socialDao.deleteEdgesFor(id)
        socialDao.deleteEventsFor(id)
    }

    /** 离开世界 → 把与该参与者相关的两向边置休眠（回来再点亮·契约 §8）。 */
    suspend fun setDormant(id: String, dormant: Boolean) = socialDao.setDormantFor(id, dormant)
}
