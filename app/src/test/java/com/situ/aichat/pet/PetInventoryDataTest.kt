package com.situ.aichat.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定库存语义（1:1 iOS PetInventoryData）：has/quantity、加减、扣到 0 删 key、扣掉正佩戴装扮自动 unequip、
 * 佩戴须已拥有。Android 改不可变函数式（返回新实例），行为等价 iOS mutating。
 */
class PetInventoryDataTest {

    @Test fun `has and quantity`() {
        val inv = PetInventoryData(owned = mapOf("a" to 2))
        assertTrue(inv.has("a"))
        assertEquals(2, inv.quantity("a"))
        assertFalse(inv.has("b"))
        assertEquals(0, inv.quantity("b"))
    }

    @Test fun `adding accumulates`() {
        val inv = PetInventoryData().adding("a").adding("a", 2)
        assertEquals(3, inv.quantity("a"))
    }

    @Test fun `adding non-positive is no-op`() {
        assertEquals(PetInventoryData(), PetInventoryData().adding("a", 0))
    }

    @Test fun `removing decrements then deletes key at zero`() {
        val inv = PetInventoryData(owned = mapOf("a" to 2)).removing("a")
        assertEquals(1, inv.quantity("a"))
        val empty = inv.removing("a")
        assertFalse(empty.has("a"))
        assertFalse(empty.owned.containsKey("a"))
    }

    @Test fun `removing equipped costume auto-unequips`() {
        val inv = PetInventoryData(owned = mapOf("crown" to 1), equippedItemId = "crown").removing("crown")
        assertFalse(inv.has("crown"))
        assertNull(inv.equippedItemId)
    }

    @Test fun `equip requires owned`() {
        assertNull(PetInventoryData().equipping("crown").equippedItemId) // 未拥有不生效
        val inv = PetInventoryData(owned = mapOf("crown" to 1)).equipping("crown")
        assertEquals("crown", inv.equippedItemId)
        assertNull(inv.unequipping().equippedItemId)
    }
}
