package com.situ.aichat.ui.pet

import com.situ.aichat.pet.PetItemCatalog
import com.situ.aichat.pet.PetStatBoosts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 商店/背包共享 UI 助手（[petBoostParts]）。断言反推 iOS effectDescription/boostsDescription：hunger 负号原样、
 * 正值带 +、null 维度省略、null boosts → 空。
 */
class PetItemUiTest {

    @Test
    fun biscuit_parts() {
        // 小饼干 hunger-15, happy+3
        val parts = petBoostParts(PetItemCatalog.find("pet_food_biscuit")!!.statBoosts)
        assertEquals(listOf("饥饿 -15", "心情 +3"), parts)
    }

    @Test
    fun premium_can_parts_three() {
        // 特级罐头 hunger-30, happy+10, health+3
        val parts = petBoostParts(PetItemCatalog.find("pet_food_premium_can")!!.statBoosts)
        assertEquals(listOf("饥饿 -30", "心情 +10", "健康 +3"), parts)
    }

    @Test
    fun birthday_cake_parts() {
        val parts = petBoostParts(PetItemCatalog.find("pet_food_birthday_cake")!!.statBoosts)
        assertEquals(listOf("饥饿 -25", "心情 +20", "健康 +5"), parts)
    }

    @Test
    fun costume_null_boosts_empty() {
        // 装扮 statBoosts=null → 空
        assertTrue(petBoostParts(PetItemCatalog.find("pet_costume_crown")!!.statBoosts).isEmpty())
        assertTrue(petBoostParts(null).isEmpty())
    }

    @Test
    fun cleanliness_dimension_included() {
        // 构造含清洁维度（目录无，验证四维齐全 + 顺序 饥饿/清洁/心情/健康）
        val parts = petBoostParts(PetStatBoosts(hunger = -10, cleanliness = 20, happiness = 5, health = 2))
        assertEquals(listOf("饥饿 -10", "清洁 +20", "心情 +5", "健康 +2"), parts)
    }
}
