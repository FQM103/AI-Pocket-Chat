package com.situ.aichat.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern
import kotlin.random.Random

/**
 * [ContentFilterService] 单测——断言反推 iOS `ContentFilterServiceTests` / `ContentFilterDeepTests` 的真值，
 * 外加移植风险点的独立验证：
 *  - **5 预设逐条 in/out**（星号动作 / 思考标签 / 连续 Emoji / 分隔线 / Markdown），exact `==` 处对齐 iOS。
 *  - **连续 Emoji 引擎等价**：[ContentFilterService.removeConsecutiveEmoji]（纯码点扫描）在 0..0x1FFFF 全平面单码点
 *    及 2000 条随机串上，逐字符等于 JVM 正则 `(\p{IsEmoji_Presentation}\p{IsEmoji_Modifier}*){3,}` 删除（= iOS ICU 行为）。
 *  - **applyFilters 编排**（空 / 无启用 / 多规则顺序 / 末尾空行清理）、**testFilter / isValidRegex** 非法正则、
 *    **loadRules** 迁移（空→默认 / 解码失败→默认 / 删失效预设 / 补新预设 / 同步 pattern / 编解码往返）。
 */
class ContentFilterServiceTest {

    // JVM 正则 oracle（= iOS NSRegularExpression ICU 的等价物，仅用于测试断言 emoji 码点扫描的等价性）。
    private val emojiOracle: Pattern =
        Pattern.compile("(\\p{IsEmoji_Presentation}\\p{IsEmoji_Modifier}*){3,}")

    private fun oracleRemove(input: String): String = emojiOracle.matcher(input).replaceAll("")

    private fun preset(id: String): ContentFilterRule =
        ContentFilterService.defaultPresetRules().first { it.id == id }.copy(isEnabled = true)

    private fun custom(
        pattern: String,
        mode: FilterMode = FilterMode.REMOVE,
        replacement: String = "",
        isEnabled: Boolean = true,
    ) = ContentFilterRule(
        id = "custom-$pattern",
        name = "测试规则",
        pattern = pattern,
        isEnabled = isEnabled,
        isPreset = false,
        mode = mode,
        replacement = replacement,
    )

    // MARK: - 默认预设结构（iOS ContentFilterServiceTests + 全量验证）

    @Test fun defaultPresetRulesCountIsFive() {
        assertEquals(5, ContentFilterService.defaultPresetRules().size)
    }

    @Test fun presetsAllDisabledAndMarkedPreset() {
        val presets = ContentFilterService.defaultPresetRules()
        assertTrue(presets.all { !it.isEnabled })
        assertTrue(presets.all { it.isPreset })
        assertTrue(presets.all { it.mode == FilterMode.REMOVE })
    }

    @Test fun presetIdsAndNamesStable() {
        val presets = ContentFilterService.defaultPresetRules().associateBy { it.id }
        assertEquals("星号动作描述", presets[ContentFilterService.PRESET_ACTION_ID]!!.name)
        assertEquals("思考标签", presets[ContentFilterService.PRESET_THINKING_ID]!!.name)
        assertEquals("连续 Emoji", presets[ContentFilterService.PRESET_EMOJI_ID]!!.name)
        assertEquals("分隔线", presets[ContentFilterService.PRESET_SEPARATOR_ID]!!.name)
        assertEquals("Markdown 格式", presets[ContentFilterService.PRESET_MARKDOWN_ID]!!.name)
        // 缺 002（iOS 已删「括号旁白」）。
        assertNull(presets["00000001-0000-0000-0000-000000000002"])
    }

    @Test fun filterModeDisplayName() {
        assertEquals("删除", FilterMode.REMOVE.displayName)
        assertEquals("替换", FilterMode.REPLACE.displayName)
    }

    // MARK: - applyFilters 编排（iOS ContentFilterServiceTests exact ==）

    @Test fun actionDescriptionFilterRemovesStarWrappedText() {
        val input = "你好啊 *微笑着挥手* 今天天气不错"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_ACTION_ID)))
        assertEquals("你好啊  今天天气不错", result)
    }

    @Test fun emptyInputReturnsEmptyString() {
        assertEquals("", ContentFilterService.applyFilters("", ContentFilterService.defaultPresetRules()))
    }

    @Test fun noMatchingRulesReturnsOriginalContent() {
        val input = "普通的一句话"
        assertEquals(input, ContentFilterService.applyFilters(input, emptyList()))
    }

    @Test fun noEnabledRulesReturnsOriginalContent() {
        // 全部预设默认关闭 → 原文返回（快返回分支）。
        val input = "原始内容不变 *动作* <think>x</think> ---"
        assertEquals(input, ContentFilterService.applyFilters(input, ContentFilterService.defaultPresetRules()))
    }

    @Test fun customReplaceRuleAppliesReplacement() {
        val input = "今天真是秘密的一天"
        val rule = custom("秘密", FilterMode.REPLACE, "特别")
        assertEquals("今天真是特别的一天", ContentFilterService.applyFilters(input, listOf(rule)))
    }

    @Test fun disabledRuleHasNoEffect() {
        val rule = custom("删我", FilterMode.REMOVE, isEnabled = false)
        assertEquals("请删我这段话", ContentFilterService.applyFilters("请删我这段话", listOf(rule)))
    }

    @Test fun onlyEnabledRulesApply() {
        val enabled = custom("AAA")
        val disabled = custom("BBB", isEnabled = false)
        val result = ContentFilterService.applyFilters("AAA和BBB", listOf(enabled, disabled))
        assertFalse(result.contains("AAA"))
        assertTrue(result.contains("BBB"))
    }

    @Test fun multipleRulesApplyInOrder() {
        val rule1 = custom("（[^）]*）", FilterMode.REMOVE)
        val rule2 = custom("\\n{2,}", FilterMode.REPLACE, "\n")
        val input = "你好（小声说）\n\n今天天气不错"
        val result = ContentFilterService.applyFilters(input, listOf(rule1, rule2))
        assertFalse(result.contains("小声说"))
        assertTrue(result.contains("你好"))
        assertTrue(result.contains("今天天气不错"))
    }

    @Test fun trailingBlankLinesCollapsedAndTrimmed() {
        // 删 X 后剩 "a\n\n\nb" → 末尾清理把三连换行折叠为双换行；首尾空白 trim。
        assertEquals("a\n\nb", ContentFilterService.applyFilters("a\n\n\nXb", listOf(custom("X"))))
        assertEquals("正文", ContentFilterService.applyFilters("\n\n  正文X  \n", listOf(custom("X"))))
    }

    // MARK: - 预设：星号动作描述（iOS ContentFilterDeepTests）

    @Test fun starAction_multipleRemoved() {
        val input = "*走过来* 嗨！*坐下* 聊会儿吧"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_ACTION_ID)))
        assertFalse(result.contains("*走过来*"))
        assertFalse(result.contains("*坐下*"))
        assertTrue(result.contains("嗨"))
        assertTrue(result.contains("聊会儿吧"))
    }

    @Test fun starAction_crossLineNotMatched() {
        val input = "*第一行\n第二行* 正文"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_ACTION_ID)))
        assertTrue(result.contains("第一行"))
    }

    // MARK: - 预设：思考标签

    @Test fun thinking_removesCompleteBlock() {
        val input = "<think>让我想想怎么回复</think>好的，我来了！"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_THINKING_ID)))
        assertEquals("好的，我来了！", result)
    }

    @Test fun thinking_removesMultilineBlock() {
        val input = "<think>\n分析用户情绪\n决定回复语气\n</think>你说得对！"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_THINKING_ID)))
        assertFalse(result.contains("分析用户情绪"))
        assertTrue(result.contains("你说得对"))
    }

    @Test fun thinking_unclosedTagRemovedToEnd() {
        // \z 串尾分支：未闭合 <think> 一直删到结尾。
        val input = "正文在前<think>未闭合的思考"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_THINKING_ID)))
        assertEquals("正文在前", result)
    }

    @Test fun thinking_variantTags() {
        val input = "<reasoning>r</reasoning>A<thought>t</thought>B"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_THINKING_ID)))
        assertEquals("AB", result)
    }

    // MARK: - 预设：连续 Emoji

    @Test fun emoji_threeOrMoreRemoved() {
        val input = "太开心了😊😊😊😊 希望每天都这样"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_EMOJI_ID)))
        assertFalse(result.contains("😊😊😊"))
        assertTrue(result.contains("太开心了"))
        assertTrue(result.contains("希望每天都这样"))
    }

    @Test fun emoji_twoNotRemoved() {
        val input = "开心😊😊"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_EMOJI_ID)))
        assertTrue(result.contains("😊😊"))
    }

    @Test fun emoji_skinModifierRunRemoved() {
        // 👍🏻×3（基础 + 肤色修饰符）= 3 单元 → 整段删。
        val input = "👍🏻👍🏻👍🏻done"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_EMOJI_ID)))
        assertEquals("done", result)
    }

    // 引擎等价：码点扫描 == JVM `\p{IsEmoji_Presentation}` 正则，全平面单码点（验证码点表的每个区间边界）。
    @Test fun emoji_codepointTableMatchesUnicodeProperty() {
        var cp = 0
        while (cp <= 0x1FFFF) {
            if (cp in 0xD800..0xDFFF) { cp++; continue } // 跳过孤立代理区（无法单独成字符串）
            val unit = String(Character.toChars(cp))
            val triple = unit + unit + unit
            assertEquals(
                "cp=U+${cp.toString(16).uppercase()}",
                oracleRemove(triple),
                ContentFilterService.removeConsecutiveEmoji(triple),
            )
            cp++
        }
    }

    // 引擎等价：随机混合串（emoji 展示符 / 修饰符 / 普通符号 / 文字 / 空白）2000 条逐条对齐 oracle。
    @Test fun emoji_randomStringsMatchOracle() {
        val pool = listOf(
            "😀", "😊", "🎉", "🐶", "🍎", "🚀", "❤", // 部分含/不含展示属性
            "🏻", "🏿",            // 肤色修饰符
            "a", "中", " ", "1", "★", "★", "\n", "*", "❌", "✅",
        )
        val rnd = Random(20260609)
        repeat(2000) {
            val len = rnd.nextInt(0, 12)
            val sb = StringBuilder()
            repeat(len) { sb.append(pool[rnd.nextInt(pool.size)]) }
            val input = sb.toString()
            assertEquals(
                "input=[$input]",
                oracleRemove(input),
                ContentFilterService.removeConsecutiveEmoji(input),
            )
        }
    }

    // MARK: - 预设：分隔线

    @Test fun separator_threeOrMoreDashesRemoved() {
        val input = "第一段\n---\n第二段"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_SEPARATOR_ID)))
        assertFalse(result.contains("---"))
        assertTrue(result.contains("第一段"))
        assertTrue(result.contains("第二段"))
    }

    @Test fun separator_inlineDashUnaffected() {
        val input = "这是一个-连字符-的句子"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_SEPARATOR_ID)))
        assertTrue(result.contains("一个-连字符-的句子"))
    }

    // MARK: - 预设：Markdown 格式（保留文字去符号）

    @Test fun markdown_boldStripped() {
        val input = "这是**重点内容**需要注意"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_MARKDOWN_ID)))
        assertFalse(result.contains("**"))
        assertTrue(result.contains("重点内容"))
        assertEquals("这是重点内容需要注意", result)
    }

    @Test fun markdown_underlineBoldStripped() {
        val input = "这是__强调__文字"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_MARKDOWN_ID)))
        assertEquals("这是强调文字", result)
    }

    @Test fun markdown_headingHashStripped() {
        val input = "# 标题\n正文内容"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_MARKDOWN_ID)))
        assertFalse(result.contains("#"))
        assertTrue(result.contains("标题"))
        assertTrue(result.contains("正文内容"))
    }

    @Test fun markdown_headingFullWidthSpaceStripped() {
        // 全角空格 U+3000（中文 LLM 常见）：iOS ICU \s 含 \p{Z} 会去 #，安卓 \s ASCII-only 不去 → 用 [\s\p{Z}] 对齐。
        val input = "#　标题\n正文"
        val result = ContentFilterService.applyFilters(input, listOf(preset(ContentFilterService.PRESET_MARKDOWN_ID)))
        assertFalse(result.contains("#"))
        assertTrue(result.contains("标题"))
        // NBSP U+00A0 同理。
        val nbsp = ContentFilterService.applyFilters("# 标题", listOf(preset(ContentFilterService.PRESET_MARKDOWN_ID)))
        assertFalse(nbsp.contains("#"))
        assertTrue(nbsp.contains("标题"))
    }

    // MARK: - 自定义规则（iOS ContentFilterDeepTests）

    @Test fun custom_removeRegexMatch() {
        val rule = custom("\\[系统提示.*?\\]", FilterMode.REMOVE)
        val input = "你好[系统提示：请注意语气]今天怎么样"
        val result = ContentFilterService.applyFilters(input, listOf(rule))
        assertFalse(result.contains("[系统提示"))
        assertTrue(result.contains("你好"))
        assertTrue(result.contains("今天怎么样"))
    }

    @Test fun custom_replaceWithText() {
        val rule = custom("坏词", FilterMode.REPLACE, "***")
        val result = ContentFilterService.applyFilters("他说了一个坏词让大家很不开心", listOf(rule))
        assertTrue(result.contains("***"))
        assertFalse(result.contains("坏词"))
    }

    @Test fun unicodeCategoryPatternDoesNotCrash() {
        // \p{So} 符号类：能编译、不崩溃（iOS Unicode特殊字符不崩溃）。
        val rule = custom("\\p{So}", FilterMode.REMOVE)
        val result = ContentFilterService.applyFilters("价格是¥100，温度是25°C", listOf(rule))
        assertTrue(result.isNotEmpty())
    }

    @Test fun longTextDoesNotCrash() {
        val rule = preset(ContentFilterService.PRESET_ACTION_ID)
        val longText = "你好 *动作* ".repeat(1000)
        val result = ContentFilterService.applyFilters(longText, listOf(rule))
        assertFalse(result.contains("*动作*"))
    }

    // MARK: - testFilter / isValidRegex

    @Test fun isValidRegex_rejectsInvalidAndEmpty() {
        assertFalse(ContentFilterService.isValidRegex("[invalid("))
        assertFalse(ContentFilterService.isValidRegex(""))
        assertTrue(ContentFilterService.isValidRegex("\\d+"))
    }

    @Test fun testFilter_invalidRegexReturnsNull() {
        assertNull(ContentFilterService.testFilter("测试文本", "[bad(", FilterMode.REMOVE, ""))
    }

    @Test fun testFilter_removeTrims() {
        // remove 模式结果 trim 首尾空白（对齐 iOS）。
        assertEquals("结果", ContentFilterService.testFilter("  XX结果XX  ", "X", FilterMode.REMOVE, ""))
    }

    @Test fun testFilter_replaceKeepsAndSubstitutes() {
        assertEquals("a-b-c", ContentFilterService.testFilter("a,b,c", ",", FilterMode.REPLACE, "-"))
    }

    // MARK: - loadRules 迁移

    @Test fun loadRules_emptyReturnsDefaults() {
        val rules = ContentFilterService.loadRules("")
        assertEquals(5, rules.size)
        assertTrue(rules.all { it.isPreset })
    }

    @Test fun loadRules_invalidJsonReturnsDefaults() {
        val rules = ContentFilterService.loadRules("{not valid json")
        assertEquals(5, rules.size)
    }

    @Test fun loadRules_roundTripPreservesCustomAndState() {
        val custom = custom("自定义", FilterMode.REPLACE, "X")
        val withCustom = ContentFilterService.defaultPresetRules().map { it.copy(isEnabled = true) } + custom
        val json = ContentFilterService.encodeRules(withCustom)
        val loaded = ContentFilterService.loadRules(json)
        assertEquals(6, loaded.size)
        assertTrue(loaded.all { it.isPreset || it.name == "测试规则" })
        assertTrue(loaded.first { it.id == ContentFilterService.PRESET_ACTION_ID }.isEnabled)
        assertEquals("X", loaded.first { !it.isPreset }.replacement)
    }

    @Test fun loadRules_removesStalePreset() {
        // 含已删的 002「括号旁白」预设 → 迁移时移除。
        val stale = ContentFilterRule(
            id = "00000001-0000-0000-0000-000000000002",
            name = "括号旁白", pattern = "（[^）]*）", isEnabled = true, isPreset = true,
            mode = FilterMode.REMOVE, replacement = "",
        )
        val json = ContentFilterService.encodeRules(listOf(stale) + ContentFilterService.defaultPresetRules())
        val loaded = ContentFilterService.loadRules(json)
        assertFalse(loaded.any { it.id == "00000001-0000-0000-0000-000000000002" })
        assertEquals(5, loaded.size)
    }

    @Test fun loadRules_addsMissingPresetsBeforeCustom() {
        // 只存了 1 个预设 + 1 个自定义 → 补齐其余 4 个预设，且插在自定义之前。
        val onlyOnePreset = ContentFilterService.defaultPresetRules().first()
        val customRule = custom("X")
        val json = ContentFilterService.encodeRules(listOf(onlyOnePreset, customRule))
        val loaded = ContentFilterService.loadRules(json)
        assertEquals(6, loaded.size)
        assertEquals(5, loaded.count { it.isPreset })
        // 所有预设排在自定义规则之前。
        val firstCustomIdx = loaded.indexOfFirst { !it.isPreset }
        assertTrue(loaded.take(firstCustomIdx).all { it.isPreset })
        assertEquals(loaded.size - 1, firstCustomIdx)
    }

    @Test fun loadRules_syncsOutdatedPresetPattern() {
        // 预设 pattern 被旧版本污染 → 迁移时同步回最新。
        val outdated = ContentFilterService.defaultPresetRules().map {
            if (it.id == ContentFilterService.PRESET_ACTION_ID) it.copy(pattern = "OLD_PATTERN") else it
        }
        val json = ContentFilterService.encodeRules(outdated)
        val loaded = ContentFilterService.loadRules(json)
        val action = loaded.first { it.id == ContentFilterService.PRESET_ACTION_ID }
        assertEquals("""\*[^*\n]+\*""", action.pattern)
    }

    @Test fun presetDescriptionsMatchIos() {
        assertEquals("过滤 *叹了口气* 等星号包裹的动作描述", ContentFilterService.presetDescription(ContentFilterService.PRESET_ACTION_ID))
        assertEquals("过滤连续 3 个以上 emoji 的堆砌", ContentFilterService.presetDescription(ContentFilterService.PRESET_EMOJI_ID))
        assertEquals("", ContentFilterService.presetDescription("unknown-id"))
    }
}
