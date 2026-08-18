package com.situ.aichat.moments

import com.situ.aichat.data.local.entity.CharacterEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with iOS `generateCommentContent` prompt assembly (Services/MomentGenerationActor+Content.swift).
 * Sentinel strings pin section order, optional-section gating, AI-AI note, photo blind/vision, reply
 * target vs direct-write, the prior-comments cap, and `scheduleLine`.
 */
class MomentCommentPromptBuilderTest {

    private fun cs() = MomentCommentPromptStrings(
        gender = "GENDER:%1\$s", occupation = "OCC:%1\$s", backstory = "BACK:%1\$s",
        speakingStyle = "STYLE:%1\$s", catchphrases = "CATCH:%1\$s", interests = "INTERESTS:%1\$s",
        requirementsHeader = "REQHDR",
        intro = "INTRO:%1\$s|%2\$s", friendPosted = "FRIEND:%1\$s", emotionTone = "EMOTION",
        aiAi = "AIAI", photosBlind = "BLIND:%1\$d", photosVision = "VISION:%1\$d",
        othersHeader = "OTHERSHDR", othersReact = "OTHERSREACT", saidToYou = "SAID:%1\$s|%2\$s",
        replyInstruction = "REPLYINSTR", writeInstruction = "WRITEINSTR", reqPersonality = "REQPERS",
        reqConcise = "REQCONCISE", reqFriends = "REQFRIENDS", reqNoAi = "REQNOAI", outputOnly = "OUTPUT",
        userMessage = "USERMSG", friendFallback = "Friend",
        schedCurrent = "SCHED:%1\$s", schedAt = " (at %1\$s)", schedFeeling = ", feeling: %1\$s",
        schedSuffix = "SCHEDSUFFIX",
    )

    private fun char(
        name: String = "小樱", personalityDescription: String = "活泼",
        gender: String = "", occupation: String = "", backstory: String = "",
        speakingStyle: String = "", catchphrases: String = "", initialInterests: String = "",
    ) = CharacterEntity(
        uuid = "c1", name = name, creationDate = 0L, personalityDescription = personalityDescription,
        gender = gender, occupation = occupation, backstory = backstory, speakingStyle = speakingStyle,
        catchphrases = catchphrases, initialInterests = initialInterests,
    )

    @Test fun `minimal user post comment omits optional sections`() {
        val out = MomentCommentPromptBuilder.build(
            strings = cs(), character = char(),
            postAuthorName = "用户", postTimeDescription = "3分钟前", postContent = "今天好开心",
            isPostByCharacter = false, nowContext = "NOWCTX", scheduleContext = null,
            photoCount = 0, visionEnabled = false, existingComments = emptyList(), replyTarget = null,
        )
        assertEquals("INTRO:小樱|活泼", out.lines().first())
        assertTrue(out.contains("FRIEND:用户"))
        assertTrue(out.contains("[3分钟前] 「今天好开心」"))
        assertTrue(out.contains("EMOTION"))
        assertFalse(out.contains("AIAI"))        // user post → no AI-AI note
        assertFalse(out.contains("BLIND"))
        assertFalse(out.contains("OTHERSHDR"))
        assertTrue(out.contains("WRITEINSTR"))   // no reply target → write instruction
        assertFalse(out.contains("REPLYINSTR"))
        assertEquals("OUTPUT", out.lines().last())
    }

    @Test fun `character post with all sections in iOS order`() {
        val out = MomentCommentPromptBuilder.build(
            strings = cs(),
            character = char(gender = "女", occupation = "学生", backstory = "B", speakingStyle = "轻快", catchphrases = "口头禅", initialInterests = "咖啡"),
            postAuthorName = "小明", postTimeDescription = "1小时前", postContent = "看电影",
            isPostByCharacter = true, nowContext = "NOWCTX", scheduleContext = "SCHEDCTX",
            photoCount = 3, visionEnabled = false,
            existingComments = listOf(CommentContextLine("30分钟前", "小红", "真好")),
            replyTarget = CommentReplyTarget("20分钟前", "小红", "真好"),
        )
        val order = listOf(
            "INTRO:小樱|活泼", "GENDER:女", "OCC:学生", "BACK:B", "STYLE:轻快", "CATCH:口头禅", "INTERESTS:咖啡",
            "FRIEND:小明", "[1小时前] 「看电影」", "EMOTION", "AIAI", "NOWCTX", "SCHEDCTX",
            "BLIND:3", "OTHERSHDR", "- [30分钟前] 小红: 真好", "OTHERSREACT",
            "[20分钟前] SAID:小红|真好", "REPLYINSTR", "REQHDR", "REQPERS", "REQCONCISE", "REQFRIENDS", "REQNOAI", "OUTPUT",
        )
        assertSubsequence(order, out)
    }

    @Test fun `photos blind vs vision`() {
        val blind = MomentCommentPromptBuilder.build(
            cs(), char(), "u", "t", "c", false, "N", null, 2, visionEnabled = false, emptyList(), null,
        )
        assertTrue(blind.contains("BLIND:2"))
        assertFalse(blind.contains("VISION"))
        val vision = MomentCommentPromptBuilder.build(
            cs(), char(), "u", "t", "c", false, "N", null, 2, visionEnabled = true, emptyList(), null,
        )
        assertTrue(vision.contains("VISION:2"))
        assertFalse(vision.contains("BLIND"))
    }

    @Test fun `existing comments capped at five`() {
        val six = (1..6).map { CommentContextLine("t$it", "n$it", "c$it") }
        val out = MomentCommentPromptBuilder.build(
            cs(), char(), "u", "t", "c", false, "N", null, 0, false, six, null,
        )
        assertTrue(out.contains("- [t1] n1: c1"))
        assertTrue(out.contains("- [t5] n5: c5"))
        assertFalse(out.contains("- [t6] n6: c6"))   // capped at 5
    }

    @Test fun `schedule line composes activity, location, mood, suffix`() {
        assertEquals(
            "SCHED:开会 (at 公司), feeling: 专注SCHEDSUFFIX",
            MomentCommentPromptBuilder.scheduleLine(cs(), "开会", "公司", "专注"),
        )
        // No location / mood → only current + suffix.
        assertEquals("SCHED:散步SCHEDSUFFIX", MomentCommentPromptBuilder.scheduleLine(cs(), "散步", "", null))
    }

    private fun assertSubsequence(markers: List<String>, text: String) {
        var idx = 0
        for (m in markers) {
            val found = text.indexOf(m, idx)
            assertTrue("marker not found in order: <$m>", found >= 0)
            idx = found + m.length
        }
    }
}
