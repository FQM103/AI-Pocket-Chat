package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 收口回归（批3 3-10·「该藏的内容藏漏了」家族）：结构化卡 JSON **解析失败**时（备份损坏/版本漂移），
 * 主聊天历史拼装路径必须整条跳过——绝不把原始 JSON（礼物 cost / 红包 amount / 系统事件原文）喂给 LLM。
 *
 * 回归方向：旧实现三种失败语义并存——GIFT_CARD/RED_PACKET 用 `?.let`（失败保持原文 JSON 直漏）、
 * SYSTEM_EVENT_CARD 失败直接进 user 桶、FUTURE_MEETING 两卡置空跳过。本测锁定统一后的「宁缺勿漏」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptBuilderCardParseFailTest {

    /** 各卡种的「必然解析失败」内容：合法 JSON 前缀 + 哨兵字段 + 截断（模拟备份损坏）。 */
    private fun promptWith(kind: MessageKind, corruptContent: String, role: String): String {
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val userMsg = MessageEntity(
            messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "在吗", timestamp = 1L,
        )
        val card = MessageEntity(
            messageUUID = "m1", conversationUuid = "c1", roleRaw = role,
            content = corruptContent, timestamp = 2L, messageKindRaw = kind.raw,
        )
        val tail = MessageEntity(
            messageUUID = "u2", conversationUuid = "c1", roleRaw = "user", content = "怎么不说话", timestamp = 3L,
        )
        return PromptBuilder.buildMessages(
            character = character,
            sortedMessages = listOf(userMsg, card, tail),
            userProfile = null,
            appSettings = AppSettings(),
            strings = strings,
            pet = null,
        ).joinToString("\n") { it.content.orEmpty() }
    }

    @Test
    fun `礼物卡解析失败_原始JSON与cost绝不进prompt`() {
        val prompt = promptWith(MessageKind.GIFT_CARD, """{"cost":9999,"giftName":"LEAK_G""", role = "user")
        assertFalse("礼物卡损坏原文不得漏，实际：$prompt", prompt.contains("LEAK_G"))
        assertFalse(prompt.contains("\"cost\""))
        assertTrue("正常消息仍在", prompt.contains("怎么不说话"))
    }

    @Test
    fun `红包卡解析失败_原始JSON与amount绝不进prompt`() {
        val prompt = promptWith(MessageKind.RED_PACKET, """{"amount":888,"blessing":"LEAK_R""", role = "assistant")
        assertFalse("红包卡损坏原文不得漏，实际：$prompt", prompt.contains("LEAK_R"))
        assertFalse(prompt.contains("\"amount\""))
        assertTrue(prompt.contains("怎么不说话"))
    }

    @Test
    fun `系统事件卡解析失败_原始JSON绝不进prompt`() {
        val prompt = promptWith(MessageKind.SYSTEM_EVENT_CARD, """{"eventType":"redPacket","title":"LEAK_S""", role = "system")
        assertFalse("系统事件卡损坏原文不得漏，实际：$prompt", prompt.contains("LEAK_S"))
        assertTrue(prompt.contains("怎么不说话"))
    }
}
