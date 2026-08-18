package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宠物成长/进化（1:1 iOS PetGrowthService）。断言反推 iOS：累计阈值 100/300/600/1000，adult→special 普通宠
 * 变身奇幻（cat→spirit/dog→dragon/rabbit→unicorn/hamster→spirit），隐藏款不再变身，满级不升。
 */
class PetGrowthServiceTest {

    private val NOW = 1_700_000_000_000L

    private fun pet(stage: String, points: Int, species: String = "cat", hidden: Boolean = false) =
        CharacterPetEntity(
            uuid = "p", name = "球球", speciesRaw = species, isHiddenSpecies = hidden,
            growthStageRaw = stage, growthPoints = points, characterUuid = "c",
        )

    @Test fun `evolve baby to young at 100 points`() {
        val r = PetGrowthService.checkAndEvolve(pet("baby", 100), NOW)
        assertTrue(r.didEvolve)
        assertFalse(r.didTransform)
        assertEquals(PetGrowthStage.YOUNG, r.newStage)
        assertEquals("young", r.pet.growthStageRaw)
    }

    @Test fun `no evolve below threshold`() {
        assertFalse(PetGrowthService.checkAndEvolve(pet("baby", 99), NOW).didEvolve)
    }

    @Test fun `cat adult to special transforms to spirit`() {
        val r = PetGrowthService.checkAndEvolve(pet("adult", 1000, "cat"), NOW)
        assertTrue(r.didEvolve)
        assertTrue(r.didTransform)
        assertEquals(PetGrowthStage.SPECIAL, r.newStage)
        assertEquals("spirit", r.pet.speciesRaw)
        assertTrue(r.pet.isHiddenSpecies)
        assertEquals("从猫咪进化", r.pet.metadata.unlockSource)
    }

    @Test fun `species transform mapping`() {
        assertEquals("dragon", PetGrowthService.checkAndEvolve(pet("adult", 1000, "dog"), NOW).pet.speciesRaw)
        assertEquals("unicorn", PetGrowthService.checkAndEvolve(pet("adult", 1000, "rabbit"), NOW).pet.speciesRaw)
        assertEquals("spirit", PetGrowthService.checkAndEvolve(pet("adult", 1000, "hamster"), NOW).pet.speciesRaw)
    }

    @Test fun `special stage never evolves`() {
        assertFalse(PetGrowthService.checkAndEvolve(pet("special", 99999), NOW).didEvolve)
    }

    @Test fun `hidden species adult to special does not transform`() {
        val r = PetGrowthService.checkAndEvolve(pet("adult", 1000, "dragon", hidden = true), NOW)
        assertTrue(r.didEvolve)
        assertFalse(r.didTransform)
        assertEquals(PetGrowthStage.SPECIAL, r.newStage)
        assertEquals("dragon", r.pet.speciesRaw) // 已隐藏款，保持不变
    }
}
