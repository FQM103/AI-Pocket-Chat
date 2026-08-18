package com.situ.aichat.ui.schedule

import com.situ.aichat.R
import com.situ.aichat.tts.EmotionType

/**
 * P1-18（批6）：日程时间线行 TalkBack 文案纯函数（iOS 日程行零语义 a11y=安卓纯超越）。
 *
 * 心情 emoji 取值域是**自由生成、无枚举**（iOS/安卓 prompt 同句「1 个 emoji 表达当时心情」，
 * few-shot 实证 😴/☕ 这类生活流 emoji 不在聊天 12 情绪集内），故三级兜底链：
 * moodText（LLM 自带中文心情词，最准）→ 映射标签（情感族经 [EmotionType] 单源分类——批5 复核 #8
 * 红线：emoji 表绝不二份；[SCHEDULE_MOOD_LABELS] 只放 EmotionType 覆盖不到的生活流条目）→
 * 原 emoji 透传（TalkBack 读 Unicode CLDR 名）。
 */
internal object ScheduleRowA11y {

    /** 聊天 12 情绪 → 心情标签资源（NEUTRAL 无标签→落补充表/透传）。分类单源=tts.EmotionType。 */
    private val EMOTION_LABELS: Map<EmotionType, Int> = mapOf(
        EmotionType.HAPPY to R.string.a11y_mood_happy,
        EmotionType.EXCITED to R.string.a11y_mood_excited,
        EmotionType.ANGRY to R.string.a11y_mood_angry,
        EmotionType.SAD to R.string.a11y_mood_sad,
        EmotionType.SHOCKED to R.string.a11y_mood_shocked,
        EmotionType.SHY to R.string.a11y_mood_shy,
        EmotionType.LOVE to R.string.a11y_mood_love,
        EmotionType.THINKING to R.string.a11y_mood_thinking,
        EmotionType.SCARED to R.string.a11y_mood_scared,
        EmotionType.PLAYFUL to R.string.a11y_mood_playful,
        EmotionType.SIGH to R.string.a11y_mood_sigh,
    )

    /** 日程生活流补充表（few-shot 实证 emoji + 聊天集漏的常用脸）；只收 EmotionType 落 NEUTRAL 的条目。 */
    private val SCHEDULE_MOOD_LABELS: Map<String, Int> = mapOf(
        "😴" to R.string.a11y_mood_sleepy, "😪" to R.string.a11y_mood_sleepy,
        "🥱" to R.string.a11y_mood_sleepy, "💤" to R.string.a11y_mood_sleepy, "🌙" to R.string.a11y_mood_sleepy,
        "☕" to R.string.a11y_mood_relaxed, "🍵" to R.string.a11y_mood_relaxed, "😌" to R.string.a11y_mood_relaxed,
        "🙂" to R.string.a11y_mood_calm, "😶" to R.string.a11y_mood_calm, "🧘" to R.string.a11y_mood_calm,
        "💪" to R.string.a11y_mood_energetic, "🔥" to R.string.a11y_mood_energetic, "⚡" to R.string.a11y_mood_energetic,
        "✨" to R.string.a11y_mood_expectant, "🌟" to R.string.a11y_mood_expectant,
        "😭" to R.string.a11y_mood_sad, "😀" to R.string.a11y_mood_happy, "😃" to R.string.a11y_mood_happy,
        "🤒" to R.string.a11y_mood_unwell, "🤧" to R.string.a11y_mood_unwell, "😷" to R.string.a11y_mood_unwell,
    )

    /** emoji→标签资源；未识别返回 null（调用方透传原 emoji 给 TalkBack 读 CLDR 名）。 */
    fun moodResId(moodEmoji: String?): Int? {
        if (moodEmoji.isNullOrBlank()) return null
        val type = EmotionType.from(moodEmoji)
        if (type != EmotionType.NEUTRAL) return EMOTION_LABELS[type]
        return SCHEDULE_MOOD_LABELS[moodEmoji]
    }

    /** 心情段三级兜底：moodText → 映射标签 → 原 emoji；全空 null=段跳过。 */
    fun moodSegment(moodText: String?, mappedLabel: String?, moodEmoji: String): String? =
        moodText?.takeIf { it.isNotBlank() } ?: mappedLabel ?: moodEmoji.takeIf { it.isNotBlank() }

    /** cd 拼接：null/空白段跳过，「·」连接。序=时段·活动·地点·心情·独白·互动（与视觉序一致）。 */
    fun contentDescription(
        periodLabel: String,
        activityText: String,
        location: String,
        mood: String?,
        innerThought: String?,
        interactionLabel: String?,
    ): String = listOfNotNull(periodLabel, activityText, location, mood, innerThought, interactionLabel)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("·")
}
