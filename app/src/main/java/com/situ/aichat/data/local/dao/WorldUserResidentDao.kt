package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.situ.aichat.data.local.entity.WorldUserResidentEntity
import kotlinx.coroutines.flow.Flow

/**
 * 用户自建居民静态人设数据访问（世界二期战役 B·图纸 §3.1）。只读写 `world_user_resident`（PK slug）；
 * 运行态（发现/眼缘/招募指针）在 [WorldNativeDao]。生命周期唯一写入口 = `WorldResidentService`，UI/VM 绝不直碰本 DAO。
 */
@Dao
interface WorldUserResidentDao {

    @Upsert
    suspend fun upsert(entity: WorldUserResidentEntity)

    @Query("SELECT * FROM world_user_resident WHERE slug = :slug")
    suspend fun get(slug: String): WorldUserResidentEntity?

    @Query("SELECT * FROM world_user_resident")
    suspend fun getAll(): List<WorldUserResidentEntity>

    @Query("DELETE FROM world_user_resident WHERE slug = :slug")
    suspend fun delete(slug: String)

    @Query("SELECT COUNT(*) FROM world_user_resident")
    suspend fun count(): Int

    /** 设置页「已有 n/50 位」计数回显（图纸 §2 WorldSettingsViewModel·count 的 observe 形态）。 */
    @Query("SELECT COUNT(*) FROM world_user_resident")
    fun observeCount(): Flow<Int>
}
