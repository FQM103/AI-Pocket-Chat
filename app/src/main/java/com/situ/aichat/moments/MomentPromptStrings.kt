package com.situ.aichat.moments

import com.situ.aichat.R
import com.situ.aichat.prompt.PromptStrings

/**
 * Localized template strings for the 朋友圈 post-generation prompt (M06 7.2.3). iOS
 * `generatePostContent` uses `String(localized:)`, so — like the diary module — these go through
 * string resources (values 中文 / values-en 英文), resolved by [PromptStrings] per locale, 1:1 with
 * iOS. (Correction to an earlier note: diary is **not** the only bilingual generation prompt — moments
 * is too. The now-context + schedule blocks, however, are hardcoded Chinese; see [MomentPromptContext].)
 *
 * Fidelity caveat (matches iOS): keys without a zh-Hans translation in the catalog
 * (gender/occupation/backstory/speakingStyle/catchphrases/interests/hotInterests/traitHigh/traitLow/
 * traits/userPostsHeader/reqWordCount) fall back to the English base string even on Chinese devices —
 * `String(localized:)` returns the key when no localization matches. The Chinese resources therefore
 * intentionally hold the English text for those keys.
 *
 * Fields with a format arg ([youAre]/[personality]/…/[reqWordCount]) store the raw template (`%1$s`);
 * [MomentPostPromptBuilder] fills them via `String.format`, keeping the builder pure + testable.
 */
data class MomentPromptStrings(
    val youAre: String,
    val personality: String,
    val characterSetup: String,
    val gender: String,
    val occupation: String,
    val backstory: String,
    val speakingStyle: String,
    val catchphrases: String,
    val interests: String,
    val hotInterests: String,
    val traitHigh: String,
    val traitLow: String,
    val traits: String,
    val memoriesHeader: String,
    val petStatus: String,
    val petMention: String,
    val userPostsHeader: String,
    val userPostsFooter: String,
    val nowWrite: String,
    val requirementsHeader: String,
    val reqContent: String,
    val reqStyle: String,
    val reqWordCount: String,
    val reqNatural: String,
    val reqNoAi: String,
    val reqEmoji: String,
    val reqNoRepeat: String,
    val outputOnly: String,
    val userMessage: String,
) {
    companion object {
        fun from(strings: PromptStrings): MomentPromptStrings = MomentPromptStrings(
            youAre = strings.s(R.string.moment_post_you_are),
            personality = strings.s(R.string.moment_post_personality),
            characterSetup = strings.s(R.string.moment_post_character_setup),
            gender = strings.s(R.string.moment_post_gender),
            occupation = strings.s(R.string.moment_post_occupation),
            backstory = strings.s(R.string.moment_post_backstory),
            speakingStyle = strings.s(R.string.moment_post_speaking_style),
            catchphrases = strings.s(R.string.moment_post_catchphrases),
            interests = strings.s(R.string.moment_post_interests),
            hotInterests = strings.s(R.string.moment_post_hot_interests),
            traitHigh = strings.s(R.string.moment_post_trait_high),
            traitLow = strings.s(R.string.moment_post_trait_low),
            traits = strings.s(R.string.moment_post_traits),
            memoriesHeader = strings.s(R.string.moment_post_memories_header),
            petStatus = strings.s(R.string.moment_post_pet_status),
            petMention = strings.s(R.string.moment_post_pet_mention),
            userPostsHeader = strings.s(R.string.moment_post_user_posts_header),
            userPostsFooter = strings.s(R.string.moment_post_user_posts_footer),
            nowWrite = strings.s(R.string.moment_post_now_write),
            requirementsHeader = strings.s(R.string.moment_post_requirements_header),
            reqContent = strings.s(R.string.moment_post_req_content),
            reqStyle = strings.s(R.string.moment_post_req_style),
            reqWordCount = strings.s(R.string.moment_post_req_word_count),
            reqNatural = strings.s(R.string.moment_post_req_natural),
            reqNoAi = strings.s(R.string.moment_post_req_no_ai),
            reqEmoji = strings.s(R.string.moment_post_req_emoji),
            reqNoRepeat = strings.s(R.string.moment_post_req_no_repeat),
            outputOnly = strings.s(R.string.moment_post_output_only),
            userMessage = strings.s(R.string.moment_post_user_message),
        )
    }
}

/**
 * Overridable fields for the 朋友圈 scene (1:1 iOS `MomentsPromptField`). No settings UI yet (→ P12);
 * overrides are always empty = default behavior. [raw] are the stable JSON keys, matching iOS, for the
 * future scene-override framework.
 */
enum class MomentsPromptField(val raw: String) {
    WORD_COUNT_RANGE("wordCountRange"),
    TONE_STYLE("toneStyle"),
    EMOJI_POLICY("emojiPolicy"),
    EXTRA_RULES("extraRules"),
}
