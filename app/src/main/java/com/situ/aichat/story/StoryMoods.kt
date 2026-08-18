package com.situ.aichat.story

/**
 * 心情词表单源（11 合法值 + 中文映射）：METADATA 解析（[StoryMetadataParser]）与正文标签解析
 * （[StoryContentParser]）共用一份词表——未知值归一失败返回 null，渲染层不再见到词表外的 mood
 * （FABLE5_STORY_REDESIGN_PROPOSAL.md §7-E4）。
 */
internal object StoryMoods {

    val valid: Set<String> = setOf(
        "warm", "tense", "romantic", "dark", "peaceful", "excited",
        "melancholy", "mysterious", "nostalgic", "horror", "dreamy",
    )

    private val chinese: Map<String, String> = mapOf(
        "温暖" to "warm",
        "紧张" to "tense",
        "浪漫" to "romantic",
        "黑暗" to "dark",
        "平静" to "peaceful",
        "兴奋" to "excited",
        "忧郁" to "melancholy",
        "神秘" to "mysterious",
        "怀旧" to "nostalgic",
        "恐怖" to "horror",
        "梦幻" to "dreamy",
    )

    /** 归一：合法英文原样（大小写不敏感）、中文映射到英文，其余返回 null。 */
    fun normalize(raw: String?): String? {
        val r = raw?.lowercase()?.trim() ?: return null
        if (r in valid) return r
        return chinese[r]
    }
}
