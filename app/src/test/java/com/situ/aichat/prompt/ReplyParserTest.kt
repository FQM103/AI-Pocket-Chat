package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H3#0 测试网 · ReplyParser（每条 AI 回复必经的解析清洗链·行为规格=移植期锁定的 iOS Parsing 语义）。
 * 覆盖：parseMood 取最后标记+色名归一化 / extractPetSpeech 30 字上限 / 思考标签（变体+大小写+未闭合）/
 * DSML/日历指令块 / 线下与 MiniMax 标签的剥离与保留开关 / 括号叙事（全角 21+·半角含中文 20+·纯英文不剥
 * =`\p{script=Han}` 双引擎路径）/ 系统指令泄漏行 / 角色名前缀（嵌套）/ 复读折叠（≥8 次且 ≥80%）。
 */
class ReplyParserTest {

    // MARK: - parseMood

    @Test
    fun parseMood_extractsAndStripsTag() {
        val r = ReplyParser.parseMood("[mood:😊|green|开心] 你好呀")
        assertEquals("你好呀", r.cleanText)
        assertEquals("green", r.colorName)
        assertEquals("开心", r.text)
        assertEquals("😊", r.emoji)
    }

    @Test
    fun parseMood_multipleTags_lastWins() {
        val r = ReplyParser.parseMood("[mood:😀|green|高兴]中间[情绪:😢|red|难过]结尾")
        assertEquals("难过", r.text)
        assertEquals("red", r.colorName)
        assertEquals("😢", r.emoji)
        assertFalse(r.cleanText.contains("mood"))
    }

    @Test
    fun parseMood_unknownColor_normalizesToGreen() {
        assertEquals("green", ReplyParser.parseMood("[mood:😶|purple|平静]").colorName)
        assertEquals("yellow", ReplyParser.parseMood("[mood:😐|YELLOW|一般]").colorName)
    }

    @Test
    fun parseMood_noTag_emptyMoodGreenDefault() {
        val r = ReplyParser.parseMood("纯文本")
        assertEquals("纯文本", r.cleanText)
        assertEquals("green", r.colorName)
        assertEquals("", r.text)
        assertEquals("", r.emoji)
    }

    // MARK: - extractPetSpeech

    @Test
    fun extractPetSpeech_extractsAndCleans() {
        val (cleaned, speech) = ReplyParser.extractPetSpeech("[PET:汪汪！] 主人好")
        assertEquals("主人好", cleaned)
        assertEquals("汪汪！", speech)
    }

    @Test
    fun extractPetSpeech_overThirtyChars_droppedButStillCleaned() {
        val long = "汪".repeat(31)
        val (cleaned, speech) = ReplyParser.extractPetSpeech("[PET:$long]你好")
        assertEquals("你好", cleaned)
        assertNull(speech)
    }

    @Test
    fun extractPetSpeech_noTag_passthrough() {
        val (cleaned, speech) = ReplyParser.extractPetSpeech("没有宠物")
        assertEquals("没有宠物", cleaned)
        assertNull(speech)
    }

    // MARK: - 思考标签

    @Test
    fun stripTags_thinkBlock_removed() {
        assertEquals("你好", ReplyParser.stripInternalAssistantTags("<think>内部推理</think>你好"))
    }

    @Test
    fun stripTags_unclosedThink_strippedToEnd() {
        // 未闭合 → \z 兜底吞到结尾（宁可丢尾巴不让 CoT 泄进气泡）。
        assertEquals("前文", ReplyParser.stripInternalAssistantTags("前文<THINK>没有闭合的推理"))
    }

    @Test
    fun stripTags_allThinkingVariants_removed() {
        val variants = listOf(
            "<thinking>x</thinking>", "<|think|>x<|/think|>",
            "<thought>x</thought>", "<reasoning>x</reasoning>",
        )
        for (v in variants) {
            assertEquals("v=$v", "好", ReplyParser.stripInternalAssistantTags("${v}好"))
        }
    }

    // MARK: - 内部标签 / DSML / 日历指令块

    @Test
    fun stripTags_calendarActionBlock_removed() {
        val input = "好的[CALENDAR_ACTION]{json}[/CALENDAR_ACTION]，记下了"
        assertEquals("好的，记下了", ReplyParser.stripInternalAssistantTags(input))
    }

    @Test
    fun stripTags_dsmlFunctionCallsBlock_removed() {
        val input = "<DSML|function_calls>调用内容</DSML|function_calls>嗯"
        assertEquals("嗯", ReplyParser.stripInternalAssistantTags(input))
    }

    @Test
    fun stripTags_offlineInviteTokens_removed() {
        assertEquals("走吧", ReplyParser.stripInternalAssistantTags("[offline_invite|咖啡|楼下]走吧[offline_end]"))
    }

    // MARK: - 线下叙事 / MiniMax 标签的剥离与保留开关

    @Test
    fun stripTags_offlineNarrativeTags_strippedByDefault_preservedWithFlag() {
        val input = "[叙述]她抬头看你[/叙述][场景：咖啡馆]"
        assertEquals("她抬头看你", ReplyParser.stripInternalAssistantTags(input))
        assertEquals(input, ReplyParser.stripInternalAssistantTags(input, preserveOfflineTags = true))
    }

    @Test
    fun stripTags_miniMaxVoiceTags_strippedByDefault_preservedWithFlag() {
        val input = "(laughs)好啦<#0.5#>别闹"
        assertEquals("好啦别闹", ReplyParser.stripInternalAssistantTags(input))
        assertEquals(input, ReplyParser.stripInternalAssistantTags(input, preserveMiniMaxVoiceTags = true))
        // 非白名单语气词不剥（中文拟声括号是正文）。
        assertEquals("(大笑)好啦", ReplyParser.stripMiniMaxVoiceTags("(大笑)好啦"))
    }

    // MARK: - 括号叙事剥离（\p{script=Han} 双引擎路径）

    @Test
    fun narration_fullWidthParen_21PlusChars_stripped() {
        val inside = "她轻轻把头靠在窗边看着远处发呆了很久很久" // 20 字 → 含括号内 21+ 规则用 21 字版
        val strippedCase = "（${inside}呢）好困哦"
        assertEquals("好困哦", ReplyParser.stripAssistantParentheticalNarration(strippedCase))
    }

    @Test
    fun narration_fullWidthParen_shortAside_kept() {
        val keep = "（小声）我想你了"
        assertEquals(keep, ReplyParser.stripAssistantParentheticalNarration(keep))
    }

    @Test
    fun narration_halfWidthWithChinese_20Plus_stripped() {
        val input = "(she slowly 把头靠在了窗边上发着呆很久很久)晚安"
        assertEquals("晚安", ReplyParser.stripAssistantParentheticalNarration(input))
    }

    @Test
    fun narration_halfWidthPureAscii_neverStripped() {
        // 半角括号必须含中文才算叙事（纯英文括号可能是正文，如表情/注音）。
        val input = "(this is a long english aside over twenty chars)okay"
        assertEquals(input, ReplyParser.stripAssistantParentheticalNarration(input))
    }

    // MARK: - 系统指令泄漏

    @Test
    fun systemDirective_knownHeadersAndPhrases_detected() {
        assertTrue(ReplyParser.isSystemDirectiveLine("【系统提示】保持人设"))
        assertTrue(ReplyParser.isSystemDirectiveLine("请始终以该角色的身份和语气回复"))
        assertTrue(ReplyParser.isSystemDirectiveLine("Always reply in this character's identity"))
        assertFalse(ReplyParser.isSystemDirectiveLine("今天想去哪玩？"))
    }

    @Test
    fun decontaminate_stripsLegacyTimestampHeaderAndDirectiveLines() {
        val input = "[2024-5-1 14:30]\n你好\n保持角色一致性\n再见"
        assertEquals("你好\n再见", ReplyParser.decontaminateAssistantContent(input))
    }

    @Test
    fun decontaminate_truncatedBracketFragmentOnly_becomesEmpty() {
        assertEquals("", ReplyParser.decontaminateAssistantContent("【今日安排"))
    }

    // MARK: - sanitizeAssistantResponse 全链

    @Test
    fun sanitize_fullChain_thinkMoodNamePrefixDirectiveMeta() {
        val input = "<think>推理</think>小七: 你好呀\n【系统提示】保持人设\n[2024年5月1日 14:30]\n小七：小七：今天去哪"
        val out = ReplyParser.sanitizeAssistantResponse(input, characterName = "小七")
        assertEquals("你好呀\n今天去哪", out)
    }

    @Test
    fun sanitize_crlfNormalized_blankLinesDropped() {
        assertEquals("第一句\n第二句", ReplyParser.sanitizeAssistantResponse("第一句\r\n\r\n第二句"))
    }

    @Test
    fun sanitize_preserveOfflineTags_keepsMetaLinesForImmersiveView() {
        // 线下沉浸模式：meta 行过滤被旁路（叙事是剧场内容），仅系统指令行仍剔除。
        val input = "[叙述]她坐下了\n【系统提示】保持人设"
        assertEquals("[叙述]她坐下了", ReplyParser.sanitizeAssistantResponse(input, preserveOfflineTags = true))
    }

    // MARK: - 复读折叠（经 sanitize 入口·≥8 次且占比 ≥80%）

    @Test
    fun repetition_eightPlusSamePhrase_collapsedToOne() {
        val phrase = "真的假的呀"
        val input = (List(9) { phrase } + "别闹了").joinToString("，")
        assertEquals("真的假的呀，别闹了", ReplyParser.sanitizeAssistantResponse(input))
    }

    @Test
    fun repetition_allSamePhrase_collapsedToSingle() {
        val input = List(10) { "我真的好喜欢你" }.joinToString("，")
        assertEquals("我真的好喜欢你", ReplyParser.sanitizeAssistantResponse(input))
    }

    @Test
    fun repetition_belowEightOccurrences_untouched() {
        // 7 次 < 8 次阈值（中文叠词保护）→ 不折叠；总长 58 > 50 确保走到计数守卫而非长度守卫。
        val input = (List(7) { "哈哈哈哈哈哈" } + "今天真的太好笑了吧").joinToString("，")
        assertEquals(input, ReplyParser.sanitizeAssistantResponse(input))
    }

    @Test
    fun repetition_shortTextUnderFiftyChars_untouched() {
        val input = "好，好，好，好，好，好，好，好，好"
        assertEquals(input, ReplyParser.sanitizeAssistantResponse(input))
    }

    // MARK: - 极简回复兜底（整条纯标点·表达性极简回复要原样保留成一条·非线下）

    @Test
    fun minimalReply_loneQuestionMark_preserved() {
        // 修复前：quote-only 行过滤把整条清空 → 消息凭空消失。现在原样保留成一条（= 用户诉求）。
        assertEquals("？", ReplyParser.sanitizeAssistantResponse("？"))
        assertEquals("?", ReplyParser.sanitizeAssistantResponse("?"))
    }

    @Test
    fun minimalReply_repeatedAndOtherExpressivePunct_preserved() {
        assertEquals("？？？", ReplyParser.sanitizeAssistantResponse("？？？"))
        assertEquals("...", ReplyParser.sanitizeAssistantResponse("..."))
        assertEquals("。。。", ReplyParser.sanitizeAssistantResponse("。。。"))
        assertEquals("！", ReplyParser.sanitizeAssistantResponse("！"))
        assertEquals("?!", ReplyParser.sanitizeAssistantResponse("?!"))
    }

    @Test
    fun minimalReply_strayQuoteJunk_stillDropped() {
        // 纯引号/括号（不含表达性标点）= 真垃圾，仍清空（核心规则也禁模型输出孤立引号）。
        assertEquals("", ReplyParser.sanitizeAssistantResponse("\""))
        assertEquals("", ReplyParser.sanitizeAssistantResponse("「」"))
        assertEquals("", ReplyParser.sanitizeAssistantResponse("“”"))
    }

    @Test
    fun minimalReply_normalReplyUnaffected() {
        // 普通短回复本就不走 quote-only 过滤，兜底不介入。
        assertEquals("在吗？", ReplyParser.sanitizeAssistantResponse("在吗？"))
        assertEquals("哈哈哈哈", ReplyParser.sanitizeAssistantResponse("哈哈哈哈"))
    }

    @Test
    fun minimalReply_quoteOnlyLineWithinMultiline_stillDropped() {
        // 多行里夹一行孤立引号：保留正文行、剔除引号行（兜底仅在整条被清空时才触发，不改此行为）。
        assertEquals("你好\n在吗", ReplyParser.sanitizeAssistantResponse("你好\n\"\n在吗"))
    }

    @Test
    fun minimalReply_offlineBranch_questionMarkUnaffected() {
        // 线下分支本就不过滤 quote-only，"？" 正常保留；兜底受 !preserveOfflineTags 守卫，不重复介入。
        assertEquals("？", ReplyParser.sanitizeAssistantResponse("？", preserveOfflineTags = true))
    }

    // MARK: - 历史时间分割线 echo 剥除（防 LLM 模仿 HistoryTimeDivider 注入的【时间·】分割线）

    @Test
    fun dividerEcho_strippedFromReply() {
        // LLM 模仿上下文里的【时间·】分割线 → 整行剥掉，正文留存（防穿帮进气泡 / 入库）。
        val out = ReplyParser.sanitizeAssistantResponse("【时间 · 今天 00:15】\n现在快十二点啦")
        assertFalse("分割线 echo 应被剥除：$out", out.contains("【时间"))
        assertTrue(out.contains("现在快十二点啦"))
    }

    @Test
    fun dividerEcho_strippedViaStripInternalTags() {
        val out = ReplyParser.stripInternalAssistantTags("前\n【时间 · 昨天 14:56】\n后")
        assertFalse(out.contains("【时间"))
        assertTrue(out.contains("前") && out.contains("后"))
    }

    @Test
    fun nonDividerBracket_notStripped() {
        // 句中的【…】、别的【标签】、或【时间·…】后还有正文 → 不是整行分割线，不误伤。
        val a = ReplyParser.stripInternalAssistantTags("【正经事】我们聊聊吧")
        assertTrue("普通【标签】不应被剥：$a", a.contains("【正经事】我们聊聊吧"))
        val b = ReplyParser.stripInternalAssistantTags("【时间 · 今天】后面还有话")
        assertTrue("非整行的【时间·…】不应被剥：$b", b.contains("后面还有话"))
    }
}
