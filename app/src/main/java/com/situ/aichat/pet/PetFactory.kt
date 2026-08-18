package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import java.util.UUID
import kotlin.random.Random

/**
 * 领养建宠（1:1 iOS `PetAdoptionView.createPet` + `CharacterPet.init`）。随机分配性格 + 3% 隐藏款奇幻彩蛋。
 * 纯函数（注入 [random]/[now]），便于确定性单测；其余初值用 [CharacterPetEntity] 默认（饱0/净100/心情80/
 * 健康100/baby/none/growthPoints0/lastInteractionDate=null → 未互动前不衰减，对齐 iOS）。
 */
object PetFactory {

    /** 隐藏款奇幻种类（3% 彩蛋池）。 */
    private val hiddenPool = listOf(PetSpecies.DRAGON, PetSpecies.UNICORN, PetSpecies.SPIRIT)

    fun createAdoptedPet(
        name: String,
        selectedSpecies: PetSpecies,
        characterUuid: String,
        now: Long = System.currentTimeMillis(),
        random: Random = Random.Default,
    ): CharacterPetEntity {
        val personality = PetPersonalityType.entries.random(random)
        // 3% 概率抽中隐藏款（Int.random(1...100) <= 3）
        val finalSpecies = if ((1..100).random(random) <= 3) hiddenPool.random(random) else selectedSpecies
        val log = listOf(
            PetGrowthLogEntry(
                id = UUID.randomUUID().toString(),
                timestamp = now,
                type = PetGrowthEventType.ADOPTED.raw,
                summary = "${name}被领养了！",
            ),
        )
        return CharacterPetEntity(
            uuid = UUID.randomUUID().toString(),
            name = name,
            speciesRaw = finalSpecies.raw,
            isHiddenSpecies = finalSpecies.isHidden,
            personalityTypeRaw = personality.raw,
            adoptedDate = now,
            characterUuid = characterUuid,
            petGrowthLogJson = PetJson.encodeGrowthLog(log),
        )
    }
}
