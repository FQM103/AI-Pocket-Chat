package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.FutureMeetingProposalData
import com.situ.aichat.data.model.FutureMeetingProposalJson
import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 收口回归（「该藏的内容藏漏了」家族）：未来约定见面确认卡（[MessageKind.FUTURE_MEETING_PROPOSAL_CARD]）插进聊天后，
 * **主聊天历史→LLM 的拼装路径** [appendConversationMessages]（经 [PromptBuilder.buildMessages]）必须把卡片 JSON
 * 脱敏成系统记录、绝不把原文喂给模型。
 *
 * 回归方向：[PromptBuilderHistory] 的内联脱敏曾只处理 GIFT_CARD / RED_PACKET，漏了约定卡 → 卡片原文 JSON
 *（含 `appointmentUuid` / 给用户看的 `tensionHint` / 角色 `invitation` 台词）逐字漏进每个助手轮次的提示词。
 * 脱敏口径对齐 [com.situ.aichat.prompt.messageLlmSafeText]：只露 时间/地点/活动，其余一律不进 LLM。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptBuilderFutureMeetingLeakTest {

    private fun promptWithProposalCard(): String {
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val cardJson = FutureMeetingProposalJson.encode(
            FutureMeetingProposalData(
                appointmentUuid = "appt-LEAK-123",
                whenDisplay = "6月27日 周六 15:00",
                location = "美术馆",
                activity = "看展",
                invitation = "周六一起去看那个展吧～", // 角色台词：不该喂 LLM
                tensionHint = "她有点小心事",          // 给用户看的暗示：不该喂 LLM
            ),
        )
        val userMsg = MessageEntity(
            messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "周六有空吗", timestamp = 1L,
        )
        val card = MessageEntity(
            messageUUID = "m1",
            conversationUuid = "c1",
            roleRaw = "assistant",
            content = cardJson,
            timestamp = 2L,
            messageKindRaw = MessageKind.FUTURE_MEETING_PROPOSAL_CARD.raw,
        )
        val messages = PromptBuilder.buildMessages(
            character = character,
            sortedMessages = listOf(userMsg, card),
            userProfile = null,
            appSettings = AppSettings(),
            strings = strings,
            pet = null,
        )
        return messages.joinToString("\n") { it.content.orEmpty() }
    }

    @Test
    fun `proposal card is desensitized to a system record for the LLM`() {
        val prompt = promptWithProposalCard()
        // 脱敏后系统记录：时间/地点/活动可见（与 llmRepresentation 同口径）。称呼词随用户名解析（图纸一 R1 承接·此处 userProfile=null → pb_user_fallback），故断言用**名字无关**的稳定子串。
        assertTrue("应含脱敏系统记录，实际：$prompt", prompt.contains("提出了未来见面的约定"))
        assertTrue(prompt.contains("时间=6月27日 周六 15:00"))
        assertTrue(prompt.contains("地点=美术馆"))
        assertTrue(prompt.contains("活动=看展"))
    }

    @Test
    fun `proposal card raw JSON and hidden fields never reach the LLM`() {
        val prompt = promptWithProposalCard()
        // 原始 JSON 结构 / 内部字段绝不漏。
        assertFalse("不应漏 kind/type 原文，实际：$prompt", prompt.contains("future_meeting_proposal"))
        assertFalse("不应漏 appointmentUuid，实际：$prompt", prompt.contains("appointmentUuid"))
        assertFalse(prompt.contains("appt-LEAK-123"))
        // llmRepresentation 故意丢弃的 invitation / tensionHint 不该出现。
        assertFalse("不应漏角色 invitation 台词，实际：$prompt", prompt.contains("一起去看那个展"))
        assertFalse("不应漏 tensionHint，实际：$prompt", prompt.contains("她有点小心事"))
    }
}
