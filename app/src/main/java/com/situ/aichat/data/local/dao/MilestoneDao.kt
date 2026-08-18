package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.MilestoneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MilestoneDao {
    @Query("SELECT * FROM relationship_milestones WHERE characterUuid = :characterUuid ORDER BY establishedDate ASC")
    fun observeForCharacter(characterUuid: String): Flow<List<MilestoneEntity>>

    /** 全量里程碑（联系人列表用·按日期升序）：调用方按 characterUuid 分组取末项＝每角色最新关系称谓。 */
    @Query("SELECT * FROM relationship_milestones ORDER BY establishedDate ASC")
    fun observeAll(): Flow<List<MilestoneEntity>>

    @Query("SELECT * FROM relationship_milestones WHERE characterUuid = :characterUuid ORDER BY establishedDate ASC")
    suspend fun getForCharacter(characterUuid: String): List<MilestoneEntity>

    @Upsert
    suspend fun upsert(milestone: MilestoneEntity)

    @Delete
    suspend fun delete(milestone: MilestoneEntity)
}
