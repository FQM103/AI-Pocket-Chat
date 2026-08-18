package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * 原住民状态数据访问（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §8 / §11 / W1 图纸 §3）。
 * 花名册（slug→人设）在 W3 代码内定义，本 DAO 只读写运行态。眼缘阈值/招募逻辑属 W6，本 DAO 只做 CRUD。
 */
@Dao
interface WorldNativeDao {

    @Upsert
    suspend fun upsert(state: WorldNativeStateEntity)

    @Upsert
    suspend fun upsertAll(states: List<WorldNativeStateEntity>)

    @Query("SELECT * FROM world_native_state WHERE nativeId = :nativeId")
    suspend fun get(nativeId: String): WorldNativeStateEntity?

    /** 反查：某正式角色是否由原住民招募而来（W13 leave 守卫·原住民出身不可离开世界）。 */
    @Query("SELECT * FROM world_native_state WHERE recruitedCharacterUuid = :characterUuid")
    suspend fun getByRecruitedUuid(characterUuid: String): WorldNativeStateEntity?

    @Query("SELECT * FROM world_native_state")
    suspend fun getAll(): List<WorldNativeStateEntity>

    @Query("SELECT * FROM world_native_state")
    fun observeAll(): Flow<List<WorldNativeStateEntity>>

    /**
     * 删除某个被招募成正式角色的角色 → 把对应原住民的招募指针清空、**眼缘/心意燃料归零**
     * （契约 §11「缘分归零」）。删角色清理走仓库层事务，与关系边/事件/旅行/世界事件一并回滚。
     */
    @Query(
        "UPDATE world_native_state SET recruitedCharacterUuid = NULL, narrativeFuel = 0, giftFuel = 0 " +
            "WHERE recruitedCharacterUuid = :characterUuid",
    )
    suspend fun resetRecruitment(characterUuid: String)

    /**
     * 删单条运行态行（世界二期战役 B·图纸 §3.3）：用户自建居民「送 TA 离开」/ 已招募被删（O2 彻底消失）时，
     * 连同 def 行一并删（官方原住民走 [resetRecruitment] 缘分归零、绝不删行）。由 `WorldResidentService` 事务内调。
     */
    @Query("DELETE FROM world_native_state WHERE nativeId = :nativeId")
    suspend fun deleteByNativeId(nativeId: String)
}
