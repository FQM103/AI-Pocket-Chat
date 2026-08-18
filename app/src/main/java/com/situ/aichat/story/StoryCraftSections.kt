package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts

/**
 * 故事二期「生成大脑」的注入面单源（卷一·契约 `故事二期提案（内部）` §3–§5）。
 *
 * 这里住三类东西，一律纯函数、零依赖注入，100% 可单测：
 * 1. **锁定物料常量**（提案里逐字过审的文本：出厂主节拍 / 弧级排布 / 区分度 / 快评三档…）；
 * 2. **三态取值单源** [resolvedSceneBeats] / [resolvedTasteProfile]——本书 › 全局 › 出厂默认，
 *    装配点只许读它们，绝不许各自写 `?:`（同 `StoryPromptSections.resolvedBannedExpressions` 的单源纪律）；
 * 3. 各 **append 助手**（把上面两者装配成 prompt 段）——由 [StoryGenerationPromptBuilder] 与
 *    [StoryOutlinePrompts] 薄调用，助手体不塞进那两个已近行数上限的文件。
 */
object StoryCraftSections {

    // MARK: - 锁定物料（提案 §3.1 物料 A·逐字过审·改动须回提案重新过审）

    /**
     * 出厂默认的书级主节拍（提案 §3.1 物料 A 全文）。三态取值的最后一层：本书没覆盖、全局也从未设置时注入它。
     * **逐字锁定**：任何改动都是产品行为变更，须回提案过审后同步这里与提案原文。
     */
    const val SCENE_BEATS_DEFAULT: String = """## 场面节拍（重点场景按此展开）
每一场重点场景按四拍展开，宁慢勿快：
1. 铺垫：先给足氛围与由头——环境、眼神、试探、心理活动，把「就要发生」的张力拉满再进入正题；张力没到位就不推进。
2. 升温：循序渐进，每一步都写透细节（环境、动作、气息、声音）与人物的反应变化，不许三两句就跳到高潮。
3. 高潮：本场的重心，篇幅给足；人物的反应、神态、语言是画面的核心载体，动作要与心理交织，不许写成流水账。
4. 余韵：事后要有情绪落点——对话、心理变化，给这一场一个收尾，不许戛然而止。
整场篇幅占本章至少一半；节拍允许因剧情自然变形，但「铺垫充分、过程写透、有余韵」三点不许省。"""

    // MARK: - 三态取值（**唯一实现点**·装配点不许再判优先级）

    /**
     * 场面节拍三层取值：本书 › 全局 › 出厂默认。
     *
     * **书级是真三态**（与文字忌口的二态刻意不同·图纸 J2，别「对齐忌口」改坏）：
     * `null` = 跟随全局；`""`/纯空白 = **本书主动关掉**（不再往下落到全局或默认）；文本 = 本书覆盖。
     * 全局同样三态：`null` = 从未设置 → 落到出厂默认；`""` = 全局关闭 → 不注入。
     *
     * @param globalOverride 全局值（`AppSettings.storySceneBeats` 原值，不许调用方先判空）
     * @return 要注入的主节拍文本；**null = 不注入主节拍段**
     */
    fun resolvedSceneBeats(story: StoryEntity, globalOverride: String?): String? {
        val perStory = CustomStoryPrompts.decode(story.customPromptsJson)?.sceneBeats
        if (perStory != null) return perStory.trim().ifEmpty { null }   // ① 本书（""/空白 = 本书关闭）
        if (globalOverride != null) return globalOverride.ifBlank { null } // ② 全局（"" = 全局关闭）
        return SCENE_BEATS_DEFAULT                                       // ③ 出厂默认
    }

    /**
     * 读者口味画像取值：本书 › 全局。三态语义同 [resolvedSceneBeats]，但**没有出厂默认**——
     * 两层都没填 = 不注入（画像是「你爱看什么」，猜一份默认出来毫无意义）。
     *
     * @param globalOverride 全局值（`AppSettings.storyTasteProfile` 原值）
     * @return 要注入的画像文本；**null = 不注入画像段**
     */
    fun resolvedTasteProfile(story: StoryEntity, globalOverride: String?): String? {
        val perStory = CustomStoryPrompts.decode(story.customPromptsJson)?.tasteProfile
        if (perStory != null) return perStory.trim().ifEmpty { null }
        return globalOverride?.ifBlank { null }
    }

    // MARK: - 锁定物料（提案 §3.2/§3.3/§4.2-§4.4/§5.1-§5.3 + 图纸 §4·**逐字**，改动须回提案过审）

    /** 物料 K 标题行：口味画像段。 */
    const val TASTE_PROFILE_HEADER = "## 读者口味画像（品味最高参照）"

    /** 物料 E 标题行：关系史段。 */
    const val INTIMACY_HISTORY_HEADER = "## 两人的关系史（已确立的事实，只能推进不能倒退）"

    /** 物料 E 收束行。 */
    const val INTIMACY_HISTORY_DIRECTIVE =
        "以上是已发生的关系事实：新场面要在此基础上有新意、有推进；称呼与亲密度不得凭空倒退回生疏状态，已确立的相处模式保持连贯。"

    /** 物料 F 标题行：章末场景状态段。 */
    const val SCENE_STATE_HEADER = "## 当前场景状态（上一章结束时）"

    /** 物料 F 收束行。 */
    const val SCENE_STATE_DIRECTIVE =
        "若本章直接衔接上一场景：人物位置、衣着、姿态必须与上述状态连贯，不许瞬移或凭空复原。若本章已切换场景，忽略此段。"

    /** 物料 I：多女主时的区分度指令（只进首/续章的写作约束面，不进共用角色段·图纸 J8）。 */
    const val CAST_DISTINCTION_DIRECTIVE =
        "本故事有多位主要角色：每一位在重点场景中的反应模式、语言习惯、行事方式必须彼此明显不同，" +
            "并与各自人设（含「私下反差」）一致；绝不允许写成互换名字也成立的同一个人。"

    /** 物料 J 标题行：读者快评段。 */
    const val READER_FEEDBACK_HEADER = "## 读者反馈"

    /** 物料 C 标题行：用户亲手改过的章级节拍段。 */
    const val USER_BEATS_HEADER = "## 用户指定的本章节拍（最高优先）"

    /** 物料 N：走向与节拍并存时的分工说明（追在物料 C 段尾·消解「两个最高优先」的表面冲突）。 */
    const val USER_BEATS_WITH_DIRECTIVE_NOTE =
        "（用户同时亲笔指定了剧情走向：走向决定写什么，本节拍决定怎么展开，两者并行执行、互不覆盖。）"

    /**
     * 物料 B：弧线大纲的场景排布要求（普通弧与终章弧共用同一常量）。
     *
     * 导演手记重构（图纸 2026-08-05 M-A4）：从「按章排日程」改成「列菜单」——大纲不再预先钉死哪一章有重点场景，
     * 只给场景池/类型池与强度递进原则，落到哪一章由每章写作时按剧情现实决定（与状态路标不绑章号同一口径）。
     */
    const val ARC_SCENE_ARRANGEMENT_DIRECTIVE =
        "结合「节奏偏好」与下方的场景台账，为本弧列一份场景菜单（不排章节日程）：本弧适合的场景池、类型池、" +
            "以及张力递进原则（如前段蓄力、中段局部爆发、后段完整收束）；类型不与台账近期条目雷同。" +
            "哪一章来、来哪种，由每章写作时按剧情现实决定。"

    /** 弧线大纲里的场景台账段标题。 */
    const val ARC_SCENE_LEDGER_HEADER = "## 已写过的场景台账（避免重样）"

    // MARK: - 导演手记重构（图纸 2026-08-05·M-B / M-D 物料·逐字锁定）

    /**
     * 帽子第一句：大纲段的**服从序**声明（首章「## 整体大纲」与续章「## 大纲」两处共用，插在标题行与大纲正文之间）。
     * 大纲改成状态路标后仍是全文注入，靠这两句把它钳在「参考」的位置上，不与用户意志抢方向。
     */
    const val OUTLINE_OBEY_LINE = "以下为剧情方向参考，若与用户指示或已写正文冲突，一律以后者为准。"

    /** 帽子第二句：**钳节奏**——防模型看见整弧路标就一章内提前兑现完（路标不绑章号后的主要风险）。 */
    const val OUTLINE_PACE_LINE = "路标按剧情自然节奏逐个实现，一章至多推进一个路标，不许提前兑现后续路标与高潮。"

    /** 物料 M-D 标题行：方向账本段。 */
    const val DIRECTIVE_LEDGER_HEADER = "## 用户亲笔指定过的走向（按时间从早到晚，越新越优先）"

    /** 物料 M-D 引导行（跟在标题行之后、账本条目之前）。 */
    const val DIRECTIVE_LEDGER_INTRO =
        "以下是用户在本段剧情中亲笔写下过的走向。其中已写到的部分不必重复，尚未展开的意图继续落实："

    /** 物料 M-F1 标题行：上一章末 AI 预排的「本章计划草稿」（`nextChapterBeats` 草稿化后的消费段）。 */
    const val DRAFT_HEADER = "## 本章计划草稿（上一章末预排）"

    /** 物料 M-F2 收束行：用户没另指方向（含「让故事自然发展」哨兵）时，本章就按草稿推进。 */
    const val DRAFT_FOLLOW_LINE = "用户未另指方向：本章按此草稿推进；若草稿与上方的用户意志或已写正文冲突，以后者为准。"

    /**
     * 物料 M-F3 收束行：用户点了预设选项时的服从序——**选择优先、草稿降级为参考**（J4：契约「点选项→草稿作废」
     * 落实为服从序而非物理不注入，草稿里仍有效的场面/衔接信息不浪费）。
     */
    fun draftPresetChoiceLine(choice: String): String =
        "用户选择了「$choice」：本章按该选择的方向推进；上方草稿仅供参考，与选择冲突处以用户的选择为准。"

    /**
     * 物料 J 三档措辞（评分 1/2/3 → 一行反馈）：与评分取值域同源，档位增删必须同步改这里。
     * **1 分行点名了「读者口味画像」段**：画像两层皆空时那段不注入 → 改走 [READER_FEEDBACK_LINE_1_NO_PROFILE]。
     */
    private val READER_FEEDBACK_LINES = mapOf(
        3 to "读者对上一章的评价：非常满意。保持这个方向与水准。",
        2 to "读者对上一章的评价：一般。本章请在场面展开或剧情推进上换个思路、增强张力，不要重复上一章的写法。",
        1 to "读者对上一章的评价：不满意。本章必须做出明显调整：换掉上一章的场景类型或推进方式；" +
            "对照「读者口味画像」检查是否偏离口味、节奏拖沓或描写重复。",
    )

    /**
     * 1 分档的**无画像兜底行**（2026-08-04 用户拍板·逐字锁定）：[resolvedTasteProfile] 两层皆空时，1 分原行会点名
     * 一个并不存在的「读者口味画像」段（指令指向空气），改注入本行——只去掉画像引用。有画像时原行**逐字不变**。
     */
    const val READER_FEEDBACK_LINE_1_NO_PROFILE = "读者对上一章的评价：不满意。本章必须做出明显调整：换掉上一章的场景类型或推进方式；检查是否节奏拖沓或描写重复。"

    /** 物料 G：台账非空时追在主节拍段尾的「别与上一场重样」提醒。 */
    fun sceneLedgerReminder(latestSceneLine: String): String =
        "提醒：上一场重点场景是「$latestSceneLine」，本章的重点场景在场景或展开方式上要与之有明显区别。"

    // MARK: - append 助手（首/续章与弧线大纲的薄调用点只调这些）

    /**
     * 口味画像段（物料 K）：注入写作指令区、主节拍段之前。三态都没值 → 整段不出现。
     */
    fun appendTasteProfile(lines: MutableList<String>, story: StoryEntity, globalTasteProfile: String?) {
        val profile = resolvedTasteProfile(story, globalTasteProfile) ?: return
        lines.add(TASTE_PROFILE_HEADER)
        lines.add(profile)
        lines.add("")
    }

    /**
     * 主节拍段（物料 A / 全局 / 本书覆盖三选一）+ 段尾的台账提醒（物料 G·图纸 J7 寄生在此段尾——
     * 本书关掉主节拍时提醒随之消失，不另起孤段）。
     *
     * @param sceneLedger 故事的场景台账（空 = 还没写过重点场景 → 无提醒）
     */
    fun appendSceneBeats(
        lines: MutableList<String>,
        story: StoryEntity,
        globalSceneBeats: String?,
        sceneLedger: String?,
    ) {
        val beats = resolvedSceneBeats(story, globalSceneBeats) ?: return
        lines.add(beats)
        StoryLedgers.latestSceneLine(sceneLedger)?.let { lines.add(sceneLedgerReminder(it)) }
        lines.add("")
    }

    /** 关系史段（物料 E）：注入续章背景区、伏笔段之后。账本空 = 整段不出现。 */
    fun appendIntimacyHistory(lines: MutableList<String>, story: StoryEntity) {
        val ledger = story.intimacyLedger?.takeIf { it.isNotBlank() } ?: return
        lines.add(INTIMACY_HISTORY_HEADER)
        lines.add(ledger)
        lines.add(INTIMACY_HISTORY_DIRECTIVE)
        lines.add("")
    }

    /**
     * 章末场景状态段（物料 F）：注入「上一章结尾」段之后。
     * 双门——本书关了快照开关、或状态列为空 → 整段不出现。
     */
    fun appendSceneState(lines: MutableList<String>, story: StoryEntity) {
        if (CustomStoryPrompts.decode(story.customPromptsJson)?.effectiveSceneSnapshot == false) return
        val state = story.sceneState?.takeIf { it.isNotBlank() } ?: return
        lines.add(SCENE_STATE_HEADER)
        lines.add(state)
        lines.add(SCENE_STATE_DIRECTIVE)
        lines.add("")
    }

    /**
     * 多女主区分度指令（物料 I）：**非用户角色 ≥2** 时注入一行，跟在角色段之后。
     * 判据有意只数「非用户角色」不解析性别（自由文本不可靠，误判成本高于宽判·图纸 J6）。
     */
    fun appendCastDistinction(lines: MutableList<String>, sortedRoles: List<StoryCharacterRoleEntity>) {
        if (sortedRoles.count { !it.isUserRole } < 2) return
        lines.add(CAST_DISTINCTION_DIRECTIVE)
        lines.add("")
    }

    /**
     * 读者快评段（物料 J）：上一章已评才注入。
     * 注入门 = `in 1..3`——范围外的畸形值（老备份/手改库）静默不注入，不 clamp 不抛（图纸 J11）。
     *
     * @param hasTasteProfile 本次装配是否真会注入口味画像段（**必填无默认值**，漏传即编译失败）。调用方一律用
     *   [resolvedTasteProfile] `!= null` 算，不许另写一遍三态优先级（REDLINES §1 三态取值单源）。
     *   false + 1 分 → [READER_FEEDBACK_LINE_1_NO_PROFILE]；其余档位与「1 分有画像」逐字不变。
     */
    fun appendReaderFeedback(lines: MutableList<String>, userRating: Int?, hasTasteProfile: Boolean) {
        val line = READER_FEEDBACK_LINES[userRating] ?: return
        lines.add(READER_FEEDBACK_HEADER)
        lines.add(if (userRating == 1 && !hasTasteProfile) READER_FEEDBACK_LINE_1_NO_PROFILE else line)
        lines.add("")
    }

    /**
     * 章级方向段的**分派单源**（提案 §3.3 + 图纸 2026-08-05 §3.3）：
     * - 用户在导演台改过且非空 → 物料 C（**不看走向**：走向管写什么、节拍管怎么展开，两者正交并存·D-7）；
     *   同时有亲笔走向时段尾补物料 N 说明分工；
     * - 用户改过但清空了 → 一个方向段都不给（留白也是指定）；
     * - 有亲笔走向（AI 预排路）→ 整段跳过（本章任务书已是走向，草稿让位·J3 原语义）；
     * - `（跳过选择，直接进入结局）`哨兵 → 整段跳过（直接进结局的章不需要计划草稿·E12）；
     * - 其余（AI 预排路）→ **物料 M-F「## 本章计划草稿（上一章末预排）」**：草稿正文 + 二选一收束行
     *   （点了预设选项 → M-F3 选择优先、草稿降参考；自然发展/未选 → M-F2 按草稿推进）。
     *   原「## 本章方向提示」标题与「请聚焦该选项对应的方向」句随 beats 草稿化一并退役。
     *
     * @param userEdited [StoryEntity.pendingBeatsUserEdited]（由 service 从库里真传·命门接线由 T2-3 钉住）
     */
    fun appendChapterDirection(
        lines: MutableList<String>,
        story: StoryEntity,
        freeformDirective: String?,
        latestChapterUserChoice: String?,
        userEdited: Boolean,
    ) {
        if (userEdited) {
            val beats = story.pendingChapterBeats?.takeIf { it.isNotEmpty() } ?: return
            lines.add(USER_BEATS_HEADER)
            lines.add("用户亲自指定了本章的展开节拍：")
            lines.add("「$beats」")
            lines.add("本章的场面与情节展开必须遵循这个节拍。它的优先级高于默认场面节拍与任何方向建议；若与它们冲突，以用户指定的节拍为准。")
            if (freeformDirective != null) lines.add(USER_BEATS_WITH_DIRECTIVE_NOTE)
            lines.add("")
            return
        }
        if (freeformDirective != null) return
        if (latestChapterUserChoice == StoryChoiceClassifier.SKIP_FOR_ENDING_CHOICE) return
        val beats = story.pendingChapterBeats?.takeIf { it.isNotEmpty() } ?: return
        lines.add(DRAFT_HEADER)
        lines.add(beats)
        val preset = latestChapterUserChoice?.takeIf {
            it.isNotEmpty() && it != StoryChoiceClassifier.NATURAL_FLOW_CHOICE
        }
        lines.add(if (preset != null) draftPresetChoiceLine(preset) else DRAFT_FOLLOW_LINE)
        lines.add("")
    }

    /**
     * 方向账本段（物料 M-D·图纸 2026-08-05 §3.1）：注入续章的场景状态段之后、本章走向/选择块**之前**
     * （历史 → 当下的递进）。账本由 [StoryChoiceClassifier.buildDirectiveLedger] 构建；null/空 = 整段不出现。
     */
    fun appendDirectiveLedger(lines: MutableList<String>, ledger: String?) {
        val entries = ledger?.takeIf { it.isNotBlank() } ?: return
        lines.add(DIRECTIVE_LEDGER_HEADER)
        lines.add(DIRECTIVE_LEDGER_INTRO)
        lines.add(entries)
        lines.add("")
    }

    /** 弧线大纲的场景台账段：注入弧线简史段之后、上一弧概述之前。台账空 = 整段不出现。 */
    fun appendArcSceneLedger(lines: MutableList<String>, sceneLedger: String?) {
        val ledger = sceneLedger?.takeIf { it.isNotBlank() } ?: return
        lines.add(ARC_SCENE_LEDGER_HEADER)
        lines.add(ledger)
        lines.add("")
    }
}
