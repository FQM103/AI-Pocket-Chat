package com.situ.aichat.economy

import com.situ.aichat.pet.PetGrowthStage
import org.junit.Assert.assertEquals
import org.junit.Test

/** 里程碑金币奖励纯函数单测（断言反推 iOS 奖励值 + 去重 key 格式）。 */
class MilestoneRewardServiceTest {

    @Test
    fun evolution_reward_values_match_ios() {
        assertEquals(0, evolutionReward(PetGrowthStage.BABY))
        assertEquals(50, evolutionReward(PetGrowthStage.YOUNG))
        assertEquals(100, evolutionReward(PetGrowthStage.TEEN))
        assertEquals(150, evolutionReward(PetGrowthStage.ADULT))
        assertEquals(300, evolutionReward(PetGrowthStage.SPECIAL))
    }

    @Test
    fun relationship_reward_is_200() = assertEquals(200, RELATIONSHIP_MILESTONE_REWARD)

    @Test
    fun pet_grow_key_uses_stage_raw() {
        assertEquals("pet_grow_abc_young", petGrowKey("abc", PetGrowthStage.YOUNG))
        assertEquals("pet_grow_abc_special", petGrowKey("abc", PetGrowthStage.SPECIAL))
    }

    @Test
    fun rel_milestone_key_uses_epoch_seconds() {
        // 毫秒 / 1000 = 秒（对齐 iOS Int(timeIntervalSince1970)）
        assertEquals("rel_milestone_c_1700000000", relMilestoneKey("c", 1_700_000_000_500L))
        assertEquals("rel_milestone_c_1700000000", relMilestoneKey("c", 1_700_000_000_999L))
    }
}
