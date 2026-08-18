package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.PetDao
import com.situ.aichat.data.local.entity.CharacterPetEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 宠物聚合读写（M11）。薄封装 [PetDao]：按角色/uuid 查、跨角色取全部（社交注入 + 启动批量衰减）、
 * upsert、删除。养护/衰减/成长的业务逻辑在服务层（PetCareService/PetGrowthService，8.2b），repo 只管持久化。
 */
@Singleton
class PetRepository @Inject constructor(
    private val dao: PetDao,
) {
    suspend fun getForCharacter(characterUuid: String): CharacterPetEntity? = dao.getForCharacter(characterUuid)
    fun observeForCharacter(characterUuid: String): Flow<CharacterPetEntity?> = dao.observeForCharacter(characterUuid)
    suspend fun getByUuid(uuid: String): CharacterPetEntity? = dao.getByUuid(uuid)
    suspend fun getAll(): List<CharacterPetEntity> = dao.getAll()
    fun observeAll(): Flow<List<CharacterPetEntity>> = dao.observeAll()
    suspend fun count(): Int = dao.count()
    suspend fun upsert(pet: CharacterPetEntity) = dao.upsert(pet)
    suspend fun delete(uuid: String) = dao.deleteByUuid(uuid)
}
