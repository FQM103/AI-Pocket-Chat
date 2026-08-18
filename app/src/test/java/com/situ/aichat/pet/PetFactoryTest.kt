package com.situ.aichat.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 领养建宠（1:1 iOS PetAdoptionView.createPet）。断言反推 iOS：3% 隐藏款（roll<=3）、随机性格、初值
 * （饱0/净100/心情80/健康100/baby/none/lastInteractionDate=null）、adopted 日志。
 */
class PetFactoryTest {

    private val NOW = 1_700_000_000_000L

    /** 强制 (1..100).random→roll；nextInt(until)→0（性格首项 LIVELY / 隐藏池首项 DRAGON）。 */
    private class ForcedRollRandom(private val roll: Int) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextInt(until: Int): Int = 0
        override fun nextInt(from: Int, until: Int): Int = roll.coerceIn(from, until - 1)
    }

    @Test fun `roll above 3 keeps selected species and initial values`() {
        val p = PetFactory.createAdoptedPet("球球", PetSpecies.CAT, "c", NOW, ForcedRollRandom(50))
        assertEquals("球球", p.name)
        assertEquals("cat", p.speciesRaw)
        assertFalse(p.isHiddenSpecies)
        assertEquals("lively", p.personalityTypeRaw) // nextInt(5)=0 → 第一个 LIVELY
        assertEquals(NOW, p.adoptedDate)
        assertEquals("c", p.characterUuid)
        // 初值（entity 默认，对齐 iOS）
        assertEquals(0, p.hunger)
        assertEquals(100, p.cleanliness)
        assertEquals(80, p.happiness)
        assertEquals(100, p.health)
        assertEquals("baby", p.growthStageRaw)
        assertEquals("none", p.neglectPhaseRaw)
        assertNull(p.lastInteractionDate) // 未互动前不衰减
        // adopted 日志
        val log = p.growthLog
        assertEquals(1, log.size)
        assertEquals(PetGrowthEventType.ADOPTED.raw, log[0].type)
        assertEquals("球球被领养了！", log[0].summary)
    }

    @Test fun `roll at 3 yields hidden species`() {
        val p = PetFactory.createAdoptedPet("阿宝", PetSpecies.DOG, "c", NOW, ForcedRollRandom(3))
        assertEquals("dragon", p.speciesRaw) // 隐藏池 [dragon,unicorn,spirit] 首项
        assertTrue(p.isHiddenSpecies)
    }

    @Test fun `roll at 4 stays selected species`() {
        val p = PetFactory.createAdoptedPet("阿宝", PetSpecies.DOG, "c", NOW, ForcedRollRandom(4))
        assertEquals("dog", p.speciesRaw)
        assertFalse(p.isHiddenSpecies)
    }

    @Test fun `personality always one of five valid`() {
        // 用真实随机多跑几次，确保不抛且落在合法集合
        repeat(20) {
            val p = PetFactory.createAdoptedPet("x", PetSpecies.RABBIT, "c", NOW, Random(it.toLong()))
            assertTrue(PetPersonalityType.entries.any { e -> e.raw == p.personalityTypeRaw })
        }
    }
}
