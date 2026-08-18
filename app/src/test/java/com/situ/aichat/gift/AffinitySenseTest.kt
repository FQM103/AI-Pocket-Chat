package com.situ.aichat.gift

import com.situ.aichat.data.model.AffinitySensePackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 心意反馈单测（断言反推 iOS）：currentSenseText 档位选择 + 手作徽章 + 空/坏包回落 fallback、effectivePackage isWellFormed
 * 守卫、isExpired 14 天边界、30 条兜底结构、buildPrompt 关键段。LLM 生成 generatePackageIfNeeded 留真机。
 */
class AffinitySenseTest {

    // 单元素各档 → randomOrNull 必返该元素（确定性）
    private val pkgJson = AffinitySenseService.encode(
        AffinitySensePackage(low = listOf("L"), mid = listOf("M"), high = listOf("H"), handmade = listOf("HM")),
    )
    private val rng = Random(42)

    // MARK: - currentSenseText 档位

    @Test fun sense_tier_selection() {
        assertEquals("L", AffinitySenseService.currentSenseText(pkgJson, gain = 3, isHandmade = false, rng = rng).text)
        assertEquals("M", AffinitySenseService.currentSenseText(pkgJson, gain = 8, isHandmade = false, rng = rng).text)
        assertEquals("H", AffinitySenseService.currentSenseText(pkgJson, gain = 15, isHandmade = false, rng = rng).text)
    }

    @Test fun sense_handmade_badge() {
        val handmade = AffinitySenseService.currentSenseText(pkgJson, gain = 3, isHandmade = true, rng = rng)
        assertEquals("L", handmade.text)
        assertEquals("HM", handmade.handmadeBadge)
        // 非手作无副标签
        assertNull(AffinitySenseService.currentSenseText(pkgJson, gain = 3, isHandmade = false, rng = rng).handmadeBadge)
    }

    @Test fun sense_empty_package_uses_fallback() {
        val low = AffinitySenseService.currentSenseText("", gain = 3, isHandmade = false, rng = rng)
        assertTrue(AffinitySenseFallback.defaultPackage.low.contains(low.text))
        assertNull(low.handmadeBadge)
        // high 档 + 手作 → fallback high + fallback handmade
        val high = AffinitySenseService.currentSenseText("", gain = 18, isHandmade = true, rng = rng)
        assertTrue(AffinitySenseFallback.defaultPackage.high.contains(high.text))
        assertTrue(AffinitySenseFallback.defaultPackage.handmade.contains(high.handmadeBadge))
    }

    // MARK: - effectivePackage 守卫

    @Test fun effective_package_fallback_on_bad_input() {
        assertEquals(AffinitySenseFallback.defaultPackage, AffinitySenseService.effectivePackage(""))
        assertEquals(AffinitySenseFallback.defaultPackage, AffinitySenseService.effectivePackage("{坏的}"))
        // 结构不完整（high 空）→ 不 wellFormed → fallback
        val incomplete = """{"version":1,"low":["a"],"mid":["b"],"high":[],"handmade":["c"]}"""
        assertEquals(AffinitySenseFallback.defaultPackage, AffinitySenseService.effectivePackage(incomplete))
    }

    @Test fun effective_package_uses_valid_decoded() {
        val pkg = AffinitySenseService.effectivePackage(pkgJson)
        assertEquals(listOf("L"), pkg.low)
        assertEquals(listOf("HM"), pkg.handmade)
    }

    // MARK: - isExpired 14 天边界

    @Test fun is_expired_boundaries() {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000
        assertTrue(AffinitySenseService.isExpired(null, now))                 // 没生成过
        assertTrue(AffinitySenseService.isExpired(now - 14 * day, now))       // 恰 14 天（>=）
        assertFalse(AffinitySenseService.isExpired(now - 13 * day, now))      // 13 天内
        assertFalse(AffinitySenseService.isExpired(now, now))                 // 刚生成
    }

    // MARK: - 30 条兜底结构

    @Test fun fallback_package_structure() {
        val p = AffinitySenseFallback.defaultPackage
        assertEquals(8, p.low.size)
        assertEquals(8, p.mid.size)
        assertEquals(8, p.high.size)
        assertEquals(6, p.handmade.size)
        assertTrue(p.isWellFormed)
    }
}
