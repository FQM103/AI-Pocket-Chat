package com.situ.aichat.ui.offline

import androidx.compose.ui.graphics.Color
import com.situ.aichat.ui.designsystem.ColorContrast
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 舞台字色对比度看门（图纸 §4.9·**R1 拍板 v2**·2026-07-04 脚本互证解封）。
 *
 * 模型 = **按档最不利底**：每档取「该档亮度**上界**的 BT.601 灰（极亮 255 / 亮 204 / 基准 140 / 暗 77）
 * 与 [OfflineTheater.curtain] 按该档**中挡 alpha**（= [OfflineTheater.curtainAlphas] 真实产出 index 1）合成」为最不利底，
 * 断言 `textBright ≥4.5:1`（硬线·全档）、`textBody`（0.92 合成后）`≥3.5:1`、`textDim`（0.76 合成后）`≥3.0:1`；
 * `textFaint` 纯装饰不测。中挡 alpha 直接取自 `curtainAlphas` → 幕布档位一旦被改动，本对比度断言即回归看门。
 *
 * 脚本实算（2026-07-04）全档通过有余量：极亮 4.63/3.99/3.27 · 亮 4.67/4.03/3.30 · 基准 5.74/4.90/3.92 · 暗 9.80/8.15/6.14。
 */
class OfflineTheaterContrastTest {

    /** fg 以 [alpha] 覆于实底 [bg] 的等效实色（同 ColorContrastTest.over·对齐 Compose copy(alpha=) over 实底）。 */
    private fun over(fg: Color, alpha: Float, bg: Color): Color = Color(
        red = fg.red * alpha + bg.red * (1 - alpha),
        green = fg.green * alpha + bg.green * (1 - alpha),
        blue = fg.blue * alpha + bg.blue * (1 - alpha),
    )

    /**
     * 某档最不利底 = 该档上界 BT.601 灰（[gray255]/255）被幕布按该档中挡 alpha（取自 [OfflineTheater.curtainAlphas]
     * 用 [sampleLuminance] 探得的 index 1）压暗后的合成色。
     */
    private fun worstBgFor(sampleLuminance: Float, gray255: Int): Color {
        val midAlpha = OfflineTheater.curtainAlphas(sampleLuminance)[1]
        val gray = Color(gray255 / 255f, gray255 / 255f, gray255 / 255f)
        return over(OfflineTheater.curtain, midAlpha, gray)
    }

    /** 带 alpha 的字色先覆于最不利底再测（等效渲染色）。 */
    private fun composedRatio(token: Color, bg: Color): Double =
        ColorContrast.ratio(over(token.copy(alpha = 1f), token.alpha, bg), bg)

    private fun assertTierReadable(tier: String, sampleLuminance: Float, gray255: Int) {
        val bg = worstBgFor(sampleLuminance, gray255)
        val bright = ColorContrast.ratio(OfflineTheater.textBright, bg)
        val body = composedRatio(OfflineTheater.textBody, bg)
        val dim = composedRatio(OfflineTheater.textDim, bg)
        assertTrue("[$tier] textBright $bright 应 ≥4.5", bright >= 4.5)
        assertTrue("[$tier] textBody $body 应 ≥3.5", body >= 3.5)
        assertTrue("[$tier] textDim $dim 应 ≥3.0", dim >= 3.0)
    }

    @Test
    fun ultraBrightTier_isReadable() {
        // 极亮档：上界灰 255（纯白）× 幕布中挡 0.62
        assertTierReadable("极亮", sampleLuminance = 1.0f, gray255 = 255)
    }

    @Test
    fun brightTier_isReadable() {
        // 亮档：上界灰 204（lum 0.80）× 幕布中挡 0.52
        assertTierReadable("亮", sampleLuminance = 0.70f, gray255 = 204)
    }

    @Test
    fun baseTier_isReadable() {
        // 基准档：上界灰 140（lum 0.55）× 幕布中挡 0.38
        assertTierReadable("基准", sampleLuminance = 0.40f, gray255 = 140)
    }

    @Test
    fun darkTier_isReadable() {
        // 暗档：上界灰 77（lum 0.30）× 幕布中挡 0.30
        assertTierReadable("暗", sampleLuminance = 0.20f, gray255 = 77)
    }
}
