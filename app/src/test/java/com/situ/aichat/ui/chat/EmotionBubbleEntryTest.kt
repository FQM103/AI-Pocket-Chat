package com.situ.aichat.ui.chat

import com.situ.aichat.tts.EmotionType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-5（批5）：情绪入场动画查表单测——断言全部从 iOS EmotionAnimationModifier.swift 真值反推：
 * emoji→情绪映射（:21-52 switch 序，⚠️ 以 switch 为准非枚举注释；分类单源=tts.EmotionType，
 * 批5 复核 #8 收编）、per-emotion 两段时长（:221-253）、情绪×阶段几何变换（:146-215）、
 * 三相弹簧换算（:108/:116/:123 + 批0 公式互证）。
 */
class EmotionBubbleEntryTest {

    // MARK: - emoji → EmotionType 映射（iOS :21-52）

    @Test
    fun `twelve representative emojis map to their emotion`() {
        assertEquals(EmotionType.HAPPY, EmotionType.from("😊"))
        assertEquals(EmotionType.EXCITED, EmotionType.from("🤩"))
        assertEquals(EmotionType.ANGRY, EmotionType.from("😡"))
        assertEquals(EmotionType.SAD, EmotionType.from("😢"))
        assertEquals(EmotionType.SHOCKED, EmotionType.from("😱"))
        assertEquals(EmotionType.SHY, EmotionType.from("😳"))
        assertEquals(EmotionType.LOVE, EmotionType.from("😍"))
        assertEquals(EmotionType.THINKING, EmotionType.from("🤔"))
        assertEquals(EmotionType.SCARED, EmotionType.from("😨"))
        assertEquals(EmotionType.PLAYFUL, EmotionType.from("😏"))
        assertEquals(EmotionType.SIGH, EmotionType.from("🫠"))
        assertEquals(EmotionType.NEUTRAL, EmotionType.from("😐"))
    }

    @Test
    fun `mapping traps pinned to iOS switch not its comment`() {
        // 🥰 在 LOVE（iOS 枚举注释写 happy 行是误导，switch :37 为准）。
        assertEquals(EmotionType.LOVE, EmotionType.from("🥰"))
        // 😅 在 SIGH（不在 happy）；💭 在 THINKING；💢 在 ANGRY；🥲 在 SAD。
        assertEquals(EmotionType.SIGH, EmotionType.from("😅"))
        assertEquals(EmotionType.THINKING, EmotionType.from("💭"))
        assertEquals(EmotionType.ANGRY, EmotionType.from("💢"))
        assertEquals(EmotionType.SAD, EmotionType.from("🥲"))
    }

    @Test
    fun `shocked exact match wins over sigh prefix fallback for bare face`() {
        // 😮 精确命中 SHOCKED 在先（=iOS :33）。😮‍💨→SIGH 是**登记有意分叉**（批5 复核 #7）：
        // Swift hasPrefix 按字素簇比较实返 false→iOS 真机落 neutral 不播；安卓按 iOS :48 注释
        // 书面意图兜成 SIGH（锁安卓有意行为，非宣称 iOS 等价）。
        assertEquals(EmotionType.SHOCKED, EmotionType.from("😮"))
        assertEquals(EmotionType.SIGH, EmotionType.from("😮‍💨"))
    }

    @Test
    fun `null empty and unknown emojis are neutral`() {
        assertEquals(EmotionType.NEUTRAL, EmotionType.from(null))
        assertEquals(EmotionType.NEUTRAL, EmotionType.from(""))
        assertEquals(EmotionType.NEUTRAL, EmotionType.from("😀"))
        // 多 emoji 串与 iOS String(prefix(2)) switch 同为不匹配 → neutral。
        assertEquals(EmotionType.NEUTRAL, EmotionType.from("😊😊"))
    }

    // MARK: - per-emotion 两段时长（iOS :221-253 全量 12×2）

    @Test
    fun `phase durations match iOS table for all twelve emotions`() {
        val expected = mapOf(
            EmotionType.HAPPY to (180L to 150L),
            EmotionType.PLAYFUL to (180L to 150L),
            EmotionType.EXCITED to (200L to 180L),
            EmotionType.ANGRY to (80L to 80L),
            EmotionType.SAD to (300L to 250L),
            EmotionType.SHOCKED to (120L to 120L),
            EmotionType.SHY to (220L to 180L),
            EmotionType.LOVE to (250L to 200L),
            EmotionType.THINKING to (200L to 200L),
            EmotionType.SCARED to (80L to 80L),
            EmotionType.SIGH to (250L to 200L),
            EmotionType.NEUTRAL to (0L to 0L),
        )
        assertEquals(EmotionType.entries.size, expected.size)
        for ((emotion, durations) in expected) {
            assertEquals("$emotion phase1", durations.first, emotion.phase1DurationMs)
            assertEquals("$emotion phase2", durations.second, emotion.phase2DurationMs)
        }
    }

    // MARK: - 情绪×阶段几何变换（iOS :146-215）

    @Test
    fun `phase 0 and phase 3 are identity for every emotion`() {
        for (emotion in EmotionType.entries) {
            assertEquals("$emotion phase0", EMOTION_IDENTITY, emotionTransform(emotion, 0))
            assertEquals("$emotion phase3", EMOTION_IDENTITY, emotionTransform(emotion, 3))
        }
    }

    @Test
    fun `transform table matches iOS values per emotion and phase`() {
        // offsetX 族（iOS :146-156）：angry ±3 / shocked ±2 / scared ±1.5。
        assertEquals(EmotionTransform(3f, 0f, 1f, 0f, 1f), emotionTransform(EmotionType.ANGRY, 1))
        assertEquals(EmotionTransform(-3f, 0f, 1f, 0f, 1f), emotionTransform(EmotionType.ANGRY, 2))
        assertEquals(EmotionTransform(1.5f, 0f, 1f, 0f, 1f), emotionTransform(EmotionType.SCARED, 1))
        assertEquals(EmotionTransform(-1.5f, 0f, 1f, 0f, 1f), emotionTransform(EmotionType.SCARED, 2))
        // offsetY+scale 族（:159-171/:174-190）：happy -5/1.02、sad -8+alpha0.5、love -6/1.03、playful -4+rot2。
        assertEquals(EmotionTransform(0f, -5f, 1.02f, 0f, 1f), emotionTransform(EmotionType.HAPPY, 1))
        assertEquals(EMOTION_IDENTITY, emotionTransform(EmotionType.HAPPY, 2))
        assertEquals(EmotionTransform(0f, -8f, 1f, 0f, 0.5f), emotionTransform(EmotionType.SAD, 1))
        assertEquals(EmotionTransform(0f, -6f, 1.03f, 0f, 1f), emotionTransform(EmotionType.LOVE, 1))
        assertEquals(EmotionTransform(0f, -4f, 1f, 2f, 1f), emotionTransform(EmotionType.PLAYFUL, 1))
        // playful phase2 rotation 即归 0（iOS :201 仅 phase1 给 2°）。
        assertEquals(EMOTION_IDENTITY, emotionTransform(EmotionType.PLAYFUL, 2))
        // 双相 scale 族：excited 0.92→1.06+rot ±1.5°、shocked 0.85→1.08+offsetX ±2、sigh 1.02→0.97。
        assertEquals(EmotionTransform(0f, 0f, 0.92f, 1.5f, 1f), emotionTransform(EmotionType.EXCITED, 1))
        assertEquals(EmotionTransform(0f, 0f, 1.06f, -1.5f, 1f), emotionTransform(EmotionType.EXCITED, 2))
        assertEquals(EmotionTransform(2f, 0f, 0.85f, 0f, 1f), emotionTransform(EmotionType.SHOCKED, 1))
        assertEquals(EmotionTransform(-2f, 0f, 1.08f, 0f, 1f), emotionTransform(EmotionType.SHOCKED, 2))
        assertEquals(EmotionTransform(0f, 0f, 1.02f, 0f, 1f), emotionTransform(EmotionType.SIGH, 1))
        assertEquals(EmotionTransform(0f, 0f, 0.97f, 0f, 1f), emotionTransform(EmotionType.SIGH, 2))
        // 单相淡化族：shy scale0.95+alpha0.7；thinking 纯旋转 ±2°。
        assertEquals(EmotionTransform(0f, 0f, 0.95f, 0f, 0.7f), emotionTransform(EmotionType.SHY, 1))
        assertEquals(EMOTION_IDENTITY, emotionTransform(EmotionType.SHY, 2))
        assertEquals(EmotionTransform(0f, 0f, 1f, 2f, 1f), emotionTransform(EmotionType.THINKING, 1))
        assertEquals(EmotionTransform(0f, 0f, 1f, -2f, 1f), emotionTransform(EmotionType.THINKING, 2))
        assertEquals(EMOTION_IDENTITY, emotionTransform(EmotionType.NEUTRAL, 1))
    }

    // MARK: - 三相弹簧换算（iOS :108/:116/:123，与批0 公式互证）

    @Test
    fun `three phase springs match iOS spring duration bounce conversion`() {
        // phase1 spring(0.25s, bounce 0.15) → ζ=0.85、k=(2π/0.25)²≈631.6547。
        assertEquals(0.85f, emotionPhase1Spring.dampingRatio, 1e-6f)
        assertEquals(631.6547f, emotionPhase1Spring.stiffness, 1e-3f)
        // phase2 spring(0.3s, bounce 0.08) → ζ=0.92、k=(2π/0.3)²≈438.6491。
        assertEquals(0.92f, emotionPhase2Spring.dampingRatio, 1e-6f)
        assertEquals(438.6491f, emotionPhase2Spring.stiffness, 1e-3f)
        // phase3 spring(0.25s, bounce 0.05) → ζ=0.95、k 同 phase1。
        assertEquals(0.95f, emotionPhase3Spring.dampingRatio, 1e-6f)
        assertEquals(631.6547f, emotionPhase3Spring.stiffness, 1e-3f)
    }
}
