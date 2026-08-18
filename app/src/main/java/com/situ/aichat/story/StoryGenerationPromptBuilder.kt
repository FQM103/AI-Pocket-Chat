package com.situ.aichat.story

import com.situ.aichat.data.local.dao.StoryChapterSummaryRow
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.data.model.MaxOutputLength
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality

/**
 * 一个故事角色的「角色段」原料（已解析），供 [StoryGenerationPromptBuilder.appendCharacterSection] 格式化。
 *
 * 对应 iOS `appendCharacterSection` 内从关联 AICharacter 直接读的身份字段；安卓由 11.1e 生成服务预先收集
 * （CharacterEntity 字段 + [com.situ.aichat.data.model.currentAge] 计算年龄），再传入纯格式化器，
 * 保持本类零 DB 依赖、100% 可单测（仿 d-1 [StoryVoiceCharacterData] 模式）。
 */
data class StoryCharacterSectionData(
    val gender: String,
    /** 当前年龄（已由调用方按 [com.situ.aichat.data.model.currentAge] 算好），null 或 ≤0 时不注入。 */
    val age: Int?,
    val occupation: String,
    val appearanceDescription: String,
    val personalityDescription: String,
    val backstory: String,
)

/**
 * 故事章节生成提示词拼装（1:1 iOS `Services/StoryGenerationPromptBuilder.swift` + `+Formatting`/`+TwoStep`/`+Outline`/`+Structuring`）。
 *
 * 模块化组合：写作身份(writingStyle) + 类型技巧(genre) + 通用规则 + 人称规则 + 标签规则 + 章节要求 + 输出格式。
 * append 风格助手取 `MutableList<String>`（1:1 iOS `appendXxx(to:&lines)` inout，最后 joinToString("\n")），
 * 既忠实又避开 trimIndent 与插值同用的缩进冲突。
 *
 * 数据依赖的 append 助手（appendCharacterSection / appendInitialDynamicState / appendRecapSection）与两步法创作
 * prompt（buildFirst/NextChapterCreationPrompt，11.1d-4）做成**纯函数**：角色段原料 [StoryCharacterSectionData]、
 * 声音档案串、聊天影响串、主角性格/关系、章节摘要投影、最新章实体全部由 11.1e 生成服务预先收集后传入（与 d-1/d-2 一致），
 * 本对象零 DB 依赖、100% 可单测。大纲生成（buildOutlinePrompt 里程碑/弧线，11.1d-5）同样取预收集角色段原料；
 * 结构化 prompt（buildStructuring/MetadataStructuringPrompt，两步法第二步）为纯字符串模板。
 * 注：iOS 旧 JSON 路径 buildFirst/NextChapterPrompt + appendOutputFormat + preferredMaxTokens 经 grep 确认 0 调用方 = 死代码，不移植。
 */
internal object StoryGenerationPromptBuilder {

    // MARK: - 故事设定

    fun appendStorySetup(lines: MutableList<String>, story: StoryEntity) {
        lines.add("## 故事设定")
        lines.add("类型：${story.genre}")
        // 用户自定义了写作身份时，文风标签行**整行不注入**（用户拍板 2026-07-27·取代长篇稳定性 L4a 的
        // 「加尾注保留」写法，契约 FABLE5_STORY_LONGFORM_STABILITY_PROPOSAL §6）：笔调既已由身份段全权决定，
        // 留一个弱标签只会与身份段争夺基调、白烧 token。判据**只看身份非空**，不分预设/自定义类型——
        // 故事落库后不存「当初是不是自定义类型」这一事实，且故事设置页对所有故事开放写作身份编辑。
        // 判据须与 [appendArcOutlineContext] 逐字同源（弧线大纲侧同避让）。
        val customPrompts = CustomStoryPrompts.decode(story.customPromptsJson)
        if (customPrompts?.effectiveWriterIdentity == null) {
            lines.add("文风：${story.writingStyle}")
        }
        lines.add("世界观：${story.worldSetting ?: "自由发挥"}")
        lines.add("剧情方向：${story.plotDirection ?: "自由发挥"}")
        // 卷三 V2：用户填了节奏偏好才多这一行；没填时本段逐字节与卷二一致。
        StoryPromptSections.pacingPreferenceLine(customPrompts)?.let { lines.add(it) }
    }

    // MARK: - 角色段（1:1 iOS appendCharacterSection :19-57·实现外搬 StoryPromptSections.kt·薄委托保签名）

    /** 注入角色信息——完整身份 + 用户角色人称注。实现见 [StoryPromptSections.appendCharacterSection]。 */
    fun appendCharacterSection(
        lines: MutableList<String>,
        sortedRoles: List<StoryCharacterRoleEntity>,
        narrativePerson: String,
        characterData: Map<String, StoryCharacterSectionData>,
    ) = StoryPromptSections.appendCharacterSection(lines, sortedRoles, narrativePerson, characterData)

    // MARK: - 动态状态参考（仅第一章，1:1 iOS appendInitialDynamicState :73-119·实现外搬 StoryPromptSections.kt）

    /** 主角性格光谱 / 关系质感开篇初始状态参考。实现见 [StoryPromptSections.appendInitialDynamicState]。 */
    fun appendInitialDynamicState(
        lines: MutableList<String>,
        protagonistSpectrum: PersonalitySpectrum?,
        protagonistQuality: RelationshipQuality?,
    ) = StoryPromptSections.appendInitialDynamicState(lines, protagonistSpectrum, protagonistQuality)

    // MARK: - 前情回顾（滑动窗口 + 故事圣经，1:1 iOS appendRecapSection :124-179·实现外搬 StoryPromptSections.kt）

    /** 滑动窗口式前情回顾 + 故事圣经 + 当前弧线。实现见 [StoryPromptSections.appendRecapSection]。 */
    fun appendRecapSection(
        lines: MutableList<String>,
        story: StoryEntity,
        chapterNumber: Int,
        chapterSummaries: List<StoryChapterSummaryRow>,
    ) = StoryPromptSections.appendRecapSection(lines, story, chapterNumber, chapterSummaries)

    // MARK: - 内容标记规则 + 创作输出格式（实现外搬 StoryFormatRules.kt·薄委托保签名）

    /** 内容标记规则（唯一版·2026-08-03 精简后）。实现见 [appendStoryMarkupRules]。 */
    fun appendMarkupRules(lines: MutableList<String>) = appendStoryMarkupRules(lines)

    /** 创作专用输出格式（两步法第一步，1:1 iOS appendCreationOutputFormat :258-309）。实现见 [appendStoryCreationOutputFormat]。 */
    fun appendCreationOutputFormat(lines: MutableList<String>, choicesEnabled: Boolean = true) =
        appendStoryCreationOutputFormat(lines, choicesEnabled)

    // MARK: - 聊天影响权重说明（1:1 iOS chatInfluenceInstruction :348-375·实现外搬 StoryPromptSections.kt）

    /** 聊天影响权重四档说明。实现见 [StoryPromptSections.chatInfluenceInstruction]。 */
    fun chatInfluenceInstruction(weight: String): String = StoryPromptSections.chatInfluenceInstruction(weight)

    // MARK: - 自定义提示词解析（customPrompts 优先，否则预设；1:1 iOS resolved* :239-260·实现外搬 StoryPromptSections.kt）

    /** 写手身份。实现见 [StoryPromptSections.resolvedWriterIdentity]。 */
    fun resolvedWriterIdentity(story: StoryEntity): String = StoryPromptSections.resolvedWriterIdentity(story)

    /** 类型技巧。实现见 [StoryPromptSections.resolvedGenreTechniques]。 */
    fun resolvedGenreTechniques(story: StoryEntity): String = StoryPromptSections.resolvedGenreTechniques(story)

    /** 通用写作铁律。实现见 [StoryPromptSections.resolvedWritingRules]。 */
    fun resolvedWritingRules(story: StoryEntity): String = StoryPromptSections.resolvedWritingRules(story)

    // MARK: - Token 上限（1:1 iOS :208-234）

    /**
     * 创作步骤 token 上限（不输出 JSON，只写故事+METADATA）；思考模型 max_tokens 含思考过程故 ×3。
     *
     * 固定档同样享受思考 ×3 余量（2026-07-26 卷一 V7：修复固定档绕过思考余量致截断）——用户在 API 配置里
     * 选了具体输出档（SHORT/MEDIUM/LONG/EXTRA_LONG）时，旧实现直接早退返回档位值，思考模型的推理过程把额度
     * 吃光后正文被掐断，症状是「选了长输出反而更容易断」。
     */
    fun preferredCreationMaxTokens(
        chapterLength: Int,
        isThinkingModel: Boolean,
        userMaxOutputLength: MaxOutputLength = MaxOutputLength.AUTO,
    ): Int {
        userMaxOutputLength.tokenLimit?.let { return if (isThinkingModel) it * 3 else it }
        // 提示词已做字数软引导，maxTokens 作硬保底可更宽容（2026-08-06 全档加倍至≈3 倍余量：思考过程与正文
        // 共享同一额度，高档思考实测把旧 1500 字档的 5000×3 挤爆致正文截断；上限只是保险丝，按实际输出计费。
        // 阈值承接章长四档选项 500/1500/3000/5000 及结局章 ×1.5 变体）
        // 超部分服务商输出硬顶（如 deepseek-chat 8192）→ 首调 400 由 LlmClient 降额自愈兜（clamp 8192 重试一次）
        val baseTokens = when {
            chapterLength < 800 -> 6_000
            chapterLength < 2_000 -> 10_000
            chapterLength < 3_500 -> 14_000
            else -> 20_000
        }
        return if (isThinkingModel) baseTokens * 3 else baseTokens
    }

    /** 结构化步骤 token 上限（纯格式转换，但要整章重输出，档位同比放大）。 */
    fun preferredStructuringMaxTokens(chapterLength: Int): Int = when {
        chapterLength < 800 -> 3_000
        chapterLength < 2_000 -> 5_000
        chapterLength < 3_500 -> 8_000
        else -> 12_000
    }

    /** 思考模型压缩额度倍率（max_tokens 含思考过程；与创作路 [preferredCreationMaxTokens] 的 ×3 同口径）。 */
    const val COMPRESSION_THINKING_MULTIPLIER = 3

    /**
     * 压缩类任务（摘要 / 圣经）token 上限：思考模型 ×[COMPRESSION_THINKING_MULTIPLIER]——第一次就给足推理余量，
     * 避免思考烧完额度后摘要/档案被半截掐断（压缩现走创作槽·思考模型尤易触顶）；非思考模型维持原基数不变。
     * 弧线大纲生成同用此倍率（2026-07-31 图纸一 D4·见 [StoryOutlineOrchestrator]）。
     * @param baseTokens 该压缩任务基础上限，由调用方各自传入（见 StoryGenerationService 摘要压缩 / StoryBibleCompressor 圣经压缩调用点）
     */
    fun preferredCompressionMaxTokens(baseTokens: Int, isThinkingModel: Boolean): Int =
        if (isThinkingModel) baseTokens * COMPRESSION_THINKING_MULTIPLIER else baseTokens

    // MARK: - 摘要压缩提示词（1:1 iOS buildCompressionPrompt :153-194·实现外搬 StoryPromptSections.kt）

    /** 把「旧压缩版 + 新 N 章摘要」合并为新的全局摘要。实现见 [StoryPromptSections.buildCompressionPrompt]。 */
    fun buildCompressionPrompt(
        existingCompressed: String,
        newSummaries: String,
        lastCompressedChapter: Int,
        currentChapter: Int,
        genre: String,
    ): String = StoryPromptSections.buildCompressionPrompt(existingCompressed, newSummaries, lastCompressedChapter, currentChapter, genre)

    // MARK: - 两步法第一步：第一章创作 prompt（1:1 iOS `+TwoStep.swift` buildFirstChapterCreationPrompt :11-83）

    /**
     * 构建第一章创作提示词（两步法第一步，输出纯文本 + METADATA，不要求 JSON）。
     *
     * 纯函数：声音档案串 [voiceProfiles]、角色段原料 [characterData]、主角性格/关系 [protagonistSpectrum]/[protagonistQuality]
     * 均由 11.1e 生成服务预先收集后传入。结构：身份指令 → **名片区**（设定/角色/区分度/声音档案·2026-08-04 人物表
     * 前置）→ 数据/上下文区（世界观/大纲/初始状态）→ 写作指令区（类型技巧 → 标记规则 + 输出格式 → 人称/铁律/忌口/
     * 画像/节拍/章节要求/重写）。
     *
     * [worldInfoSection] = 世界书激活归并段（ST5·[StoryWorldInfoService] 产出），注入**名片区之后、「整体大纲」之前**；
     * null/空 = prompt 字节级零变化（契约 §4 红线：段标题与 METADATA 格式零改动；段序经 2026-08-04 用户拍板修订一次）。
     * [globalBannedExpressions] = 全局文字忌口原值（三态原样传入·别在调用侧判）；默认 null = 「从未设置→内置默认」而非「不注入」，调用方必须真传（接线由 T2-3 命门测试钉住）。
     *
     * 「内容标记 + 输出格式」两段的落位 = **B 序恒定**（2026-08-03 用户拍板：A/B 实验定胜负为 B 序，开关整链拆除）：
     * 两段发在**类型技巧段之后**，不再排在 system 末位。B 序锁的是两段在整条 prompt 里的绝对位置、不跟随声音档案
     * ——2026-08-04 名片区前置后本块原地未动。
     */
    fun buildFirstChapterCreationPrompt(
        story: StoryEntity,
        roles: List<StoryCharacterRoleEntity>,
        characterData: Map<String, StoryCharacterSectionData>,
        voiceProfiles: String,
        protagonistSpectrum: PersonalitySpectrum?,
        protagonistQuality: RelationshipQuality?,
        worldInfoSection: String? = null,
        globalBannedExpressions: String? = null,
        choicesEnabled: Boolean = true,
        globalSceneBeats: String? = null,
        globalTasteProfile: String? = null,
    ): String {
        val lines = mutableListOf<String>()
        val sortedRoles = sortedStoryRoles(roles)
        val hasUserRole = sortedRoles.any { it.isUserRole }
        val userRoleName = sortedRoles.firstOrNull { it.isUserRole }?.roleName
        val appendFormatBlock: () -> Unit = { // B 序固化：两段整体落在类型技巧段之后，段内文本零碰
            appendMarkupRules(lines)
            appendCreationOutputFormat(lines, choicesEnabled)
        }

        // ═══ 身份和指令 ═══
        lines.add(resolvedWriterIdentity(story))
        // 图纸 L8：题材短名 ≤12 字时嵌入引导语（长题材退回原句·题材约束由技法段承担）。
        val genreLabel = StoryWritingTechniques.genreAnchorLabel(story.genre)
        lines.add(if (genreLabel != null) "请创作这个连载「$genreLabel」故事的第一章。" else "请创作这个连载故事的第一章。")
        lines.add("")

        // ═══ 名片区：设定 → 角色 → 区分度 → 声音档案（人物表前置·2026-08-04 用户拍板·内序恒定）═══
        // 让后文所有名字第一次出现前模型已认识角色（前提顺序 + U 形注意力 + 前缀缓存）；随剧情变的段（初始状态参考）
        // 不随搬。接缝规则：任意两相邻段之间恰一个空行——每个可缺块自带尾随空行，后继段头行直出。
        appendStorySetup(lines, story)

        appendCharacterSection(lines, sortedRoles, story.narrativePerson, characterData) // 自带前导空行
        lines.add("")
        StoryCraftSections.appendCastDistinction(lines, sortedRoles) // 物料 I：多女主区分度（非用户角色 ≥2）

        if (voiceProfiles.isNotEmpty()) {
            lines.add("## 角色声音档案（确保每个角色说话方式有辨识度）")
            lines.add(voiceProfiles)
            lines.add("")
        }

        // ═══ 数据/上下文区 ═══（世界观 = ST5 世界书注入锚点：名片区之后、「整体大纲」之前；null/空 = 零变化）
        if (!worldInfoSection.isNullOrBlank()) {
            lines.add("## 世界观设定")
            lines.add(worldInfoSection)
            lines.add("")
        }

        if (!story.storyOutline.isNullOrEmpty()) {
            lines.add("## 整体大纲（参考方向，不要生硬复述大纲内容）")
            // 导演手记重构（图纸 2026-08-05 M-B）：帽子两行钳住服从序与节奏，插在标题与大纲正文之间。
            lines.add(StoryCraftSections.OUTLINE_OBEY_LINE)
            lines.add(StoryCraftSections.OUTLINE_PACE_LINE)
            lines.add(story.storyOutline)
            lines.add("")
            lines.add("本章对应大纲的建置阶段。")
            lines.add("")
        }

        // ═══ 动态状态参考（仅第一章）═══
        appendInitialDynamicState(lines, protagonistSpectrum, protagonistQuality)

        // ═══ 写作指令区（放下方，靠近输出，影响力最强）═══
        val genreTech = resolvedGenreTechniques(story)
        if (genreTech.isNotEmpty()) {
            lines.add(genreTech)
            lines.add("")
        }

        // B 序插入点：类型技巧段之后（恒执行）——锁的是**绝对位置**，不跟随声音档案，名片区前移后本块原地不动。
        appendFormatBlock()
        lines.add("")
        val personRule = StoryWritingTechniques.narrativePersonRules(story.narrativePerson, hasUserRole)
        if (personRule.isNotEmpty()) {
            lines.add(personRule)
            lines.add("")
        }

        lines.add(resolvedWritingRules(story))
        lines.add("")
        // 文字忌口（三层取值·null=用户已清空则整段不注入）。独立成段但位置紧跟写作规则段：joinToString("\n") 后与旧「规则+\n\n+忌口」逐字节相同（图纸 §2.3）
        StoryPromptSections.resolvedBannedExpressions(story, globalBannedExpressions)?.let {
            lines.add(it)
            lines.add("")
        }
        // 故事二期卷一：口味画像（物料 K）→ 主节拍（物料 A/全局/本书三态），顺序固定，两者各自三态可关
        StoryCraftSections.appendTasteProfile(lines, story, globalTasteProfile)
        StoryCraftSections.appendSceneBeats(lines, story, globalSceneBeats, story.sceneLedger)

        lines.add(
            StoryWritingTechniques.chapterRequirements(
                chapterNumber = 1,
                chapterLength = story.chapterLengthPreference,
                isFirstChapter = true,
                narrativePerson = story.narrativePerson,
                userRoleName = userRoleName,
                choicesEnabled = choicesEnabled,
            ),
        )
        lines.add("")

        // 重写第一章时注入重写指令
        if (story.rewriteInstruction != null) {
            lines.add(StoryWritingTechniques.rewriteInstruction(story.rewriteInstruction))
            lines.add("")
        }

        // 两段已在上方（B 位）发过，此处只抹掉原本充当「块前分隔」的末尾空行。
        if (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)

        return lines.joinToString("\n")
    }

    // MARK: - 两步法第一步：续章创作 prompt（1:1 iOS `+TwoStep.swift` buildNextChapterCreationPrompt :89-263）

    /**
     * 构建续章创作提示词（两步法第一步）。注意力排序：开头先放**名片区**（设定/角色/区分度/声音档案·2026-08-04 人物
     * 表前置，后文名字第一次出现前模型已认识角色）再放本章动态指令（衔接+选择+方向），中间背景参考，结尾规则约束。
     *
     * 纯函数：[latestChapter]（上一章实体，含正文，供衔接/选择/续写检测）、[chapterSummaries]（前情滑窗投影）、
     * [voiceProfiles]、[chatInfluence]（聊天影响串，按权重档预生成）、[characterData] 均由 11.1e 预先收集后传入。
     *
     * [worldInfoSection] = 世界书激活归并段（ST5·[StoryWorldInfoService] 产出），注入「前情回顾」段之前；
     * null/空 = prompt 字节级零变化（契约 §4 红线：既有段标题与 METADATA 输出格式零改动，只新增段）。
     *
     * [freeformDirective] = 上一章「用户亲笔自由输入」的走向原文（[StoryChoiceClassifier.freeformDirective] 判定·
     * null = 预设点选 / 无选择）。非 null 时用户选择段换成「## 用户亲笔指定的剧情走向（本章的任务书·最高优先）」，且整段跳过
     * 「## 本章计划草稿（上一章末预排）」段（走向已是本章任务书·J3）；null = prompt 字节级零变化（照 worldInfoSection 范式·§4 红线）。
     * [globalBannedExpressions] = 全局文字忌口原值（口径同 [buildFirstChapterCreationPrompt]·三态原样传入）。
     *
     * 「内容标记 + 输出格式」两段的落位 = **B 序恒定**（口径同首章·插入点在**大纲块之后、聊天互动段之前**；
     * 2026-08-04 名片区前置后本块原地未动）。
     */
    fun buildNextChapterCreationPrompt(
        story: StoryEntity,
        chapterNumber: Int,
        roles: List<StoryCharacterRoleEntity>,
        characterData: Map<String, StoryCharacterSectionData>,
        voiceProfiles: String,
        chatInfluence: String,
        latestChapter: StoryChapterEntity?,
        chapterSummaries: List<StoryChapterSummaryRow>,
        worldInfoSection: String? = null,
        freeformDirective: String? = null,
        globalBannedExpressions: String? = null,
        choicesEnabled: Boolean = true,
        globalSceneBeats: String? = null,
        globalTasteProfile: String? = null,
        /** [StoryEntity.pendingBeatsUserEdited]：true = 节拍段换成「用户指定」且不被亲笔走向跳过（提案 §3.3）。 */
        pendingBeatsUserEdited: Boolean = false,
        /** 方向账本（[StoryChoiceClassifier.buildDirectiveLedger]·M-D）：本弧历史走向清单，注入场景状态段之后、
         *  走向/选择块之前；**null/空 = prompt 字节级零变化**（照 [worldInfoSection] 范式·等值测试钉）。 */
        directiveLedger: String? = null,
    ): String {
        val lines = mutableListOf<String>()
        val sortedRoles = sortedStoryRoles(roles)
        val hasUserRole = sortedRoles.any { it.isUserRole }
        val userRoleName = sortedRoles.firstOrNull { it.isUserRole }?.roleName
        val appendFormatBlock: () -> Unit = { // B 序固化：绝对位置见插入点注释，段内文本零碰
            appendMarkupRules(lines)
            appendCreationOutputFormat(lines, choicesEnabled)
        }
        // 图纸 L8/L9：题材短名 ≤12 字时嵌入引导语与伏笔平衡行（长题材退回原句/整段不加·题材约束由技法段承担）。
        val genreLabel = StoryWritingTechniques.genreAnchorLabel(story.genre)

        // ═══ 开头：注意力高峰区 → 身份 → 名片区 → 本章必须精准执行的动态指令 ═══

        lines.add(resolvedWriterIdentity(story))

        // （卷二·单模式化：原「续写/第二部」检测靠 `latestChapter.chapterNumber == maxChapters - 10` 识别自动扩展
        // 后的第一章，随有限模式退役删除——无限连载没有「满章后的续篇」这个概念。）
        lines.add(if (genreLabel != null) "请继续创作这个连载「$genreLabel」故事的下一章。" else "请继续创作这个连载故事的下一章。")
        lines.add("")

        // ═══ 名片区：设定 → 角色 → 区分度 → 声音档案（人物表前置·2026-08-04 用户拍板·口径同首章）═══
        // 后文（上一章全文/圣经/弧线/大纲）里的名字第一次出现前，模型已经认识这些角色。
        appendStorySetup(lines, story)

        appendCharacterSection(lines, sortedRoles, story.narrativePerson, characterData) // 自带前导空行
        lines.add("")
        StoryCraftSections.appendCastDistinction(lines, sortedRoles) // 物料 I：同首章

        if (voiceProfiles.isNotEmpty()) {
            lines.add("## 角色声音档案")
            lines.add(voiceProfiles)
            lines.add("")
        }

        // 上一章结尾（衔接点）+ 用户选择 + 方向提示——紧跟名片区，仍在动态指令区的最前
        if (latestChapter != null) {
            lines.add(StoryWritingTechniques.previousChapterEnding(latestChapter.content, latestChapter.mood))
            lines.add("")
            StoryCraftSections.appendSceneState(lines, story) // 物料 F：章末场景状态（本书可关·列空则不出现）
            // 物料 M-D 方向账本：落在走向/选择块**之前**（历史 → 当下），且在下面那道门**之外**——关选项书也要看得到。
            StoryCraftSections.appendDirectiveLedger(lines, directiveLedger)

            // J3：亲笔走向时段恒出——关选项书新章 hasChoice=false，原门会把三明治整块吞掉（J2 同源缺陷）。
            if (freeformDirective != null || latestChapter.hasChoice) {
                if (freeformDirective != null) {
                    // 三明治·system 开头任务书声明（用户拍板②·L1 → M-C1 换文）；beats 段随后整段跳过（J3 原语义）。
                    lines.add("## 用户亲笔指定的剧情走向（本章的任务书·最高优先）")
                    lines.add("用户亲自写下了接下来的剧情走向：")
                    lines.add("「$freeformDirective」")
                    lines.add("这就是本章的任务书，必须照此推进。下方的大纲与任何方向建议都只是参考；若与它冲突，一律以用户写下的走向为准，并把既有线索自然过渡到该方向上。")
                } else {
                    lines.add("## 上一章的用户选择")
                    lines.add(latestChapter.userChoice?.let { "用户选择了「$it」" } ?: "用户未做出选择，请按最自然的方向推进。")
                }
                lines.add("")
            }
        }

        // 物料 J：上一章快评（1..3 才注入）·hasTasteProfile 经三态单源算（1 分且无画像 → 兜底行·2026-08-04）
        val hasTasteProfile = StoryCraftSections.resolvedTasteProfile(story, globalTasteProfile) != null
        StoryCraftSections.appendReaderFeedback(lines, latestChapter?.userRating, hasTasteProfile)

        // 章级方向段的分派单源（用户改过 → 物料 C 且不被走向跳过 / 没改过 → 「## 本章计划草稿」M-F 二选一收束行）
        StoryCraftSections.appendChapterDirection(
            lines = lines,
            story = story,
            freeformDirective = freeformDirective,
            latestChapterUserChoice = latestChapter?.userChoice,
            userEdited = pendingBeatsUserEdited,
        )

        // 重写指令（如果有，紧跟在方向提示后面，确保高注意力）
        if (story.rewriteInstruction != null) {
            lines.add(StoryWritingTechniques.rewriteInstruction(story.rewriteInstruction))
            lines.add("")
        }

        // ═══ 中间：背景参考信息 ═══

        // 世界观设定（ST5 世界书注入锚点：「前情回顾」段之前；null/空 = 零变化）
        if (!worldInfoSection.isNullOrBlank()) {
            lines.add("## 世界观设定")
            lines.add(worldInfoSection)
            lines.add("")
        }

        appendRecapSection(lines, story, chapterNumber, chapterSummaries)

        if (!story.characterStates.isNullOrEmpty()) {
            lines.add("## 角色当前状态")
            lines.add(story.characterStates)
            lines.add("")
        }

        if (!story.openThreads.isNullOrEmpty()) {
            lines.add("## 待回收的伏笔/悬念")
            lines.add(story.openThreads)
            // 图纸 L9：伏笔平衡语（仅 label 非 null 时）——平衡伏笔机制单向放大悬念、防把故事带偏成另一种类型。
            if (genreLabel != null) {
                lines.add("注意：伏笔与悬念服务于本故事「$genreLabel」类型的主线，是佐料不是主菜，不得堆积悬念把故事带偏成另一种类型。")
            }
            lines.add("")
        }

        StoryCraftSections.appendIntimacyHistory(lines, story) // 物料 E：关系史（伏笔段之后、大纲块之前）

        if (!story.storyOutline.isNullOrEmpty()) {
            // 无前导空行：新前驱（关系史/伏笔/角色状态/前情回顾）全部以尾随空行收尾，再补一个就成双空行。
            lines.add("## 大纲（参考方向，不要生硬复述）")
            // 导演手记重构（图纸 2026-08-05 M-B）：帽子两行，口径同首章。
            lines.add(StoryCraftSections.OUTLINE_OBEY_LINE)
            lines.add(StoryCraftSections.OUTLINE_PACE_LINE)
            lines.add(story.storyOutline)
            // 无限连载：只给客观进度事实，不摊派阶段（无终点的故事没有「至暗期」可言·卷一 V3）。
            // （卷二·单模式化：原有限模式的「第 N/M 章，处于 X 期」五阶段分支随有限模式退役删除。）
            val arcStart = story.currentArcStartChapter ?: 1
            val arcIndex = StoryArcPlanning.arcIndex(story.currentArcStartChapter, chapterNumber)
            if (arcIndex >= 1) {
                lines.add("当前进度：第${chapterNumber}章（无限连载·本弧线从第${arcStart}章开始，本章是本弧第${arcIndex}章）。")
            } else {
                lines.add("当前进度：第${chapterNumber}章（无限连载）。")
            }
            // 卷二 B3 弧末收束令：终章弧每章恒给全书收束令；普通弧只在距本弧末 ≤1 章时给阶段性收束令
            // （自报章数解析失败 → 不给——宁可不提醒，也不在错误的章号上瞎喊收束）。
            val plannedLength = StoryArcPlanning.parseArcPlannedLength(story.storyOutline)
            if (story.finaleEndingType != null) {
                lines.add(StoryArcPlanning.FINALE_WRAP_UP_DIRECTIVE)
            } else if (plannedLength != null && arcIndex >= StoryArcPlanning.effectiveArcLength(plannedLength) - 1) {
                lines.add(StoryArcPlanning.ARC_WRAP_UP_DIRECTIVE)
            }
            lines.add("")
        }

        // B 序插入点：大纲块之后、聊天互动段之前（恒执行）——锁的是**绝对位置**（数据带与指令带交界），名片区前移后原地不动。
        appendFormatBlock()
        lines.add("")
        if (chatInfluence.isNotEmpty() && story.chatInfluenceWeight != StoryChatInfluenceWeight.NONE) {
            lines.add("## 聊天互动数据")
            lines.add(chatInfluence)
            lines.add("")
            lines.add("### 聊天影响权重")
            lines.add(chatInfluenceInstruction(story.chatInfluenceWeight))
            lines.add("")
        }

        // ═══ 结尾：注意力高峰区 → 必须严格遵守的规则约束 ═══

        lines.add(StoryWritingTechniques.continuationRules)
        lines.add("")

        val genreTech = resolvedGenreTechniques(story)
        if (genreTech.isNotEmpty()) {
            lines.add(genreTech)
            lines.add("")
        }

        val personRule = StoryWritingTechniques.narrativePersonRules(story.narrativePerson, hasUserRole)
        if (personRule.isNotEmpty()) {
            lines.add(personRule)
            lines.add("")
        }

        lines.add(resolvedWritingRules(story))
        lines.add("")
        // 文字忌口（同首章锚点·两处改法完全一致）
        StoryPromptSections.resolvedBannedExpressions(story, globalBannedExpressions)?.let {
            lines.add(it)
            lines.add("")
        }
        // 画像 → 主节拍（+ 段尾台账提醒），顺序与锚点同首章
        StoryCraftSections.appendTasteProfile(lines, story, globalTasteProfile)
        StoryCraftSections.appendSceneBeats(lines, story, globalSceneBeats, story.sceneLedger)

        // 结局请求：用结局专用指令替换普通章节要求
        val endingType = story.requestedEndingType
        if (endingType != null) {
            lines.add(
                StoryWritingTechniques.requestedEndingRequirements(
                    endingType = endingType,
                    endingDetail = story.requestedEndingDetail,
                    chapterNumber = chapterNumber,
                    chapterLength = story.chapterLengthPreference,
                ),
            )
        } else {
            lines.add(
                StoryWritingTechniques.chapterRequirements(
                    chapterNumber = chapterNumber,
                    chapterLength = story.chapterLengthPreference,
                    isFirstChapter = false,
                    narrativePerson = story.narrativePerson,
                    userRoleName = userRoleName,
                    choicesEnabled = choicesEnabled,
                ),
            )
            lines.add(StoryWritingTechniques.PACING_GUIDANCE)
        }
        lines.add("")

        // 两段已在上方（B 位）发过，此处只抹掉原本充当「块前分隔」的末尾空行。
        if (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)

        return lines.joinToString("\n")
    }

    // MARK: - 大纲生成（里程碑/弧线，1:1 iOS `+Outline.swift`）

    /**
     * 生成剧情弧线大纲（11.1e ensureOutline 调用）。纯函数：角色段原料 [characterData] 由 11.1e 预收集传入（同 d-4）。
     * （卷二·单模式化：原 `isArc` 分派随有限模式里程碑大纲一并退役——只剩「普通弧 / 终章弧」这一处分叉。）
     *
     * @param isFinale true = 生成**终章弧**大纲（卷二 J1·收尾使命块替换设计要求块，见 [buildFinaleArcOutlinePrompt]）
     */
    fun buildOutlinePrompt(
        story: StoryEntity,
        roles: List<StoryCharacterRoleEntity>,
        characterData: Map<String, StoryCharacterSectionData>,
        isFinale: Boolean = false,
    ): String = if (isFinale) {
        buildFinaleArcOutlinePrompt(story, roles, characterData)
    } else {
        buildArcOutlinePrompt(story, roles, characterData)
    }

    // MARK: - 两步法第二步：结构化 prompt（纯文本输出 → JSON·实现外搬 StoryStructuringPrompts.kt·薄委托保签名）

    /**
     * 结构化提示词：把创作模型的纯文本输出整理成合法 JSON（1:1 iOS buildStructuringPrompt :8-45）。
     * 实现外搬至 [buildStoryStructuringPrompt]（文件瘦身·薄委托保签名逐字不变）。
     */
    fun buildStructuringPrompt(rawCreationOutput: String): String =
        buildStoryStructuringPrompt(rawCreationOutput)

    /**
     * 元数据结构化提示词：只处理 METADATA 部分（不含正文），比 [buildStructuringPrompt] 省约 90% token、更快更可靠
     * （1:1 iOS buildMetadataStructuringPrompt :49-91）。实现外搬至 [buildStoryMetadataStructuringPrompt]。
     */
    fun buildMetadataStructuringPrompt(metadataText: String): String =
        buildStoryMetadataStructuringPrompt(metadataText)
}
