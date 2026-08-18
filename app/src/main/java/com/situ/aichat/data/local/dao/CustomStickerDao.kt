package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.situ.aichat.data.local.entity.CustomStickerEntity
import kotlinx.coroutines.flow.Flow

/**
 * Custom sticker store (M17). iOS uses a SwiftData `FetchDescriptor` sorted by `createdAt`; here the
 * prompt path needs ascending order (deterministic alias map) while the management page observes
 * descending (newest first), so both are explicit queries.
 */
@Dao
interface CustomStickerDao {

    /** Prompt 用：按 createdAt 升序（保证 `buildCustomStickerAliasMap` 确定性）。 */
    @Query("SELECT * FROM custom_sticker ORDER BY createdAt ASC")
    suspend fun getAllOrderByCreatedAtAsc(): List<CustomStickerEntity>

    /** 管理页用：按 createdAt 降序响应式观察（新导入排最前）。 */
    @Query("SELECT * FROM custom_sticker ORDER BY createdAt DESC")
    fun observeAllOrderByCreatedAtDesc(): Flow<List<CustomStickerEntity>>

    @Query("SELECT * FROM custom_sticker WHERE stickerUuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): CustomStickerEntity?

    @Query("SELECT COUNT(*) FROM custom_sticker")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sticker: CustomStickerEntity)

    @Query("DELETE FROM custom_sticker WHERE stickerUuid = :uuid")
    suspend fun delete(uuid: String)
}
