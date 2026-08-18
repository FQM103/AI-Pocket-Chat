package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H3#0 测试网 · MessageSplitter（每条 AI 回复必经的分句算法·行为规格=移植期锁定的 iOS M04 方案）。
 * 断言从规格独立手推：主分隔符切句 → >26 字二次按逗号切（块 ≥8 字）→ ≤6 字短段并入前段 /
 * 双方 <18 且合计 ≤26 合并 → 元叙事行剔除 → 段尾连接标点剥除（…/！/？保留）→ 卡片行 [#En] 完整保留
 * 且不计入 min/max 段数。
 */
class MessageSplitterTest {

    // MARK: - 基础与主分隔符

    @Test
    fun split_empty_returnsEmptyList() {
        assertEquals(emptyList<String>(), MessageSplitter.split(""))
        assertEquals(emptyList<String>(), MessageSplitter.split("   \n "))
    }

    @Test
    fun split_shortPlainText_singleSegmentAsIs() {
        assertEquals(listOf("你好"), MessageSplitter.split("你好"))
    }

    @Test
    fun split_twoLongSentences_splitAtPrimaryDelimiters() {
        // 两句各 15/17 字（均 <18 但合计 32 > 26 合并上限）→ 不合并成两条；
        // 句尾「。」属连接标点被剥，「！」非连接标点保留。
        val input = "今天的天气真是好得不可思议呢。我们一起去公园散步顺便买杯奶茶吧！"
        assertEquals(
            listOf("今天的天气真是好得不可思议呢", "我们一起去公园散步顺便买杯奶茶吧！"),
            MessageSplitter.split(input),
        )
    }

    @Test
    fun split_shortSegments_mergeIntoPrevious() {
        // 「好的。」3 字 ≤6 → 并入前段；合并后整体仅一条，段尾「。」剥除。
        assertEquals(listOf("嗯。好的"), MessageSplitter.split("嗯。好的。"))
    }

    @Test
    fun split_overThreshold_secondarySplitAtCommas_minChunkEight() {
        // 单句 37 字 >26 → 按逗号二次切（每块 ≥8 字）；末块 6 字 ≤6 并回前块；
        // 第一块尾逗号剥除。
        val input = "我觉得这个计划真的很不错呢，不过我们还需要再考虑一下时间安排，你说对不对呀"
        assertEquals(
            listOf("我觉得这个计划真的很不错呢", "不过我们还需要再考虑一下时间安排，你说对不对呀"),
            MessageSplitter.split(input),
        )
    }

    // MARK: - 连接标点剥除

    @Test
    fun split_trailingEllipsis_threePlusDotsPreserved() {
        assertEquals(listOf("让我想想..."), MessageSplitter.split("让我想想..."))
    }

    @Test
    fun split_trailingSingleDot_stripped() {
        assertEquals(listOf("就这样吧"), MessageSplitter.split("就这样吧."))
    }

    // MARK: - 卡片行（[#E1]/[#R1]）

    @Test
    fun split_cardLine_keptIntactAndNotMergedAcross() {
        // 卡片行独立成段且完整；maxSegments=1 只约束非卡片段，但卡片隔断的两段文本
        // 不相邻 → 无法合并（规格：绝不跨卡片合并），三段保留。
        val input = "给你安排好了\n[#E1] 明天下午三点 牙医预约\n记得准时哦"
        val out = MessageSplitter.split(input, maxSegments = 1)
        assertEquals(3, out.size)
        assertEquals("[#E1] 明天下午三点 牙医预约", out[1])
    }

    @Test
    fun split_longSentenceWithCalendarRef_notSecondarySplit() {
        // 含 [#E1] 引用的长句不做二次拆分（卡片引用必须完整留在一条里）。
        val input = "这一条带日历引用的句子特别特别长所以平时一定会被拆开的，[#E1] 但现在不可以"
        val out = MessageSplitter.split(input)
        assertEquals(1, out.count { it.contains("[#E1]") })
    }

    // MARK: - min/max 段数钳位

    @Test
    fun split_maxSegments_mergesShortestAdjacentPair() {
        // 三条 ≥18 字长句（不触发自然合并），max=2 → 合并「合计最短」的相邻对（前两句）。
        val s1 = "这第一句话特意写得有十八个字那么长呢。"
        val s2 = "这第二句话也特意写得超过十八个字了哦。"
        val s3 = "这第三句话当然也是超过十八个字的长句子。"
        val out = MessageSplitter.split(s1 + s2 + s3, maxSegments = 2)
        assertEquals(2, out.size)
        assertTrue(out[0].contains("第一句话") && out[0].contains("第二句话"))
        assertTrue(out[1].contains("第三句话"))
    }

    @Test
    fun split_minSegments_splitsMergedLongSegmentAtMidpointDelimiter() {
        // 9+15 字两句先被合并（双方 <18 且合计 24 ≤26）成 24 字单段；min=2 → 从中点附近的
        // 主分隔符重新切开，两段尾「。」剥除。
        val input = "今天玩得真开心呀。下次我们再一起去那家店好不好。"
        assertEquals(
            listOf("今天玩得真开心呀", "下次我们再一起去那家店好不好"),
            MessageSplitter.split(input, minSegments = 2),
        )
    }

    @Test
    fun split_minSegments_noDelimiter_cannotSplit_staysOne() {
        // 无任何主分隔符 → min 钳位无处下刀，保持 1 条（规格：宁可不拆不造句中断点）。
        val input = "这是一段没有任何标点但是长度超过二十个字符的完整内容哦"
        assertEquals(1, MessageSplitter.split(input, minSegments = 2).size)
    }

    // MARK: - 元叙事过滤

    @Test
    fun split_metaNarrationLine_dropped() {
        // 叙事行 ≥18 字（短内容行不会被并进 meta 行），时间叙事段被整段剔除。
        val input = "（深夜 23:45，她翻了个身又翻了个身）\n今晚怎么也睡不着呢"
        assertEquals(listOf("今晚怎么也睡不着呢"), MessageSplitter.split(input))
    }

    @Test
    fun split_allMetaContent_fallsBackToTrimmedOriginal() {
        // 全部内容都被过滤 → 兜底返回原文（绝不吞掉整条回复）。
        val input = "（晚上 9:30，发来一条语音）"
        assertEquals(listOf(input), MessageSplitter.split(input))
    }

    // MARK: - isQuoteOnlyLine / isMetaLine（被 ReplyParser.sanitize 复用的行过滤器）

    @Test
    fun isQuoteOnlyLine_punctuationOnly_true() {
        assertTrue(MessageSplitter.isQuoteOnlyLine("！！！"))
        assertTrue(MessageSplitter.isQuoteOnlyLine("“”…"))
        assertTrue(MessageSplitter.isQuoteOnlyLine("   "))
        assertFalse(MessageSplitter.isQuoteOnlyLine("好！"))
    }

    @Test
    fun isMetaLine_bracketedTimestamp_true() {
        assertTrue(MessageSplitter.isMetaLine("[2024年5月1日 14:30]"))
        assertTrue(MessageSplitter.isMetaLine("[2024-5-1 14:30]"))
    }

    @Test
    fun isMetaLine_truncatedBracketFragment_true() {
        // ≤30 字、以 [ / 【 开头且无闭合 → 截断残片。
        assertTrue(MessageSplitter.isMetaLine("【今日任务"))
        assertFalse(MessageSplitter.isMetaLine("【今日任务】去公园"))
    }

    @Test
    fun isMetaLine_metaKeywordLine_true() {
        // 批2 2-1 新语义：裸整短语（日常聊天不会自然出现）+ 括号包裹舞台指示 + 行首标签式。
        assertTrue(MessageSplitter.isMetaLine("（她发来一条语音）"))
        assertTrue(MessageSplitter.isMetaLine("她发来一条语音，带着笑意"))
        assertTrue(MessageSplitter.isMetaLine("（镜头拉近，她的眼眶红了）"))
        assertTrue(MessageSplitter.isMetaLine("（背景音：雨声渐大）"))
        assertTrue(MessageSplitter.isMetaLine("旁白：她转过身去"))
        assertTrue(MessageSplitter.isMetaLine("镜头：缓缓拉远"))
    }

    @Test
    fun isMetaLine_dailyWordsNoLongerFalsePositive() {
        // 批2 2-1（2026-07-02 过审）：旧裸子串匹配把含「在线/镜头/背景音/输入中」的正常回复整行吃掉——
        // "在线下"含"在线"是最高频误杀。以下全部必须存活。
        assertFalse(MessageSplitter.isMetaLine("我们在线下见吧"))
        assertFalse(MessageSplitter.isMetaLine("我一直在线呀，随时找我"))
        assertFalse(MessageSplitter.isMetaLine("新买的镜头拍夜景真不错"))
        assertFalse(MessageSplitter.isMetaLine("这首歌的背景音乐好好听"))
        assertFalse(MessageSplitter.isMetaLine("写小说的旁白部分最难"))
        assertFalse(MessageSplitter.isMetaLine("在线等，挺急的"))
        assertFalse(MessageSplitter.isMetaLine("我正在输入中文地址呢"))
        // 裸叙事句「对方正在输入中」按新取舍放行（宁漏不误杀；括号包裹时仍会被删）。
        assertFalse(MessageSplitter.isMetaLine("对方正在输入中"))
        assertTrue(MessageSplitter.isMetaLine("（对方正在输入中）"))
    }

    @Test
    fun isMetaLine_dsmlResidue_true() {
        assertTrue(MessageSplitter.isMetaLine("DSML"))
        assertTrue(MessageSplitter.isMetaLine("suggest_offline_meeting"))
        assertFalse(MessageSplitter.isMetaLine("normal text"))
    }

    @Test
    fun isMetaLine_plainChat_false() {
        assertFalse(MessageSplitter.isMetaLine("今晚八点见？"))
        assertFalse(MessageSplitter.isMetaLine(""))
    }
}

/**
 * V10 源头分段权威化（2026-07-08·契约 FABLE5_CHAT_REVERSE_LIST_PROPOSAL §9）：模型用空行/换行分好的段
 * 照单全收——不逗号二次切、不短段强合、不凑 minSegments;只留元信息过滤/卡片独立/maxSegments 安全钳。
 * 断言从规格独立反推。
 */
class MessageSplitterTrustedSegmentationTest {

    @Test
    fun blankLineSegments_takenVerbatim_noSecondarySplit() {
        // 空行分段:第二段 >26 字且含逗号——旧算法会按逗号二次切,权威模式必须整段保留。
        val text = "今天路过那家咖啡店啦\n\n他们家新出的桂花拿铁真的很好喝，我差点没忍住买第二杯，你下次一定要试试"
        val result = MessageSplitter.split(text, maxSegments = 6, minSegments = 2)
        assertEquals(2, result.size)
        assertTrue(result[1].contains("差点没忍住"))
    }

    @Test
    fun singleNewlineSegments_lineMode_shortReactionKept() {
        // 单换行逐行分段:2 字短句旧算法会被强合(≤6 字合并),权威模式保留为独立气泡(texting 短反应)。
        val text = "真的假的\n那也太离谱了吧\n笑死"
        val result = MessageSplitter.split(text, maxSegments = 6, minSegments = 2)
        assertEquals(listOf("真的假的", "那也太离谱了吧", "笑死"), result)
    }

    @Test
    fun noNewline_fallsBackToLegacySplitter() {
        // 无任何换行=模型没分段 → 旧算法兜底(标点拆分照旧)。
        val text = "今天天气很好。我们出去走走吧。顺便买杯咖啡。"
        val result = MessageSplitter.split(text, maxSegments = 6, minSegments = 2)
        assertTrue(result.size >= 2)
    }

    @Test
    fun trustedMode_noMinSegmentPadding() {
        // 模型只分了 2 段、minSegments=3:权威模式不凑数强拆。
        val text = "嗯嗯我在呢\n\n刚刚在给 Milk 梳毛来着"
        val result = MessageSplitter.split(text, maxSegments = 6, minSegments = 3)
        assertEquals(2, result.size)
    }

    @Test
    fun trustedMode_maxSegmentsSafetyCapStillApplies() {
        // 模型分了 5 段、maxSegments=3:安全钳生效,收敛到 ≤3 条(不丢内容)。
        val text = "一段\n\n第二段内容\n\n第三段内容\n\n第四段内容\n\n第五段内容"
        val result = MessageSplitter.split(text, maxSegments = 3, minSegments = 0)
        assertTrue(result.size <= 3)
        assertTrue(result.joinToString("").contains("第五段内容"))
    }

    @Test
    fun trustedMode_cardLineStandsAlone() {
        // 卡片行恒独立成段,不与文本合并。
        val text = "帮你记上啦\n\n[#E1]\n\n明天见"
        val result = MessageSplitter.split(text, maxSegments = 6, minSegments = 0)
        assertEquals(3, result.size)
        assertEquals("[#E1]", result[1])
    }

    @Test
    fun trustedMode_intraBlockNewlinesPreserved() {
        // 空行分段模式下,块内单换行保留为气泡内换行(模型的排版意图)。
        val text = "给你写了两句:\n第一句\n第二句\n\n怎么样"
        val result = MessageSplitter.split(text, maxSegments = 6, minSegments = 0)
        assertEquals(2, result.size)
        assertTrue(result[0].contains("第一句\n第二句"))
    }

    @Test
    fun trustedMode_punctuationOnlySegment_gluedToPrevious() {
        // 纯标点段(!!!)回贴上一条,不丢字不独立成泡。
        val text = "他真的这么说了\n！！！\n我都惊了"
        val result = MessageSplitter.split(text, maxSegments = 6, minSegments = 0)
        assertEquals(2, result.size)
        assertTrue(result[0].endsWith("！！！"))
    }
}
