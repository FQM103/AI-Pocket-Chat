package com.situ.aichat.ui.story

import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.data.model.UserStoryTemplatePayload
import com.situ.aichat.story.StoryCreationCatalog
import com.situ.aichat.story.StoryNarrativePerson
import com.situ.aichat.story.StoryRoleType
import com.situ.aichat.story.StoryTemplate

/**
 * 开书装配（ST6·契约 §3.1）：模板 + 选角 → 现有 [StoryCreationForm]，随后走
 * [StoryCreationViewModel.createStory] 原路建 StoryEntity，**生成管线零改动**；
 * 「改一改再开」= 同一 form 预填进高级自定义（复用本函数产物）。
 *
 * 模板流对聊天权重 / 章长一律不再询问（J3·契约 §8）：吃 [StoryCreationForm] 默认值
 * （中度 / 无限 / 适中）；开书 sheet 只让用户定「谁主演」与「我也入场」。
 */
object StoryTemplateAssembly {

    /**
     * @param selectedRoles charId → roleType（开书 sheet：主演单选 + 可选配角）。
     * @param includeUserRole 「我也入场」开关（默认开）：
     *   开 = 以模板设定人称沉浸（多为「你」，悬疑「我」/ 科幻「他」）；
     *   关 = 不把用户放进故事、以旁观第三人称阅读（照 mockup 屏三「关掉则以旁观视角阅读」）。
     */
    fun toCreationForm(
        template: StoryTemplate,
        selectedRoles: Map<String, String>,
        includeUserRole: Boolean,
    ): StoryCreationForm = StoryCreationForm(
        selectedGenre = template.genre,
        isCustomGenre = false,
        selectedRoles = selectedRoles,
        includeUserRole = includeUserRole,
        userRoleType = StoryRoleType.PROTAGONIST,
        worldSetting = template.worldSetting,
        plotDirection = template.plotDirection,
        writingStyle = template.writingStyle,
        narrativePerson = if (includeUserRole) template.narrativePerson else StoryNarrativePerson.THIRD,
        presetTitle = template.title,
    )

    /**
     * 「我的模板」装配（图纸四 §3.4）：整套创作设定 → 同一个 [StoryCreationForm]，随后走**同一条**
     * [StoryCreationViewModel.createStory]。内置模板那条路（[toCreationForm]）一个字都没动。
     *
     * 与内置模板的三处不同：
     * ① **不预填书名**（模板是设定包不是书 → `presetTitle = null`，标题回落「{题材}故事」）；
     * ② 题材按 [UserStoryTemplatePayload.isCustomGenre] 放进自定义输入框或预设选择器——两条路
     *    `StoryCreationLogic.resolvedGenre` 解析出的最终题材字符串都等于存下的那个；
     * ③ 忌口 / 两个格式开关 / 追更设置走无 UI 隐藏字段（表单不加控件·§0.2-7）。
     */
    fun toCreationFormFromUserTemplate(
        payload: UserStoryTemplatePayload,
        selectedRoles: Map<String, String>,
        includeUserRole: Boolean,
    ): StoryCreationForm {
        val prompts = CustomStoryPrompts.decode(payload.customPromptsJson)
        return StoryCreationForm(
            selectedGenre = if (payload.isCustomGenre) StoryCreationCatalog.genres.first() else payload.genre,
            isCustomGenre = payload.isCustomGenre,
            customGenreName = if (payload.isCustomGenre) payload.genre else "",
            referenceGenre = payload.referenceGenre,
            customGenreTechniques = prompts?.genreTechniques.orEmpty(),
            customWriterIdentity = prompts?.writerIdentity.orEmpty(),
            customWritingRules = prompts?.writingRules.orEmpty(),
            selectedRoles = selectedRoles,
            includeUserRole = includeUserRole,
            userRoleType = StoryRoleType.PROTAGONIST,
            worldSetting = payload.worldSetting.orEmpty(),
            plotDirection = payload.plotDirection.orEmpty(),
            pacingPreference = prompts?.pacingPreference.orEmpty(),
            writingStyle = payload.writingStyle,
            chapterLength = chapterLengthOf(payload.chapterLengthPreference),
            chatInfluenceWeight = payload.chatInfluenceWeight,
            // 「我也入场」关掉时改旁观第三人称——口径与内置模板逐字相同。
            narrativePerson = if (includeUserRole) payload.narrativePerson else StoryNarrativePerson.THIRD,
            presetTitle = null,
            templatePromptsJson = payload.customPromptsJson,
            templateUpdateMode = payload.updateMode,
            templateUnlockHour = payload.unlockHour,
            templateUnlockMinute = payload.unlockMinute,
            // 章长走原值：上面那个四档枚举只是给「改一改再开」的表单看的，四档之外的数值不许被它四舍五入。
            templateChapterLength = payload.chapterLengthPreference,
        )
    }
}
