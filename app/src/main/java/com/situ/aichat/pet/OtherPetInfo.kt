package com.situ.aichat.pet

/**
 * 其他角色宠物的社交注入信息（供 petStatus 提示词的「其他角色也养了宠物」段）。调用方从全部宠物 +
 * 角色名解析后传入（1:1 iOS `buildOtherPetsInfo` 里 `p.character?.name` 的安卓等价）。
 */
data class OtherPetInfo(
    val characterName: String,
    val petName: String,
    val species: PetSpecies,
    val stage: PetGrowthStage,
)
