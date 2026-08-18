package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.situ.aichat.data.local.entity.CharacterPetEntity
import kotlinx.coroutines.flow.Flow

/**
 * 宠物读写（M11）。1 角色 : 0~1 宠物，按 characterUuid 唯一定位。`observeForCharacter` 供详情页响应式
 * （照顾/衰减后自动刷新）；`getAll` 供跨角色社交注入（其他角色的宠物）+ 启动批量衰减。
 */
@Dao
interface PetDao {

    @Query("SELECT * FROM character_pet WHERE characterUuid = :characterUuid LIMIT 1")
    suspend fun getForCharacter(characterUuid: String): CharacterPetEntity?

    @Query("SELECT * FROM character_pet WHERE characterUuid = :characterUuid LIMIT 1")
    fun observeForCharacter(characterUuid: String): Flow<CharacterPetEntity?>

    @Query("SELECT * FROM character_pet WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): CharacterPetEntity?

    @Query("SELECT * FROM character_pet")
    suspend fun getAll(): List<CharacterPetEntity>

    /** 响应式全部宠物（宠物列表枢纽：领养后自动刷新）。 */
    @Query("SELECT * FROM character_pet")
    fun observeAll(): Flow<List<CharacterPetEntity>>

    @Query("SELECT COUNT(*) FROM character_pet")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pet: CharacterPetEntity)

    @Query("DELETE FROM character_pet WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)
}
