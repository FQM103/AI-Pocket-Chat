package com.situ.aichat.tts

/**
 * 12 emotion types, classifying a mood emoji (from a `[mood:emoji|color|desc]` tag).
 * Ported 1:1 from iOS `EmotionType` (Views/Chat/EmotionAnimationModifier.swift) — the same emoji
 * dictionary iOS deliberately shares between the bubble entrance animation and TTS emotion mapping.
 * Single source for both consumers on Android too: TTS (MoodEmojiToMiniMaxEmotion) and the bubble
 * entrance animation (P1-5, ui/chat/EmotionBubbleEntry.kt — durations/transforms live there as
 * extensions per this contract).
 */
enum class EmotionType {
    HAPPY,
    EXCITED,
    ANGRY,
    SAD,
    SHOCKED,
    SHY,
    LOVE,
    THINKING,
    SCARED,
    PLAYFUL,
    SIGH,
    NEUTRAL;

    companion object {
        private val HAPPY_SET = setOf("😊", "😄", "😆", "☺️", "😁", "😋")
        private val EXCITED_SET = setOf("🤩", "🎉", "🥳", "🤗", "🎊")
        private val ANGRY_SET = setOf("😡", "😤", "🤬", "💢")
        private val SAD_SET = setOf("😢", "😔", "😞", "💔", "😿", "🥲")
        private val SHOCKED_SET = setOf("😱", "😲", "😮", "🫢", "😧", "😦")
        private val SHY_SET = setOf("😳", "🥺")
        private val LOVE_SET = setOf("😍", "❤️", "💕", "💗", "💖", "🥰")
        private val THINKING_SET = setOf("🤔", "😑", "🫤", "🧐", "💭")
        private val SCARED_SET = setOf("😨", "😰", "😥")
        private val PLAYFUL_SET = setOf("😏", "😜", "🤭", "😝", "😛", "😈")
        private val SIGH_SET = setOf("🫠", "😩", "😅", "🤦")

        /**
         * iOS `EmotionType.from(emoji:)`. Maps a mood emoji to a category. A plain `😮` matches
         * SHOCKED; a combined ZWJ sequence like `😮‍💨` falls through to the prefix check → SIGH.
         * 登记有意分叉（批5 复核 #7）：Swift `hasPrefix` 按字素簇比较，`😮‍💨` 实返 false → iOS 真机
         * 落 neutral 不播/不映射；安卓按 iOS :48 注释的书面意图（「处理 😮‍💨 等组合 emoji」被
         * Swift 字素语义反杀的事实 bug）兜成 SIGH——1:1 是地板，不降级复刻。
         */
        fun from(emoji: String?): EmotionType {
            if (emoji.isNullOrEmpty()) return NEUTRAL
            return when (emoji) {
                in HAPPY_SET -> HAPPY
                in EXCITED_SET -> EXCITED
                in ANGRY_SET -> ANGRY
                in SAD_SET -> SAD
                in SHOCKED_SET -> SHOCKED
                in SHY_SET -> SHY
                in LOVE_SET -> LOVE
                in THINKING_SET -> THINKING
                in SCARED_SET -> SCARED
                in PLAYFUL_SET -> PLAYFUL
                in SIGH_SET -> SIGH
                else -> if (emoji.startsWith("😮")) SIGH else NEUTRAL
            }
        }
    }
}
