package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.entity.WorldCityLoreEntity
import com.situ.aichat.data.local.entity.WorldDiscoveryEntity
import com.situ.aichat.data.local.entity.WorldEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldTravelEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 世界状态 / 旅行 / 世界事件 / 风物志 / 发现的仓库门面（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §5 / W1 图纸 §2）。
 *
 * 只做存储与通道：首次建种（[ensureState]）、单调锚推进、CRUD 转发——**不含任何世界时钟/懒结算/生成/通知逻辑**
 * （那属 W2+）。关系边/事件门面见 [WorldSocialRepository]，删角色的世界痕迹清理在 [CharacterRepository]。
 */
@Singleton
class WorldRepository @Inject constructor(
    private val worldDao: WorldDao,
) {
    /** 防并发重入建双行（照 CharacterRepository 的每角色轻锁思路）：[ensureState] 首建走串行化。 */
    private val ensureStateMutex = Mutex()

    /**
     * 拿世界单行状态，没有就建一行并落库（seed = `Random.nextLong()`、createdAt = now，其余列用默认）。
     * 双检 + Mutex：两处并发调只建**一行、一个种子**（E9）。世界从未初始化时读侧仍可先拿 null（[getState]）。
     */
    suspend fun ensureState(): WorldStateEntity =
        worldDao.getState() ?: ensureStateMutex.withLock {
            worldDao.getState() ?: WorldStateEntity(
                seed = Random.nextLong(),
                createdAt = System.currentTimeMillis(),
            ).also { worldDao.upsertState(it) }
        }

    suspend fun getState(): WorldStateEntity? = worldDao.getState()

    fun observeState(): Flow<WorldStateEntity?> = worldDao.observeState()

    /** 懒结算单调锚推进（只进不退在 SQL 层保证·天然幂等可重放）。 */
    suspend fun advanceSettledAt(at: Long) = worldDao.advanceSettledAt(at)

    // MARK: - 在途旅行

    suspend fun upsertTravel(travel: WorldTravelEntity) = worldDao.upsertTravel(travel)

    suspend fun getTravel(ownerId: String): WorldTravelEntity? = worldDao.getTravel(ownerId)

    suspend fun clearTravel(ownerId: String) = worldDao.deleteTravel(ownerId)

    fun observeTravels(): Flow<List<WorldTravelEntity>> = worldDao.observeTravels()

    // MARK: - 世界事件

    suspend fun recordEvent(event: WorldEventEntity) = worldDao.upsertEvent(event)

    fun observeEvents(): Flow<List<WorldEventEntity>> = worldDao.observeEvents()

    suspend fun unseenEvents(): List<WorldEventEntity> = worldDao.unseenEvents()

    suspend fun markEventSeen(uuid: String, at: Long) = worldDao.markEventSeen(uuid, at)

    suspend fun markEventNotified(uuid: String, at: Long) = worldDao.markEventNotified(uuid, at)

    // MARK: - 首访风物志（一次定稿 canon 永久）

    /** 首访点亮：已存在则不覆盖（IGNORE·canon 永久·契约 §7.A）。 */
    suspend fun canonizeLore(lore: WorldCityLoreEntity) = worldDao.insertLore(lore)

    suspend fun getLore(cityId: String): WorldCityLoreEntity? = worldDao.getLore(cityId)

    // MARK: - 发现记录（IGNORE 幂等）

    suspend fun recordDiscovery(placeId: String, at: Long) =
        worldDao.insertDiscovery(WorldDiscoveryEntity(placeId = placeId, discoveredAt = at))

    suspend fun isDiscovered(placeId: String): Boolean = worldDao.getDiscovery(placeId) != null
}
