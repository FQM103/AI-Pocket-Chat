package com.situ.aichat.story

import com.situ.aichat.data.local.dao.StoryChapterSummaryRow
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import kotlin.math.abs

/**
 * 故事生成提示词「共用段」（自 [StoryGenerationPromptBuilder] 拆出 · 文件瘦身，**行为零改 / 逐字搬**）。
 *
 * 首章 / 续章创作 prompt 共用的 append 助手（角色段 / 动态状态参考 / 前情回顾）、聊天影响权重说明、
 * 自定义提示词解析（customPrompts 优先，否则预设）与摘要压缩提示词模板。纯函数、零 DB 依赖
 * （原料由 11.1e 生成服务预收集传入）。经 [StoryGenerationPromptBuilder] 同名成员薄委托调用
 * （公开 API 与单测点名签名逐字不变）；§5 强耦合性质：提示词文案逐字节搬，一个字符未动。
 */
internal object StoryPromptSections {

    // MARK: - 角色段（1:1 iOS appendCharacterSection :19-57）

    /**
     * 注入角色信息——从关联 AI 角色读取完整身份（性别/年龄/职业/外貌/性格/背景）。
     *
     * @param sortedRoles 已按 [sortedStoryRoles]（用户角色优先、再按名字）排好序的角色（= iOS `story.sortedCharacters`）
     * @param narrativePerson 叙事人称 raw（[StoryNarrativePerson]），决定用户扮演角色的人称注
     * @param characterData 关联 AI 角色的已解析身份原料，按 `characterId` 索引（无关联或查不到则只出用户填写的设定）
     */
    fun appendCharacterSection(
        lines: MutableList<String>,
        sortedRoles: List<StoryCharacterRoleEntity>,
        narrativePerson: String,
        characterData: Map<String, StoryCharacterSectionData>,
    ) {
        lines.add("")
        lines.add("## 角色")
        for (role in sortedRoles) {
            val roleType = when (role.roleType) {
                StoryRoleType.PROTAGONIST -> "主角"
                StoryRoleType.ANTAGONIST -> "反派"
                else -> "配角"
            }

            val parts = mutableListOf<String>()

            // 从关联 AI 角色读取完整身份信息（通过 characterId 关联）
            val data = role.characterId?.let { characterData[it] }
            if (data != null) {
                if (data.gender.isNotEmpty()) parts.add("性别：${data.gender}")
                data.age?.let { if (it > 0) parts.add("年龄：${it}岁") }
                if (data.occupation.isNotEmpty()) parts.add("职业/身份：${data.occupation}")
                if (data.appearanceDescription.isNotEmpty()) parts.add("外貌：${data.appearanceDescription}")
                if (data.personalityDescription.isNotEmpty()) parts.add("性格：${data.personalityDescription}")
                // 卷一 V6：300 → 800（角色卡读不全是「角色不像本人」的头号成因；上限仍保留防单卡失控）
                if (data.backstory.isNotEmpty()) parts.add("背景：${data.backstory.take(800)}")
            }

            // 用户填写的角色描述补充
            if (!role.roleDescription.isNullOrEmpty()) parts.add(role.roleDescription)

            if (role.isUserRole) {
                val personNote = when (narrativePerson) {
                    StoryNarrativePerson.FIRST -> "这是用户扮演的角色，请以第一人称「我」来描写。"
                    StoryNarrativePerson.THIRD -> "这是用户扮演的角色，请以第三人称来描写。"
                    else -> "这是用户扮演的角色，请以第二人称「你」来描写行动和感受。"
                }
                parts.add(personNote)
            }

            // 私下反差（故事二期卷一·提案 §5.1）：追在该角色**行尾**。首/续章与弧线大纲三处共用本函数，
            // 故大纲也看得到（对场景排布规划有益、无害·图纸 J8）；空/未填 = 该角色行逐字节零变化。
            role.intimatePersona?.takeIf { it.isNotBlank() }?.let { parts.add("私下反差：$it") }

            // 卷一 V6：整卡 2000 → 4000
            val joined = if (parts.isEmpty()) "按角色定位自由发挥" else parts.joinToString("；").take(4000)
            lines.add("- ${role.roleName}（$roleType）：$joined")
        }
    }

    // MARK: - 动态状态参考（仅第一章使用，1:1 iOS appendInitialDynamicState :73-119）

    /**
     * 将主角当前的性格光谱和关系质感作为开篇「初始状态参考」注入第一章。后续章节不再注入（关系发展由剧情自驱）。
     *
     * 只输出偏离中性值 50 达 ≥20 的维度：性格→偏高/偏低(值)，关系→较高/较低。两者皆无显著偏离则整段不输出。
     *
     * @param protagonistSpectrum 主角关联角色的性格光谱（无主角或无关联角色 → null）
     * @param protagonistQuality 主角关联角色的关系质感（同上）
     */
    fun appendInitialDynamicState(
        lines: MutableList<String>,
        protagonistSpectrum: PersonalitySpectrum?,
        protagonistQuality: RelationshipQuality?,
    ) {
        val stateParts = mutableListOf<String>()

        // 性格光谱概述（只输出偏离中性值较大的维度）
        if (protagonistSpectrum != null) {
            val personalityTraits = PersonalitySpectrum.DIMENSION_NAMES.zip(protagonistSpectrum.values)
                .mapNotNull { (name, value) ->
                    if (abs(value - 50) >= 20) {
                        val direction = if (value > 50) "偏高" else "偏低"
                        "$name$direction（$value）"
                    } else {
                        null
                    }
                }
            if (personalityTraits.isNotEmpty()) {
                stateParts.add("性格倾向：${personalityTraits.joinToString("、")}")
            }
        }

        // 关系质感概述
        if (protagonistQuality != null) {
            val relationshipTraits = RelationshipQuality.DIMENSION_NAMES.zip(protagonistQuality.values)
                .mapNotNull { (name, value) ->
                    if (abs(value - 50) >= 20) {
                        val direction = if (value > 50) "较高" else "较低"
                        "$name$direction"
                    } else {
                        null
                    }
                }
            if (relationshipTraits.isNotEmpty()) {
                stateParts.add("与用户的关系质感：${relationshipTraits.joinToString("、")}")
            }
        }

        if (stateParts.isEmpty()) return
        lines.add("## 角色初始状态参考（仅供第一章参考，后续章节的关系发展由故事剧情自然推动）")
        for (part in stateParts) lines.add("- $part")
        lines.add("")
    }

    // MARK: - 前情回顾（滑动窗口 + 故事圣经，1:1 iOS appendRecapSection :124-179）

    /**
     * 滑动窗口式前情回顾：最近 5 章完整、6-12 章前截断 100 字、更早的视全局摘要情况处理（有则略·无则截 50 字兜底）。
     *
     * @param chapterNumber 即将创作的章号（窗口锚点）
     * @param chapterSummaries 该故事所有章的摘要投影（[StoryChapterSummaryRow]，由 `StoryDao.getChapterSummaries` 取，不含正文）
     */
    fun appendRecapSection(
        lines: MutableList<String>,
        story: StoryEntity,
        chapterNumber: Int,
        chapterSummaries: List<StoryChapterSummaryRow>,
    ) {
        val lastCompressed = story.lastCompressedAtChapter ?: 0
        val hasGlobalSummary = lastCompressed > 0 && !story.storySummary.isNullOrEmpty()

        lines.add("## 前情回顾")

        // 全局摘要（如果有）
        if (hasGlobalSummary) {
            lines.add("### 全局摘要（第1-${lastCompressed}章精华）")
            lines.add(story.storySummary)
            lines.add("")
        }

        // 需要逐章列出的范围：全局摘要之后 ~ 当前章之前
        val startAfter = if (hasGlobalSummary) lastCompressed else 0
        val previousChapters = chapterSummaries
            .filter { it.chapterNumber in (startAfter + 1) until chapterNumber }
            .sortedBy { it.chapterNumber }

        if (previousChapters.isEmpty() && !hasGlobalSummary) {
            lines.add("暂无前情，请根据已知设定自然创作。")
            lines.add("")
        } else if (previousChapters.isNotEmpty()) {
            lines.add("### 近期章节摘要")
            for (ch in previousChapters) {
                val summary = ch.chapterSummary
                if (summary.isNullOrEmpty()) continue
                val distance = chapterNumber - ch.chapterNumber
                when {
                    // 最近 5 章：完整摘要
                    distance <= 5 -> lines.add("第${ch.chapterNumber}章：$summary")
                    // 6-12 章前：截断到 100 字
                    distance <= 12 -> lines.add("第${ch.chapterNumber}章：${truncateSummary(summary, 100)}")
                    // 有全局摘要时：更早的章节不再单独列出
                    hasGlobalSummary -> Unit
                    // 无全局摘要时：截断到 50 字兜底
                    else -> lines.add("第${ch.chapterNumber}章：${truncateSummary(summary, 50)}")
                }
            }
            lines.add("")
        }

        if (!story.storyBible.isNullOrEmpty()) {
            lines.add("## 故事圣经（角色和伏笔的完整记录，必须保持一致性）")
            lines.add(story.storyBible)
            lines.add("")
        }

        lines.add("## 当前剧情弧线")
        lines.add(story.currentArc ?: "暂无，请根据前文自然推进。")
        lines.add("")
    }

    /** 截断摘要到指定字数，超出部分用「…」替代（1:1 iOS `truncateSummary` :182-185）。 */
    private fun truncateSummary(text: String, maxLength: Int): String =
        if (text.length <= maxLength) text else text.take(maxLength) + "…"

    // MARK: - 聊天影响权重说明（1:1 iOS chatInfluenceInstruction :348-375）

    fun chatInfluenceInstruction(weight: String): String = when (weight) {
        StoryChatInfluenceWeight.NONE ->
            "不允许聊天内容影响剧情。故事只能依据既有设定、前情摘要和用户在故事内做出的选择推进。"
        StoryChatInfluenceWeight.LIGHT -> """
            轻度影响规则：
            - 只允许聊天内容影响话题、细节描写、对话用词和氛围点缀
            - 不允许直接改变主线方向、核心人物关系、重大剧情节点和结局
            - 如果聊天内容和故事主线冲突，以故事主线为准
        """.trimIndent()
        StoryChatInfluenceWeight.HEAVY -> """
            重度影响规则：
            - 聊天内容可以显著影响角色态度、关系演进、支线发展以及部分主线推进
            - 允许聊天中的情绪、关系状态、共同记忆和成长变化进入关键剧情节点
            - 用户最近互动可以影响角色在故事中的立场、选择倾向和事件后果
            - 仍需保证故事连贯，不能为了引用聊天信息而破坏世界观和前文逻辑
        """.trimIndent()
        else -> """
            中度影响规则：
            - 聊天内容可以影响角色态度、互动方式、情绪走向和支线细节
            - 可以自然影响部分剧情推进，但不要直接主导主线和结局
            - 重点参考关系状态、近期互动和长期记忆，让故事更贴近用户当前陪伴状态
        """.trimIndent()
    }

    // MARK: - 自定义提示词解析（customPrompts 优先，否则预设；1:1 iOS resolved* :239-260）

    fun resolvedWriterIdentity(story: StoryEntity): String {
        // 判据走单源 effectiveWriterIdentity（trim 后非空才算数）：纯空白身份回退到文风默认身份，
        // 不再产出一段空白身份段（R1 复核 🔵-2·与两处文风行避让同源，口径不许分叉）。
        val custom = CustomStoryPrompts.decode(story.customPromptsJson)?.effectiveWriterIdentity
        if (custom != null) return custom
        return StoryWritingTechniques.writerIdentity(story.writingStyle)
    }

    fun resolvedGenreTechniques(story: StoryEntity): String {
        val custom = CustomStoryPrompts.decode(story.customPromptsJson)?.genreTechniques
        if (!custom.isNullOrEmpty()) return custom
        return StoryWritingTechniques.genreTechniques(story.genre)
    }

    /**
     * 风格原则：用户填了「写作规则」就整段接管，否则用默认风格原则。
     *
     * 文字忌口改造（2026-07-30·J2）：**不再往这里拼忌口**。原先「用户规则 + 忌口」的合体拼接是双重注入
     * bug 的一半（另一半在创建页「填入默认」），且让用户一接管风格就连带影响忌口。两者自此正交——
     * 忌口走 [resolvedBannedExpressions] 独立成段注入，位置紧跟本段，prompt 字节级不变。
     */
    fun resolvedWritingRules(story: StoryEntity): String =
        CustomStoryPrompts.decode(story.customPromptsJson)?.writingRules?.takeIf { it.isNotBlank() }
            ?: StoryWritingTechniques.writingPrinciples

    /**
     * 文字忌口三层取值（**唯一实现点**·别处不许再判优先级）：本故事 › 全局 › 内置默认。
     *
     * **本书层是真三态**（故事二期卷二 J1 闭环旧缺口，口径自此与
     * [com.situ.aichat.story.StoryCraftSections.resolvedSceneBeats] 完全一致，别「对齐旧忌口」改回二态）：
     * `null` = 跟随全局；`""`/纯空白 = **本书主动关掉**（不再往下落到全局或内置默认）；文本 = 本书覆盖。
     * 产品定位拍板明言「默认词表与部分题材打架须可整本关」是刚需，二态表达不出这一态。
     * 正常写路 trim 后空归 null，故库里的 `""` 只可能来自备份导入——该来源的语义本就是「用户清空过」。
     *
     * @param globalOverride 全局值（`AppSettings.storyBannedExpressions` 原值）。**三态语义**：
     *   `null` = 从未设置 → 落到内置默认；`""` = 用户主动清空 → **整段不注入**；其他 = 用户自定义文本。
     *   两态必须分开，不许用 `?: default` 把空串折叠成默认（否则用户永远删不掉忌口·提案 §6.2 过审「清空允许」）。
     * @return 要注入的忌口文本；**null = 不注入任何忌口段**
     */
    fun resolvedBannedExpressions(story: StoryEntity, globalOverride: String?): String? {
        val perStory = CustomStoryPrompts.decode(story.customPromptsJson)?.bannedExpressions
        if (perStory != null) return perStory.trim().ifEmpty { null }       // ① 本书（""/空白 = 本书关闭）
        if (globalOverride != null) return globalOverride.ifBlank { null }  // ② 全局（""=主动清空→不注入）
        return StoryWritingTechniques.bannedExpressionsBaseline             // ③ 内置默认
    }

    // MARK: - 节奏偏好注入行（卷三 V2·创作与弧线两锚共用单源）

    /** 节奏偏好注入行的标签（图纸 §9 锁定文本）——labeled 信号，与「剧情方向」并列而不混写。 */
    const val PACING_PREFERENCE_PREFIX = "节奏偏好（用户指定）："

    /**
     * 用户填的节奏偏好注入行；没填 / 纯空白 → null（**不注入**，老故事两锚 prompt 字节级零变化·图纸 §5 E3）。
     * 两个锚点（[StoryGenerationPromptBuilder.appendStorySetup] 与弧线大纲装配）共用本函数，格式不许分叉。
     */
    fun pacingPreferenceLine(prompts: CustomStoryPrompts?): String? =
        prompts?.pacingPreference?.trim()?.ifEmpty { null }?.let { PACING_PREFERENCE_PREFIX + it }

    /** [pacingPreferenceLine] 的 story 入口（自行解码 customPromptsJson）。 */
    fun pacingPreferenceLine(story: StoryEntity): String? =
        pacingPreferenceLine(CustomStoryPrompts.decode(story.customPromptsJson))

    // MARK: - 摘要压缩提示词（1:1 iOS buildCompressionPrompt :153-194）

    /** 把「旧压缩版 + 新 N 章摘要」合并为新的全局摘要。[genre] = 题材锚（图纸 L4），必传（调用点唯一·避可选参静默丢功能）。 */
    fun buildCompressionPrompt(
        existingCompressed: String,
        newSummaries: String,
        lastCompressedChapter: Int,
        currentChapter: Int,
        genre: String,
    ): String {
        val existingSection = if (existingCompressed.isEmpty()) {
            "（首次压缩，无已有摘要）"
        } else {
            "### 已有压缩摘要（第1-${lastCompressedChapter}章精华）\n$existingCompressed"
        }

        // 逐行 add（existingSection/newSummaries 可能含内部换行，作单元素）避免 trimIndent 与插值冲突
        val lines = mutableListOf<String>()
        lines.add("你是一个故事编辑助手。请把以下\"已有压缩摘要\"和\"新增章节摘要\"合并压缩为一份新的全局摘要。")
        lines.add("这是一部「$genre」类型的故事。概括时必须保留支撑该类型核心体验的线索（如感情线的进展与温度、关键关系的走向），不要把摘要写成与类型无关的纯事件流水账。")
        lines.add("")
        lines.add(existingSection)
        lines.add("")
        lines.add("### 新增章节摘要（第${lastCompressedChapter + 1}-${currentChapter}章）")
        lines.add(newSummaries)
        lines.add("")
        lines.add("## 压缩要求")
        lines.add("1. 合并后的摘要控制在 600-800 字")
        lines.add("2. 必须保留：")
        lines.add("   - 所有角色名字和当前关系状态")
        lines.add("   - 所有重大剧情转折点（标注章节号，如\"第5章天台告白\"）")
        lines.add("   - 所有未解决的伏笔和悬念")
        lines.add("   - 当前主线进展到哪里了")
        lines.add("   - 与「$genre」类型核心体验直接相关的情感与关系进展")
        lines.add("3. 必须去重：")
        lines.add("   - 同一事件在不同章节被提到多次的，只保留最完整的一次描述")
        lines.add("   - 已解决的伏笔标注\"（已解决）\"，但不要删除")
        lines.add("4. 按以下结构组织：")
        lines.add("   【主线进展】一段话概述从开头到现在的主线发展")
        lines.add("   【角色状态】每个主要角色的当前状态和关系")
        lines.add("   【关键事件】按时间线列出重大转折（标注章节号）")
        lines.add("   【未解悬念】当前所有未解决的伏笔")
        lines.add("5. 只输出摘要本身，不要解释，不要添加任何前后缀")
        return lines.joinToString("\n")
    }
}
