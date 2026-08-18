package com.situ.aichat.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宠物商店纯函数（[PetShopService.prunedExpensivePurchases]）。断言反推 iOS：append 新记录后 `removeAll
 * { purchasedAt < now-7天 }`——新记录(now)必保留、>7 天前的剔除、恰好 7 天前的保留（边界 ≥ cutoff）。
 */
class PetShopServiceMathTest {

    private val NOW = 1_700_000_000_000L
    private val DAY = 86_400_000L

    @Test
    fun appends_new_record_and_prunes_older_than_7_days() {
        val existing = listOf(
            PetExpensivePurchaseRecord("pet_costume_crown", NOW - 8 * DAY), // 8 天前 → 剔除
            PetExpensivePurchaseRecord("pet_costume_scarf", NOW - 1 * DAY),  // 1 天前 → 保留
        )
        val result = PetShopService.prunedExpensivePurchases(existing, "pet_costume_wings", NOW)
        assertEquals(2, result.size)
        // 保留的是 1 天前的围巾 + 新的翅膀（8 天前皇冠被剔除）
        assertFalse(result.any { it.itemId == "pet_costume_crown" })
        assertTrue(result.any { it.itemId == "pet_costume_scarf" })
        // 新记录追加在末尾，时间戳 = now
        assertEquals("pet_costume_wings", result.last().itemId)
        assertEquals(NOW, result.last().purchasedAt)
    }

    @Test
    fun record_exactly_7_days_old_is_kept_boundary() {
        // cutoff = now - 7 天；purchasedAt == cutoff → ≥ cutoff → 保留（iOS removeAll 移除严格 <）
        val existing = listOf(PetExpensivePurchaseRecord("pet_costume_crown", NOW - 7 * DAY))
        val result = PetShopService.prunedExpensivePurchases(existing, "pet_costume_wings", NOW)
        assertEquals(2, result.size)
        assertTrue(result.any { it.itemId == "pet_costume_crown" })
    }

    @Test
    fun empty_existing_yields_just_new_record() {
        val result = PetShopService.prunedExpensivePurchases(emptyList(), "pet_costume_crown", NOW)
        assertEquals(1, result.size)
        assertEquals("pet_costume_crown", result.first().itemId)
        assertEquals(NOW, result.first().purchasedAt)
    }
}
