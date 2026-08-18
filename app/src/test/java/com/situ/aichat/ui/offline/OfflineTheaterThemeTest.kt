package com.situ.aichat.ui.offline

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.situ.aichat.ui.designsystem.ColorContrast
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 舞台 token 纯函数看门（图纸 §4.1 / T1-3·R1 拍板 TODO-1 四档显式值）：
 * - [OfflineTheater.curtainAlphas] **四档**亮度自适应，逐档值 + 三边界断言
 *   （>0.80 严格进极亮、>0.55 严格进亮档、≤0.30 含等号进暗档、0.30<lum≤0.55 基准）+ 返回副本不泄共享基准；
 * - [OfflineTheater.harmonize] 舞台调和定点值（输入 teal #14B8A6 与陶土玫 #C99A86 断言 lerp 35% 精确 ARGB）。
 */
class OfflineTheaterThemeTest {

    private val base = floatArrayOf(0.50f, 0.38f, 0.55f)
    private val ultraBright = floatArrayOf(0.74f, 0.62f, 0.79f)
    private val bright = floatArrayOf(0.64f, 0.52f, 0.69f)
    private val dark = floatArrayOf(0.42f, 0.30f, 0.47f)

    @Test
    fun curtainAlphas_null_returnsBase() {
        assertArrayEquals(base, OfflineTheater.curtainAlphas(null), EPS)
    }

    @Test
    fun curtainAlphas_ultraBright_above080_isUltraBrightTier() {
        // lum > 0.80 → 极亮档逐值 [0.74, 0.62, 0.79]
        assertArrayEquals(ultraBright, OfflineTheater.curtainAlphas(0.81f), EPS)
        assertArrayEquals(ultraBright, OfflineTheater.curtainAlphas(1.0f), EPS)
    }

    @Test
    fun curtainAlphas_exactly080_staysBrightTier() {
        // 0.80 不 > 0.80（极亮无等号）→ 落亮档
        assertArrayEquals(bright, OfflineTheater.curtainAlphas(0.80f), EPS)
    }

    @Test
    fun curtainAlphas_bright_above055_isBrightTier() {
        // 0.55 < lum <= 0.80 → 亮档逐值 [0.64, 0.52, 0.69]
        assertArrayEquals(bright, OfflineTheater.curtainAlphas(0.56f), EPS)
        assertArrayEquals(bright, OfflineTheater.curtainAlphas(0.79f), EPS)
    }

    @Test
    fun curtainAlphas_exactly055_staysBase() {
        // 0.55 不 > 0.55（亮档无等号）→ 基准
        assertArrayEquals(base, OfflineTheater.curtainAlphas(0.55f), EPS)
    }

    @Test
    fun curtainAlphas_dark_atOrBelow030_isDarkTier() {
        // lum <= 0.30 → 暗档逐值 [0.42, 0.30, 0.47]（含等号）
        assertArrayEquals(dark, OfflineTheater.curtainAlphas(0.30f), EPS) // 含等号
        assertArrayEquals(dark, OfflineTheater.curtainAlphas(0.10f), EPS)
    }

    @Test
    fun curtainAlphas_justAbove030_staysBase() {
        // 0.30 < lum <= 0.55 → 基准（暗档上边界外侧）
        assertArrayEquals(base, OfflineTheater.curtainAlphas(0.31f), EPS)
    }

    @Test
    fun curtainAlphas_midRange_staysBase() {
        assertArrayEquals(base, OfflineTheater.curtainAlphas(0.40f), EPS)
    }

    @Test
    fun curtainAlphas_returnsCopy_doesNotMutateSharedBase() {
        val a = OfflineTheater.curtainAlphas(0.40f)
        a[0] = 999f
        assertEquals(0.50f, OfflineTheater.curtainBase[0], EPS)
    }

    @Test
    fun harmonize_teal_isLerp35TowardWarmWhite() {
        val input = Color(0xFF14B8A6)
        val expected = lerp(input, Color(0xFFF5EFEA), 0.35f)
        assertEquals(expected.toArgb(), OfflineTheater.harmonize(input).toArgb())
    }

    @Test
    fun harmonize_clay_isLerp35TowardWarmWhite() {
        val input = Color(0xFFC99A86)
        val expected = lerp(input, Color(0xFFF5EFEA), 0.35f)
        assertEquals(expected.toArgb(), OfflineTheater.harmonize(input).toArgb())
    }

    @Test
    fun harmonize_liftsLuminance() {
        // 调和朝暖白混色 → 提明度（压振动）
        val input = Color(0xFF14B8A6)
        assertTrue(
            ColorContrast.relativeLuminance(OfflineTheater.harmonize(input)) >
                ColorContrast.relativeLuminance(input),
        )
    }

    private companion object {
        const val EPS = 1e-6f
    }
}
