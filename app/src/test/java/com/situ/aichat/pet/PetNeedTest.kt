package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 需求标题优先级 + 成长进度纯逻辑（S1）。断言反推 §2 规格：
 * 优先级 ranAway>sick>hunger≥70>cleanliness≤30>happiness<30>happiness≥80>满足；成长 = growthPoints/阶段阈值钳 0~1。
 */
class PetNeedTest {

    private fun pet(
        neglect: String = "none",
        hunger: Int = 0,
        cleanliness: Int = 100,
        happiness: Int = 80,
        stage: String = "baby",
        growthPoints: Int = 0,
    ) = CharacterPetEntity(
        neglectPhaseRaw = neglect,
        hunger = hunger,
        cleanliness = cleanliness,
        happiness = happiness,
        growthStageRaw = stage,
        growthPoints = growthPoints,
    )

    // ---- petNeedHeadline 优先级 ----

    @Test fun `ranAway wins over every status`() {
        // 同时又饿又脏又病也只报「寻回」。
        assertEquals(PetCareAction.RETRIEVE, petNeedHeadline(pet(neglect = "ranAway", hunger = 99, cleanliness = 0, happiness = 0)).action)
    }

    @Test fun `sick wins over hunger`() {
        assertEquals(PetCareAction.TREAT, petNeedHeadline(pet(neglect = "sick", hunger = 99)).action)
    }

    @Test fun `hunger triggers at 70 boundary and outranks dirty`() {
        assertEquals(PetCareAction.FEED, petNeedHeadline(pet(hunger = 70, cleanliness = 0)).action) // 边界 70 命中
        assertNotEquals(PetCareAction.FEED, petNeedHeadline(pet(hunger = 69, cleanliness = 100, happiness = 50)).action) // 69 不触发
    }

    @Test fun `dirty triggers at cleanliness 30 boundary when not hungry`() {
        assertEquals(PetCareAction.CLEAN, petNeedHeadline(pet(hunger = 69, cleanliness = 30)).action) // 边界 30 命中
        assertNotEquals(PetCareAction.CLEAN, petNeedHeadline(pet(cleanliness = 31, happiness = 50)).action) // 31 不触发
    }

    @Test fun `sad triggers below happiness 30 when clean and fed`() {
        assertEquals(PetCareAction.PLAY, petNeedHeadline(pet(hunger = 0, cleanliness = 100, happiness = 29)).action) // 边界 29 命中
        assertNotEquals(PetCareAction.PLAY, petNeedHeadline(pet(happiness = 30)).action) // 30 不算难过
    }

    @Test fun `happy at 80 and satisfied between 30 and 80 both have no urgent action but differ`() {
        val happy = petNeedHeadline(pet(happiness = 80))      // 边界 80
        val satisfied = petNeedHeadline(pet(happiness = 79))  // 79 = 满足
        val alsoSatisfied = petNeedHeadline(pet(happiness = 30)) // 30 = 满足（既非难过也非开心）
        assertNull(happy.action)
        assertNull(satisfied.action)
        assertNull(alsoSatisfied.action)
        assertNotEquals(happy, satisfied) // 开心 ≠ 满足（不同 emoji/文案）
    }

    // ---- growthProgressFraction ----

    @Test fun `growth fraction is points over current stage threshold`() {
        assertEquals(0.5f, growthProgressFraction(pet(stage = "baby", growthPoints = 50)), 1e-4f) // 50/100
        assertEquals(0.5f, growthProgressFraction(pet(stage = "young", growthPoints = 150)), 1e-4f) // 150/300
        assertEquals(0.6f, growthProgressFraction(pet(stage = "teen", growthPoints = 360)), 1e-4f) // 360/600
        assertEquals(0.4f, growthProgressFraction(pet(stage = "adult", growthPoints = 400)), 1e-4f) // 400/1000
    }

    @Test fun `growth fraction clamps to 1 and zero`() {
        assertEquals(1f, growthProgressFraction(pet(stage = "baby", growthPoints = 250)), 1e-4f) // 250/100 钳 1
        assertEquals(0f, growthProgressFraction(pet(stage = "baby", growthPoints = 0)), 1e-4f)
    }

    @Test fun `special stage is full`() {
        assertEquals(1f, growthProgressFraction(pet(stage = "special", growthPoints = 0)), 1e-4f) // 满级 threshold=null
    }
}
