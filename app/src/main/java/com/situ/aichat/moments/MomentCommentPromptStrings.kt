package com.situ.aichat.moments

import com.situ.aichat.R
import com.situ.aichat.prompt.PromptStrings

/**
 * Localized template strings for the 朋友圈 comment-generation prompt (M06 7.2.4), 1:1 with iOS
 * `generateCommentContent` + `buildCommentScheduleContext`. Bilingual via resources, same convention
 * as [MomentPromptStrings].
 *
 * Shared "Your gender/occupation/backstory/speaking-style/catchphrases/interests" + "Requirements:"
 * are the SAME xcstrings keys as the post prompt, so they reuse the `moment_post_*` resources (no
 * duplicate strings). Comment-specific keys use `moment_comment_*`. Keys without a zh-Hans translation
 * fall back to English on all devices (faithful to iOS `String(localized:)`).
 */
data class MomentCommentPromptStrings(
    // Reused from the post prompt (identical xcstrings keys).
    val gender: String,
    val occupation: String,
    val backstory: String,
    val speakingStyle: String,
    val catchphrases: String,
    val interests: String,
    val requirementsHeader: String,
    // Comment-specific.
    val intro: String,
    val friendPosted: String,
    val emotionTone: String,
    val aiAi: String,
    val photosBlind: String,
    val photosVision: String,
    val othersHeader: String,
    val othersReact: String,
    val saidToYou: String,
    val replyInstruction: String,
    val writeInstruction: String,
    val reqPersonality: String,
    val reqConcise: String,
    val reqFriends: String,
    val reqNoAi: String,
    val outputOnly: String,
    val userMessage: String,
    val friendFallback: String,
    val schedCurrent: String,
    val schedAt: String,
    val schedFeeling: String,
    val schedSuffix: String,
) {
    companion object {
        fun from(strings: PromptStrings): MomentCommentPromptStrings = MomentCommentPromptStrings(
            gender = strings.s(R.string.moment_post_gender),
            occupation = strings.s(R.string.moment_post_occupation),
            backstory = strings.s(R.string.moment_post_backstory),
            speakingStyle = strings.s(R.string.moment_post_speaking_style),
            catchphrases = strings.s(R.string.moment_post_catchphrases),
            interests = strings.s(R.string.moment_post_interests),
            requirementsHeader = strings.s(R.string.moment_post_requirements_header),
            intro = strings.s(R.string.moment_comment_intro),
            friendPosted = strings.s(R.string.moment_comment_friend_posted),
            emotionTone = strings.s(R.string.moment_comment_emotion_tone),
            aiAi = strings.s(R.string.moment_comment_ai_ai),
            photosBlind = strings.s(R.string.moment_comment_photos_blind),
            photosVision = strings.s(R.string.moment_comment_photos_vision),
            othersHeader = strings.s(R.string.moment_comment_others_header),
            othersReact = strings.s(R.string.moment_comment_others_react),
            saidToYou = strings.s(R.string.moment_comment_said_to_you),
            replyInstruction = strings.s(R.string.moment_comment_reply_instruction),
            writeInstruction = strings.s(R.string.moment_comment_write_instruction),
            reqPersonality = strings.s(R.string.moment_comment_req_personality),
            reqConcise = strings.s(R.string.moment_comment_req_concise),
            reqFriends = strings.s(R.string.moment_comment_req_friends),
            reqNoAi = strings.s(R.string.moment_comment_req_no_ai),
            outputOnly = strings.s(R.string.moment_comment_output_only),
            userMessage = strings.s(R.string.moment_comment_user_message),
            friendFallback = strings.s(R.string.moment_comment_friend_fallback),
            schedCurrent = strings.s(R.string.moment_comment_sched_current),
            schedAt = strings.s(R.string.moment_comment_sched_at),
            schedFeeling = strings.s(R.string.moment_comment_sched_feeling),
            schedSuffix = strings.s(R.string.moment_comment_sched_suffix),
        )
    }
}
