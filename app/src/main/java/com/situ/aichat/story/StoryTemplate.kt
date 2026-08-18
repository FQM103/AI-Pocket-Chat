package com.situ.aichat.story

/**
 * 内置故事模板（ST6·契约 FABLE5_STORY_REDESIGN_PROPOSAL §3.2）。
 *
 * 纯数据：一套模板 = 一键开书的全部预设值。内容单一事实源 = `FABLE5_STORY_TEMPLATES_DRAFT.md`
 * （2026-07-03 用户整体过审·照现稿），注册表见 [StoryTemplates]，开书装配见
 * [com.situ.aichat.ui.story.StoryTemplateAssembly]。主演不写死——开书时由所选 AI 角色填入主演位；
 * worldSetting / plotDirection 直接进生成 prompt。
 */
data class StoryTemplate(
    /** 稳定标识（模板墙列表 key + 埋点，不落库）。 */
    val id: String,
    /** 剧名感书名（开书时作故事标题预填，见 [StoryCreationLogic.resolvedTitle]）。 */
    val title: String,
    /** 一句话钩子（模板卡副行）。 */
    val tagline: String,
    /** 题材，取自 [StoryCreationCatalog.genres]。 */
    val genre: String,
    /** 文风，取自 [StoryCreationCatalog.writingStyles]。 */
    val writingStyle: String,
    /** 叙事人称，取 [StoryNarrativePerson]（本集多为 second，悬疑=first / 科幻=third）。 */
    val narrativePerson: String,
    /** 预写世界观（300–500 字，直接作生成原料）。 */
    val worldSetting: String,
    /** 预写剧情钩子（200–400 字，只给开局 + 核心悬念 + 情感张力方向）。 */
    val plotDirection: String,
    /** 开书 sheet 选角提示语（如「适合深情内敛的角色」）。 */
    val roleHint: String,
    /** 封面一句话意象；程序化封面参数（StoryCoverSpec）由 ST7a 按契约 §6.1 映射。 */
    val coverMotif: String,
)
