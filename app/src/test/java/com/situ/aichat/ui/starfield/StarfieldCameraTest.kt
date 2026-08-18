package com.situ.aichat.ui.starfield

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 相机钳制与命中 T1（图纸 §4.10 / J8）：pan 双向钳制区间、缩放边界、命中半径 max(24dp, 核径×3) 与取最近者。
 * 断言从图纸公式手算独立反推（density=2 → 1dp=2px；视口 360×800dp = 720×1600px）。
 */
class StarfieldCameraTest {

    private val density = Density(2f)
    private val viewport = IntSize(720, 1600) // 360×800 dp

    private fun star(id: String, x: Float, y: Float, radiusDp: Float = 3f) = PlacedStar(
        node = StarNode(id = id, type = StarType.MEETING, timestampMillis = 1L, title = id, weight = radiusDp),
        xDp = x, yDp = y, radiusDp = radiusDp, alpha = 1f, hero = false,
    )

    private fun clusterOf(vararg stars: PlacedStar) = listOf(
        StarCluster(label = "7月", centerXDp = 0f, centerYDp = 0f, depthIndex = 0, stars = stars.toList(), links = emptyList()),
    )

    // ── J8 pan 钳制 ────────────────────────────────────────────────────────

    @Test
    fun panX_clampedToEighteenPercentOfViewportTimesScale() {
        // maxX = 0.18 × 720 × 1 = 129.6
        assertEquals(129.6f, clampPan(Offset(9999f, 0f), 1f, viewport, 912f, density).x, 0.01f)
        assertEquals(-129.6f, clampPan(Offset(-9999f, 0f), 1f, viewport, 912f, density).x, 0.01f)
        // 缩放参与：0.18 × 720 × 1.6 = 207.36
        assertEquals(207.36f, clampPan(Offset(9999f, 0f), 1.6f, viewport, 912f, density).x, 0.01f)
        // 区间内原样
        assertEquals(50f, clampPan(Offset(50f, 0f), 1f, viewport, 912f, density).x, 0.01f)
    }

    @Test
    fun panY_clampedBetweenCanvasBottomAndTopMargin() {
        // 画布 912dp = 1824px；scale 1 → minY = -(1824-1600) - 48 = -272；maxY = +48（24dp 余量）
        assertEquals(-272f, clampPan(Offset(0f, -9999f), 1f, viewport, 912f, density).y, 0.01f)
        assertEquals(48f, clampPan(Offset(0f, 9999f), 1f, viewport, 912f, density).y, 0.01f)
        assertEquals(-100f, clampPan(Offset(0f, -100f), 1f, viewport, 912f, density).y, 0.01f)
    }

    @Test
    fun panY_zoomedIn_reachesTopOfCanvas() {
        // scale 1.6 → minY = -(1824×1.6 - 1600) - 48 = -(2918.4-1600) - 48 = -1366.4
        assertEquals(-1366.4f, clampPan(Offset(0f, -9999f), 1.6f, viewport, 912f, density).y, 0.01f)
    }

    @Test
    fun panY_canvasShorterThanViewport_doesNotThrow_andPinsTop() {
        // 画布=视口高且缩到 0.8 → 下界(+272) 高过上界(+48)：区间翻转，必须兜底不崩（coerceIn 会抛）。
        val out = clampPan(Offset(0f, -500f), 0.8f, viewport, 800f, density)
        assertEquals(48f, out.y, 0.01f)
    }

    @Test
    fun scaleBounds_areLocked() {
        assertEquals(0.8f, MIN_SCALE, 0f)
        assertEquals(1.6f, MAX_SCALE, 0f)
    }

    // ── §4.10 命中 ─────────────────────────────────────────────────────────

    @Test
    fun tapOnStar_hits_withinMinRadius() {
        val clusters = clusterOf(star("a", 50f, 100f))
        // density 2、scale 1、pan 0：屏幕 (100,200)px = 画布 (50,100)dp = 正中星心。
        assertEquals("a", hitTestStar(Offset(100f, 200f), clusters, 1f, Offset.Zero, 2f)?.id)
        // 距 23dp（<24dp 最小命中半径）仍命中。
        assertEquals("a", hitTestStar(Offset(100f + 46f, 200f), clusters, 1f, Offset.Zero, 2f)?.id)
    }

    @Test
    fun tapOutsideRadius_missesAndClears() {
        val clusters = clusterOf(star("a", 50f, 100f))
        // 距 25dp > max(24, 3×3=9) → 落空。
        assertNull(hitTestStar(Offset(100f + 50f, 200f), clusters, 1f, Offset.Zero, 2f))
        assertNull(hitTestStar(Offset(0f, 0f), emptyList(), 1f, Offset.Zero, 2f))
    }

    @Test
    fun bigStar_usesThreeTimesCoreRadius() {
        // 核径 12dp → 命中半径 max(24, 36) = 36dp：距 30dp 应命中（若按 24dp 就会漏）。
        val clusters = clusterOf(star("big", 50f, 100f, radiusDp = 12f))
        assertEquals("big", hitTestStar(Offset(100f + 60f, 200f), clusters, 1f, Offset.Zero, 2f)?.id)
        // 距 37dp 超出 → 落空。
        assertNull(hitTestStar(Offset(100f + 74f, 200f), clusters, 1f, Offset.Zero, 2f))
    }

    @Test
    fun overlappingCandidates_nearestWins() {
        val clusters = clusterOf(star("far", 50f, 100f), star("near", 60f, 100f))
        // 屏幕 (118,200)px = 画布 (59,100)dp：距 near 1dp、距 far 9dp → 取 near。
        assertEquals("near", hitTestStar(Offset(118f, 200f), clusters, 1f, Offset.Zero, 2f)?.id)
    }

    @Test
    fun hitTest_accountsForPanAndScale() {
        val clusters = clusterOf(star("a", 50f, 100f))
        // pan(60,80)px、scale 1：星心屏幕落点 = 50dp×2×1 + 60 = 160，100×2×1 + 80 = 280。
        assertEquals("a", hitTestStar(Offset(160f, 280f), clusters, 1f, Offset(60f, 80f), 2f)?.id)
        // scale 1.6：星心屏幕落点 = 50×2×1.6 = 160，100×2×1.6 = 320。
        assertEquals("a", hitTestStar(Offset(160f, 320f), clusters, 1.6f, Offset.Zero, 2f)?.id)
    }
}
