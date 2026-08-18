package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity

/**
 * [CharacterPetEntity] 的派生访问器（项目惯例：JSON 列 + 扩展访问器，无 Room TypeConverter；同
 * diary/moment 的 imagePaths）。enum 走 rawValue 解码，metadata/growthLog 走 [PetJson]。
 */

val CharacterPetEntity.species: PetSpecies get() = PetSpecies.fromRaw(speciesRaw)
val CharacterPetEntity.growthStage: PetGrowthStage get() = PetGrowthStage.fromRaw(growthStageRaw)
val CharacterPetEntity.personalityType: PetPersonalityType get() = PetPersonalityType.fromRaw(personalityTypeRaw)
val CharacterPetEntity.neglectPhase: PetNeglectPhase get() = PetNeglectPhase.fromRaw(neglectPhaseRaw)
val CharacterPetEntity.metadata: PetMetadata get() = PetJson.decodeMetadata(petMetadataJson)
val CharacterPetEntity.growthLog: List<PetGrowthLogEntry> get() = PetJson.decodeGrowthLog(petGrowthLogJson)
