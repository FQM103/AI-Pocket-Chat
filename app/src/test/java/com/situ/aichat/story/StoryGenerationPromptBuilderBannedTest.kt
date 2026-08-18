package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文字忌口在**创作 prompt 两锚**（首章 / 续章）里的注入行为（图纸 §7 T2-2 / T2-2b）。
 *
 * 两件事：
 * - **T2-2 内容**：三层取值的结果真进了 prompt，且 E2「全局主动清空」时整段消失；每种情况都断言**恰一次**
 *   （`indexOf == lastIndexOf`）——这是双重注入 bug（旧「填入默认」灌合体串 + 装配再拼一次）的反证钉。
 * - **T2-2b 位置零漂移**：J2 把忌口从「拼进写作规则字符串」改成「独立 lines.add」，本测试证明
 *   段落位置与前后空行**逐字节未变**：把新忌口整段替换回旧忌口文本后，「写作规则段尾 → 忌口段 → 下一段段首」
 *   这段邻接关系与改造前完全一致。
 */
class StoryGenerationPromptBuilderBannedTest {

    private val B = StoryGenerationPromptBuilder
    private val HEADER = "### 别写出 AI 味"

    /** 改造前（提交 66d4e192）的忌口全文，逐字重打——T2-2b 的 before 基准，勿改。 */
    private val oldBanned: String = """
        ### 绝对禁止的表达（出现任何一个都会严重降低质量）

        **禁止词组（出现即扣分）：**
        不禁、映入眼帘、心中一动、一股暖流、嘴角微微上扬、目光深邃、时光荏苒、
        不禁陷入沉思、心头涌上一股莫名的情绪、嘴角勾起一抹弧度、
        眼中闪过一丝（任何东西）、空气中弥漫着（任何东西）、
        深吸一口气、时间仿佛凝固、内心掀起了波澜、
        默默地看着这一切、心中五味杂陈、不知不觉、恍若隔世

        **禁止的万能修饰词（可以删除而不影响句意时，必须删除）：**
        淡淡的、莫名的、缓缓地、静静地、默默地

        **禁止句式：**
        - "不是X，而是Y"（反义对比膨胀——直接写Y）
        - "没有X，没有Y——只有Z"（列举否定——直接写Z）
        - "TA不知道的是……"（全知泄露——用行为暗示）
        - 连续两个以上比喻修饰同一事物（比喻堆砌）
        - 每段结尾都用省略号或破折号制造"意味深长"
        - 以"此刻"或"仿佛"开头的句子
    """.trimIndent()

    // ── builders ──

    private fun story(customPromptsJson: String? = null) =
        StoryEntity(genre = "言情", writingStyle = "古风", customPromptsJson = customPromptsJson)

    /**
     * 邻接类断言专用夹具：本书把故事二期的主节拍段关掉（三态 `""`）。
     *
     * 那个段落默认注入在**忌口段与章节要求之间**（D-2 有意改变默认路径），会把这里要证明的
     * 「忌口段前后空行零漂移」邻接关系撑开。关掉它 = 回到「三关路径」，本组断言的语义与改造前逐字不变。
     */
    private fun storyWithSceneBeatsOff(base: CustomStoryPrompts = CustomStoryPrompts()) =
        story(CustomStoryPrompts.encode(base.copy(sceneBeats = "")))

    private fun roles() = listOf(StoryCharacterRoleEntity(roleName = "甲", roleType = StoryRoleType.PROTAGONIST))

    private fun firstPrompt(story: StoryEntity, global: String?) = B.buildFirstChapterCreationPrompt(
        story = story,
        roles = roles(),
        characterData = emptyMap(),
        voiceProfiles = "",
        protagonistSpectrum = null,
        protagonistQuality = null,
        globalBannedExpressions = global,
    )

    private fun nextPrompt(story: StoryEntity, global: String?) = B.buildNextChapterCreationPrompt(
        story = story,
        chapterNumber = 2,
        roles = roles(),
        characterData = emptyMap(),
        voiceProfiles = "",
        chatInfluence = "",
        latestChapter = StoryChapterEntity(chapterNumber = 1, content = "上一章正文", mood = "peaceful"),
        chapterSummaries = emptyList(),
        globalBannedExpressions = global,
    )

    /** 出现次数恰一次的双证：出现过 + 首末次位置相同。 */
    private fun assertExactlyOnce(prompt: String, needle: String) {
        assertTrue("应出现：$needle", prompt.contains(needle))
        assertEquals("出现次数必须恰一次（防双重注入复发）", prompt.indexOf(needle), prompt.lastIndexOf(needle))
    }

    // ── T2-2：三层取值的结果真进 prompt ──

    @Test fun e1_default_banned_injected_exactly_once_in_both_anchors() {
        val default = StoryWritingTechniques.bannedExpressionsBaseline
        assertExactlyOnce(firstPrompt(story(), null), default)
        assertExactlyOnce(nextPrompt(story(), null), default)
    }

    @Test fun e2_globally_cleared_removes_the_whole_section() {
        // 用户全删后确认（""）→ 忌口段整个不出现；写作规则段与后续段照旧
        val first = firstPrompt(storyWithSceneBeatsOff(), "")
        assertFalse(first.contains(HEADER))
        assertFalse(first.contains("映入眼帘"))
        assertTrue(first.contains("### 写作铁律"))
        assertTrue(first.contains("## 章节要求"))
        // 规则段与下一段之间只隔一个空行（没留下空洞）
        assertTrue(first.contains("6. 段落控制：别一段写到底，段落长短跟着内容走\n\n## 章节要求"))

        val next = nextPrompt(storyWithSceneBeatsOff(), "")
        assertFalse(next.contains(HEADER))
        assertTrue(next.contains("6. 段落控制：别一段写到底，段落长短跟着内容走\n\n## 章节要求"))
    }

    @Test fun e3_global_custom_text_injected_exactly_once() {
        val global = "少写天气，多写手上的动作"
        assertExactlyOnce(firstPrompt(story(), global), global)
        assertExactlyOnce(nextPrompt(story(), global), global)
        // 全局接管后内置默认不再出现
        assertFalse(firstPrompt(story(), global).contains(HEADER))
    }

    @Test fun e4_per_story_override_wins_and_appears_once() {
        val perStory = story(CustomStoryPrompts.encode(CustomStoryPrompts(bannedExpressions = "本书不许出现「雨」")))
        val first = firstPrompt(perStory, "全局忌口文本")
        assertExactlyOnce(first, "本书不许出现「雨」")
        assertFalse(first.contains("全局忌口文本"))
        assertFalse(first.contains(HEADER))

        val next = nextPrompt(perStory, "全局忌口文本")
        assertExactlyOnce(next, "本书不许出现「雨」")
        assertFalse(next.contains("全局忌口文本"))
    }

    // ── T2-2b：位置零漂移（§2.3 的字节级 claim）──

    @Test fun t2_2b_section_position_is_byte_identical_to_pre_refactor_prompt() {
        val newBanned = StoryWritingTechniques.bannedExpressionsBaseline
        val principlesTail = "6. 段落控制：别一段写到底，段落长短跟着内容走"

        for (prompt in listOf(firstPrompt(storyWithSceneBeatsOff(), null), nextPrompt(storyWithSceneBeatsOff(), null))) {
            // 现状邻接：风格原则段尾 → 空行 → 忌口段 → 空行 → 下一段段首
            assertTrue(prompt.contains(principlesTail + "\n\n" + newBanned + "\n\n## 章节要求"))
            // 把新忌口整段换回旧文本 → 与改造前的 prompt 该段落逐字节相同（J2 没有多/少一个 \n）
            val restored = prompt.replace(newBanned, oldBanned)
            assertTrue(
                "换回旧忌口后应与改造前逐字节一致",
                restored.contains(principlesTail + "\n\n" + oldBanned + "\n\n## 章节要求"),
            )
        }
    }

    @Test fun t2_2b_custom_writing_rules_path_keeps_old_adjacency_too() {
        // 老写法是 `custom + "\n\n" + 忌口` 一个字符串；新写法是两次 add——joinToString 后仍是同样的字节
        val custom = storyWithSceneBeatsOff(CustomStoryPrompts(writingRules = "自定义铁律"))
        val newBanned = StoryWritingTechniques.bannedExpressionsBaseline
        for (prompt in listOf(firstPrompt(custom, null), nextPrompt(custom, null))) {
            assertTrue(prompt.contains("自定义铁律\n\n" + newBanned + "\n\n## 章节要求"))
            assertTrue(prompt.replace(newBanned, oldBanned).contains("自定义铁律\n\n" + oldBanned + "\n\n## 章节要求"))
        }
    }
}
