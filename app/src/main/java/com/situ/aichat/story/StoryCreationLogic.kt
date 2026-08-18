package com.situ.aichat.story

/**
 * 故事创建的纯逻辑：canCreateStory / defaultStoryTitle / normalizedText / 解析类型。无 UI/DB 依赖。
 * （`resolvedMaxChapters` 已随卷二单模式化退役，见下方注释。）
 */
object StoryCreationLogic {
    /** trim 后空串归 null（1:1 iOS normalizedText）。 */
    fun normalizedText(text: String): String? = text.trim().ifEmpty { null }

    /**
     * 是否可创建：至少一个角色；自定义类型还需类型名。
     *
     * **D-7 拍板（2026-08-01·图纸三 §3.4）**：「至少一个角色」放开到**本书专属角色**也算数
     * （[customRoleCount] > 0）——只写专属角色、不拉聊天角色也不让自己入场，是完全正当的开书方式。
     * 计数只算**非空名**的草稿（与「空名不落库」口径一致，见 StoryCreationViewModel 的落库过滤）。
     * 尾参带默认值：模板开书流（StoryOpenBookSheet）本就没有自建入口，调用点零改。
     */
    fun canCreateStory(
        isCustomGenre: Boolean,
        customGenreName: String,
        includeUserRole: Boolean,
        selectedCharacterCount: Int,
        customRoleCount: Int = 0,
    ): Boolean {
        val hasRole = includeUserRole || selectedCharacterCount > 0 || customRoleCount > 0
        return if (isCustomGenre) hasRole && customGenreName.trim().isNotEmpty() else hasRole
    }

    // 卷二·单模式化：`resolvedMaxChapters` 随 StorySerialMode 枚举一并退役——新书的 maxChapters 恒 null。

    /** 解析最终类型：自定义取类型名（空则「自定义」），否则取所选预设（1:1 iOS createStory resolvedGenre）。 */
    fun resolvedGenre(isCustomGenre: Boolean, customGenreName: String, selectedGenre: String): String =
        if (isCustomGenre) (normalizedText(customGenreName) ?: "自定义") else selectedGenre

    /**
     * 默认故事标题：一律「{类型}故事」（用户拍板 2026-07-13·去角色名）。
     * 原「1:1 iOS 拼角色名/用户名」的分支已按新铁律退役（只求好用，不再对齐 iOS）。仅用于自定义/无模板创建流。
     */
    fun defaultStoryTitle(genre: String): String = "${genre}故事"

    /**
     * 最终标题：模板开书带来的 presetTitle 优先（非空即用，如「与你重逢的第七年」），否则回落
     * [defaultStoryTitle]（一律「{类型}故事」）；presetTitle 优先逻辑不变。
     */
    fun resolvedTitle(presetTitle: String?, genre: String): String =
        presetTitle?.let { normalizedText(it) } ?: defaultStoryTitle(genre)

    /**
     * 文风偏好是否已被「写作身份」接管（创建屏文风行据此显示辅助小字）：仅自定义类型且身份草稿非空白。
     * 预设类型不合成写作口径（身份不落库，见 CustomStoryPrompts.composeForCreation 调用侧），
     * 切回预设后残留的身份草稿不生效，故不提示。
     *
     * 注：这是**创建屏草稿态**的提示谓词。真正决定文风是否注入提示词的是落库后的
     * `customPrompts.writerIdentity` 非空（见 [StoryGenerationPromptBuilder.appendStorySetup]），
     * 那一侧**不分类型**——设置页对所有故事开放写作身份编辑（用户拍板 2026-07-27）。
     */
    fun styleOverriddenByWriterIdentity(isCustomGenre: Boolean, customWriterIdentity: String): Boolean =
        isCustomGenre && customWriterIdentity.isNotBlank()
}
