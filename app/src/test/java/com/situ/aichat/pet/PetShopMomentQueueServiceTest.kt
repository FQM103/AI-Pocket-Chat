package com.situ.aichat.pet

import com.situ.aichat.pet.PetShopMomentQueueService.Companion.buildDiaryPromptHint
import com.situ.aichat.pet.PetShopMomentQueueService.Companion.buildMomentPromptHint
import com.situ.aichat.pet.PetShopMomentQueueService.Companion.canPostPetShopMoment
import com.situ.aichat.pet.PetShopMomentQueueService.Companion.filterFreshInWindow
import com.situ.aichat.pet.PetShopMomentQueueService.Companion.representativeItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宠物商店朋友圈/日记联动纯决策（1:1 iOS `PetShopMomentQueueService`）。断言反推 iOS：24h 冷却、48h 候选窗 +
 * 晚于上次 T、升序、代表物品「价高>最新」、朋友圈英文 hint / 日记中文 hint 逐字。
 */
class PetShopMomentQueueServiceTest {

    private val NOW = 1_700_000_000_000L
    private val HOUR = 3_600_000L
    private val DAY = 86_400_000L

    private fun rec(id: String, at: Long) = PetExpensivePurchaseRecord(id, at)

    // ── 冷却 ──

    @Test
    fun cooldown_null_last_is_postable() {
        assertTrue(canPostPetShopMoment(null, NOW))
    }

    @Test
    fun cooldown_under_24h_blocked_at_boundary_passes() {
        assertFalse(canPostPetShopMoment(NOW - 23 * HOUR, NOW))  // 23h < 24h → 冷却中
        assertTrue(canPostPetShopMoment(NOW - 24 * HOUR, NOW))   // 恰 24h → 可发（>=）
        assertTrue(canPostPetShopMoment(NOW - 25 * HOUR, NOW))
    }

    @Test
    fun constants_match_ios() {
        assertEquals(300, PetShopMomentQueueService.PRICE_THRESHOLD)
        assertEquals(24L, PetShopMomentQueueService.COOLDOWN_HOURS)
        assertEquals(48L, PetShopMomentQueueService.CANDIDATE_WINDOW_HOURS)
    }

    // ── 候选窗口 ──

    @Test
    fun candidates_window_and_since_and_ascending() {
        val records = listOf(
            rec("pet_costume_crown", NOW - 50 * HOUR), // 窗外（>48h）→ 排除
            rec("pet_costume_wings", NOW - 10 * HOUR), // 窗内、晚于 since
            rec("pet_costume_scarf", NOW - 40 * HOUR), // 窗内、晚于 since
            rec("pet_food_biscuit", NOW - 30 * HOUR),  // 窗内但早于 since(NOW-35h) → 排除
        )
        val since = NOW - 35 * HOUR
        val result = filterFreshInWindow(records, NOW, since)
        // 窗口 cutoff=NOW-48h，since=NOW-35h（须 purchasedAt > since）：
        // crown@-50h 出窗排除；scarf@-40h 早于 since 排除；biscuit@-30h、wings@-10h 保留 → 升序 [biscuit, wings]
        assertEquals(listOf("pet_food_biscuit", "pet_costume_wings"), result.map { it.itemId })
    }

    @Test
    fun candidates_empty_when_all_before_since() {
        val records = listOf(rec("pet_costume_crown", NOW - 5 * HOUR))
        assertTrue(filterFreshInWindow(records, NOW, NOW - 2 * HOUR).isEmpty()) // -5h 不 > -2h
    }

    // ── 代表物品：价高 > 最新 ──

    @Test
    fun representative_highest_price_wins() {
        // crown 380 vs wings 500 → wings（价高），与时间无关
        assertEquals(
            "pet_costume_wings",
            representativeItemId(listOf(rec("pet_costume_crown", NOW), rec("pet_costume_wings", NOW - 5 * HOUR))),
        )
    }

    @Test
    fun representative_tie_takes_latest() {
        // 胡萝卜/坚果种子同价 25 → 并列取最新（purchasedAt 更大）
        assertEquals(
            "pet_food_seeds",
            representativeItemId(listOf(rec("pet_food_carrot", NOW - 3 * HOUR), rec("pet_food_seeds", NOW - 1 * HOUR))),
        )
    }

    @Test
    fun representative_empty_and_unknown_fallback() {
        assertEquals("", representativeItemId(emptyList()))
        // 全是目录查不到的 → 兜底取最后一条（最新）的 id
        assertEquals(
            "unknown_b",
            representativeItemId(listOf(rec("unknown_a", NOW - 2 * HOUR), rec("unknown_b", NOW - 1 * HOUR))),
        )
    }

    // ── 朋友圈 hint（英文，逐字）──

    @Test
    fun moment_hint_english_exact() {
        val hint = buildMomentPromptHint(listOf(rec("pet_costume_crown", NOW)), "旺财")
        val expected = listOf(
            "[Pet Shop Moment Inspiration]",
            "User just bought 旺财 some premium items: 金色小皇冠(380 coins).",
            "You feel touched and proud — your shared pet is being well-cared for.",
            "Write a short moment post (~30-80 chars in Chinese) that:",
            "- shows you noticed the user's care for 旺财",
            "- shares a sweet observation of 旺财 wearing or using the new item",
            "- conveys gratitude subtly — never thank the user explicitly or sound cheesy",
            "Tone: warm, slightly sentimental, like a real person quietly noticing a small kindness.",
        ).joinToString("\n")
        assertEquals(expected, hint)
    }

    @Test
    fun moment_hint_multiple_items_joined() {
        val hint = buildMomentPromptHint(
            listOf(rec("pet_costume_crown", NOW - 2 * HOUR), rec("pet_costume_wings", NOW - 1 * HOUR)),
            "旺财",
        )!!
        assertTrue(hint.contains("金色小皇冠(380 coins), 精灵翅膀(500 coins)"))
    }

    @Test
    fun moment_hint_empty_name_fallback_and_unknown_null() {
        // 空名 → "the pet"
        val hint = buildMomentPromptHint(listOf(rec("pet_costume_wings", NOW)), "")!!
        assertTrue(hint.contains("User just bought the pet some premium items: 精灵翅膀(500 coins)."))
        // 目录全查不到 → null
        assertNull(buildMomentPromptHint(listOf(rec("unknown_x", NOW)), "旺财"))
    }

    // ── 日记 hint（中文，逐字）──

    @Test
    fun diary_hint_chinese_exact() {
        val rec = rec("pet_costume_crown", NOW)
        val hint = buildDiaryPromptHint(listOf(rec), mapOf(rec to "旺财"))
        val expected = listOf(
            "[Pet Shop Diary Inspiration]",
            "今天给宠物买了贵价用品:给旺财买了金色小皇冠(380金币)。",
            "在日记里自然提一下,体现:",
            "- 给宠物花钱时的小满足感或偶尔纠结",
            "- 看到宠物用上新东西的小确幸",
            "- 1-2 句即可,不要单独成段,要融入今天的整体心情",
        ).joinToString("\n")
        assertEquals(expected, hint)
    }

    @Test
    fun diary_hint_missing_name_fallback_and_multiple() {
        val a = rec("pet_costume_crown", NOW - 2 * HOUR)
        val b = rec("pet_costume_wings", NOW - 1 * HOUR)
        // a 有名、b 缺名 → "宠物"；分号拼接
        val hint = buildDiaryPromptHint(listOf(a, b), mapOf(a to "旺财"))!!
        assertTrue(hint.contains("给旺财买了金色小皇冠(380金币);给宠物买了精灵翅膀(500金币)。"))
    }

    @Test
    fun diary_hint_unknown_null() {
        val r = rec("unknown_x", NOW)
        assertNull(buildDiaryPromptHint(listOf(r), mapOf(r to "旺财")))
    }
}
