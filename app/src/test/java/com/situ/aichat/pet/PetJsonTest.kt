package com.situ.aichat.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 PetMetadata/growthLog 的 JSON 往返 + 向后兼容（缺字段→默认 = iOS decodeIfPresent；坏数据→空不抛）。
 */
class PetJsonTest {

    @Test fun `metadata round-trip preserves all fields`() {
        val md = PetMetadata(
            unlockSource = "从咪咪进化",
            lastDecayDate = 1_700_000_000_000L,
            treatmentCount = 2,
            searchAttempts = 1,
            trustRecovery = 0.6,
            playCount = 42,
            learnedTricks = listOf("sit", "shake"),
            walkStartTime = 1_700_000_111_000L,
            souvenirs = listOf(PetSouvenir("s1", "四叶草", "🍀", 1_700_000_222_000L, "散步捡到")),
            dailyWalkCount = 3,
            lastViewedHunger = 30,
            petInventory = PetInventoryData(owned = mapOf("pet_costume_crown" to 1), equippedItemId = "pet_costume_crown"),
            recentExpensivePurchases = listOf(PetExpensivePurchaseRecord("pet_costume_crown", 1_700_000_333_000L)),
        )
        val decoded = PetJson.decodeMetadata(PetJson.encodeMetadata(md))
        assertEquals(md, decoded)
    }

    @Test fun `blank metadata is empty`() {
        assertEquals(PetMetadata.EMPTY, PetJson.decodeMetadata(""))
    }

    @Test fun `bad metadata json falls back to empty (no throw)`() {
        assertEquals(PetMetadata.EMPTY, PetJson.decodeMetadata("{ not json"))
    }

    @Test fun `missing fields use defaults`() {
        // 只给一个字段，其余应回退默认（decodeIfPresent 等价）
        val decoded = PetJson.decodeMetadata("""{"playCount":7}""")
        assertEquals(7, decoded.playCount)
        assertEquals("", decoded.unlockSource)
        assertEquals(0.0, decoded.trustRecovery, 0.0001)
        assertTrue(decoded.learnedTricks.isEmpty())
        assertEquals(PetInventoryData.EMPTY, decoded.petInventory)
    }

    @Test fun `lastComputedAchievedIds missing means never computed (null not empty)`() {
        // P1-34 seed 语义：缺字段旧 JSON → null（=从未计算→首算静默 seed）≠ emptyList（已 seed 且零成就）。
        assertEquals(null, PetJson.decodeMetadata("{}").lastComputedAchievedIds)
        assertEquals(null, PetJson.decodeMetadata("{\"playCount\":7}").lastComputedAchievedIds)
    }

    @Test fun `lastComputedAchievedIds round-trip and serialized by encodeDefaults`() {
        val md = PetMetadata(lastComputedAchievedIds = listOf("days1", "interact5"))
        val encoded = PetJson.encodeMetadata(md)
        assertTrue(encoded.contains("lastComputedAchievedIds"))
        assertEquals(listOf("days1", "interact5"), PetJson.decodeMetadata(encoded).lastComputedAchievedIds)
    }

    @Test fun `growth log round-trip + empty`() {
        val log = listOf(
            PetGrowthLogEntry("1", 1_700_000_000_000L, PetGrowthEventType.FED.raw, "喂食"),
            PetGrowthLogEntry("2", 1_700_000_111_000L, PetGrowthEventType.EVOLVED.raw, "进化了"),
        )
        assertEquals(log, PetJson.decodeGrowthLog(PetJson.encodeGrowthLog(log)))
        assertEquals("", PetJson.encodeGrowthLog(emptyList()))
        assertTrue(PetJson.decodeGrowthLog("").isEmpty())
    }
}
