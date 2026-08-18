package com.situ.aichat.ui.starfield

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染器纯函数 T1（图纸 2026-07-16-记忆星空 §4.4/§4.5）：nova 呼吸曲线的锁定数值与全周期，
 * 尘星散布的确定性、颗数、亮度金字塔与边距不变量。断言从 §4 规格独立反推（数值手算）。
 */
class StarfieldCanvasTest {

    private val density = Density(2.75f)
    private val canvas = Size(360f * 2.75f, 912f * 2.75f)

    // ── §4.5 nova 呼吸：α = 0.10 + 0.16×(0.5 + 0.5×sin(πt/1200))·全周期 2400ms ──

    @Test
    fun novaHalo_reduceMotion_isFlat18() {
        assertEquals(0.18f, novaHaloAlpha(0f, animate = false), 0f)
        assertEquals(0.18f, novaHaloAlpha(777f, animate = false), 0f)
    }

    @Test
    fun novaHalo_curveHitsLockedExtremes() {
        // t=0 → sin0=0 → 0.10+0.16×0.5 = 0.18（中点起步）
        assertEquals(0.18f, novaHaloAlpha(0f, animate = true), 0.0001f)
        // t=600 → sin(π/2)=1 → 0.10+0.16×1 = 0.26（峰）
        assertEquals(0.26f, novaHaloAlpha(600f, animate = true), 0.0001f)
        // t=1800 → sin(3π/2)=-1 → 0.10+0.16×0 = 0.10（谷）
        assertEquals(0.10f, novaHaloAlpha(1800f, animate = true), 0.0001f)
    }

    @Test
    fun novaHalo_fullPeriodIs2400ms() {
        // 全周期 = 2400ms（≠ 半程 1200）——CSS keyframes ↔ Compose 半程口径的经典坑。
        assertEquals(novaHaloAlpha(0f, true), novaHaloAlpha(2400f, true), 0.0001f)
        assertEquals(novaHaloAlpha(300f, true), novaHaloAlpha(2700f, true), 0.0001f)
        // 半程处不等于起点（否则周期就成 1200 了）。
        assertNotEquals(novaHaloAlpha(600f, true), novaHaloAlpha(0f, true))
    }

    // ── §4.4 尘星 ─────────────────────────────────────────────────────────

    @Test
    fun dust_isDeterministicPerSeed() {
        val a = makeDust(42, canvas, density)
        val b = makeDust(42, canvas, density)
        assertEquals(a, b)
        assertNotEquals(a, makeDust(43, canvas, density))
    }

    @Test
    fun dust_countIs88() {
        assertEquals(88, makeDust(7, canvas, density).size)
    }

    @Test
    fun dust_brightnessPyramidInvariants() {
        val dust = makeDust(7, canvas, density)
        val minR = with(density) { 0.6.dp.toPx() }
        val maxR = with(density) { 2.4.dp.toPx() }
        val glowThreshold = with(density) { 1.4.dp.toPx() }
        dust.forEach { d ->
            assertTrue("半径应落 0.6–2.4dp，实得 ${d.radiusPx}", d.radiusPx in minR..maxR)
            assertTrue("辉光系数只能是 0 / 0.5 / 1.0", d.glow == 0f || d.glow == 0.5f || d.glow == 1.0f)
            // 只有第三、四档（r ≥ 1.4dp）才有辉光。
            if (d.glow > 0f) assertTrue("带辉光的星半径必 ≥1.4dp", d.radiusPx >= glowThreshold - 0.01f)
            assertTrue("基础 α 应落 0.38–0.63", d.baseAlpha in 0.38f..0.63f)
            assertTrue("闪烁周期应落 2.6–5.0s", d.periodMs in 2600f..5000f)
        }
    }

    @Test
    fun dust_colorsComeFromLockedPalette() {
        val palette = setOf(Color(0xFFFFD6AA), Color(0xFFFFF1E0), Color(0xFFF0F0F4), Color(0xFFCBD8F7))
        val used = makeDust(7, canvas, density).map { it.color }.toSet()
        assertTrue("尘星色必出自锁定色温表", palette.containsAll(used))
        assertTrue("88 颗应覆盖到多种色温", used.size > 1)
    }

    @Test
    fun dust_respectsMargins() {
        val dust = makeDust(7, canvas, density)
        val mx = with(density) { 4.dp.toPx() }
        val top = with(density) { 30.dp.toPx() }
        val bottom = canvas.height - with(density) { 70.dp.toPx() }
        dust.forEach { d ->
            assertTrue("x 应在左右边距内", d.center.x >= mx - 0.01f && d.center.x <= canvas.width - mx + 0.01f)
            assertTrue("y 应在上下边距内", d.center.y >= top - 0.01f && d.center.y <= bottom + 0.01f)
        }
    }

    @Test
    fun haloColors_areLockedTriples() {
        assertEquals(Color(0xFFE8C77B), haloColorOf(StarType.MEETING))
        assertEquals(Color(0xFFA9C5BE), haloColorOf(StarType.PROMISE))
        assertEquals(Color(0xFFE2DEF0), haloColorOf(StarType.MILESTONE))
    }
}
