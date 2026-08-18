package com.situ.aichat.pet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1-2（图纸 §7）：候选朦胧短语四档阈值（含等值边界）+ 候选排序（无宠 percent 降序·有宠沉底·组内按名字）。
 * 纯函数（[eggNestPhrase]/[sortEggNestCandidates]）· 断言从图纸 §3 映射/排序规则独立反推（不照搬实现分支）。
 */
class EggNestPhrasesTest {

    // ── 短语映射：canAdopt→READY；≥0.75→CLOSE；≥0.35→WARMING；else FAR（等值走高档·§3）──

    @Test fun `canAdopt overrides to ready regardless of percent`() {
        assertEquals(EggNestPhrase.READY, eggNestPhrase(canAdopt = true, overallPercent = 0f))
        assertEquals(EggNestPhrase.READY, eggNestPhrase(canAdopt = true, overallPercent = 0.1f))
    }

    @Test fun `percent equal to 0_75 is close`() {
        assertEquals(EggNestPhrase.CLOSE, eggNestPhrase(canAdopt = false, overallPercent = 0.75f))
    }

    @Test fun `percent just below 0_75 is warming`() {
        assertEquals(EggNestPhrase.WARMING, eggNestPhrase(canAdopt = false, overallPercent = 0.749f))
    }

    @Test fun `percent equal to 0_35 is warming`() {
        assertEquals(EggNestPhrase.WARMING, eggNestPhrase(canAdopt = false, overallPercent = 0.35f))
    }

    @Test fun `percent just below 0_35 is far`() {
        assertEquals(EggNestPhrase.FAR, eggNestPhrase(canAdopt = false, overallPercent = 0.349f))
    }

    @Test fun `high percent without canAdopt is close not ready`() {
        assertEquals(EggNestPhrase.CLOSE, eggNestPhrase(canAdopt = false, overallPercent = 1f))
    }

    // ── 排序：无宠者按 percent 降序在前，有宠者沉底；组内按名字（Collator）──

    @Test fun `no-pet rows sort by percent desc, pet rows sink`() {
        val a = candNoPet("uA", "小K", pct = 0.85f)
        val b = candNoPet("uB", "阿哲", pct = 0.34f)
        val c = candNoPet("uC", "苏晚", pct = 0.12f)
        val d = candPet("uD", "林", pet = "团子")
        val sorted = sortEggNestCandidates(listOf(c, d, a, b))
        assertEquals(listOf("uA", "uB", "uC", "uD"), sorted.map { it.characterUuid })
    }

    @Test fun `equal percent no-pet rows tie broken by name`() {
        val x = candNoPet("uX", "Bob", pct = 0.5f)
        val y = candNoPet("uY", "Alice", pct = 0.5f)
        val sorted = sortEggNestCandidates(listOf(x, y))
        assertEquals(listOf("uY", "uX"), sorted.map { it.characterUuid })
    }

    @Test fun `pet rows ordered among themselves by name`() {
        val p1 = candPet("u1", "Bella", pet = "团子")
        val p2 = candPet("u2", "Anna", pet = "饭团")
        val sorted = sortEggNestCandidates(listOf(p1, p2))
        assertEquals(listOf("u2", "u1"), sorted.map { it.characterUuid })
    }

    private fun candNoPet(uuid: String, name: String, pct: Float) =
        EggNestCandidate(uuid, name, avatarPath = null, petName = null, phrase = null, overallPercent = pct)

    private fun candPet(uuid: String, name: String, pet: String) =
        EggNestCandidate(uuid, name, avatarPath = null, petName = pet, phrase = null, overallPercent = 0f)
}
