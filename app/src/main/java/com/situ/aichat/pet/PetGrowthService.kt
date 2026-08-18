package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import java.util.UUID

/**
 * 宠物成长/进化（1:1 iOS `PetGrowthService`）。每次照顾后检查累计 growthPoints 是否达当前阶段阈值，
 * 达到则升一阶；adult→special 时普通宠物变身为奇幻种类（cat→精灵/dog→龙/rabbit→独角兽/hamster→精灵）。
 * 纯函数（entity 进 → 结果出）；进化金币奖励由调用方在 P9 接入（按 newStage 去重发放）。
 */
object PetGrowthService {

    data class EvolveResult(
        val pet: CharacterPetEntity,
        val didEvolve: Boolean,
        val didTransform: Boolean,
        val newStage: PetGrowthStage,
    )

    /** 检查并升级一阶（积分够才升）。1:1 iOS `checkAndEvolve`。 */
    fun checkAndEvolve(pet: CharacterPetEntity, now: Long = System.currentTimeMillis()): EvolveResult {
        val currentStage = PetGrowthStage.fromRaw(pet.growthStageRaw)
        val threshold = PetGrowthThresholds.threshold(currentStage)
        val nextStage = PetGrowthThresholds.nextStage(currentStage)
        if (threshold == null || nextStage == null) return EvolveResult(pet, false, false, currentStage)
        if (pet.growthPoints < threshold) return EvolveResult(pet, false, false, currentStage)

        var speciesRaw = pet.speciesRaw
        var isHiddenSpecies = pet.isHiddenSpecies
        var metadata = pet.metadata
        var didTransform = false
        if (nextStage == PetGrowthStage.SPECIAL && !pet.isHiddenSpecies) {
            val original = PetSpecies.fromRaw(pet.speciesRaw)
            val newSpecies = specialFormSpecies(original)
            speciesRaw = newSpecies.raw
            isHiddenSpecies = true
            metadata = metadata.copy(unlockSource = "从${original.displayName}进化")
            didTransform = true
        }

        val logType = if (didTransform) PetGrowthEventType.EVOLVED else PetGrowthEventType.STAGE_UP
        val summary = if (didTransform) {
            "${pet.name}进化为${PetSpecies.fromRaw(speciesRaw).displayName}了！"
        } else {
            "${pet.name}成长为${nextStage.displayName}了！"
        }
        val entry = PetGrowthLogEntry(UUID.randomUUID().toString(), now, logType.raw, summary)
        val log = (pet.growthLog + entry).let { if (it.size > 50) it.takeLast(50) else it }

        val updated = pet.copy(
            growthStageRaw = nextStage.raw,
            speciesRaw = speciesRaw,
            isHiddenSpecies = isHiddenSpecies,
            petMetadataJson = PetJson.encodeMetadata(metadata),
            petGrowthLogJson = PetJson.encodeGrowthLog(log),
        )
        return EvolveResult(updated, true, didTransform, nextStage)
    }

    /** 普通种类进化对应的奇幻种类（1:1 iOS `specialFormSpecies`）。 */
    private fun specialFormSpecies(original: PetSpecies): PetSpecies = when (original) {
        PetSpecies.CAT -> PetSpecies.SPIRIT
        PetSpecies.DOG -> PetSpecies.DRAGON
        PetSpecies.RABBIT -> PetSpecies.UNICORN
        PetSpecies.HAMSTER -> PetSpecies.SPIRIT
        else -> original
    }
}
