package com.situ.aichat.story

import androidx.compose.ui.unit.sp
import com.situ.aichat.ui.story.StoryReaderLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-6 阅读字号档位单测。断言自 iOS 真值反推：默认档 = iOS 原值 (18,15,22,38)
 * （AppTheme.swift:176/:190/:192 + StoryReaderAnimatedBlocks.swift:244）；派生列与建表公式
 * `round(body × iOS字号/18)` 互证（查表实现，公式只在测试里锁口径防漂移）；行距 = 字号 × 1.8
 * （ST7d·契约 §6.4-③ 超越 iOS 旧「字号+12」）由 StoryReaderLayout.lineHeight 随动。
 */
class StoryReaderTypographyTest {

    @Test fun fullTable_matchesPrecomputedTiers() {
        val expected = listOf(
            listOf(16, 13, 20, 34),
            listOf(18, 15, 22, 38),
            listOf(20, 17, 24, 42),
            listOf(22, 18, 27, 46),
        )
        expected.forEachIndexed { i, row ->
            val t = StoryReaderTypography.forIndex(i)
            assertEquals(row, listOf(t.bodySp, t.whisperSp, t.shoutSp, t.dropCapSp))
        }
    }

    @Test fun defaultTier_isExactIosValues() {
        assertEquals(1, StoryReaderTypography.DEFAULT_INDEX)
        val t = StoryReaderTypography.forIndex(StoryReaderTypography.DEFAULT_INDEX)
        assertEquals(18, t.bodySp)
        assertEquals(15, t.whisperSp)
        assertEquals(22, t.shoutSp)
        assertEquals(38, t.dropCapSp)
    }

    @Test fun derivedColumns_matchBuildFormula_halfUpRounding() {
        // 查表与派生公式互证：whisper=round(body·15/18)、shout=round(body·22/18)、dropCap=round(body·38/18)。
        StoryReaderTypography.TIERS.forEach { t ->
            assertEquals(Math.round(t.bodySp * 15.0 / 18.0).toInt(), t.whisperSp)
            assertEquals(Math.round(t.bodySp * 22.0 / 18.0).toInt(), t.shoutSp)
            assertEquals(Math.round(t.bodySp * 38.0 / 18.0).toInt(), t.dropCapSp)
        }
    }

    @Test fun lineHeight_isBodyTimes1point8_multiplicative() {
        // 行高倍率 1.8（ST7d·契约 §6.4-③）：body 行高 = 16/18/20/22 × 1.8 → 28.8/32.4/36/39.6。
        // 直接调真函数（非在测试里复算），且随字号档同比放大。
        StoryReaderTypography.TIERS.forEach { t ->
            val lh = StoryReaderLayout.lineHeight(t.bodySp.sp).value
            assertEquals(t.bodySp * StoryReaderLayout.LINE_HEIGHT_MULTIPLIER, lh, 0.001f)
            assertTrue("行高应明显大于字号（呼吸感）", lh > t.bodySp * 1.5f)
        }
    }

    @Test fun displaySizes_bakeConstantScaleIntoFontSize() {
        // 2026-07-13 方案 A：shout ×1.15 / emphasis ×1.02（原 iOS scaleEffect 常量）烘进字号——
        // 布局即真实尺寸（左缘对齐正文、长句真实换行），绘制期不再整框放大（StoryTextMotion 恒 1.0 互指）。
        StoryReaderTypography.TIERS.forEach { t ->
            assertEquals(t.shoutSp * 1.15f, t.shoutDisplaySp, 1e-4f)
            assertEquals(t.bodySp * 1.02f, t.emphasisDisplaySp, 1e-4f)
        }
        // 默认档字面值：22×1.15=25.3、18×1.02=18.36——视觉大小与烘焙前（22sp 再放大 15%）等价。
        val d = StoryReaderTypography.forIndex(StoryReaderTypography.DEFAULT_INDEX)
        assertEquals(25.3f, d.shoutDisplaySp, 1e-4f)
        assertEquals(18.36f, d.emphasisDisplaySp, 1e-4f)
    }

    @Test fun forIndex_clampsOutOfRange() {
        assertEquals(StoryReaderTypography.forIndex(0), StoryReaderTypography.forIndex(-1))
        assertEquals(StoryReaderTypography.forIndex(3), StoryReaderTypography.forIndex(99))
    }

    @Test fun allColumns_monotonicNonDecreasingAcrossTiers() {
        StoryReaderTypography.TIERS.zipWithNext().forEach { (a, b) ->
            assertTrue(b.bodySp > a.bodySp)
            assertTrue(b.whisperSp >= a.whisperSp)
            assertTrue(b.shoutSp >= a.shoutSp)
            assertTrue(b.dropCapSp >= a.dropCapSp)
        }
    }
}
