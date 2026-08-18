package com.situ.aichat.ui.diary

import com.situ.aichat.R

/** 一个心情选项（emoji + 文案资源）。文案存进 [com.situ.aichat.data.local.entity.DiaryEntryEntity.moodText]。 */
data class DiaryMood(val emoji: String, val labelRes: Int)

/**
 * 12 个固定心情（1:1 iOS `ComposeDiaryView.moodOptions` 的 emoji 顺序）。标签走资源（zh 逐字对齐 iOS / en 译文）。
 */
val DIARY_MOODS: List<DiaryMood> = listOf(
    DiaryMood("😊", R.string.diary_mood_happy),
    DiaryMood("😌", R.string.diary_mood_calm),
    DiaryMood("🥰", R.string.diary_mood_blissful),
    DiaryMood("😔", R.string.diary_mood_sad),
    DiaryMood("😤", R.string.diary_mood_angry),
    DiaryMood("😰", R.string.diary_mood_anxious),
    DiaryMood("🤔", R.string.diary_mood_thoughtful),
    DiaryMood("😴", R.string.diary_mood_tired),
    DiaryMood("🎉", R.string.diary_mood_excited),
    DiaryMood("😢", R.string.diary_mood_heartbroken),
    DiaryMood("💪", R.string.diary_mood_fulfilled),
    DiaryMood("🌈", R.string.diary_mood_hopeful),
)
