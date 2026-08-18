package com.situ.aichat.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定宠物枚举与平衡常量（1:1 iOS PetTypes.swift）。值反推自 iOS：阈值 baby100/young300/teen600/adult1000、
 * 恢复 3/5/0.2、技能 10/30/60/100、4 普通物种 + 3 隐藏款。
 */
class PetTypesTest {

    @Test fun `species fromRaw + fallback`() {
        assertEquals(PetSpecies.DRAGON, PetSpecies.fromRaw("dragon"))
        assertEquals(PetSpecies.CAT, PetSpecies.fromRaw("未知")) // 未知回退 cat
    }

    @Test fun `normal species are the 4 non-hidden`() {
        val normal = PetSpecies.normalSpecies
        assertEquals(4, normal.size)
        assertTrue(normal.none { it.isHidden })
        assertEquals(listOf(PetSpecies.CAT, PetSpecies.DOG, PetSpecies.RABBIT, PetSpecies.HAMSTER), normal)
    }

    @Test fun `hidden species flagged`() {
        assertTrue(PetSpecies.DRAGON.isHidden)
        assertTrue(PetSpecies.UNICORN.isHidden)
        assertTrue(PetSpecies.SPIRIT.isHidden)
        assertFalse(PetSpecies.CAT.isHidden)
    }

    @Test fun `growth stage fromRaw fallback baby`() {
        assertEquals(PetGrowthStage.SPECIAL, PetGrowthStage.fromRaw("special"))
        assertEquals(PetGrowthStage.BABY, PetGrowthStage.fromRaw("???"))
    }

    @Test fun `neglect severity increases by phase`() {
        assertTrue(PetNeglectPhase.NONE.severity < PetNeglectPhase.UNHAPPY.severity)
        assertTrue(PetNeglectPhase.UNHAPPY.severity < PetNeglectPhase.UPSET.severity)
        assertTrue(PetNeglectPhase.UPSET.severity < PetNeglectPhase.SICK.severity)
        assertTrue(PetNeglectPhase.SICK.severity < PetNeglectPhase.RAN_AWAY.severity)
        assertEquals(PetNeglectPhase.NONE, PetNeglectPhase.fromRaw("bad"))
    }

    @Test fun `growth thresholds match iOS`() {
        assertEquals(100, PetGrowthThresholds.threshold(PetGrowthStage.BABY))
        assertEquals(300, PetGrowthThresholds.threshold(PetGrowthStage.YOUNG))
        assertEquals(600, PetGrowthThresholds.threshold(PetGrowthStage.TEEN))
        assertEquals(1000, PetGrowthThresholds.threshold(PetGrowthStage.ADULT))
        assertNull(PetGrowthThresholds.threshold(PetGrowthStage.SPECIAL))
    }

    @Test fun `next stage chain`() {
        assertEquals(PetGrowthStage.YOUNG, PetGrowthThresholds.nextStage(PetGrowthStage.BABY))
        assertEquals(PetGrowthStage.SPECIAL, PetGrowthThresholds.nextStage(PetGrowthStage.ADULT))
        assertNull(PetGrowthThresholds.nextStage(PetGrowthStage.SPECIAL))
    }

    @Test fun `recovery + trick constants`() {
        assertEquals(3, PetRecoveryThresholds.TREATMENTS_TO_HEAL)
        assertEquals(5, PetRecoveryThresholds.ATTEMPTS_TO_FIND)
        assertEquals(0.2, PetRecoveryThresholds.TRUST_RECOVERY_PER_CARE, 0.0001)
        assertEquals(4, PetTrickMilestones.milestones.size)
        assertEquals(PetTrickMilestones.Trick(10, "sit", "坐下"), PetTrickMilestones.milestones[0])
        assertEquals(PetTrickMilestones.Trick(100, "dance", "跳舞"), PetTrickMilestones.milestones[3])
    }
}
