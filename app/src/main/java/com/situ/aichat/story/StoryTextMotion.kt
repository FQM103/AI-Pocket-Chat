package com.situ.aichat.story

import kotlin.math.sin

/**
 * 文字块的逐帧动效数学（1:1 iOS `StoryAnimatedTextBlock` 的 horizontalOffset/verticalOffset/scaleEffect，
 * `StoryReaderAnimatedBlocks.swift:303-334`）。
 *
 * 仅 trembling/angry（横抖）、excited（纵跳 + 微缩放）随时间变化；其余静止。shout/emphasis 原是
 * scaleEffect 常量放大，2026-07-13 起烘进字号（[StoryReaderTypography.shoutDisplaySp]/[StoryReaderTypography.emphasisDisplaySp]）
 * ——绘制期对整行宽文本框中心缩放会把左缘顶出页边距（「操！」贴屏边），本处对二者恒返 1.0。
 * `time` 单位秒。纯函数，单测锁规格。
 */
internal object StoryTextMotion {
    /** 水平抖动偏移（点/dp）。 */
    fun horizontalOffset(style: StoryTextStyle, time: Double): Double = when (style) {
        StoryTextStyle.TREMBLING -> sin(time * 22) * 1.2
        StoryTextStyle.ANGRY -> sin(time * 26) * 1.4
        else -> 0.0
    }

    /** 垂直跳动偏移（点/dp）。 */
    fun verticalOffset(style: StoryTextStyle, time: Double): Double = when (style) {
        StoryTextStyle.EXCITED -> sin(time * 8) * -1.2
        else -> 0.0
    }

    /** 缩放系数（仅 excited 随时间在 1.00~1.02 间脉动；shout/emphasis 已烘进字号，此处恒 1.0）。 */
    fun scale(style: StoryTextStyle, time: Double): Double = when (style) {
        StoryTextStyle.EXCITED -> 1 + (sin(time * 8) + 1) / 2 * 0.02
        else -> 1.0
    }
}
