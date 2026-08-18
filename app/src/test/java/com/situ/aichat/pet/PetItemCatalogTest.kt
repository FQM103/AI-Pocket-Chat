package com.situ.aichat.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宠物用品目录（1:1 iOS `PetItemCatalog`）。断言**全部从 iOS 真实数值反推**：10 件（零食 6 消耗 + 装扮 4 永久）的
 * 价格/分类/性质/statBoosts/targetSpecies/isSignature 精确照搬；isAvailable 物种过滤；items 分类/物种过滤数量；
 * 贵价阈值 300。
 */
class PetItemCatalogTest {

    @Test
    fun catalog_has_exactly_10_items_6_food_4_costume() {
        assertEquals(10, PetItemCatalog.allItems.size)
        assertEquals(6, PetItemCatalog.items(PetItemCategory.FOOD).size)
        assertEquals(4, PetItemCatalog.items(PetItemCategory.COSTUME).size)
        // 4 件重磅款：特级罐头 / 生日蛋糕 / 皇冠 / 翅膀
        assertEquals(4, PetItemCatalog.allItems.count { it.isSignature })
    }

    @Test
    fun biscuit_exact_values() {
        val it = PetItemCatalog.find("pet_food_biscuit")!!
        assertEquals("小饼干", it.name)
        assertEquals(20, it.price)
        assertEquals(PetItemCategory.FOOD, it.category)
        assertEquals(PetItemKind.CONSUMABLE, it.kind)
        assertEquals(-15, it.statBoosts!!.hunger)
        assertEquals(3, it.statBoosts.happiness)
        assertNull(it.statBoosts.cleanliness)
        assertNull(it.statBoosts.health)
        assertNull(it.targetSpecies) // 全物种
        assertFalse(it.isSignature)
    }

    @Test
    fun carrot_rabbit_only_25() {
        val it = PetItemCatalog.find("pet_food_carrot")!!
        assertEquals(25, it.price)
        assertEquals(-15, it.statBoosts!!.hunger)
        assertEquals(8, it.statBoosts.happiness)
        assertEquals(listOf(PetSpecies.RABBIT), it.targetSpecies)
        assertTrue(it.isAvailable(PetSpecies.RABBIT))
        assertFalse(it.isAvailable(PetSpecies.CAT))
    }

    @Test
    fun seeds_hamster_only_25() {
        val it = PetItemCatalog.find("pet_food_seeds")!!
        assertEquals(25, it.price)
        assertEquals(-15, it.statBoosts!!.hunger)
        assertEquals(8, it.statBoosts.happiness)
        assertEquals(listOf(PetSpecies.HAMSTER), it.targetSpecies)
    }

    @Test
    fun cat_can_cat_only_35() {
        val it = PetItemCatalog.find("pet_food_cat_can")!!
        assertEquals(35, it.price)
        assertEquals(-20, it.statBoosts!!.hunger)
        assertEquals(5, it.statBoosts.happiness)
        assertEquals(listOf(PetSpecies.CAT), it.targetSpecies)
    }

    @Test
    fun premium_can_60_three_boosts_signature() {
        val it = PetItemCatalog.find("pet_food_premium_can")!!
        assertEquals(60, it.price)
        assertEquals(-30, it.statBoosts!!.hunger)
        assertEquals(10, it.statBoosts.happiness)
        assertEquals(3, it.statBoosts.health)
        assertNull(it.targetSpecies)
        assertTrue(it.isSignature)
    }

    @Test
    fun birthday_cake_80_signature() {
        val it = PetItemCatalog.find("pet_food_birthday_cake")!!
        assertEquals(80, it.price)
        assertEquals(-25, it.statBoosts!!.hunger)
        assertEquals(20, it.statBoosts.happiness)
        assertEquals(5, it.statBoosts.health)
        assertTrue(it.isSignature)
    }

    @Test
    fun costumes_exact_prices_no_boosts_equippable() {
        val bowtie = PetItemCatalog.find("pet_costume_bowtie")!!
        val scarf = PetItemCatalog.find("pet_costume_scarf")!!
        val crown = PetItemCatalog.find("pet_costume_crown")!!
        val wings = PetItemCatalog.find("pet_costume_wings")!!
        assertEquals(150, bowtie.price)
        assertEquals(200, scarf.price)
        assertEquals(380, crown.price)
        assertEquals(500, wings.price)
        listOf(bowtie, scarf, crown, wings).forEach {
            assertEquals(PetItemKind.EQUIPPABLE, it.kind)
            assertEquals(PetItemCategory.COSTUME, it.category)
            assertNull(it.statBoosts)
            assertNull(it.targetSpecies)
        }
        assertFalse(bowtie.isSignature)
        assertFalse(scarf.isSignature)
        assertTrue(crown.isSignature)
        assertTrue(wings.isSignature)
    }

    @Test
    fun items_for_species_filter() {
        // 7 通用（3 食物 null + 4 装扮 null）+ 各物种专属
        assertEquals(8, PetItemCatalog.items(PetSpecies.CAT).size)     // +猫咪罐头
        assertEquals(8, PetItemCatalog.items(PetSpecies.RABBIT).size)  // +胡萝卜
        assertEquals(8, PetItemCatalog.items(PetSpecies.HAMSTER).size) // +坚果种子
        assertEquals(7, PetItemCatalog.items(PetSpecies.DOG).size)     // 仅通用
        assertEquals(7, PetItemCatalog.items(PetSpecies.DRAGON).size)  // 隐藏款仅通用
    }

    @Test
    fun unknown_id_returns_null() {
        assertNull(PetItemCatalog.find("pet_food_nonexistent"))
    }

    @Test
    fun expensive_threshold_is_300() {
        assertEquals(300, PetItemCatalog.EXPENSIVE_PURCHASE_THRESHOLD)
        // 仅皇冠(380)/翅膀(500) 达标 → 写 recentExpensivePurchases
        assertEquals(
            2,
            PetItemCatalog.allItems.count { it.price >= PetItemCatalog.EXPENSIVE_PURCHASE_THRESHOLD },
        )
    }
}
