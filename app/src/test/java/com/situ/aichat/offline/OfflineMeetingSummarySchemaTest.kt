package com.situ.aichat.offline

import com.situ.aichat.offline.OfflineMeetingSummarySchema.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 见面摘要 schema 纯函数看门（提示词优化·图纸 2026-07-15-见面摘要总结提示词优化.md §4/§7）：断言从规格独立反推，非照搬实现。
 * - parseAndValidate：合法全字段 / 缺 highlights 缺省空数组 / summary 边界（39 拒·40 过·401 拒）/
 *   含线下标签拒（[对话] / 【）/ **summary、highlights、promises 任一含「用户」拒（日记体硬闸）** /
 *   mood 非法置空·合法保留 / highlights 超 3 截断 · 超 60 截断 / JSONExtractor 包裹可解；
 * - buildUserPrompt：人称统一+记录名字化两变体逐字全串锁（有昵称 A / 无昵称 B / 昵称=「用户」落 B）+ highlights 人称示范 + previousError 追加。
 */
class OfflineMeetingSummarySchemaTest {

    private fun success(response: String): OfflineMeetingSummarySchema.MeetingSummaryDraft {
        val r = OfflineMeetingSummarySchema.parseAndValidate(response)
        assertTrue("期望 Success 实为 $r", r is ParseResult.Success)
        return (r as ParseResult.Success).draft
    }

    private fun failure(response: String): String {
        val r = OfflineMeetingSummarySchema.parseAndValidate(response)
        assertTrue("期望 Failure 实为 $r", r is ParseResult.Failure)
        return (r as ParseResult.Failure).error
    }

    // ── parseAndValidate · 正常路径 ──

    @Test
    fun validFullJson_parsesAllFields() {
        val summary = "这".repeat(60)
        val d = success(
            """
            {
              "summary": "$summary",
              "highlights": ["她笑了", "点了拿铁"],
              "promises": ["下次去看海"],
              "mood": "warm"
            }
            """.trimIndent(),
        )
        assertEquals(summary, d.summary)
        assertEquals(listOf("她笑了", "点了拿铁"), d.highlights)
        assertEquals(listOf("下次去看海"), d.promises)
        assertEquals("warm", d.mood)
    }

    @Test
    fun missingHighlights_defaultsToEmptyList() {
        val summary = "温".repeat(50)
        val d = success("""{"summary": "$summary", "mood": "sweet"}""")
        assertEquals(emptyList<String>(), d.highlights)
        assertEquals(emptyList<String>(), d.promises)
        assertEquals("sweet", d.mood)
    }

    @Test
    fun summaryExactly40Chars_passes() {
        val summary = "温".repeat(40)
        assertEquals(summary, success("""{"summary": "$summary"}""").summary)
    }

    @Test
    fun highlightsOverThree_truncatedToThree() {
        val summary = "温".repeat(50)
        val d = success(
            """{"summary": "$summary", "highlights": ["a", "b", "c", "d", "e"]}""",
        )
        assertEquals(listOf("a", "b", "c"), d.highlights)
    }

    @Test
    fun highlightOverSixtyChars_truncatedToSixty() {
        val summary = "温".repeat(50)
        val longHighlight = "细".repeat(80)
        val d = success("""{"summary": "$summary", "highlights": ["$longHighlight"]}""")
        assertEquals(1, d.highlights.size)
        assertEquals(60, d.highlights[0].length)
    }

    @Test
    fun illegalMood_setToEmpty() {
        val summary = "温".repeat(50)
        assertEquals("", success("""{"summary": "$summary", "mood": "excited"}""").mood)
    }

    @Test
    fun missingMood_setToEmpty() {
        val summary = "温".repeat(50)
        assertEquals("", success("""{"summary": "$summary"}""").mood)
    }

    @Test
    fun jsonExtractor_unwrapsFencedAndSurroundingProse() {
        val summary = "温".repeat(50)
        val wrapped = "好的，这是结果：\n```json\n{\"summary\": \"$summary\", \"mood\": \"neutral\"}\n```\n希望有用"
        val d = success(wrapped)
        assertEquals(summary, d.summary)
        assertEquals("neutral", d.mood)
    }

    // ── parseAndValidate · 错误路径 ──

    @Test
    fun notJson_fails() {
        failure("这根本不是 JSON")
    }

    @Test
    fun missingSummary_fails() {
        failure("""{"mood": "warm"}""")
    }

    @Test
    fun summary39Chars_rejected() {
        val summary = "温".repeat(39)
        failure("""{"summary": "$summary"}""")
    }

    @Test
    fun summaryOver400Chars_rejected() {
        val summary = "温".repeat(401)
        failure("""{"summary": "$summary"}""")
    }

    @Test
    fun summaryContainsOfflineTag_rejected() {
        val summary = "温".repeat(40) + "[对话]你好"
        val err = failure("""{"summary": "$summary"}""")
        assertTrue("错误应点名标签: $err", err.contains("[对话]"))
    }

    @Test
    fun summaryContainsLenticularBracket_rejected() {
        val summary = "温".repeat(40) + "【见面】"
        failure("""{"summary": "$summary"}""")
    }

    // ── parseAndValidate · 日记体硬闸（微图纸 §4）──

    @Test
    fun summaryContainsYonghu_rejected() {
        val summary = "温".repeat(40) + "用户很开心"
        val err = failure("""{"summary": "$summary"}""")
        assertTrue("错误应提示换称呼: $err", err.contains("「你」"))
    }

    @Test
    fun highlightContainsYonghu_rejected() {
        val summary = "温".repeat(50)
        failure("""{"summary": "$summary", "highlights": ["用户给她买了烤梨"]}""")
    }

    @Test
    fun promiseContainsYonghu_rejected() {
        val summary = "温".repeat(50)
        failure("""{"summary": "$summary", "promises": ["用户答应下次去看海"]}""")
    }

    // ── 「用户」豁免复合词（微图纸 §7）：职业/领域词放行，裸称呼仍拦 ──

    @Test
    fun summaryWithExemptCompound_passes() {
        val summary = "温".repeat(40) + "听你讲了用户运营的新活动"
        assertEquals(summary, success("""{"summary": "$summary"}""").summary)
    }

    @Test
    fun highlightWithExemptCompound_passes() {
        val summary = "温".repeat(50)
        val d = success("""{"summary": "$summary", "highlights": ["聊到用户增长的新玩法很起劲"]}""")
        assertEquals(listOf("聊到用户增长的新玩法很起劲"), d.highlights)
    }

    @Test
    fun exemptCompoundAtEndOfSentence_passes() {
        val summary = "温".repeat(40) + "你最近在做用户画像"
        assertEquals(summary, success("""{"summary": "$summary"}""").summary)
    }

    @Test
    fun mixedCompoundAndBareYonghu_stillRejected() {
        val summary = "温".repeat(40) + "聊了用户体验，用户很开心"
        failure("""{"summary": "$summary"}""")
    }

    @Test
    fun bareYonghuAtEndOfText_rejected() {
        val summary = "温".repeat(40) + "他是个好用户"
        failure("""{"summary": "$summary"}""")
    }

    @Test
    fun compoundImmediatelyFollowedByBareYonghu_rejected() {
        // 直击 i+2 续扫路径（复核二 🔵-2）：「用户运营」放行后紧跟的裸「用户」必须被第二轮扫描抓到。
        val summary = "温".repeat(40) + "聊用户运营用户都爱听"
        failure("""{"summary": "$summary"}""")
    }

    @Test
    fun pseudoCompoundPrefix_rejected() {
        // 伪复合前缀（复核二 🔵-2）：「用户体检」前两字同「用户体验」但不在名单，逐字锁不做前缀模糊。
        val summary = "温".repeat(40) + "陪你去做用户体检"
        failure("""{"summary": "$summary"}""")
    }

    // ── buildUserPrompt · 人称统一+记录名字化两变体逐字锁（图纸 §4·2026-07-15）──

    /**
     * 展开两变体的完整期望串（逐字·独立反推·重新打字为字面量）。变体间**唯一差异**三处，作字面量入参：
     * [meetLine]（首行见面陈述）/ [addressRule]（写作要求里的对方称呼规则）/ [highlightExample]（highlights 人称示范）；
     * 其余（见面事实块、对话记录段居中、日记体指令、JSON 契约）两变体逐字共用。
     */
    private fun expectedPrompt(meetLine: String, addressRule: String, highlightExample: String) = listOf(
        "你就是角色「测试角色」本人。$meetLine，这是你们真实的相处。",
        "见面事实（系统已记录，不用复述）：",
        "- 时间：2026-04-18 15:30 至 16:50（约1小时20分钟）",
        "- 地点：公园；活动：散步；共 42 轮对话",
        "",
        "## 这次见面的对话记录",
        "用户：你好\n角色：嗨",
        "",
        "现在请你以第一人称、像睡前写日记一样回忆这次见面。写作要求：用「我」指代你自己，$addressRule，绝对不要出现「用户」这个词；自由中文句子，不用列表、不用标签、不用 markdown。",
        "",
        "只输出一个 JSON 对象，不要任何其他文字：",
        "{",
        "  \"summary\": \"100-200 字一段话，日记口吻：我们做了什么、聊了什么、最打动我的瞬间、我们的情绪基调。\",",
        "  \"highlights\": [\"最多 3 条难忘的具体细节，每条从「我」的视角写完整一句、带上是谁（我/名字），例：「$highlightExample」；没有就给空数组\"],",
        "  \"promises\": [\"最多 3 条这次见面里许下的约定、说好的下次、或没说完的话头；没有就给空数组\"],",
        "  \"mood\": \"从 warm/sweet/melancholic/awkward/neutral 里选一个最贴切的\"",
        "}",
    ).joinToString("\n")

    private fun buildPrompt(userName: String, previousError: String? = null) =
        OfflineMeetingSummarySchema.buildUserPrompt(
            characterName = "测试角色",
            startText = "2026-04-18 15:30",
            endText = "16:50",
            durationText = "1小时20分钟",
            location = "公园",
            activity = "散步",
            messageCount = 42,
            conversationRecord = "用户：你好\n角色：嗨",
            userName = userName,
            previousError = previousError,
        )

    // T1-1（E1）：有昵称走变体 A——设定/称呼/示范全用「阿泽」、称呼去「你」化、highlights 带人称示范。
    @Test
    fun buildUserPrompt_withNickname_matchesVariantAByteForByte() {
        val expected = expectedPrompt(
            meetLine = "刚才你和阿泽线下见了一面",
            addressRule = "提到对方就写「阿泽」，不要用「你」",
            highlightExample = "阿泽记得我怕辣，悄悄跟店员说了不要辣",
        )
        assertEquals(expected, buildPrompt(userName = "阿泽"))
    }

    // T1-2（E2）：无昵称走变体 B——设定「和对方」、正文称呼「你」、highlights 示范用「你」。
    @Test
    fun buildUserPrompt_blankNickname_matchesVariantBByteForByte() {
        val expected = expectedPrompt(
            meetLine = "刚才你和对方线下见了一面",
            addressRule = "提到对方就写「你」",
            highlightExample = "你记得我怕辣，悄悄跟店员说了不要辣",
        )
        assertEquals(expected, buildPrompt(userName = ""))
    }

    // T1-3（E3）：昵称恰为「用户」→ 与无昵称同（!= "用户" 判定落变体 B）。
    @Test
    fun buildUserPrompt_nicknameLiterallyYonghu_fallsToVariantB() {
        assertEquals(buildPrompt(userName = ""), buildPrompt(userName = "用户"))
    }

    // T1-4（E1·新增）：变体 A 含 highlights 人称示范提示，且对方称呼去「你」化（不残留旧「或「你」」允许项）。
    @Test
    fun buildUserPrompt_withNickname_highlightsCarryPersonExample() {
        val prompt = buildPrompt(userName = "阿泽")
        assertTrue("highlights 应含人称示范提示", prompt.contains("带上是谁（我/名字）"))
        assertTrue("称呼规则应为去「你」化", prompt.contains("提到对方就写「阿泽」，不要用「你」"))
        assertTrue("不应残留旧「或「你」」允许对方称呼", !prompt.contains("写「阿泽」或「你」"))
    }

    // T1-5（E4）：previousError 非空 → 末尾追加校验反馈行（N-3 不变）。
    @Test
    fun buildUserPrompt_appendsPreviousError() {
        val out = buildPrompt(userName = "阿泽", previousError = "summary 太短")
        assertTrue(out.endsWith("\n\n上一次输出未通过校验：summary 太短。请修正后重新只输出 JSON。"))
    }
}
