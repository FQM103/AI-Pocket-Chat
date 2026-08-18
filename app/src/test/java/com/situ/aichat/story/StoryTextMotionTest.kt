package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `StoryTextMotion` 测试，时变部分反推 iOS `StoryAnimatedTextBlock` 的 horizontalOffset/verticalOffset/scaleEffect
 * （`StoryReaderAnimatedBlocks.swift:303-334`）；shout/emphasis 常量放大已烘进字号
 * （2026-07-13 方案 A·见 StoryReaderTypographyTest），绘制期缩放必须恒 1.0。
 */
class StoryTextMotionTest {

    @Test fun horizontal_offset_only_for_trembling_and_angry() {
        assertEquals(0.0, StoryTextMotion.horizontalOffset(StoryTextStyle.NORMAL, 0.13), 0.0)
        assertEquals(0.0, StoryTextMotion.horizontalOffset(StoryTextStyle.EXCITED, 0.13), 0.0)
        // 左对齐防线（2026-07-13）：shout/emphasis 平移必须恒 0（缩放恒 1.0 见 scale 测试），
        // 否则给呐喊/强调加平移会让左缘悄悄挪出正文对齐、现有对齐测试全绿地破功。
        assertEquals(0.0, StoryTextMotion.horizontalOffset(StoryTextStyle.SHOUT, 0.13), 0.0)
        assertEquals(0.0, StoryTextMotion.horizontalOffset(StoryTextStyle.EMPHASIS, 0.13), 0.0)
        // trembling: sin(t·22)，t=π/44 → sin(π/2)=1 → 1.2
        assertEquals(1.2, StoryTextMotion.horizontalOffset(StoryTextStyle.TREMBLING, Math.PI / 44), 1e-9)
        // angry: sin(t·26)，t=π/52 → 1.4
        assertEquals(1.4, StoryTextMotion.horizontalOffset(StoryTextStyle.ANGRY, Math.PI / 52), 1e-9)
    }

    @Test fun vertical_offset_only_for_excited() {
        assertEquals(0.0, StoryTextMotion.verticalOffset(StoryTextStyle.NORMAL, 0.2), 0.0)
        assertEquals(0.0, StoryTextMotion.verticalOffset(StoryTextStyle.TREMBLING, 0.2), 0.0)
        assertEquals(0.0, StoryTextMotion.verticalOffset(StoryTextStyle.SHOUT, 0.2), 0.0)
        assertEquals(0.0, StoryTextMotion.verticalOffset(StoryTextStyle.EMPHASIS, 0.2), 0.0)
        // excited: sin(t·8)·-1.2，t=π/16 → sin(π/2)=1 → -1.2
        assertEquals(-1.2, StoryTextMotion.verticalOffset(StoryTextStyle.EXCITED, Math.PI / 16), 1e-9)
    }

    @Test fun scale_is_identity_for_all_constant_styles() {
        // shout/emphasis 的 1.15/1.02 已烘进字号：绘制期若再缩放，整行宽文本框以中心为轴放大
        // 会把左缘顶出页边距（2026-07-13 真机「操！」贴屏边的病根），此处必须恒 1.0。
        assertEquals(1.0, StoryTextMotion.scale(StoryTextStyle.SHOUT, 99.0), 0.0)
        assertEquals(1.0, StoryTextMotion.scale(StoryTextStyle.EMPHASIS, 99.0), 0.0)
        assertEquals(1.0, StoryTextMotion.scale(StoryTextStyle.NORMAL, 99.0), 0.0)
        assertEquals(1.0, StoryTextMotion.scale(StoryTextStyle.WHISPER, 99.0), 0.0)
    }

    @Test fun excited_scale_pulses_between_1_and_1_02() {
        // t=0 → sin0=0 → 1 + 0.5·0.02 = 1.01
        assertEquals(1.01, StoryTextMotion.scale(StoryTextStyle.EXCITED, 0.0), 1e-9)
        // t=π/16 → sin(π/2)=1 → 1 + 1·0.02 = 1.02
        assertEquals(1.02, StoryTextMotion.scale(StoryTextStyle.EXCITED, Math.PI / 16), 1e-9)
        // t=3π/16 → sin(3π/2)=-1 → 1 + 0 = 1.0
        assertEquals(1.0, StoryTextMotion.scale(StoryTextStyle.EXCITED, 3 * Math.PI / 16), 1e-9)
    }
}
