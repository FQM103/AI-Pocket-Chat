package com.situ.aichat.ui.settings

import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 14.3a 回复规则范围纯函数单测。断言反推 iOS `ReplySegmentRangeSettingsView` / `VoiceReplyRoundRangeSettingsView`
 * 的 Stepper 互钳范围（bounds 1..15 / 1..20）+ `AppSettings.sanitized*Range` 的 clamp + upper>lower 兜底。
 */
class ReplyRuleRangeTest {

    // ── 步进器互钳范围 ──

    @Test
    fun minStepperRange_upperBoundIsMaxMinusOne() {
        // 默认 max=6 → 最少值可取 1..5（不能 >= max）。
        assertEquals(1..5, ReplyRuleRange.minStepperRange(currentMax = 6, boundLower = 1))
    }

    @Test
    fun maxStepperRange_lowerBoundIsMinPlusOne() {
        // 默认 min=2 → 最多值可取 3..15（不能 <= min）。
        assertEquals(3..15, ReplyRuleRange.maxStepperRange(currentMin = 2, boundUpper = 15))
    }

    @Test
    fun minStepperRange_atMinMax_isSingleValue() {
        // max=2（最小可能的 max）→ 最少值只能是 1。
        assertEquals(1..1, ReplyRuleRange.minStepperRange(currentMax = 2, boundLower = 1))
    }

    @Test
    fun voiceRanges_useTheirOwnBounds() {
        // 语音轮次 bounds 1..20，默认 3..5。
        assertEquals(1..4, ReplyRuleRange.minStepperRange(currentMax = 5, boundLower = 1))
        assertEquals(4..20, ReplyRuleRange.maxStepperRange(currentMin = 3, boundUpper = 20))
    }

    // ── sanitizedReplySegmentRange：clamp + upper>lower ──

    @Test
    fun sanitizedReplySegment_defaults() {
        assertEquals(2..6, AppSettings().sanitizedReplySegmentRange)
    }

    @Test
    fun sanitizedReplySegment_clampsOutOfBounds() {
        // min=0(<1) → 1；max=99(>15) → 15。
        val s = AppSettings(replySegmentMin = 0, replySegmentMax = 99)
        assertEquals(1..15, s.sanitizedReplySegmentRange)
    }

    @Test
    fun sanitizedReplySegment_fixesInvertedRange() {
        // min=8 > max=3 → upper<=lower 触发修正：lower=8 还小于上界 15 → upper=9。
        val s = AppSettings(replySegmentMin = 8, replySegmentMax = 3)
        assertEquals(8..9, s.sanitizedReplySegmentRange)
    }

    @Test
    fun sanitizedReplySegment_invertedAtUpperBound() {
        // min=15(=上界) > max → lower=15 不能再 +1 → 回退 14..15。
        val s = AppSettings(replySegmentMin = 15, replySegmentMax = 2)
        assertEquals(14..15, s.sanitizedReplySegmentRange)
    }

    // ── sanitizedVoiceReplyRoundRange ──

    @Test
    fun sanitizedVoiceRound_defaults() {
        assertEquals(3..5, AppSettings().sanitizedVoiceReplyRoundRange)
    }

    @Test
    fun sanitizedVoiceRound_clampsAndFixesInversion() {
        assertEquals(1..20, AppSettings(voiceReplyRoundMin = -5, voiceReplyRoundMax = 50).sanitizedVoiceReplyRoundRange)
        assertEquals(19..20, AppSettings(voiceReplyRoundMin = 20, voiceReplyRoundMax = 1).sanitizedVoiceReplyRoundRange)
    }
}
