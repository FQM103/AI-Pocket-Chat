package com.situ.aichat.ui.designsystem

import com.situ.aichat.data.model.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 青花多主题契约回归（[FABLE5_THEME_QINGHUA_PROPOSAL.md] §1/§2）：
 * ① [ThemePalette.fromRaw] 往返 + 未知/空回退暖陶 + raw 串稳定（改了破坏老用户持久化偏好）；
 * ② 青花**只换** text/surface/accent/bubble，economy/status/emotion/pet 与暖陶**同一引用**（复用不复制·缩范围降风险）；
 * ③ isDark 档位正确。对比红线另由 [ColorContrastTest] 的 qinghua 两档看门。
 */
class ThemePaletteTest {

    @Test fun fromRaw_roundTripsAndDefaultsToClay() {
        assertEquals(ThemePalette.CLAY, ThemePalette.fromRaw("clay"))
        assertEquals(ThemePalette.QINGHUA, ThemePalette.fromRaw("qinghua"))
        assertEquals(ThemePalette.CLAY, ThemePalette.fromRaw(null))
        assertEquals(ThemePalette.CLAY, ThemePalette.fromRaw("unknown"))
        // raw 串是持久化键，必须稳定。
        assertEquals("clay", ThemePalette.CLAY.raw)
        assertEquals("qinghua", ThemePalette.QINGHUA.raw)
    }

    @Test fun qinghua_swapsOnlyFourFamilies_light() {
        // 换了的四族：与暖陶不同。
        assertNotEquals(LightAppColors.surface.base, QinghuaLightAppColors.surface.base)
        assertNotEquals(LightAppColors.text.primary, QinghuaLightAppColors.text.primary)
        assertNotEquals(LightAppColors.accent.primary, QinghuaLightAppColors.accent.primary)
        assertNotEquals(LightAppColors.bubble.userStart, QinghuaLightAppColors.bubble.userStart)
        // 沿用的四族：与暖陶**同一引用**（复用，不复制）。
        assertSame(LightAppColors.economy, QinghuaLightAppColors.economy)
        assertSame(LightAppColors.status, QinghuaLightAppColors.status)
        assertSame(LightAppColors.emotion, QinghuaLightAppColors.emotion)
        assertSame(LightAppColors.pet, QinghuaLightAppColors.pet)
    }

    @Test fun qinghua_swapsOnlyFourFamilies_dark() {
        assertNotEquals(DarkAppColors.surface.base, QinghuaDarkAppColors.surface.base)
        assertNotEquals(DarkAppColors.text.primary, QinghuaDarkAppColors.text.primary)
        assertNotEquals(DarkAppColors.bubble.userStart, QinghuaDarkAppColors.bubble.userStart)
        assertSame(DarkAppColors.economy, QinghuaDarkAppColors.economy)
        assertSame(DarkAppColors.status, QinghuaDarkAppColors.status)
        assertSame(DarkAppColors.emotion, QinghuaDarkAppColors.emotion)
        assertSame(DarkAppColors.pet, QinghuaDarkAppColors.pet)
    }

    @Test fun qinghua_isDarkFlagsCorrect() {
        assertFalse(QinghuaLightAppColors.isDark)
        assertTrue(QinghuaDarkAppColors.isDark)
    }
}
