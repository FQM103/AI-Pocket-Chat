package com.situ.aichat.tts.pricing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * TTS pricing + usage bucketing. Assertions reverse-derived from iOS `TTSCostEstimator` /
 * `TTSUsageTracker` (×30/7 projection, hd $100 / turbo $60 / speech-01 & unknown nil with the
 * speech-01 check FIRST; UTC daily buckets; ±90-day prune; 7-day snapshot).
 */
class TtsPricingTest {

    // MARK: - unit price (speech-01 checked before turbo/hd)

    @Test
    fun `unit price per model`() {
        assertEquals(100.0, TtsCostEstimator.unitPriceUSDPerMillion("speech-2.8-hd")!!, 0.0)
        assertEquals(60.0, TtsCostEstimator.unitPriceUSDPerMillion("speech-2.8-turbo")!!, 0.0)
        assertEquals(60.0, TtsCostEstimator.unitPriceUSDPerMillion("speech-2.6-turbo")!!, 0.0)
        assertEquals(100.0, TtsCostEstimator.unitPriceUSDPerMillion("speech-02-hd")!!, 0.0)
        // speech-01 is unpublished and MUST be checked before turbo/hd → null
        assertNull(TtsCostEstimator.unitPriceUSDPerMillion("speech-01-hd"))
        assertNull(TtsCostEstimator.unitPriceUSDPerMillion("speech-01-turbo"))
        assertNull(TtsCostEstimator.unitPriceUSDPerMillion("some-unknown-model"))
    }

    @Test
    fun `estimate projects monthly chars and cost`() {
        val snapshot = TtsUsageSnapshot(charactersLast7Days = 7000, daysWithData = 7, windowStartMillis = 0, windowEndMillis = 0)
        val est = TtsCostEstimator.estimate(snapshot, "speech-2.8-hd")
        assertEquals(7000, est.actualCharactersLast7Days)
        assertEquals(30000, est.projectedMonthlyCharacters) // 7000 * 30 / 7
        assertEquals(3.0, est.projectedMonthlyUSD!!, 1e-9) // 30000 / 1e6 * 100
        assertEquals(100.0, est.unitPriceUSDPerMillion!!, 0.0)
    }

    @Test
    fun `estimate hides cost when price unknown`() {
        val snapshot = TtsUsageSnapshot(charactersLast7Days = 7000, daysWithData = 7, windowStartMillis = 0, windowEndMillis = 0)
        val est = TtsCostEstimator.estimate(snapshot, "speech-01-hd")
        assertEquals(30000, est.projectedMonthlyCharacters)
        assertNull(est.projectedMonthlyUSD)
        assertNull(est.unitPriceUSDPerMillion)
    }

    // MARK: - usage tracker pure helpers (UTC)

    @Test
    fun `bucket key is a UTC date`() {
        assertEquals("2026-06-02", TtsUsageTracker.bucketKey(Instant.parse("2026-06-02T12:00:00Z").toEpochMilli()))
        // just before midnight UTC of the next day stays on 06-02
        assertEquals("2026-06-02", TtsUsageTracker.bucketKey(Instant.parse("2026-06-02T23:59:59Z").toEpochMilli()))
    }

    @Test
    fun `snapshot sums the last 7 UTC days only`() {
        val ref = Instant.parse("2026-06-10T12:00:00Z").toEpochMilli()
        val bucket = mapOf(
            "2026-06-10" to 100, // offset 0
            "2026-06-09" to 50,  // offset 1
            "2026-06-04" to 10,  // offset 6 (still in window)
            "2026-06-03" to 999, // offset 7 (OUT of window)
        )
        val snap = TtsUsageTracker.computeSnapshot(bucket, ref)
        assertEquals(160, snap.charactersLast7Days)
        assertEquals(3, snap.daysWithData)
        assertEquals(ref, snap.windowEndMillis)
        assertEquals(ref - 6L * 86_400_000L, snap.windowStartMillis)
    }

    @Test
    fun `prune keeps a symmetric 90-day window`() {
        val refDate = LocalDate.of(2026, 6, 10)
        val ref = Instant.parse("2026-06-10T00:00:00Z").toEpochMilli()
        val inside = refDate.toString()
        val keepPast = refDate.minusDays(90).toString()
        val dropPast = refDate.minusDays(91).toString()
        val keepFuture = refDate.plusDays(90).toString()
        val dropFuture = refDate.plusDays(91).toString()
        val bucket = mapOf(inside to 1, keepPast to 1, dropPast to 1, keepFuture to 1, dropFuture to 1)
        val pruned = TtsUsageTracker.prune(bucket, ref, keepDaysEachSide = 90)
        assertTrue(pruned.containsKey(inside))
        assertTrue(pruned.containsKey(keepPast))
        assertTrue(pruned.containsKey(keepFuture))
        assertFalse(pruned.containsKey(dropPast))
        assertFalse(pruned.containsKey(dropFuture))
    }
}
