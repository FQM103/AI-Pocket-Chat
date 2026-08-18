package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.tooling.PendingCalendarFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * ②（执行失败回流·陪伴改良版）的提示词侧：
 * - [buildCalendarFailureNudgePrompt] 措辞（**经用户过审·陪伴口吻红线**）：标题/原因/用户称呼正确嵌入 + 含自然找补引导。
 * - 装配接线：[PromptBuilder.buildMessages] 仅在 `calendarFailure` 非空时注入【有件小事没办成】系统消息（additive·
 *   null 时零注入·与 0-1 golden 同向不破坏）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarFailureNudgeTest {

    // ── 措辞（纯函数·红线过审版逐点核） ──

    @Test fun nudge_text_embeds_user_verb_title_reason_and_companion_guidance() {
        val s = buildCalendarFailureNudgePrompt("阿哲", "创建", "牙医预约", "没认出你说的时间")
        assertTrue(s.contains("【有件小事没办成】"))
        assertTrue(s.contains("你刚才想帮阿哲创建日历日程「牙医预约」"))
        assertTrue(s.contains("没认出你说的时间"))
        // 陪伴口吻：给「自然找补 / 可提可不提」的引导，而非助手腔报错。
        assertTrue(s.contains("像真人那样"))
        assertTrue(s.contains("自然带过一次就好"))
        assertTrue(s.contains("先不提也行"))
    }

    @Test fun nudge_text_falls_back_to_generic_address_when_user_name_blank() {
        assertTrue(buildCalendarFailureNudgePrompt(null, "修改", "会议", "手机日历那边没能写进去").contains("你刚才想帮对方修改"))
        assertTrue(buildCalendarFailureNudgePrompt("", "删除", "聚餐", "没找到那条日程").contains("你刚才想帮对方删除"))
    }

    // ── 装配接线（Robolectric·present/absent） ──

    private fun assembledSystemText(failure: PendingCalendarFailure?): String {
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val user = UserProfileEntity(nickname = "阿哲")
        val userMsg = MessageEntity(
            messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "在吗", timestamp = 1L,
        )
        return PromptBuilder.buildMessages(
            character = character,
            sortedMessages = listOf(userMsg),
            userProfile = user,
            appSettings = AppSettings(),
            strings = strings,
            calendarFailure = failure,
            now = Instant.ofEpochMilli(1_700_000_000_000L),
        ).filter { it.role == "system" }.joinToString("\n") { it.content.orEmpty() }
    }

    @Test fun assembly_injects_nudge_when_failure_present() {
        val s = assembledSystemText(
            PendingCalendarFailure(verb = "创建", title = "牙医预约", reason = "没认出你说的时间", recordedAtMillis = 0L),
        )
        assertTrue("有未消费失败 → 应注入陪伴口吻提示", s.contains("【有件小事没办成】"))
        assertTrue(s.contains("你刚才想帮阿哲创建日历日程「牙医预约」"))
        assertTrue(s.contains("没认出你说的时间"))
    }

    @Test fun assembly_omits_nudge_when_no_failure() {
        assertFalse("无失败（默认 null）→ 绝不注入", assembledSystemText(null).contains("【有件小事没办成】"))
    }
}
