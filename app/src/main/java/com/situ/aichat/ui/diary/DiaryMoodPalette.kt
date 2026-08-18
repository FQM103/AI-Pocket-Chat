package com.situ.aichat.ui.diary

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.situ.aichat.ui.designsystem.AppEmotionColors
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 日记 12 心情 → 5 情绪原型色的 valence-arousal 归并（日记重设计 R1·契约 FABLE5_DIARY_REDESIGN_PROPOSAL §1 手法3）。
 *
 * 归并口径 = [com.situ.aichat.ui.designsystem.Palette] 情绪色注释的既有语义：Joy=喜悦/兴奋、Calm=平静/思考、
 * Sad=悲伤/叹气、Shy=害羞/爱、Anger=怒/惊/恐（负向高唤起）。色彩只承载**氛围**，辨识恒靠 emoji+文案冗余
 * （设计语言 §1.3 色弱硬约束）；tint 上的功能文字一律 `text.primary`（secondary 在部分 tint 上跌破 4.5，
 * 见 ColorContrastTest「diary.mood」网）。
 */
enum class DiaryMoodTone { JOY, CALM, SAD, SHY, ANGER }

/** emoji → 情绪原型。未知/空 → null（不染色）。12 心情全覆盖由 `DiaryMoodPaletteTest` 看门。 */
internal fun diaryMoodTone(emoji: String?): DiaryMoodTone? = when (emoji) {
    "😊", "🎉", "💪" -> DiaryMoodTone.JOY
    "😌", "🤔" -> DiaryMoodTone.CALM
    "😔", "😢", "😴" -> DiaryMoodTone.SAD
    "🥰", "🌈" -> DiaryMoodTone.SHY
    "😤", "😰" -> DiaryMoodTone.ANGER
    else -> null
}

/** 情绪原型 → 装饰档情绪色（[AppEmotionColors] 深浅档由主题映射自带）。 */
internal fun AppEmotionColors.toneColor(tone: DiaryMoodTone): Color = when (tone) {
    DiaryMoodTone.JOY -> joy
    DiaryMoodTone.CALM -> calm
    DiaryMoodTone.SAD -> sad
    DiaryMoodTone.SHY -> shy
    DiaryMoodTone.ANGER -> anger
}

/**
 * 心情 tint 不透明度（心情日历格 / 详情洇染头 / 撰写选中胶囊·情绪色 `copy(alpha=)` 叠于实底）。
 * 浅色 0.55 贴 mockup 观感；深色 0.35 防「深情绪色实底 × Cream 文字」跌破 4.5（合成对比由
 * ColorContrastTest 按本常量看门——调值须连测试一起看，单一事实源）。
 */
internal const val MoodTintAlphaLight = 0.55f

/** 深色档 tint 不透明度（见 [MoodTintAlphaLight]）。 */
internal const val MoodTintAlphaDark = 0.35f

/** 当前主题下的 tint 不透明度。 */
internal fun moodTintAlpha(isDark: Boolean): Float = if (isDark) MoodTintAlphaDark else MoodTintAlphaLight

/** 心情 tint 色（已含 alpha·直接作背景叠于表面）；无心情/未知 emoji → null。 */
@Composable
@ReadOnlyComposable
internal fun diaryMoodTint(emoji: String?): Color? {
    val tone = diaryMoodTone(emoji) ?: return null
    val colors = AppTheme.colors
    return colors.emotion.toneColor(tone).copy(alpha = moodTintAlpha(colors.isDark))
}

/** 心情色带（卡片左缘 3dp 竖条·全强度装饰色·非文字底）；无心情 → null。 */
@Composable
@ReadOnlyComposable
internal fun diaryMoodBand(emoji: String?): Color? {
    val tone = diaryMoodTone(emoji) ?: return null
    return AppTheme.colors.emotion.toneColor(tone)
}
