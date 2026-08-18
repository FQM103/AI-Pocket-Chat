package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宠物日记/状态文案纯函数（1:1 iOS PetDiaryGenerationService / buildPetContextForDiary）。断言反推 iOS：
 * mood emoji 优先级与阈值、状态档位边界（hunger<=30/>=70 等）、角色日记宠物摘要行格式、技能按里程碑序。
 */
class PetDiaryPromptsTest {

    private fun pet(
        name: String = "球球",
        speciesRaw: String = "cat",
        growthStageRaw: String = "baby",
        personalityRaw: String = "lively",
        hunger: Int = 0,
        cleanliness: Int = 100,
        happiness: Int = 80,
        health: Int = 100,
        metadata: PetMetadata = PetMetadata.EMPTY,
    ) = CharacterPetEntity(
        uuid = "p", name = name, speciesRaw = speciesRaw, personalityTypeRaw = personalityRaw,
        growthStageRaw = growthStageRaw, hunger = hunger, cleanliness = cleanliness,
        happiness = happiness, health = health,
        petMetadataJson = PetJson.encodeMetadata(metadata), characterUuid = "c",
    )

    // ---- petMoodEmoji（优先级 happiness>=80 > happiness<=30 > hunger>=70 > 默认） ----

    @Test fun `mood happy when happiness ge 80`() {
        assertEquals("😸", PetDiaryPrompts.petMoodEmoji(pet(happiness = 80, hunger = 90)))
    }

    @Test fun `mood sad when happiness le 30`() {
        assertEquals("😿", PetDiaryPrompts.petMoodEmoji(pet(happiness = 30, hunger = 90)))
    }

    @Test fun `mood hungry when not happy nor sad but hunger ge 70`() {
        assertEquals("🍽️", PetDiaryPrompts.petMoodEmoji(pet(happiness = 50, hunger = 70)))
    }

    @Test fun `mood paw default`() {
        assertEquals("🐾", PetDiaryPrompts.petMoodEmoji(pet(happiness = 79, hunger = 69)))
    }

    // ---- buildPetContextForDiary ----

    @Test fun `pet context empty when no pets`() {
        assertEquals("", PetDiaryPrompts.buildPetContextForDiary(emptyList()))
    }

    @Test fun `pet context line format`() {
        val line = PetDiaryPrompts.buildPetContextForDiary(listOf(pet(happiness = 70)))
        assertEquals("球球（猫咪）：开心，宝宝阶段", line)
    }

    @Test fun `pet context mood tiers`() {
        assertTrue(PetDiaryPrompts.buildPetContextForDiary(listOf(pet(happiness = 30))).contains("：不开心，"))
        assertTrue(PetDiaryPrompts.buildPetContextForDiary(listOf(pet(happiness = 50))).contains("：还好，"))
    }

    @Test fun `pet context joins multiple pets by newline`() {
        val out = PetDiaryPrompts.buildPetContextForDiary(
            listOf(pet(name = "A", happiness = 80), pet(name = "B", speciesRaw = "dog", happiness = 20, growthStageRaw = "adult")),
        )
        assertEquals("A（猫咪）：开心，宝宝阶段\nB（狗狗）：不开心，成年阶段", out)
    }

    // ---- buildPetDiaryPrompt 档位 + 头部 ----

    @Test fun `diary prompt header uses name species personality`() {
        val p = PetDiaryPrompts.buildPetDiaryPrompt(pet(personalityRaw = "lively"), "小明")
        assertTrue(p.startsWith("你是一只叫球球的猫咪，性格活泼。"))
        assertTrue(p.contains("你的主人是小明。"))
        assertTrue(p.contains("## 我今天的状态"))
        assertTrue(p.endsWith("只输出日记内容，不要标题和额外解释。"))
    }

    @Test fun `diary hunger tiers`() {
        assertTrue(PetDiaryPrompts.buildPetDiaryPrompt(pet(hunger = 30), "主人").contains("饱不饱：吃得饱饱的"))
        assertTrue(PetDiaryPrompts.buildPetDiaryPrompt(pet(hunger = 50), "主人").contains("饱不饱：有点饿了"))
        assertTrue(PetDiaryPrompts.buildPetDiaryPrompt(pet(hunger = 70), "主人").contains("饱不饱：好饿啊"))
    }

    @Test fun `diary cleanliness happiness health tiers`() {
        val dirtySadSick = PetDiaryPrompts.buildPetDiaryPrompt(pet(cleanliness = 69, happiness = 30, health = 69), "主人")
        assertTrue(dirtySadSick.contains("干不干净：有点脏了"))
        assertTrue(dirtySadSick.contains("开不开心：不太开心"))
        assertTrue(dirtySadSick.contains("身体：有点不舒服"))
        val cleanOkHealthy = PetDiaryPrompts.buildPetDiaryPrompt(pet(cleanliness = 70, happiness = 50, health = 70), "主人")
        assertTrue(cleanOkHealthy.contains("干不干净：香香的"))
        assertTrue(cleanOkHealthy.contains("开不开心：还行吧"))
        assertTrue(cleanOkHealthy.contains("身体：健健康康"))
        assertTrue(PetDiaryPrompts.buildPetDiaryPrompt(pet(happiness = 70), "主人").contains("开不开心：很开心！"))
    }

    @Test fun `diary tricks listed in milestone order`() {
        // 存储顺序 roll,sit → 输出按里程碑序 sit(坐下) 先于 roll(打滚)
        val p = PetDiaryPrompts.buildPetDiaryPrompt(
            pet(metadata = PetMetadata.EMPTY.copy(learnedTricks = listOf("roll", "sit"))), "主人",
        )
        assertTrue(p.contains("我会的技能：坐下、打滚"))
    }

    @Test fun `diary recent souvenirs take last 3`() {
        val souvenirs = listOf(
            PetSouvenir(name = "小石头", emoji = "🪨"),
            PetSouvenir(name = "四叶草", emoji = "🍀"),
            PetSouvenir(name = "贝壳", emoji = "🐚"),
            PetSouvenir(name = "松果", emoji = "🌰"),
        )
        val p = PetDiaryPrompts.buildPetDiaryPrompt(pet(metadata = PetMetadata.EMPTY.copy(souvenirs = souvenirs)), "主人")
        assertTrue(p.contains("最近散步捡到的宝贝：🍀四叶草、🐚贝壳、🌰松果"))
        assertFalse(p.contains("🪨小石头")) // 只取最近 3 个
    }

    @Test fun `diary omits optional sections when empty`() {
        val p = PetDiaryPrompts.buildPetDiaryPrompt(pet(), "主人")
        assertFalse(p.contains("## 最近发生的事"))
        assertFalse(p.contains("我会的技能"))
        assertFalse(p.contains("最近散步捡到的宝贝"))
    }
}
