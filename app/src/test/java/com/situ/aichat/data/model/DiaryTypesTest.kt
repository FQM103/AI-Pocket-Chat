package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Enum raw-value + fallback parity with iOS (`DiaryVisibility` / `MomentTriggerType`). The raw
 * strings are persisted and must match iOS exactly for backup round-trips; the fallback target on an
 * unknown value mirrors iOS `?? .openToAI` / `?? .autoDraft`.
 */
class DiaryTypesTest {

    @Test fun `diary visibility raw values match iOS`() {
        assertEquals("private", DiaryVisibility.PRIVATE.raw)
        assertEquals("openToAI", DiaryVisibility.OPEN_TO_AI.raw)
    }

    @Test fun `diary visibility parses known and falls back to openToAI`() {
        assertEquals(DiaryVisibility.PRIVATE, DiaryVisibility.fromRaw("private"))
        assertEquals(DiaryVisibility.OPEN_TO_AI, DiaryVisibility.fromRaw("openToAI"))
        assertEquals(DiaryVisibility.OPEN_TO_AI, DiaryVisibility.fromRaw("bogus"))
        assertEquals(DiaryVisibility.OPEN_TO_AI, DiaryVisibility.fromRaw(""))
    }

    @Test fun `moment trigger raw values match iOS`() {
        assertEquals("auto_draft", MomentTriggerType.AUTO_DRAFT.raw)
        assertEquals("gift_received", MomentTriggerType.GIFT_RECEIVED.raw)
        assertEquals("pet_shop_purchase", MomentTriggerType.PET_SHOP_PURCHASE.raw)
    }

    @Test fun `moment trigger parses known and falls back to autoDraft`() {
        assertEquals(MomentTriggerType.GIFT_RECEIVED, MomentTriggerType.fromRaw("gift_received"))
        assertEquals(MomentTriggerType.PET_SHOP_PURCHASE, MomentTriggerType.fromRaw("pet_shop_purchase"))
        assertEquals(MomentTriggerType.AUTO_DRAFT, MomentTriggerType.fromRaw("auto_draft"))
        assertEquals(MomentTriggerType.AUTO_DRAFT, MomentTriggerType.fromRaw("unknown"))
    }
}
