package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryGenerationPromptBuilder` (11.1d-5) 弧线大纲生成 + 两步法第二步结构化 prompt 测试。
 *
 * **卷二·单模式化**：原「里程碑大纲（有限模式）」五例随 `buildMilestoneOutlinePrompt` 整函数退役删除属预期；
 * `buildOutlinePrompt` 的 `isArc` 分派参数同步消失。
 *
 * 结构化 prompt 的 `\n`/`\"` 校验：raw string 里 `\n`/`\"` 即字面反斜杠，故断言用 `"\\n"`/`"\\\""`
 * （普通串里 = 反斜杠+字符）。
 */
class StoryGenerationPromptBuilderOutlineStructuringTest {

    private val B = StoryGenerationPromptBuilder

    private fun story(
        genre: String = "言情",
        writingStyle: String = "古风",
        worldSetting: String? = null,
        plotDirection: String? = null,
        narrativePerson: String = StoryNarrativePerson.SECOND,
        storySummary: String? = null,
        characterStates: String? = null,
        openThreads: String? = null,
        currentArc: String? = null,
        storyBible: String? = null,
        arcHistory: String? = null,
    ) = StoryEntity(
        genre = genre, writingStyle = writingStyle, worldSetting = worldSetting, plotDirection = plotDirection,
        narrativePerson = narrativePerson, storySummary = storySummary,
        characterStates = characterStates, openThreads = openThreads, currentArc = currentArc,
        storyBible = storyBible, arcHistory = arcHistory,
    )

    private fun role(name: String, type: String = StoryRoleType.PROTAGONIST) =
        StoryCharacterRoleEntity(roleName = name, roleType = type)

    // ── buildStructuringPrompt ──

    @Test fun structuring_prompt_embeds_raw_and_full_json_template() {
        val raw = "你坐在咖啡馆\n\n---METADATA---\ntitle: 相遇"
        val out = B.buildStructuringPrompt(raw)
        assertTrue(out.startsWith("你是一个文本结构化助手。请把以下故事创作输出整理成合法 JSON 对象。"))
        // 原文嵌在「## 创作输出原文」与「## 输出要求」之间
        assertTrue(out.contains("## 创作输出原文\n$raw\n\n## 输出要求"))
        // JSON 模板字段（2 空格缩进保留）
        assertTrue(out.contains("  \"title\": \"从 METADATA 的 title 字段提取\""))
        assertTrue(out.contains("  \"nextChapterBeats\": \"从 METADATA 的 nextChapterBeats 字段提取，没有则为 null\""))
        assertTrue(out.contains("## JSON 安全规则"))
        assertTrue(out.contains("## hasChoice 推断规则"))
        // 转义示意：字面反斜杠 n / 反斜杠引号
        assertTrue(out.contains("所有换行必须写成 \\n"))
        assertTrue(out.contains("必须转义为 \\\""))
    }

    // ── buildMetadataStructuringPrompt ──

    @Test fun metadata_structuring_prompt_embeds_text_and_spec() {
        val meta = "title: 雨夜\nmood: tense\nhasChoice: true"
        val out = B.buildMetadataStructuringPrompt(meta)
        assertTrue(out.startsWith("你是元数据提取助手。将以下 key:value 文本转换为一行紧凑 JSON。"))
        assertTrue(out.contains("## 输入\n$meta\n\n## 输出格式"))
        assertTrue(out.contains("{\"title\":string,\"teaser\":string|null,\"mood\":string,"))
        assertTrue(out.contains("## 完整示例"))
        assertTrue(out.contains("## 规则（逐条遵守）"))
        assertTrue(out.contains("值内部的双引号转义为 \\\""))
        assertTrue(out.contains("warm/tense/romantic/dark/peaceful/excited/melancholy/mysterious/nostalgic/horror/dreamy"))
        // 完整示例的输出行（紧凑单行 JSON）
        assertTrue(out.contains("{\"title\":\"雨夜的真相\",\"teaser\":\"当真相浮出水面\",\"mood\":\"tense\","))
    }

    // ── buildOutlinePrompt：弧线（无限连载唯一一条大纲路） ──

    @Test fun arc_outline_setup_and_requirements() {
        val out = B.buildOutlinePrompt(
            story(genre = "都市", writingStyle = "轻松幽默", worldSetting = "现代", plotDirection = "慢热正剧"),
            roles = listOf(role("林悦")),
            characterData = emptyMap(),
        )
        // 卷二 B1：开头指令删「约覆盖 10-15 章」——章数改由输出首行自报。
        assertTrue(out.startsWith("你是一位资深故事编剧。请为以下连载故事设计下一个剧情弧线。"))
        assertFalse(out.contains("约覆盖 10-15 章"))
        assertTrue(out.contains("## 故事设定"))
        assertTrue(out.contains("类型：都市"))
        assertTrue(out.contains("文风：轻松幽默"))
        assertTrue(out.contains("世界观：现代"))
        // 图纸 L6-①（J6）预裁决翻案：弧线大纲故事设定自此含「剧情方向」；仍不含 人称/篇幅
        assertTrue(out.contains("剧情方向：慢热正剧"))
        assertFalse(out.contains("叙事人称："))
        assertFalse(out.contains("计划篇幅："))
        assertTrue(out.contains("## 角色"))
        assertTrue(out.contains("## 弧线设计要求"))
        assertTrue(out.contains("**弧线结构：**"))
        assertTrue(out.contains("只输出弧线内容，不要额外解释。"))
        // 里程碑大纲已整体退役——其独有段标题绝不许再出现在任何大纲里
        assertFalse(out.contains("## 大纲要求"))
        assertFalse(out.contains("**里程碑分布建议（按故事进度）：**"))
    }

    @Test fun arc_outline_optional_recap_sections_gated() {
        // 全有 → 四段都在
        val full = B.buildOutlinePrompt(
            story(storySummary = "前情精华", characterStates = "林悦：纠结", openThreads = "未读消息", currentArc = "上一弧线"),
            roles = listOf(role("甲")), characterData = emptyMap(),
        )
        assertTrue(full.contains("## 前情摘要"))
        assertTrue(full.contains("前情精华"))
        assertTrue(full.contains("## 当前角色状态"))
        assertTrue(full.contains("林悦：纠结"))
        assertTrue(full.contains("## 当前未解伏笔"))
        assertTrue(full.contains("未读消息"))
        assertTrue(full.contains("## 上一个弧线概述"))
        assertTrue(full.contains("上一弧线"))

        // 全空 → 四段都不在
        val empty = B.buildOutlinePrompt(
            story(storySummary = null, characterStates = null, openThreads = null, currentArc = null),
            roles = listOf(role("甲")), characterData = emptyMap(),
        )
        assertFalse(empty.contains("## 前情摘要"))
        assertFalse(empty.contains("## 当前角色状态"))
        assertFalse(empty.contains("## 当前未解伏笔"))
        assertFalse(empty.contains("## 上一个弧线概述"))
    }

    // ── 长篇稳定性 L2c：弧线注入圣经档案 + 久别角色回归令（契约 FABLE5_STORY_LONGFORM_STABILITY_PROPOSAL §4） ──

    @Test fun arc_outline_injects_bible_and_return_directive() {
        val out = B.buildOutlinePrompt(
            story(storyBible = "【角色档案】\n- 林悦｜女主｜最后出场：第8章"),
            roles = listOf(role("甲")), characterData = emptyMap(),
        )
        assertTrue(out.contains("## 角色档案与伏笔记录（含每个角色的最后出场章）"))
        assertTrue(out.contains("- 林悦｜女主｜最后出场：第8章"))
        assertTrue(out.contains("检查角色记录中久未出场（距今 ≥10 章）的角色"))
    }

    @Test fun arc_outline_without_bible_skips_section_but_keeps_directive() {
        val out = B.buildOutlinePrompt(story(storyBible = null), roles = listOf(role("甲")), characterData = emptyMap())
        assertFalse(out.contains("## 角色档案与伏笔记录"))
        assertTrue(out.contains("检查角色记录中久未出场（距今 ≥10 章）的角色"))
    }

    // ── 卷二 B1/B2/B4：自报章数指令 + 开篇过渡令 + 弧线简史段（图纸 §4.1 逐字·§7 T2-2）──

    @Test fun arc_outline_asks_for_self_reported_length_and_transition() {
        val out = B.buildOutlinePrompt(story(), roles = listOf(role("甲")), characterData = emptyMap())
        assertTrue(
            out.contains(
                "**第一行必须输出**：本弧预计章数：N（N 取 8-15 之间的一个整数，" +
                    "按这段剧情的自然体量定，紧凑的冲突用短弧，铺陈的篇章用长弧）",
            ),
        )
        assertTrue(out.contains("**开篇过渡**：新弧线必须从上一个弧线的收束状态自然生长出来，禁止无过渡的断崖式新篇"))
        // 两行落在「弧线设计要求」块顶部、在「弧线结构」之前
        assertTrue(out.indexOf("**第一行必须输出**") > out.indexOf("## 弧线设计要求"))
        assertTrue(out.indexOf("**第一行必须输出**") < out.indexOf("**弧线结构：**"))
        assertTrue(out.indexOf("**开篇过渡**") < out.indexOf("**弧线结构：**"))
    }

    @Test fun arc_outline_injects_arc_history_section_when_present() {
        val out = B.buildOutlinePrompt(
            story(arcHistory = "第1–12章·雨夜追凶\n第13–24章·旧案重启", currentArc = "上一弧线"),
            roles = listOf(role("甲")), characterData = emptyMap(),
        )
        assertTrue(out.contains("## 已写过的弧线（一行一弧·避免重复同类冲突与桥段）"))
        assertTrue(out.contains("第1–12章·雨夜追凶\n第13–24章·旧案重启"))
        assertTrue(out.contains("注意：新弧线的核心冲突不得与以上任何一弧雷同——换冲突类型、换驱动角色、或把旧题写出新层次。"))
        // 简史段须排在「上一个弧线概述」之前（图纸 §4.1）
        assertTrue(out.indexOf("## 已写过的弧线") < out.indexOf("## 上一个弧线概述"))
    }

    @Test fun arc_outline_without_history_skips_whole_section() {
        val out = B.buildOutlinePrompt(story(arcHistory = null), roles = listOf(role("甲")), characterData = emptyMap())
        assertFalse(out.contains("## 已写过的弧线"))
        assertFalse(out.contains("注意：新弧线的核心冲突不得与以上任何一弧雷同"))
        // 空史与非空史的唯一差别就是这一段：其余指令照常在
        assertTrue(out.contains("**第一行必须输出**：本弧预计章数："))
        assertTrue(out.contains("**开篇过渡**"))
    }

    @Test fun arc_outline_empty_history_string_also_skips_section() {
        val out = B.buildOutlinePrompt(story(arcHistory = ""), roles = listOf(role("甲")), characterData = emptyMap())
        assertFalse(out.contains("## 已写过的弧线"))
    }

    // ── 图纸 L6：大纲题材锚（chunk 4·T2-5）──

    @Test fun arc_outline_injects_genre_techniques_and_genre_principle() {
        val out = B.buildOutlinePrompt(
            story(genre = "悬疑", plotDirection = "层层反转"),
            roles = listOf(role("甲")), characterData = emptyMap(),
        )
        assertTrue(out.contains("剧情方向：层层反转"))          // L6-①
        assertTrue(out.contains("【悬疑核心技法】"))            // L6-② 预设技法段
        assertTrue(out.contains("- 整条弧线必须符合「悬疑」类型的推进方式与基调，不得把故事带偏成另一种类型")) // L6-③
    }

    // ── 导演手记重构（图纸 2026-08-05 §7 T2-5）：状态路标 / 核心原则 / 输出格式 / 场景菜单 ──

    @Test fun T2_5_弧线结构改状态路标形态_旧里程碑措辞整体退场() {
        val out = B.buildOutlinePrompt(story(), roles = listOf(role("甲")), characterData = emptyMap())
        // M-A1：六行结构逐字（写意高潮 / 候选钩子 / 路标不指定实现方式）
        assertTrue(out.contains("- 弧线主题：本弧要完成的人物与关系转变（一句话）"))
        assertTrue(out.contains("- 触发事件：什么新情况打破当前状态"))
        assertTrue(out.contains("- 状态路标：3-4个（人物或关系的状态变化，不指定场景与实现方式）"))
        assertTrue(out.contains("- 高潮：本弧张力的集中爆发（写意不写实）"))
        assertTrue(out.contains("- 弧线收束：高潮后的新平衡（写状态不写事件）"))
        assertTrue(out.contains("- 下一弧线候选钩子：2-3个方向性悬念（候选而非承诺，下一弧按彼时剧情择用或全弃）"))
        // 旧「预定剧本」措辞必须整体消失
        assertFalse(out.contains("发展转折点"))
        assertFalse(out.contains("读者每章结尾会做选择"))
        assertFalse(out.contains("里程碑"))
    }

    @Test fun T2_5_核心原则新三条与保留四条并存() {
        val out = B.buildOutlinePrompt(story(genre = "都市"), roles = listOf(role("甲")), characterData = emptyMap())
        assertTrue(out.contains("**核心原则（极其重要）：**"))
        // M-A2 新增三条（取代原「里程碑只定义…」「读者每章结尾会做选择…」两条）
        assertTrue(
            out.contains(
                "- 路标只钉人物与关系的变化方向，不指定章节号、场景与实现方式——具体在哪发生、怎么发生，由写作当时的剧情现实就地取材",
            ),
        )
        assertTrue(
            out.contains(
                "- 用户会随时亲笔指定剧情走向，这是常态：用户方向与路标冲突时，顺着用户方向为路标另找实现时机，实在不合则放弃该路标",
            ),
        )
        assertTrue(out.contains("- 路标之间的先后是参考递进不是死顺序，允许被用户的走向跳过、合并或推迟"))
        // 保留四条逐字仍在（伏笔回收 / 新角色 / 久别回归 / 题材 / 与前情一致）
        assertTrue(out.contains("- 必须回收至少 1-2 条已有的未解伏笔"))
        assertTrue(out.contains("- 可以引入新角色或新关系线"))
        assertTrue(out.contains("- 检查角色记录中久未出场（距今 ≥10 章）的角色：为其安排一次回归、或给出明确的退场交代，不要让角色无声消失"))
        assertTrue(out.contains("- 整条弧线必须符合「都市」类型的推进方式与基调，不得把故事带偏成另一种类型"))
        assertTrue(out.contains("- 与前情保持绝对一致，不能矛盾"))
    }

    @Test fun T2_5_输出格式改状态路标四项加高潮写意行() {
        val out = B.buildOutlinePrompt(story(), roles = listOf(role("甲")), characterData = emptyMap())
        assertTrue(out.contains("## 输出格式\n每个状态路标包含："))
        assertTrue(out.contains("- 状态变化：谁从什么状态走到什么状态、为何服务本弧主题（不写场景、方式与对白）"))
        assertTrue(out.contains("- 出手条件：何种剧情时机成熟即可实现"))
        assertTrue(out.contains("- 可预埋的伏笔：写成可择机植入的道具或细节，不绑定场景"))
        assertTrue(out.contains("- 情感方向（↑/↓/→/↑↓）"))
        assertTrue(
            out.contains(
                "高潮只写三样：爆发的性质（什么张力集中爆发）、到位条件（哪些人物状态就绪之后）、落点（爆发后各人的状态）；" +
                    "不预写场景、动作与对白。",
            ),
        )
        assertTrue(out.contains("只输出弧线内容，不要额外解释。"))
        // 旧输出格式的转折点四项逐字退场
        assertFalse(out.contains("每个转折点包含："))
        assertFalse(out.contains("- 事件描述（2-3句话）"))
        assertFalse(out.contains("- 需要预埋的伏笔"))
        assertFalse(out.contains("- 角色状态变化"))
    }

    @Test fun T2_5_场景菜单指令换值_普通弧带前缀终章弧无前缀() {
        val menu = StoryCraftSections.ARC_SCENE_ARRANGEMENT_DIRECTIVE
        assertTrue("菜单常量必须是新值", menu.startsWith("结合「节奏偏好」与下方的场景台账，为本弧列一份场景菜单（不排章节日程）："))
        assertTrue(menu.endsWith("哪一章来、来哪种，由每章写作时按剧情现实决定。"))

        val arc = B.buildOutlinePrompt(story(), roles = listOf(role("甲")), characterData = emptyMap())
        assertTrue("普通弧：菜单句作为核心原则末条（带 - 前缀）", arc.contains("\n- $menu"))
        assertFalse("旧「按章排日程」措辞退场", arc.contains("写进对应章的规划里"))

        val finale = B.buildOutlinePrompt(
            story(), roles = listOf(role("甲")), characterData = emptyMap(), isFinale = true,
        )
        assertTrue("终章弧：菜单句独立成行（无 - 前缀）", finale.contains("\n$menu\n"))
        assertFalse(finale.contains("\n- $menu"))
        // 终章弧「收尾使命」块逐字仍在（N4：零碰）
        assertTrue(finale.contains("**收尾使命**：这条弧线的唯一目标是圆满收束全书——"))
        assertTrue(
            finale.contains(
                "- 对照上方「角色档案与伏笔记录」逐项清点：每一条未回收的伏笔都必须在本弧内回收或给出明确交代，一条都不许悬空",
            ),
        )
        assertTrue(
            finale.contains("- 感情线/主线冲突逐章落定，张力递减、余味递增，不许在本弧内开任何新的大冲突或引入新的重要角色"),
        )
        assertTrue(
            finale.contains("- 最后一章是大结局章（系统会单独给它结局写作协议），本弧前几章要把一切铺垫到位，让结局水到渠成"),
        )
        assertTrue(finale.contains("**开篇过渡**：从上一段剧情的现状自然进入收束期，禁止断崖。"))
        // 终章弧没有弧线结构/核心原则块（设计要求块整体替换·N4）
        assertFalse(finale.contains("**弧线结构：**"))
        assertFalse(finale.contains("**核心原则（极其重要）：**"))
    }

    @Test fun T2_5_预计章数头两行逐字不动_红线零碰() {
        val out = B.buildOutlinePrompt(story(), roles = listOf(role("甲")), characterData = emptyMap())
        assertTrue(
            out.contains(
                "**第一行必须输出**：本弧预计章数：N（N 取 8-15 之间的一个整数，" +
                    "按这段剧情的自然体量定，紧凑的冲突用短弧，铺陈的篇章用长弧）",
            ),
        )
        assertTrue(out.contains("**开篇过渡**：新弧线必须从上一个弧线的收束状态自然生长出来，禁止无过渡的断崖式新篇"))
        val finale = B.buildOutlinePrompt(
            story(), roles = listOf(role("甲")), characterData = emptyMap(), isFinale = true,
        )
        assertTrue(finale.contains("**第一行必须输出**：本弧预计章数：N（N 取 3-5 之间的一个整数）"))
    }
}
