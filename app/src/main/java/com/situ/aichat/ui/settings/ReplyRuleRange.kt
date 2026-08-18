package com.situ.aichat.ui.settings

/**
 * 回复规则范围设置（14.3a）的 min/max 步进器互钳纯函数。1:1 iOS `ReplySegmentRangeSettingsView` /
 * `VoiceReplyRoundRangeSettingsView` 的 Stepper 范围：最少值上界=最多值-1、最多值下界=最少值+1，
 * 各端再受 bounds 限制。纯函数便于单测（断言反推 iOS bounds 1..15 / 1..20）。
 */
internal object ReplyRuleRange {

    /** 「最少值」步进器允许范围：[boundLower, currentMax-1]（1:1 iOS Stepper in: bounds.lower..(max-1)）。 */
    fun minStepperRange(currentMax: Int, boundLower: Int): IntRange = boundLower..(currentMax - 1)

    /** 「最多值」步进器允许范围：[currentMin+1, boundUpper]（1:1 iOS Stepper in: (min+1)..bounds.upper）。 */
    fun maxStepperRange(currentMin: Int, boundUpper: Int): IntRange = (currentMin + 1)..boundUpper
}
