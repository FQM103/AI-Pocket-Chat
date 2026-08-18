package com.situ.aichat.story

import com.situ.aichat.data.local.dao.StoryChapterSummaryRow
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryGenerationPromptBuilder` (11.1d-4) 数据依赖助手 + 两步法创作 prompt 测试，
 * 反推 iOS `+Formatting.swift`（appendCharacterSection/InitialDynamicState/RecapSection）与
 * `+TwoStep.swift`（buildFirst/NextChapterCreationPrompt）。
 */
class StoryGenerationPromptBuilderCreationTest {

    private val B = StoryGenerationPromptBuilder

    // ── builders ──

    private fun story(
        genre: String = "言情",
        writingStyle: String = "古风",
        maxChapters: Int? = 20,
        chapterLengthPreference: Int = 1500,
        narrativePerson: String = StoryNarrativePerson.SECOND,
        chatInfluenceWeight: String = StoryChatInfluenceWeight.MEDIUM,
        worldSetting: String? = null,
        plotDirection: String? = null,
        storyOutline: String? = null,
        storySummary: String? = null,
        currentArc: String? = null,
        characterStates: String? = null,
        openThreads: String? = null,
        storyBible: String? = null,
        lastCompressedAtChapter: Int? = null,
        pendingChapterBeats: String? = null,
        requestedEndingType: String? = null,
        requestedEndingDetail: String? = null,
        rewriteInstruction: String? = null,
        currentArcStartChapter: Int? = null,
        finaleEndingType: String? = null,
    ) = StoryEntity(
        currentArcStartChapter = currentArcStartChapter,
        finaleEndingType = finaleEndingType,
        genre = genre, writingStyle = writingStyle, maxChapters = maxChapters,
        chapterLengthPreference = chapterLengthPreference, narrativePerson = narrativePerson,
        chatInfluenceWeight = chatInfluenceWeight, worldSetting = worldSetting, plotDirection = plotDirection,
        storyOutline = storyOutline, storySummary = storySummary, currentArc = currentArc,
        characterStates = characterStates, openThreads = openThreads, storyBible = storyBible,
        lastCompressedAtChapter = lastCompressedAtChapter, pendingChapterBeats = pendingChapterBeats,
        requestedEndingType = requestedEndingType, requestedEndingDetail = requestedEndingDetail,
        rewriteInstruction = rewriteInstruction,
    )

    private fun role(
        roleName: String,
        roleType: String = StoryRoleType.SUPPORTING,
        roleDescription: String? = null,
        isUserRole: Boolean = false,
        characterId: String? = null,
    ) = StoryCharacterRoleEntity(
        roleName = roleName, roleType = roleType, roleDescription = roleDescription,
        isUserRole = isUserRole, characterId = characterId,
    )

    private fun chapter(
        chapterNumber: Int,
        content: String = "正文",
        mood: String = "peaceful",
        hasChoice: Boolean = false,
        userChoice: String? = null,
    ) = StoryChapterEntity(
        chapterNumber = chapterNumber, content = content, mood = mood,
        hasChoice = hasChoice, userChoice = userChoice,
    )

    private fun sectionData(
        gender: String = "",
        age: Int? = null,
        occupation: String = "",
        appearance: String = "",
        personality: String = "",
        backstory: String = "",
    ) = StoryCharacterSectionData(
        gender = gender, age = age, occupation = occupation,
        appearanceDescription = appearance, personalityDescription = personality, backstory = backstory,
    )

    // ── sortedStoryRoles ──

    @Test fun sorted_roles_user_first_then_by_pinyin() {
        val sorted = sortedStoryRoles(
            listOf(role("张三"), role("你", isUserRole = true), role("阿明")),
        )
        assertEquals(listOf("你", "阿明", "张三"), sorted.map { it.roleName })
    }

    // ── appendCharacterSection ──

    @Test fun character_section_full_identity_line() {
        val lines = mutableListOf<String>()
        B.appendCharacterSection(
            lines,
            listOf(role("林悦", StoryRoleType.PROTAGONIST, characterId = "cid")),
            StoryNarrativePerson.SECOND,
            mapOf("cid" to sectionData("女", 22, "画家", "长发", "温柔", "背景故事")),
        )
        assertEquals("", lines[0])
        assertEquals("## 角色", lines[1])
        assertEquals(
            "- 林悦（主角）：性别：女；年龄：22岁；职业/身份：画家；外貌：长发；性格：温柔；背景：背景故事",
            lines[2],
        )
    }

    @Test fun character_section_role_type_labels() {
        val lines = mutableListOf<String>()
        B.appendCharacterSection(
            lines,
            listOf(
                role("甲", StoryRoleType.PROTAGONIST),
                role("乙", StoryRoleType.ANTAGONIST),
                role("丙", StoryRoleType.SUPPORTING),
            ),
            StoryNarrativePerson.SECOND,
            emptyMap(),
        )
        assertEquals("- 甲（主角）：按角色定位自由发挥", lines[2])
        assertEquals("- 乙（反派）：按角色定位自由发挥", lines[3])
        assertEquals("- 丙（配角）：按角色定位自由发挥", lines[4])
    }

    @Test fun character_section_age_zero_or_null_skipped() {
        val lines = mutableListOf<String>()
        B.appendCharacterSection(
            lines,
            listOf(role("甲", characterId = "a"), role("乙", characterId = "b")),
            StoryNarrativePerson.SECOND,
            mapOf("a" to sectionData(gender = "男", age = 0), "b" to sectionData(gender = "女", age = null)),
        )
        assertEquals("- 甲（配角）：性别：男", lines[2])
        assertEquals("- 乙（配角）：性别：女", lines[3])
    }

    /** 卷一 V6（图纸 §4.7）：背景 300→800、整卡 2000→4000。 */
    @Test fun character_section_backstory_truncated_to_800_no_ellipsis() {
        val lines = mutableListOf<String>()
        val long = "背".repeat(1_000)
        B.appendCharacterSection(
            lines,
            listOf(role("甲", characterId = "a")),
            StoryNarrativePerson.SECOND,
            mapOf("a" to sectionData(backstory = long)),
        )
        assertEquals("- 甲（配角）：背景：${"背".repeat(800)}", lines[2])
        assertFalse(lines[2].endsWith("…"))
    }

    @Test fun character_section_whole_card_truncated_to_4000() {
        val lines = mutableListOf<String>()
        // 背景 800（新上限）+ 一条超长 roleDescription → 整卡逼过 4000
        B.appendCharacterSection(
            lines,
            listOf(role("甲", characterId = "a", roleDescription = "补".repeat(5_000))),
            StoryNarrativePerson.SECOND,
            mapOf("a" to sectionData(backstory = "背".repeat(1_000))),
        )
        val card = lines[2].removePrefix("- 甲（配角）：")
        assertEquals(4_000, card.length)
        assertTrue(card.startsWith("背景：${"背".repeat(800)}；补"))
    }

    @Test fun character_section_user_role_person_note_by_narrative() {
        fun note(person: String): String {
            val lines = mutableListOf<String>()
            B.appendCharacterSection(lines, listOf(role("你", isUserRole = true)), person, emptyMap())
            return lines[2]
        }
        assertTrue(note(StoryNarrativePerson.FIRST).endsWith("这是用户扮演的角色，请以第一人称「我」来描写。"))
        assertTrue(note(StoryNarrativePerson.THIRD).endsWith("这是用户扮演的角色，请以第三人称来描写。"))
        assertTrue(note(StoryNarrativePerson.SECOND).endsWith("这是用户扮演的角色，请以第二人称「你」来描写行动和感受。"))
    }

    @Test fun character_section_role_description_appended_and_data_missing_for_unmapped_id() {
        val lines = mutableListOf<String>()
        B.appendCharacterSection(
            lines,
            listOf(role("甲", roleDescription = "神秘的访客", characterId = "missing")),
            StoryNarrativePerson.SECOND,
            emptyMap(), // characterId 不在 map → 无身份字段，仅出 roleDescription
        )
        assertEquals("- 甲（配角）：神秘的访客", lines[2])
    }

    // ── appendInitialDynamicState ──

    @Test fun initial_dynamic_state_personality_and_relationship_traits() {
        val lines = mutableListOf<String>()
        B.appendInitialDynamicState(
            lines,
            PersonalitySpectrum(extroversion = 75, warmth = 20), // 其余 50
            RelationshipQuality(familiarity = 50, trust = 80, closeness = 50, rapport = 50, respect = 50, funValue = 50, tension = 10, attachment = 50),
        )
        assertEquals("## 角色初始状态参考（仅供第一章参考，后续章节的关系发展由故事剧情自然推动）", lines[0])
        assertEquals("- 性格倾向：外向性偏高（75）、温暖度偏低（20）", lines[1])
        assertEquals("- 与用户的关系质感：信任感较高、张力值较低", lines[2])
        assertEquals("", lines[3])
    }

    @Test fun initial_dynamic_state_threshold_is_20() {
        val lines = mutableListOf<String>()
        B.appendInitialDynamicState(
            lines,
            // 70 偏离 20→入选；69 偏离 19→落选；30 偏离 20→入选；31 偏离 19→落选
            PersonalitySpectrum(extroversion = 70, emotionality = 69, adventurousness = 30, warmth = 31),
            null,
        )
        assertEquals("- 性格倾向：外向性偏高（70）、冒险性偏低（30）", lines[1])
    }

    @Test fun initial_dynamic_state_empty_when_no_deviation_or_null() {
        val flat = mutableListOf<String>()
        B.appendInitialDynamicState(
            flat,
            PersonalitySpectrum(50, 50, 50, 50, 50, 50, 50, 50),
            RelationshipQuality(50, 50, 50, 50, 50, 50, 50, 50),
        )
        assertTrue(flat.isEmpty())

        val nulls = mutableListOf<String>()
        B.appendInitialDynamicState(nulls, null, null)
        assertTrue(nulls.isEmpty())
    }

    // ── appendRecapSection ──

    @Test fun recap_no_global_no_chapters_uses_fallbacks() {
        val lines = mutableListOf<String>()
        B.appendRecapSection(lines, story(), chapterNumber = 1, chapterSummaries = emptyList())
        val joined = lines.joinToString("\n")
        assertTrue(joined.contains("## 前情回顾"))
        assertTrue(joined.contains("暂无前情，请根据已知设定自然创作。"))
        assertTrue(joined.contains("## 当前剧情弧线"))
        assertTrue(joined.contains("暂无，请根据前文自然推进。"))
        assertFalse(joined.contains("### 全局摘要"))
    }

    @Test fun recap_sliding_window_distance_buckets_no_global() {
        val s200 = "摘".repeat(200)
        val lines = mutableListOf<String>()
        B.appendRecapSection(
            lines,
            story(currentArc = "当前弧线"),
            chapterNumber = 20,
            chapterSummaries = listOf(
                StoryChapterSummaryRow(18, s200), // 距 2 → 完整
                StoryChapterSummaryRow(12, s200), // 距 8 → 截 100
                StoryChapterSummaryRow(5, s200), // 距 15，无全局 → 截 50
            ),
        )
        val joined = lines.joinToString("\n")
        assertTrue(joined.contains("### 近期章节摘要"))
        assertTrue(joined.contains("第18章：$s200"))
        assertTrue(joined.contains("第12章：${"摘".repeat(100)}…"))
        assertTrue(joined.contains("第5章：${"摘".repeat(50)}…"))
        assertTrue(joined.contains("当前弧线"))
    }

    @Test fun recap_with_global_summary_skips_older_than_12() {
        val s200 = "摘".repeat(200)
        val lines = mutableListOf<String>()
        B.appendRecapSection(
            lines,
            story(lastCompressedAtChapter = 4, storySummary = "全局精华"),
            chapterNumber = 20,
            chapterSummaries = listOf(
                StoryChapterSummaryRow(18, s200), // 距 2 → 完整
                StoryChapterSummaryRow(12, s200), // 距 8 → 截 100
                StoryChapterSummaryRow(5, s200), // 距 15，有全局 → 跳过
            ),
        )
        val joined = lines.joinToString("\n")
        assertTrue(joined.contains("### 全局摘要（第1-4章精华）"))
        assertTrue(joined.contains("全局精华"))
        assertTrue(joined.contains("第18章：$s200"))
        assertTrue(joined.contains("第12章：${"摘".repeat(100)}…"))
        assertFalse(joined.contains("第5章："))
    }

    @Test fun recap_chapters_before_lastCompressed_excluded() {
        val lines = mutableListOf<String>()
        B.appendRecapSection(
            lines,
            story(lastCompressedAtChapter = 4, storySummary = "全局精华"),
            chapterNumber = 8,
            chapterSummaries = listOf(
                StoryChapterSummaryRow(3, "应被全局摘要覆盖"), // 3 <= startAfter(4) → 不单列
                StoryChapterSummaryRow(6, "第六章摘要"), // 4<6<8 → 列出
            ),
        )
        val joined = lines.joinToString("\n")
        assertTrue(joined.contains("第6章：第六章摘要"))
        assertFalse(joined.contains("第3章："))
    }

    @Test fun recap_bible_section_present_when_set() {
        val lines = mutableListOf<String>()
        B.appendRecapSection(lines, story(storyBible = "圣经内容"), chapterNumber = 1, chapterSummaries = emptyList())
        val joined = lines.joinToString("\n")
        assertTrue(joined.contains("## 故事圣经（角色和伏笔的完整记录，必须保持一致性）"))
        assertTrue(joined.contains("圣经内容"))
    }

    // ── buildFirstChapterCreationPrompt ──

    @Test fun first_chapter_prompt_section_order() {
        val out = B.buildFirstChapterCreationPrompt(
            story = story(genre = "言情", writingStyle = "古风", storyOutline = "整体走向"),
            roles = listOf(role("林悦", StoryRoleType.PROTAGONIST, characterId = "cid")),
            characterData = mapOf("cid" to sectionData(gender = "女", age = 22)),
            voiceProfiles = "【林悦】说话温柔",
            protagonistSpectrum = PersonalitySpectrum(extroversion = 80),
            protagonistQuality = null,
        )
        // 关键段都在
        assertTrue(out.contains("古风小说名家"))
        assertTrue(out.contains("请创作这个连载「言情」故事的第一章。"))
        assertTrue(out.contains("## 故事设定"))
        assertTrue(out.contains("## 整体大纲（参考方向，不要生硬复述大纲内容）"))
        assertTrue(out.contains("本章对应大纲的建置阶段。"))
        assertTrue(out.contains("## 角色"))
        assertTrue(out.contains("## 角色初始状态参考（仅供第一章参考"))
        assertTrue(out.contains("【言情核心技法】"))
        assertTrue(out.contains("## 角色声音档案（确保每个角色说话方式有辨识度）"))
        assertTrue(out.contains("### 叙事人称：第二人称"))
        assertTrue(out.contains("## 章节要求"))
        assertTrue(out.contains("- 这是第一章"))
        assertTrue(out.contains("---METADATA---"))

        // 注意力排序：身份/指令在前；名片区（设定→角色→区分度→声音档案）紧随其后（2026-08-04 人物表前置拍板），
        // 再是数据/上下文区（世界观/大纲/初始状态）；「内容标记 + 输出格式」两段固化在类型技巧段之后
        // （B 序·2026-08-03 拍板·锁绝对位置不跟随声音档案），章节要求等规则段殿后 ——
        // 故 ---METADATA--- 现在排在「## 章节要求」**之前**。
        assertTrue(out.indexOf("请创作这个连载「言情」故事的第一章。") < out.indexOf("## 故事设定"))
        assertTrue(out.indexOf("## 故事设定") < out.indexOf("## 角色"))
        assertTrue(out.indexOf("## 角色") < out.indexOf("## 章节要求"))
        assertTrue(out.indexOf("## 角色声音档案（确保每个角色说话方式有辨识度）") < out.indexOf("## 内容标记（严格遵守以下规则）"))
        assertTrue(out.indexOf("---METADATA---") < out.indexOf("## 章节要求"))
        // 人物表前置：名片区整体排在「整体大纲」之前，初始状态参考（随剧情变，不属名片）仍在大纲之后
        assertTrue(out.indexOf("## 角色") < out.indexOf("## 整体大纲（参考方向，不要生硬复述大纲内容）"))
        assertTrue(
            out.indexOf("## 角色声音档案（确保每个角色说话方式有辨识度）") <
                out.indexOf("## 整体大纲（参考方向，不要生硬复述大纲内容）"),
        )
        assertTrue(
            out.indexOf("## 整体大纲（参考方向，不要生硬复述大纲内容）") <
                out.indexOf("## 角色初始状态参考（仅供第一章参考"),
        )
    }

    @Test fun creation_output_format_includes_continuity_rules() {
        // 长篇稳定性 L2a/L2b（契约 FABLE5_STORY_LONGFORM_STABILITY_PROPOSAL §4）：伏笔继承令 + 状态字数弹性——
        // 首章/续章共用 appendCreationOutputFormat，验一处即两路皆有；字段名与 METADATA 分隔符零变。
        val out = B.buildFirstChapterCreationPrompt(
            story = story(),
            roles = listOf(role("甲", StoryRoleType.PROTAGONIST)),
            characterData = emptyMap(),
            voiceProfiles = "",
            protagonistSpectrum = null,
            protagonistQuality = null,
        )
        assertTrue(out.contains("### 伏笔与角色状态（保持长篇连续性）"))
        assertTrue(out.contains("openThreads 必须继承上一章清单中仍未解决的条目"))
        assertTrue(out.contains("只有确已解决的条目才可移除"))
        assertTrue(out.contains("characterStates 覆盖本章出场的每个角色，每条 10-25 字"))
        // 新增块位于选择分支规则之后、格式规则之前
        assertTrue(out.indexOf("### 选择分支（最重要，必须严格遵守）") < out.indexOf("### 伏笔与角色状态（保持长篇连续性）"))
        assertTrue(out.indexOf("### 伏笔与角色状态（保持长篇连续性）") < out.indexOf("### 格式规则"))
    }

    @Test fun first_chapter_prompt_rewrite_instruction_injected() {
        val out = B.buildFirstChapterCreationPrompt(
            story = story(rewriteInstruction = "节奏再快些"),
            roles = listOf(role("甲", StoryRoleType.PROTAGONIST)),
            characterData = emptyMap(),
            voiceProfiles = "",
            protagonistSpectrum = null,
            protagonistQuality = null,
        )
        assertTrue(out.contains("## 重写指令"))
        assertTrue(out.contains("用户的补充要求：「节奏再快些」"))
    }

    @Test fun first_chapter_prompt_omits_voice_section_when_empty() {
        val out = B.buildFirstChapterCreationPrompt(
            story = story(),
            roles = listOf(role("甲", StoryRoleType.PROTAGONIST)),
            characterData = emptyMap(),
            voiceProfiles = "",
            protagonistSpectrum = null,
            protagonistQuality = null,
        )
        assertFalse(out.contains("## 角色声音档案"))
    }

    // ── buildNextChapterCreationPrompt ──

    @Test fun next_chapter_prompt_normal_continuation() {
        val out = B.buildNextChapterCreationPrompt(
            story = story(storyOutline = "走向", currentArcStartChapter = 5, chatInfluenceWeight = StoryChatInfluenceWeight.MEDIUM),
            chapterNumber = 10,
            roles = listOf(role("林悦", StoryRoleType.PROTAGONIST)),
            characterData = emptyMap(),
            voiceProfiles = "【林悦】温柔",
            chatInfluence = "最近聊到旅行",
            latestChapter = chapter(9, content = "上一章结尾[mood:tense]", mood = "tense", hasChoice = true, userChoice = "去找他"),
            chapterSummaries = listOf(StoryChapterSummaryRow(9, "第九章摘要")),
        )
        assertTrue(out.contains("请继续创作这个连载「言情」故事的下一章。"))
        assertTrue(out.contains("## 上一章全文（新章节必须从其结尾自然衔接，不要复述或改写上一章的内容）"))
        assertTrue(out.contains("## 上一章的用户选择"))
        assertTrue(out.contains("用户选择了「去找他」"))
        assertTrue(out.contains("## 前情回顾"))
        assertTrue(out.contains("## 大纲（参考方向，不要生硬复述）"))
        assertTrue(out.contains("当前进度：第10章（无限连载·本弧线从第5章开始，本章是本弧第6章）。"))
        assertTrue(out.contains("## 角色声音档案"))
        assertTrue(out.contains("## 聊天互动数据"))
        assertTrue(out.contains("### 聊天影响权重"))
        assertTrue(out.contains("中度影响规则"))
        assertTrue(out.contains("### 续写规则"))
        assertTrue(out.contains("## 章节要求"))
        assertTrue(out.contains("---METADATA---"))

        // 人物表前置（2026-08-04）：名片区（设定→角色→声音档案）搬到上一章全文之前
        assertTrue(out.indexOf("请继续创作这个连载「言情」故事的下一章。") < out.indexOf("## 故事设定"))
        assertTrue(out.indexOf("## 故事设定") < out.indexOf("## 角色"))
        assertTrue(out.indexOf("## 角色") < out.indexOf("## 角色声音档案"))
        assertTrue(
            out.indexOf("## 角色声音档案") <
                out.indexOf("## 上一章全文（新章节必须从其结尾自然衔接，不要复述或改写上一章的内容）"),
        )
        // B 序绝对位置不变：格式块仍夹在大纲块与聊天互动段之间
        assertTrue(out.indexOf("## 大纲（参考方向，不要生硬复述）") < out.indexOf("## 内容标记（严格遵守以下规则）"))
        assertTrue(out.indexOf("## 内容标记（严格遵守以下规则）") < out.indexOf("## 聊天互动数据"))
    }

    /**
     * 卷二·单模式化：原例 `next_chapter_prompt_stage_label_buildup`（钉「第2/20章，处于建置期」）与
     * `next_chapter_prompt_continuation_after_ending`（钉满章续篇开场白）随两条有限模式分支退役删除属预期。
     * 替身分别是文件末尾的 `stale_max_chapters_never_revives_finite_curves` 与
     * `continuation_after_ending_preamble_is_retired`。
     */
    @Test fun next_chapter_prompt_requested_ending_replaces_chapter_requirements() {
        val out = B.buildNextChapterCreationPrompt(
            story = story(requestedEndingType = "open"),
            chapterNumber = 20,
            roles = emptyList(), characterData = emptyMap(), voiceProfiles = "", chatInfluence = "",
            latestChapter = chapter(19, hasChoice = true),
            chapterSummaries = emptyList(),
        )
        assertTrue(out.contains("## ⚠️ 这是最终章（结局章）——请不遗余力地写出一个完美的结局"))
        assertTrue(out.contains("### 结局类型：开放式结局"))
        assertFalse(out.contains("## 章节要求"))
    }

    @Test fun next_chapter_prompt_chat_influence_gated_by_weight_none() {
        val out = B.buildNextChapterCreationPrompt(
            story = story(chatInfluenceWeight = StoryChatInfluenceWeight.NONE),
            chapterNumber = 5,
            roles = emptyList(), characterData = emptyMap(), voiceProfiles = "",
            chatInfluence = "有内容但权重为 none",
            latestChapter = chapter(4, hasChoice = true),
            chapterSummaries = emptyList(),
        )
        assertFalse(out.contains("## 聊天互动数据"))
    }

    @Test fun next_chapter_prompt_pending_beats_with_user_choice() {
        val out = B.buildNextChapterCreationPrompt(
            story = story(pendingChapterBeats = "方向A / 方向B"),
            chapterNumber = 5,
            roles = emptyList(), characterData = emptyMap(), voiceProfiles = "", chatInfluence = "",
            latestChapter = chapter(4, hasChoice = true, userChoice = "选A"),
            chapterSummaries = emptyList(),
        )
        // 2026-08-05 M-F 翻案：「## 本章方向提示 + 请聚焦该选项对应的方向」随 beats 草稿化退役，
        // 换成「## 本章计划草稿（上一章末预排）」+ 预设选择服从行（选择优先、草稿降参考）。
        assertTrue(out.contains(StoryCraftSections.DRAFT_HEADER))
        assertTrue(out.contains("方向A / 方向B"))
        assertTrue(out.contains(StoryCraftSections.draftPresetChoiceLine("选A")))
        assertFalse(out.contains("请聚焦该选项对应的方向"))
    }

    // ── 三明治：freeformDirective（图纸 §8 chunk 2·T2-1/T2-2）──

    @Test fun next_chapter_freeform_null_byte_identical_and_no_directive_section() {
        // T2-1（E1/E15·照 WorldInfoTest 范式）：freeformDirective=null 与不传参字节级一致；预设路径不含自由输入段。
        val s = story(pendingChapterBeats = "方向A / 方向B")
        val latest = chapter(4, hasChoice = true, userChoice = "选A")
        val baseline = B.buildNextChapterCreationPrompt(
            story = s, chapterNumber = 5, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "", latestChapter = latest, chapterSummaries = emptyList(),
        )
        val explicitNull = B.buildNextChapterCreationPrompt(
            story = s, chapterNumber = 5, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "", latestChapter = latest, chapterSummaries = emptyList(),
            freeformDirective = null,
        )
        assertEquals(baseline, explicitNull)
        assertFalse(baseline.contains("## 用户亲笔指定的剧情走向（本章的任务书·最高优先）"))
        // 预设点选路径保留「## 上一章的用户选择」与草稿段两段
        assertTrue(baseline.contains("## 上一章的用户选择"))
        assertTrue(baseline.contains(StoryCraftSections.DRAFT_HEADER))
    }

    @Test fun next_chapter_freeform_injects_directive_skips_beats_coexists_rewrite() {
        // T2-2（E2/E7）：freeform 非 null → L1 段注入 + beats 整段与聚焦句不出现 + 与重写指令共存。
        val out = B.buildNextChapterCreationPrompt(
            story = story(pendingChapterBeats = "方向A / 方向B", rewriteInstruction = "节奏再快些"),
            chapterNumber = 5, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "",
            latestChapter = chapter(4, hasChoice = true, userChoice = "让他们私奔去边关"),
            chapterSummaries = emptyList(),
            freeformDirective = "让他们私奔去边关",
        )
        assertTrue(out.contains("## 用户亲笔指定的剧情走向（本章的任务书·最高优先）"))
        assertTrue(out.contains("这就是本章的任务书，必须照此推进。"))
        assertTrue(out.contains("「让他们私奔去边关」"))
        // beats 整段跳过（J3）+ 聚焦句不出现（反向断言目标串在 main 仅 PromptBuilder 一源·PITFALLS §2.21）
        assertFalse(out.contains(StoryCraftSections.DRAFT_HEADER))
        assertFalse(out.contains("请聚焦该选项对应的方向"))
        // 与预设「## 上一章的用户选择」互斥
        assertFalse(out.contains("## 上一章的用户选择"))
        // 与重写指令共存（E7）
        assertTrue(out.contains("## 重写指令"))
        assertTrue(out.contains("用户的补充要求：「节奏再快些」"))
    }

    // ── 题材引导语 + 伏笔平衡行（图纸 §8 chunk 5·T2-6·L8/L9·E10/E11/E14）──

    @Test fun guide_and_thread_lines_use_genre_label_when_short() {
        val first = B.buildFirstChapterCreationPrompt(
            story = story(genre = "武侠"),
            roles = listOf(role("甲", StoryRoleType.PROTAGONIST)),
            characterData = emptyMap(), voiceProfiles = "",
            protagonistSpectrum = null, protagonistQuality = null,
        )
        assertTrue(first.contains("请创作这个连载「武侠」故事的第一章。")) // E14

        val next = B.buildNextChapterCreationPrompt(
            story = story(genre = "武侠", openThreads = "断掉的项链"),
            chapterNumber = 5, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "",
            latestChapter = chapter(4, hasChoice = true, userChoice = "选A"),
            chapterSummaries = emptyList(),
        )
        assertTrue(next.contains("请继续创作这个连载「武侠」故事的下一章。"))
        assertTrue(next.contains("注意：伏笔与悬念服务于本故事「武侠」类型的主线，是佐料不是主菜，不得堆积悬念把故事带偏成另一种类型。")) // L9
    }

    @Test fun guide_and_thread_lines_fall_back_to_plain_when_genre_over_12() { // E10/E11
        val genre13 = "一二三四五六七八九十一二三" // 13 字 → anchorLabel null
        val first = B.buildFirstChapterCreationPrompt(
            story = story(genre = genre13),
            roles = listOf(role("甲", StoryRoleType.PROTAGONIST)),
            characterData = emptyMap(), voiceProfiles = "",
            protagonistSpectrum = null, protagonistQuality = null,
        )
        assertTrue(first.contains("请创作这个连载故事的第一章。")) // 退回原句
        assertFalse(first.contains("请创作这个连载「"))            // 未嵌入

        val next = B.buildNextChapterCreationPrompt(
            story = story(genre = genre13, openThreads = "断掉的项链"),
            chapterNumber = 5, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "",
            latestChapter = chapter(4, hasChoice = true, userChoice = "选A"),
            chapterSummaries = emptyList(),
        )
        assertTrue(next.contains("请继续创作这个连载故事的下一章。")) // 退回原句
        assertFalse(next.contains("注意：伏笔与悬念服务于本故事"))   // L9 整段不加
    }

    // ── 卷一 V2：上一章**全文**注入（图纸 §7 T2-3·E5）──

    @Test fun previous_chapter_full_text_is_injected_without_truncation() {
        // 5000+ 字含标签的长章（LONG 结局章体量）：首句与末句都必须进 prompt，且不留任何沉浸标签残留
        val body = "[mood:dark]那一夜她推开了门。" + "长".repeat(5_000) + "[weather:rain]于此别过，再无归期。[chapter_end]"
        val out = B.buildNextChapterCreationPrompt(
            story = story(maxChapters = 20),
            chapterNumber = 10, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "",
            latestChapter = chapter(9, content = body, mood = "dark", hasChoice = false),
            chapterSummaries = emptyList(),
        )
        val header = "## 上一章全文（新章节必须从其结尾自然衔接，不要复述或改写上一章的内容）"
        assertTrue(out.contains(header))
        assertTrue(out.contains("那一夜她推开了门。"))                 // 首句（旧「末 400 字」实现会切掉）
        assertTrue(out.contains("于此别过，再无归期。"))                // 末句
        assertTrue(out.contains("长".repeat(5_000)))                  // 中段全量，无截断
        assertTrue(out.contains("上一章结束时的氛围：dark"))            // 末 mood 标签值

        // 剥标签断言**只能切到衔接段内**：整篇 prompt 的标记规则段与输出格式示例本就合法地含 [mood: / [chapter_end]
        // （PITFALLS §2.21 反向断言陷阱），拿全文做否定式必假红。
        val section = out.substringAfter(header).substringBefore("上一章结束时的氛围：")
        assertFalse(section.contains("[mood:"))
        assertFalse(section.contains("[weather:"))
        assertFalse(section.contains("[chapter_end]"))
        assertFalse(section.contains("["))
    }

    // ── 卷一 V3：无限连载中性进度事实（图纸 §7 T2-4·E7）──

    @Test fun infinite_mode_states_neutral_progress_facts() {
        val out = B.buildNextChapterCreationPrompt(
            story = story(maxChapters = null, storyOutline = "弧线大纲", currentArcStartChapter = 13),
            chapterNumber = 20, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "",
            latestChapter = chapter(19, hasChoice = false),
            chapterSummaries = emptyList(),
        )
        assertTrue(out.contains("当前进度：第20章（无限连载·本弧线从第13章开始，本章是本弧第8章）。"))
        assertTrue(out.contains("- 这是第 20 章（无限连载）"))
        // 有限模式专属的阶段摊派绝不出现在无限故事里
        assertFalse(out.contains("处于建置期"))
        assertFalse(out.contains("处于升温期"))
        assertFalse(out.contains("处于危机期"))
        assertFalse(out.contains("处于至暗期"))
        assertFalse(out.contains("处于高潮期"))
    }

    @Test fun infinite_mode_null_arc_start_is_treated_as_chapter_one() { // E7 首弧
        val out = B.buildNextChapterCreationPrompt(
            story = story(maxChapters = null, storyOutline = "弧线大纲", currentArcStartChapter = null),
            chapterNumber = 4, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "",
            latestChapter = chapter(3, hasChoice = false),
            chapterSummaries = emptyList(),
        )
        assertTrue(out.contains("当前进度：第4章（无限连载·本弧线从第1章开始，本章是本弧第4章）。"))
    }

    // ── 卷二 B3：弧末收束令（图纸 §4.3 逐字·追加在进度行之后）──

    private fun outlineWithPlannedLength(n: Int) = "${StoryArcPlanning.ARC_PLANNED_LENGTH_PREFIX}$n\n弧线主题：某某"

    @Test fun arc_wrap_up_directive_fires_in_last_two_chapters_of_arc() {
        // 自报 10、arcStart=21 → 本弧覆盖 21..30；收束令在本弧第 9、10 章（= 第 29、30 章）出现。
        fun promptAt(chapter: Int) = B.buildNextChapterCreationPrompt(
            story = story(storyOutline = outlineWithPlannedLength(10), currentArcStartChapter = 21),
            chapterNumber = chapter, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "",
            latestChapter = chapter(chapter - 1, hasChoice = false), chapterSummaries = emptyList(),
        )
        val directive =
            "本弧接近收束：请把本弧的主线冲突收拢出一个阶段性落点，并为下一段剧情留一个钩子；不要在本弧结尾开新的大冲突。"
        assertFalse("本弧第 8 章还早，不给收束令", promptAt(28).contains(directive))
        assertTrue("本弧第 9 章（倒数第二章）起给收束令", promptAt(29).contains(directive))
        assertTrue("本弧第 10 章（末章）给收束令", promptAt(30).contains(directive))
        // 位置：紧跟在进度行之后
        val out = promptAt(30)
        assertTrue(out.contains("当前进度：第30章（无限连载·本弧线从第21章开始，本章是本弧第10章）。\n$directive"))
    }

    @Test fun arc_wrap_up_directive_absent_when_planned_length_unparseable() {
        // 自报行缺失 → 不给收束令（宁可不提醒，也不在错误的章号上瞎喊收束）。
        val out = B.buildNextChapterCreationPrompt(
            story = story(storyOutline = "没有自报行的老大纲", currentArcStartChapter = 1),
            chapterNumber = 12, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "",
            latestChapter = chapter(11, hasChoice = false), chapterSummaries = emptyList(),
        )
        assertFalse(out.contains("本弧接近收束"))
    }

    @Test fun finale_wrap_up_directive_fires_every_chapter_of_finale_arc() {
        val directive = "全书收束期：本章继续回收伏笔、沉淀感情线，靠近大结局一步；不开新冲突、不引入新重要角色。"
        for (chapter in 40..43) {
            val out = B.buildNextChapterCreationPrompt(
                story = story(
                    storyOutline = outlineWithPlannedLength(4),
                    currentArcStartChapter = 40,
                    finaleEndingType = StoryEndingType.AI,
                ),
                chapterNumber = chapter, roles = emptyList(), characterData = emptyMap(),
                voiceProfiles = "", chatInfluence = "",
                latestChapter = chapter(chapter - 1, hasChoice = false), chapterSummaries = emptyList(),
            )
            assertTrue("终章弧第 ${chapter - 39} 章须带全书收束令", out.contains(directive))
            assertFalse("终章弧不给普通弧的阶段性收束令", out.contains("本弧接近收束"))
        }
    }

    @Test fun no_wrap_up_directive_without_outline() {
        // 大纲整体为空 → 进度行本身就不渲染，两条收束令自然都不出现（prompt 字节级零变化）。
        val out = B.buildNextChapterCreationPrompt(
            story = story(storyOutline = null, currentArcStartChapter = 1, finaleEndingType = StoryEndingType.AI),
            chapterNumber = 3, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "",
            latestChapter = chapter(2, hasChoice = false), chapterSummaries = emptyList(),
        )
        assertFalse(out.contains("全书收束期"))
        assertFalse(out.contains("本弧接近收束"))
        assertFalse(out.contains("当前进度："))
    }

    // ── 卷二·单模式化看门狗（原卷一「有限模式两套曲线金丝雀」`finite_mode_keeps_stage_curve_and_never_says_infinite`
    //    随机制退役删除属预期——它锁的两条曲线本卷整体移除）──

    /**
     * 即便故事上残留着老的 maxChapters 脏数据（迁移会归一化，脏数据防御照做），续章 prompt 也**一律**走
     * 无限连载口径：中性进度事实 + 通用节奏指引，五个阶段名与「共 N 章」一个都不许再冒出来。
     */
    @Test fun stale_max_chapters_never_revives_finite_curves() {
        val out = B.buildNextChapterCreationPrompt(
            story = story(maxChapters = 60, storyOutline = "老大纲", currentArcStartChapter = 25),
            chapterNumber = 30, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "",
            latestChapter = chapter(29, hasChoice = false),
            chapterSummaries = emptyList(),
        )
        assertTrue(out.contains("当前进度：第30章（无限连载·本弧线从第25章开始，本章是本弧第6章）。"))
        assertTrue(out.contains("- 这是第 30 章（无限连载）"))
        assertTrue(out.contains("- 节奏指引：保持每章有推进、有悬念，让读者想看下一章"))
        assertFalse(out.contains("章（共 "))
        for (stage in listOf("建置期", "升温期", "危机期", "至暗期", "高潮期")) {
            assertFalse("有限模式阶段名不许复活：$stage", out.contains("处于$stage"))
        }
        for (stage in listOf("序章期", "发展期", "高潮期", "收束期")) {
            assertFalse("有限模式节奏曲线不许复活：$stage", out.contains("节奏指引（$stage）"))
        }
    }

    /**
     * 「续写/第二部」检测（原靠 `上一章章号 == maxChapters - 10` 识别自动扩展后的第一章）已随有限模式退役：
     * 任何输入组合都不许再吐出那三句续篇开场白——恒走普通续章开场。
     */
    @Test fun continuation_after_ending_preamble_is_retired() {
        for (latestHasChoice in listOf(false, true)) {
            val out = B.buildNextChapterCreationPrompt(
                story = story(maxChapters = 20),   // 老脏数据：上一章号 10 == 20-10，原实现会命中续篇分支
                chapterNumber = 11,
                roles = emptyList(), characterData = emptyMap(), voiceProfiles = "", chatInfluence = "",
                latestChapter = chapter(10, hasChoice = latestHasChoice),
                chapterSummaries = emptyList(),
            )
            assertFalse(out.contains("前面的故事已经写到了一个结局。现在用户想看「后续/续篇」。"))
            assertFalse(out.contains("不要否定或推翻之前的结局，而是自然地在其基础上生长。"))
            assertTrue(out.contains("请继续创作这个连载「言情」故事的下一章。"))
        }
    }

    // ── 导演手记重构（图纸 2026-08-05 §7 T2-1/T2-2/T2-3）：方向账本 + 大纲帽子 + 三明治 M-C1 ──

    private fun nextWithLedger(
        ledger: String? = null,
        freeformDirective: String? = null,
        latest: StoryChapterEntity? = chapter(4, hasChoice = true, userChoice = "选A"),
        storyOutline: String? = "路标一：她愿意开口",
    ) = B.buildNextChapterCreationPrompt(
        story = story(storyOutline = storyOutline, pendingChapterBeats = "方向A", currentArcStartChapter = 3),
        chapterNumber = 5, roles = emptyList(), characterData = emptyMap(),
        voiceProfiles = "", chatInfluence = "", latestChapter = latest, chapterSummaries = emptyList(),
        freeformDirective = freeformDirective, directiveLedger = ledger,
    )

    @Test fun T2_1_账本为null与不传参字节级一致_且整段缺席() { // E7/E20
        val baseline = B.buildNextChapterCreationPrompt(
            story = story(storyOutline = "路标一：她愿意开口", pendingChapterBeats = "方向A", currentArcStartChapter = 3),
            chapterNumber = 5, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "",
            latestChapter = chapter(4, hasChoice = true, userChoice = "选A"), chapterSummaries = emptyList(),
        )
        assertEquals("显式传 null 与不传参必须逐字节相同", baseline, nextWithLedger(ledger = null))
        assertEquals("空串同理", baseline, nextWithLedger(ledger = ""))
        assertFalse(baseline.contains(StoryCraftSections.DIRECTIVE_LEDGER_HEADER))
        assertFalse(baseline.contains(StoryCraftSections.DIRECTIVE_LEDGER_INTRO))
    }

    @Test fun T2_2_账本段逐字且落在场景状态之后走向选择块之前() {
        val ledger = "- 第3章时指定：「先去码头」\n- 第4章时指定：「把信烧掉」"
        val out = B.buildNextChapterCreationPrompt(
            story = story(storyOutline = "路标一：她愿意开口", pendingChapterBeats = "方向A", currentArcStartChapter = 3)
                .copy(sceneState = "码头｜两人隔着雨"),
            chapterNumber = 5, roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", chatInfluence = "",
            latestChapter = chapter(4, hasChoice = true, userChoice = "选A"), chapterSummaries = emptyList(),
            directiveLedger = ledger,
        )
        assertTrue(
            out.contains(
                "## 用户亲笔指定过的走向（按时间从早到晚，越新越优先）\n" +
                    "以下是用户在本段剧情中亲笔写下过的走向。其中已写到的部分不必重复，尚未展开的意图继续落实：\n" +
                    ledger,
            ),
        )
        val ledgerIdx = out.indexOf(StoryCraftSections.DIRECTIVE_LEDGER_HEADER)
        val stateIdx = out.indexOf(StoryCraftSections.SCENE_STATE_HEADER)
        val choiceIdx = out.indexOf("## 上一章的用户选择")
        assertTrue("场景状态段真的在（否则下面那条邻位钉是假绿）", stateIdx >= 0)
        assertTrue("账本在场景状态段之后", stateIdx in 0 until ledgerIdx)
        assertTrue("账本在本章走向/选择块之前（历史 → 当下）", ledgerIdx < choiceIdx)
        // E23 接缝（R1 复核补·48 组矩阵不含账本维度，账本段的接缝在此独立钉）
        assertFalse("含账本段的 prompt 不得出现三连换行", out.contains("\n\n\n"))
    }

    @Test fun T2_2_账本与三明治并存_M_C1四行逐字() { // E2/E3
        val ledger = "- 第3章时指定：「先去码头」"
        val out = nextWithLedger(ledger = ledger, freeformDirective = "让他们私奔去边关")
        assertTrue(
            "M-C1 四行逐字",
            out.contains(
                "## 用户亲笔指定的剧情走向（本章的任务书·最高优先）\n" +
                    "用户亲自写下了接下来的剧情走向：\n" +
                    "「让他们私奔去边关」\n" +
                    "这就是本章的任务书，必须照此推进。下方的大纲与任何方向建议都只是参考；" +
                    "若与它冲突，一律以用户写下的走向为准，并把既有线索自然过渡到该方向上。",
            ),
        )
        assertTrue("账本在三明治之前", out.indexOf(StoryCraftSections.DIRECTIVE_LEDGER_HEADER) in 0 until out.indexOf("## 用户亲笔指定的剧情走向"))
        // 旧 L1 措辞整体退场
        assertFalse(out.contains("用户没有从预设选项中挑选，而是亲自写下了接下来的剧情走向："))
        assertFalse(out.contains("本章必须按照这个走向推进。"))
    }

    @Test fun T2_2_J3关选项书亲笔走向也出三明治段() { // E3
        // D3 关选项的书：latestChapter.hasChoice = false —— 原门会把整块吞掉
        val out = nextWithLedger(
            freeformDirective = "让他们私奔去边关",
            latest = chapter(4, hasChoice = false, userChoice = "让他们私奔去边关"),
        )
        assertTrue(out.contains("## 用户亲笔指定的剧情走向（本章的任务书·最高优先）"))
        assertFalse("无预设选择块", out.contains("## 上一章的用户选择"))
        // 无 freeform 且 !hasChoice 时仍无段（现状保持）
        val noDirective = nextWithLedger(latest = chapter(4, hasChoice = false, userChoice = null))
        assertFalse(noDirective.contains("## 用户亲笔指定的剧情走向"))
        assertFalse(noDirective.contains("## 上一章的用户选择"))
    }

    @Test fun T2_3_大纲帽子两行紧跟标题_首续两处() { // E1
        val obey = StoryCraftSections.OUTLINE_OBEY_LINE
        val pace = StoryCraftSections.OUTLINE_PACE_LINE
        assertEquals("以下为剧情方向参考，若与用户指示或已写正文冲突，一律以后者为准。", obey)
        assertEquals("路标按剧情自然节奏逐个实现，一章至多推进一个路标，不许提前兑现后续路标与高潮。", pace)

        val next = nextWithLedger()
        assertTrue(
            "续章：标题 → OBEY → PACE → 大纲正文 相邻",
            next.contains("## 大纲（参考方向，不要生硬复述）\n$obey\n$pace\n路标一：她愿意开口"),
        )
        // 预设点选路其余段逐字不变
        assertTrue(next.contains("## 上一章的用户选择\n用户选择了「选A」"))
        assertTrue(
            next.contains(
                StoryCraftSections.DRAFT_HEADER + "\n方向A\n" + StoryCraftSections.draftPresetChoiceLine("选A"),
            ),
        )

        val first = B.buildFirstChapterCreationPrompt(
            story = story(storyOutline = "路标一：她愿意开口"),
            roles = emptyList(), characterData = emptyMap(), voiceProfiles = "",
            protagonistSpectrum = null, protagonistQuality = null,
        )
        assertTrue(
            "首章：标题 → OBEY → PACE → 大纲正文 → 空行 → 建置句",
            first.contains(
                "## 整体大纲（参考方向，不要生硬复述大纲内容）\n$obey\n$pace\n路标一：她愿意开口\n\n本章对应大纲的建置阶段。",
            ),
        )
    }

    @Test fun T2_3_大纲为空时帽子两行一并缺席() {
        val next = nextWithLedger(storyOutline = null)
        assertFalse(next.contains(StoryCraftSections.OUTLINE_OBEY_LINE))
        assertFalse(next.contains(StoryCraftSections.OUTLINE_PACE_LINE))
        val first = B.buildFirstChapterCreationPrompt(
            story = story(storyOutline = null), roles = emptyList(), characterData = emptyMap(),
            voiceProfiles = "", protagonistSpectrum = null, protagonistQuality = null,
        )
        assertFalse(first.contains(StoryCraftSections.OUTLINE_OBEY_LINE))
        assertFalse(first.contains(StoryCraftSections.OUTLINE_PACE_LINE))
    }
}
