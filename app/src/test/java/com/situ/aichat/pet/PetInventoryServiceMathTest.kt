package com.situ.aichat.pet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 宠物库存服务纯数值（[PetInventoryService.applyBoosts] / [PetInventoryService.clampStat]）。断言反推 iOS
 * `useConsumable` 第 3 步：逐维 `clamp(0,100)`、负 hunger=喂饱、null 维度不变、上下溢钳位。
 */
class PetInventoryServiceMathTest {

    private fun snap(h: Int, c: Int, hp: Int, ht: Int) = PetStatSnapshot(h, c, hp, ht)

    @Test
    fun clampStat_boundaries() {
        assertEquals(0, PetInventoryService.clampStat(-5))
        assertEquals(0, PetInventoryService.clampStat(0))
        assertEquals(50, PetInventoryService.clampStat(50))
        assertEquals(100, PetInventoryService.clampStat(100))
        assertEquals(100, PetInventoryService.clampStat(105))
    }

    @Test
    fun biscuit_lowers_hunger_raises_happiness() {
        // 小饼干 hunger-15, happiness+3；cleanliness/health 不变
        val boosts = PetStatBoosts(hunger = -15, happiness = 3)
        val r = PetInventoryService.applyBoosts(snap(50, 80, 60, 100), boosts)
        assertEquals(35, r.hunger)
        assertEquals(80, r.cleanliness)
        assertEquals(63, r.happiness)
        assertEquals(100, r.health)
    }

    @Test
    fun premium_can_clamps_overflow_and_underflow() {
        // 特级罐头 hunger-30, happiness+10, health+3
        val boosts = PetStatBoosts(hunger = -30, happiness = 10, health = 3)
        val r = PetInventoryService.applyBoosts(snap(20, 50, 95, 99), boosts)
        assertEquals(0, r.hunger)        // 20-30 = -10 → clamp 0
        assertEquals(50, r.cleanliness)  // 不变（null 维度）
        assertEquals(100, r.happiness)   // 95+10 = 105 → clamp 100
        assertEquals(100, r.health)      // 99+3 = 102 → clamp 100
    }

    @Test
    fun birthday_cake_full_boosts() {
        // 生日蛋糕 hunger-25, happiness+20, health+5
        val boosts = PetStatBoosts(hunger = -25, happiness = 20, health = 5)
        val r = PetInventoryService.applyBoosts(snap(60, 70, 40, 80), boosts)
        assertEquals(35, r.hunger)
        assertEquals(70, r.cleanliness)
        assertEquals(60, r.happiness)
        assertEquals(85, r.health)
    }

    @Test
    fun null_boosts_unchanged() {
        // 装扮 statBoosts=null → 状态值完全不变
        val base = snap(50, 50, 50, 50)
        assertEquals(base, PetInventoryService.applyBoosts(base, null))
    }
}
