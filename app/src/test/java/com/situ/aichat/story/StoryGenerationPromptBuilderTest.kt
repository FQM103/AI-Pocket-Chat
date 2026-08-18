package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.data.model.MaxOutputLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryGenerationPromptBuilder` (11.1d-3) tests, reverse-derived from iOS
 * `StoryGenerationPromptBuilder` + `+Formatting`：故事设定 / 标记规则 / 创作输出格式 / 聊天影响 4 档 /
 * customPrompts 覆盖 / token 上限(用户档优先·思考×3) / 摘要压缩 prompt。
 */
class StoryGenerationPromptBuilderTest {

    private val B = StoryGenerationPromptBuilder

    private fun story(
        genre: String = "言情",
        writingStyle: String = "古风",
        worldSetting: String? = null,
        plotDirection: String? = null,
        customPromptsJson: String? = null,
    ) = StoryEntity(
        genre = genre, writingStyle = writingStyle, worldSetting = worldSetting,
        plotDirection = plotDirection, customPromptsJson = customPromptsJson,
    )

    @Test fun story_setup_lines() {
        val lines = mutableListOf<String>()
        B.appendStorySetup(lines, story(genre = "悬疑", writingStyle = "网文爽文", worldSetting = "架空", plotDirection = "慢热"))
        assertEquals(
            listOf("## 故事设定", "类型：悬疑", "文风：网文爽文", "世界观：架空", "剧情方向：慢热"),
            lines,
        )
    }

    @Test fun story_setup_null_world_and_plot_fall_back() {
        val lines = mutableListOf<String>()
        B.appendStorySetup(lines, story(worldSetting = null, plotDirection = null))
        assertTrue(lines.contains("世界观：自由发挥"))
        assertTrue(lines.contains("剧情方向：自由发挥"))
    }

    @Test fun story_setup_style_line_yields_to_custom_writer_identity() {
        // 用户拍板 2026-07-27（取代 L4a 的「加尾注保留」）：填了写作身份 → 文风行整行不注入。
        val withIdentity = CustomStoryPrompts.encode(CustomStoryPrompts(writerIdentity = "你是修仙大师"))
        val lines = mutableListOf<String>()
        B.appendStorySetup(lines, story(writingStyle = "网文爽文", customPromptsJson = withIdentity))
        assertFalse("文风整行不得出现", lines.any { it.startsWith("文风：") })
        assertTrue("其余设定行照旧", lines.contains("类型：言情"))

        // 自定义了其它项但没定义身份 → 标签行不变
        val withoutIdentity = CustomStoryPrompts.encode(CustomStoryPrompts(writingRules = "自定义铁律"))
        val plain = mutableListOf<String>()
        B.appendStorySetup(plain, story(writingStyle = "网文爽文", customPromptsJson = withoutIdentity))
        assertTrue(plain.contains("文风：网文爽文"))
    }

    @Test fun story_setup_style_line_yields_for_preset_genre_too() {
        // 口径锁：判据只看身份非空，**不分预设/自定义类型**（设置页对所有故事开放身份编辑）。
        // 「言情」是预设 10 题材之一，照样避让。
        val withIdentity = CustomStoryPrompts.encode(CustomStoryPrompts(writerIdentity = "你是严肃文学作家"))
        val lines = mutableListOf<String>()
        B.appendStorySetup(lines, story(genre = "言情", writingStyle = "古风", customPromptsJson = withIdentity))
        assertFalse("预设题材也避让", lines.any { it.startsWith("文风：") })

        // 身份为空串/纯空白 → 都不算接管，文风行照常注入（判据走单源 effectiveWriterIdentity，trim 后非空才算数）
        listOf("", "   ", "\n \t ").forEach { blank ->
            val blankIdentity = CustomStoryPrompts.encode(CustomStoryPrompts(writerIdentity = blank))
            val plain = mutableListOf<String>()
            B.appendStorySetup(plain, story(writingStyle = "古风", customPromptsJson = blankIdentity))
            assertTrue("空白身份不算接管（${blank.length} 字空白）", plain.contains("文风：古风"))
        }
    }

    @Test fun 空白写作身份三处口径一致_不出现两头落空() {
        // R1 复核 🔵-2 根治：备份导入可能带进纯空白身份。旧口径（isNullOrEmpty）下会出现
        // 「身份段是一片空白 + 文风行又被避让掉」= 模型收不到任何笔调信号。三处必须同判据。
        val blank = CustomStoryPrompts.encode(CustomStoryPrompts(writerIdentity = "   "))
        val s = story(writingStyle = "古风", customPromptsJson = blank)

        val lines = mutableListOf<String>()
        B.appendStorySetup(lines, s)
        assertTrue("① 创作 prompt 文风行在", lines.contains("文风：古风"))

        val identity = B.resolvedWriterIdentity(s)
        assertTrue("② 身份段回退到文风默认身份、不是一片空白", identity.isNotBlank())
        assertEquals("② 与文风默认身份逐字相同", StoryWritingTechniques.writerIdentity("古风"), identity)

        val outline = B.buildOutlinePrompt(s, emptyList(), emptyMap())
        assertTrue("③ 弧线大纲文风行在", outline.lines().contains("文风：古风"))
    }

    @Test fun markup_rules_contain_full_tag_lists_and_rules() {
        // 2026-08-03 格式块精简：可用标记只剩排版三类（scene / text 七样式 / chapter_end），
        // 氛围演出类（mood/weather/effect/pause）整族退役且**提示词里一个字都不提**（图纸 §9-③ J6）。
        val lines = mutableListOf<String>()
        B.appendMarkupRules(lines)
        val joined = lines.joinToString("\n")
        assertTrue(joined.startsWith("## 内容标记"))
        assertTrue(joined.contains("场景切换（描述文字简短，不超过10个字）："))
        assertTrue(joined.contains("[scene:三小时后·卧室]"))
        listOf("whisper", "shout", "thought", "trembling", "angry", "excited", "emphasis").forEach {
            assertTrue("text 七样式缺 $it", joined.contains("[text:$it]"))
        }
        assertTrue(joined.contains("[chapter_end]"))
        assertTrue(joined.contains("禁止：[text: whisper] （标签内有空格）"))
        // 精确否定：被砍的四类标记名一个都不许在提示词里露面（含「禁止使用」式的负面提及）
        listOf("[mood:", "[weather:", "[effect:", "[pause:").forEach {
            assertFalse("已删标记不该出现在提示词里：$it", joined.contains(it))
        }
    }

    // ── 卷一 V5：配额硬摊派 → 软引导 + chapter_end 补录（图纸 §4.1/§4.2·T2-9）──

    @Test fun markup_rules_soften_quota_into_principles_and_list_chapter_end() {
        val lines = mutableListOf<String>()
        B.appendMarkupRules(lines)
        val joined = lines.joinToString("\n")
        // 新段头 + 逐标记场景语义保留（2026-08-03 精简后只剩 text / scene 两条）+ 「可不用」出口
        assertTrue(joined.contains("### 使用原则（跟随剧情自然使用，不设次数指标）"))
        assertTrue(joined.contains("- text 样式：留给最值得强调的语句，点缀即可"))
        assertTrue(joined.contains("- scene：时间或地点变化时使用"))
        assertTrue(joined.contains("- 以上标记都不是必须的——本章剧情用不上就不用，不要为凑标记而打断叙事"))
        // chapter_end 进清单（解析器 StoryContentParser 早已识别，此处消除「示例有、清单无」的自相矛盾）
        assertTrue(joined.contains("章节收尾装饰（可选，全章至多一次，放在正文最末一行）："))
        assertTrue(joined.contains("[chapter_end]"))
        // 精确否定：旧摊派句一个都不许留（取旧文本的独有句，非泛串——PITFALLS §2.21）
        assertFalse(joined.contains("### 使用节奏（非常重要）"))
        assertFalse(joined.contains("mood 变化：每章 2-4 次"))
        assertFalse(joined.contains("weather 变化：每章 1-2 次"))
        assertFalse(joined.contains("effect 特效：每章 2-3 次"))
        assertFalse(joined.contains("text 样式：每章 3-6 次"))
        assertFalse(joined.contains("pause 停顿：每章 1-3 次"))
    }

    @Test fun markup_rules_keep_remaining_tag_vocabulary() {
        // 精简后的值域锁：留下来的三类**逐字**还在（砍面之外的行不许被顺手改措辞）
        val lines = mutableListOf<String>()
        B.appendMarkupRules(lines)
        val joined = lines.joinToString("\n")
        assertTrue(joined.contains("6. 只能使用下方列出的标记，禁止自创任何标记类型或标记值"))
        assertTrue(joined.contains("[scene:三小时后·卧室]"))
        assertTrue(joined.contains("章节收尾装饰（可选，全章至多一次，放在正文最末一行）："))
        assertTrue(joined.contains("[text:emphasis]重点强调[/text]"))
        assertTrue(joined.contains("文字样式（必须用[/text]关闭，内容不超过20字）："))
    }

    @Test fun creation_output_format_has_separator_and_choice_fields() {
        val lines = mutableListOf<String>()
        B.appendCreationOutputFormat(lines)
        val joined = lines.joinToString("\n")
        assertTrue(joined.contains("---METADATA---"))
        assertTrue(joined.contains("### 选择分支（最重要，必须严格遵守）"))
        assertTrue(joined.contains("nextChapterBeats: 两人约好周末去美术馆；"))
        assertTrue(joined.contains("不要输出 JSON，不要输出 markdown 代码块"))
        assertEquals("", lines.first()) // 首元素为空行（接在 markupRules 后空一行）
    }

    @Test fun chat_influence_instruction_four_tiers() {
        assertTrue(B.chatInfluenceInstruction(StoryChatInfluenceWeight.NONE).startsWith("不允许聊天内容影响剧情"))
        assertTrue(B.chatInfluenceInstruction(StoryChatInfluenceWeight.LIGHT).startsWith("轻度影响规则"))
        assertTrue(B.chatInfluenceInstruction(StoryChatInfluenceWeight.HEAVY).startsWith("重度影响规则"))
        assertTrue(B.chatInfluenceInstruction(StoryChatInfluenceWeight.MEDIUM).startsWith("中度影响规则"))
        assertTrue(B.chatInfluenceInstruction("unknown").startsWith("中度影响规则")) // 兜底 medium
    }

    @Test fun resolved_prefers_custom_then_preset() {
        val customJson = CustomStoryPrompts.encode(
            CustomStoryPrompts(writerIdentity = "你是自定义大师", genreTechniques = "【自定义技法】", writingRules = "自定义铁律"),
        )
        val withCustom = story(customPromptsJson = customJson)
        assertEquals("你是自定义大师", B.resolvedWriterIdentity(withCustom))
        assertEquals("【自定义技法】", B.resolvedGenreTechniques(withCustom))
        // 文字忌口 J2（2026-07-30·取代 L4b 的「尾附」写法）：写作规则整段由用户接管，忌口不再拼在这里
        // ——它独立成段注入（[StoryPromptSections.resolvedBannedExpressions]），故本段就是用户原文。
        assertEquals("自定义铁律", B.resolvedWritingRules(withCustom))
        assertFalse(B.resolvedWritingRules(withCustom).contains("### 别写出 AI 味"))

        // 无自定义 → 预设
        val preset = story(genre = "言情", writingStyle = "古风", customPromptsJson = null)
        assertTrue(B.resolvedWriterIdentity(preset).contains("古风小说名家"))
        assertTrue(B.resolvedGenreTechniques(preset).startsWith("【言情核心技法】"))
        assertTrue(B.resolvedWritingRules(preset).startsWith("### 写作铁律"))
    }

    @Test fun custom_genre_techniques_win_over_fallback_for_non_preset_genre() { // T2-6·E13
        // 自定义题材（非预设 10 类）已填技法 → 用户文本生效，chunk 3 兜底段不上场（resolved 优先级不变）
        val customJson = CustomStoryPrompts.encode(CustomStoryPrompts(genreTechniques = "【我的武侠技法】多线并进"))
        val out = B.resolvedGenreTechniques(story(genre = "武侠", customPromptsJson = customJson))
        assertEquals("【我的武侠技法】多线并进", out)
        assertFalse(out.contains("【类型核心技法】"))
    }

    @Test fun preferred_creation_max_tokens() {
        // 用户档优先于章节长度分档；卷一 V7 起固定档也享思考 ×3 余量（旧期望 4_000 = 被修掉的绕过 bug 本身）
        assertEquals(12_000, B.preferredCreationMaxTokens(3000, isThinkingModel = true, userMaxOutputLength = MaxOutputLength.MEDIUM))
        assertEquals(4_000, B.preferredCreationMaxTokens(3000, isThinkingModel = false, userMaxOutputLength = MaxOutputLength.MEDIUM))
        // AUTO → 按章节长度，思考模型 ×3（2026-08-06 全档加倍：思考与正文共享额度，旧档实测被高档思考挤爆截断）
        assertEquals(6_000, B.preferredCreationMaxTokens(500, isThinkingModel = false))
        assertEquals(18_000, B.preferredCreationMaxTokens(500, isThinkingModel = true))
        assertEquals(10_000, B.preferredCreationMaxTokens(1500, isThinkingModel = false))
        assertEquals(30_000, B.preferredCreationMaxTokens(1500, isThinkingModel = true))
        assertEquals(14_000, B.preferredCreationMaxTokens(3000, isThinkingModel = false))
        assertEquals(42_000, B.preferredCreationMaxTokens(3000, isThinkingModel = true))
        assertEquals(20_000, B.preferredCreationMaxTokens(5000, isThinkingModel = false))
        assertEquals(60_000, B.preferredCreationMaxTokens(5000, isThinkingModel = true))
    }

    @Test fun preferred_structuring_max_tokens() {
        assertEquals(3_000, B.preferredStructuringMaxTokens(500))
        assertEquals(5_000, B.preferredStructuringMaxTokens(1500))
        assertEquals(8_000, B.preferredStructuringMaxTokens(3000))
    }

    @Test fun preferred_compression_max_tokens_x3_for_thinking() {
        // 非思考模型：base 原值（现行 base 摘要 2400 / 圣经 2800）
        assertEquals(2_400, B.preferredCompressionMaxTokens(2_400, isThinkingModel = false))
        assertEquals(2_800, B.preferredCompressionMaxTokens(2_800, isThinkingModel = false))
        // 思考模型：×3 给足推理余量（摘要 7200 / 圣经 8400）
        assertEquals(7_200, B.preferredCompressionMaxTokens(2_400, isThinkingModel = true))
        assertEquals(8_400, B.preferredCompressionMaxTokens(2_800, isThinkingModel = true))
    }

    @Test fun compression_prompt_first_time_vs_existing() {
        val first = B.buildCompressionPrompt(
            existingCompressed = "", newSummaries = "第6章…第10章…",
            lastCompressedChapter = 5, currentChapter = 10, genre = "言情",
        )
        assertTrue(first.contains("（首次压缩，无已有摘要）"))
        assertTrue(first.contains("### 新增章节摘要（第6-10章）"))
        assertTrue(first.contains("第6章…第10章…"))
        assertTrue(first.contains("## 压缩要求"))
        assertTrue(first.contains("合并后的摘要控制在 600-800 字"))
        assertFalse(first.contains("### 已有压缩摘要"))
        // 图纸 L4 两行（题材锚·T2-4）
        assertTrue(first.contains("这是一部「言情」类型的故事。概括时必须保留支撑该类型核心体验的线索（如感情线的进展与温度、关键关系的走向），不要把摘要写成与类型无关的纯事件流水账。"))
        assertTrue(first.contains("   - 与「言情」类型核心体验直接相关的情感与关系进展"))

        val withExisting = B.buildCompressionPrompt(
            existingCompressed = "前情精华内容", newSummaries = "新摘要",
            lastCompressedChapter = 8, currentChapter = 12, genre = "宫斗",
        )
        assertTrue(withExisting.contains("### 已有压缩摘要（第1-8章精华）"))
        assertTrue(withExisting.contains("前情精华内容"))
        assertTrue(withExisting.contains("### 新增章节摘要（第9-12章）"))
        assertFalse(withExisting.contains("（首次压缩"))
        assertTrue(withExisting.contains("这是一部「宫斗」类型的故事。"))
    }
}
