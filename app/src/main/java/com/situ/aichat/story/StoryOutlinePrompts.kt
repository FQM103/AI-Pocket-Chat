package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts

/**
 * 故事弧线大纲生成提示词（自 [StoryGenerationPromptBuilder] 抽出 · 文件瘦身）。
 * 长篇稳定性 L2c 起（契约 FABLE5_STORY_LONGFORM_STABILITY_PROPOSAL §4）弧线 prompt 有意增补：
 * 注入圣经档案段 + 久别角色回归令。
 *
 * 经 [StoryGenerationPromptBuilder.buildOutlinePrompt] 调用；回调 object 成员 appendCharacterSection 经
 * [StoryGenerationPromptBuilder] 限定。角色段原料由 11.1e 生成服务预收集传入（纯函数·零 DB 依赖）。
 *
 * （卷二·单模式化：有限模式的「里程碑大纲」`buildMilestoneOutlinePrompt` 随有限模式整体退役删除，
 * 全库只剩这一条弧线大纲路。）
 */

/** 无限连载：弧线大纲。 */
internal fun buildArcOutlinePrompt(
    story: StoryEntity,
    roles: List<StoryCharacterRoleEntity>,
    characterData: Map<String, StoryCharacterSectionData>,
): String {
    val lines = mutableListOf<String>()
    appendArcOutlineContext(lines, story, roles, characterData)
    lines.add(
        """
        ## 弧线设计要求
        **第一行必须输出**：${StoryArcPlanning.ARC_PLANNED_LENGTH_PREFIX}N（N 取 ${StoryArcPlanning.ARC_LENGTH_MIN}-${StoryArcPlanning.ARC_LENGTH_MAX} 之间的一个整数，按这段剧情的自然体量定，紧凑的冲突用短弧，铺陈的篇章用长弧）
        **开篇过渡**：新弧线必须从上一个弧线的收束状态自然生长出来，禁止无过渡的断崖式新篇

        **弧线结构：**
        - 弧线主题：本弧要完成的人物与关系转变（一句话）
        - 触发事件：什么新情况打破当前状态
        - 状态路标：3-4个（人物或关系的状态变化，不指定场景与实现方式）
        - 高潮：本弧张力的集中爆发（写意不写实）
        - 弧线收束：高潮后的新平衡（写状态不写事件）
        - 下一弧线候选钩子：2-3个方向性悬念（候选而非承诺，下一弧按彼时剧情择用或全弃）

        **核心原则（极其重要）：**
        - 路标只钉人物与关系的变化方向，不指定章节号、场景与实现方式——具体在哪发生、怎么发生，由写作当时的剧情现实就地取材
        - 用户会随时亲笔指定剧情走向，这是常态：用户方向与路标冲突时，顺着用户方向为路标另找实现时机，实在不合则放弃该路标
        - 路标之间的先后是参考递进不是死顺序，允许被用户的走向跳过、合并或推迟
        - 必须回收至少 1-2 条已有的未解伏笔
        - 可以引入新角色或新关系线
        - 检查角色记录中久未出场（距今 ≥10 章）的角色：为其安排一次回归、或给出明确的退场交代，不要让角色无声消失
        - 整条弧线必须符合「${story.genre}」类型的推进方式与基调，不得把故事带偏成另一种类型
        - 与前情保持绝对一致，不能矛盾
        - ${StoryCraftSections.ARC_SCENE_ARRANGEMENT_DIRECTIVE}
        """.trimIndent(),
    )
    lines.add("")
    lines.add(ARC_OUTLINE_OUTPUT_FORMAT)
    return lines.joinToString("\n")
}

/**
 * 无限连载：**终章弧**大纲（卷二 J1）——「从容收尾」计划落定后生成的最后一段剧情弧线。
 *
 * 与 [buildArcOutlinePrompt] 共用**同一套**设定/前情/圣经/弧线简史装配（[appendArcOutlineContext]）与输出格式，
 * 仅「设计要求」块整体替换成收尾使命（图纸 §4.2 逐字）。收尾方向 [StoryEntity.finaleEndingDetail] 非空时追加一行。
 */
internal fun buildFinaleArcOutlinePrompt(
    story: StoryEntity,
    roles: List<StoryCharacterRoleEntity>,
    characterData: Map<String, StoryCharacterSectionData>,
): String {
    val lines = mutableListOf<String>()
    appendArcOutlineContext(lines, story, roles, characterData)
    val requirements = mutableListOf(
        "## 终章弧设计要求（这是全书最后一段剧情）",
        "**第一行必须输出**：${StoryArcPlanning.ARC_PLANNED_LENGTH_PREFIX}N" +
            "（N 取 ${StoryArcPlanning.FINALE_LENGTH_MIN}-${StoryArcPlanning.FINALE_LENGTH_MAX} 之间的一个整数）",
        "**收尾使命**：这条弧线的唯一目标是圆满收束全书——",
        "- 对照上方「角色档案与伏笔记录」逐项清点：每一条未回收的伏笔都必须在本弧内回收或给出明确交代，一条都不许悬空",
        "- 感情线/主线冲突逐章落定，张力递减、余味递增，不许在本弧内开任何新的大冲突或引入新的重要角色",
        "- 最后一章是大结局章（系统会单独给它结局写作协议），本弧前几章要把一切铺垫到位，让结局水到渠成",
    )
    if (!story.finaleEndingDetail.isNullOrEmpty()) {
        requirements.add("用户期望的结局方向：「${story.finaleEndingDetail}」——整条弧线朝此收束。")
    }
    // 物料 B（故事二期卷一 §3.2 层 2）：普通弧与终章弧共用同一常量，收尾弧同样要排布场景
    requirements.add(StoryCraftSections.ARC_SCENE_ARRANGEMENT_DIRECTIVE)
    requirements.add("**开篇过渡**：从上一段剧情的现状自然进入收束期，禁止断崖。")
    lines.add(requirements.joinToString("\n"))
    lines.add("")
    lines.add(ARC_OUTLINE_OUTPUT_FORMAT)
    return lines.joinToString("\n")
}

/** 弧线大纲的输出格式块（普通弧与终章弧共用·单源）。 */
private val ARC_OUTLINE_OUTPUT_FORMAT = """
    ## 输出格式
    每个状态路标包含：
    - 状态变化：谁从什么状态走到什么状态、为何服务本弧主题（不写场景、方式与对白）
    - 出手条件：何种剧情时机成熟即可实现
    - 可预埋的伏笔：写成可择机植入的道具或细节，不绑定场景
    - 情感方向（↑/↓/→/↑↓）

    高潮只写三样：爆发的性质（什么张力集中爆发）、到位条件（哪些人物状态就绪之后）、落点（爆发后各人的状态）；不预写场景、动作与对白。

    只输出弧线内容，不要额外解释。
""".trimIndent()

/**
 * 弧线大纲的**共用装配**：开场白 + 故事设定 + 题材技法 + 角色段 + 前情/角色状态/伏笔/圣经/弧线简史/上一弧概述。
 * 普通弧与终章弧只在「设计要求」块上分叉，其余一字不差（图纸 §4.2）。
 */
private fun appendArcOutlineContext(
    lines: MutableList<String>,
    story: StoryEntity,
    roles: List<StoryCharacterRoleEntity>,
    characterData: Map<String, StoryCharacterSectionData>,
) {
    // 卷二 B1：删「约覆盖 10-15 章」——章数改由弧线自己在输出首行申报（见下方「第一行必须输出」指令）。
    lines.add("你是一位资深故事编剧。请为以下连载故事设计下一个剧情弧线。")
    lines.add("")
    val customPrompts = CustomStoryPrompts.decode(story.customPromptsJson)
    lines.add("## 故事设定")
    lines.add("类型：${story.genre}")
    // ⚠️ 文风避让（用户 2026-07-27 拍板·判据走单源 effectiveWriterIdentity，与
    // [StoryGenerationPromptBuilder.appendStorySetup] 同源不许分叉）。
    // **别照着创作侧的理由读这里**（R1 复核揭示·如实记录）：创作 prompt 开头有 resolvedWriterIdentity
    // 身份段接管笔调；本 prompt **从来没有身份段**（开场白恒为上面那句「你是一位资深故事编剧。」），
    // 所以填了身份时这里删掉文风行 = **净删一个风格信号**，不是「让位给身份段」。
    // **用户 2026-07-27 复核后二次拍板：有意如此，不补文风也不补身份段**——大纲只管剧情骨架，
    // 笔调交给正章创作 prompt 承担；本 prompt 剩余的风格输入 = 类型 + 题材技法段 + 剧情方向 + 节奏偏好。
    // 有意决定，勿「好心修回来」（REDLINES §7 口径）；真机批已登记观察点：换弧后基调是否跑偏。
    if (customPrompts?.effectiveWriterIdentity == null) {
        lines.add("文风：${story.writingStyle}")
    }
    lines.add("世界观：${story.worldSetting ?: "自由发挥"}")
    // 图纸 L6-①（J6 补遗漏）：里程碑版有「剧情方向」行、弧线版没有；plotDirection 是全书基调方向，弧线重生成正是漂移固化点，必须看到它。
    lines.add("剧情方向：${story.plotDirection ?: "自由发挥"}")
    // 卷三 V2：节奏偏好正是「弧线该多长、密度多大」的输入，与创作两 prompt 用同一条注入行（普通弧/终章弧共此装配）。
    // 复用上面已解码的 customPrompts（同段落只解一次 JSON·CLAUDE.md §2）。
    StoryPromptSections.pacingPreferenceLine(customPrompts)?.let { lines.add(it) }
    lines.add("")
    // 图纸 L6-②：题材技法段（与首/续章一致），把类型基调前置到大纲重生成，防漂移固化。
    val genreTech = StoryPromptSections.resolvedGenreTechniques(story)
    if (genreTech.isNotEmpty()) {
        lines.add(genreTech)
        lines.add("")
    }
    StoryGenerationPromptBuilder.appendCharacterSection(lines, sortedStoryRoles(roles), story.narrativePerson, characterData)
    lines.add("")

    if (!story.storySummary.isNullOrEmpty()) {
        lines.add("## 前情摘要")
        lines.add(story.storySummary)
        lines.add("")
    }
    if (!story.characterStates.isNullOrEmpty()) {
        lines.add("## 当前角色状态")
        lines.add(story.characterStates)
        lines.add("")
    }
    if (!story.openThreads.isNullOrEmpty()) {
        lines.add("## 当前未解伏笔")
        lines.add(story.openThreads)
        lines.add("")
    }
    // 长篇稳定性 L2c：注入圣经（压缩后 = 角色档案+伏笔账本，含每人「最后出场」章号），支撑下方久别角色回归令。
    if (!story.storyBible.isNullOrEmpty()) {
        lines.add("## 角色档案与伏笔记录（含每个角色的最后出场章）")
        lines.add(story.storyBible)
        lines.add("")
    }
    // 卷二 B2：已写过的弧线简史（每弧一行）——防连载久了反复写同一类冲突。空史 = 整段不出现（prompt 零变化）。
    if (!story.arcHistory.isNullOrEmpty()) {
        lines.add("## 已写过的弧线（一行一弧·避免重复同类冲突与桥段）")
        lines.add(story.arcHistory)
        lines.add("注意：新弧线的核心冲突不得与以上任何一弧雷同——换冲突类型、换驱动角色、或把旧题写出新层次。")
        lines.add("")
    }
    // 故事二期卷一：已写过的场景台账（弧级排布的输入·物料 B 点名「下方的场景台账」）。空 = 整段不出现。
    StoryCraftSections.appendArcSceneLedger(lines, story.sceneLedger)
    if (!story.currentArc.isNullOrEmpty()) {
        lines.add("## 上一个弧线概述")
        lines.add(story.currentArc)
        lines.add("")
    }
}
