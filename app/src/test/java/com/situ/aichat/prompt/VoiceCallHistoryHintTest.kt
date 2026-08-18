package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.UserProfileEntity
import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * 语音历史回顾提示 [buildVoiceCallHistoryHint] 的人称指名：
 * - 措辞（纯函数）：带真实用户名（{{user}} 同理·由角色直读的提示词用真名字更自然），无昵称回退「对方」。
 * - 装配接线：[PromptBuilder.buildMessages] 仅在「非通话中 且 历史含通话消息」时注入【历史提示】，
 *   且把 `userProfile?.nickname` 穿透进去（缺昵称 → 「对方」）。
 *
 * 备注：`【历史提示】` 段头是 [ReplyParser] 的输出剥离锚点——本改动只动正文人称、不碰段头。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceCallHistoryHintTest {

    // ── 措辞（纯函数） ──

    @Test fun hint_text_embeds_real_user_name_and_keeps_header() {
        val s = buildVoiceCallHistoryHint("小明")
        assertTrue("段头原样保留（ReplyParser 锚点）", s.contains("【历史提示】"))
        assertTrue(s.contains("来自之前与小明语音通话的消息"))
        assertTrue(s.contains("如果小明提到"))
        assertFalse("带昵称时不应残留通用代称「对方」", s.contains("对方"))
    }

    @Test fun hint_text_falls_back_to_generic_address_when_name_blank() {
        for (blank in listOf(null, "")) {
            val s = buildVoiceCallHistoryHint(blank)
            assertTrue("无昵称 → 回退「对方」", s.contains("来自之前与对方语音通话的消息"))
            assertTrue(s.contains("如果对方提到"))
        }
    }

    // ── 装配接线（Robolectric·present/absent + 名字穿透） ──

    private fun assembledSystemText(withVoiceCallMsg: Boolean, nickname: String): String {
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val user = UserProfileEntity(nickname = nickname)
        val messages = buildList {
            add(
                MessageEntity(
                    messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "在吗", timestamp = 1L,
                ),
            )
            if (withVoiceCallMsg) {
                add(
                    MessageEntity(
                        messageUUID = "v1", conversationUuid = "c1", roleRaw = "assistant", content = "嗯呐",
                        timestamp = 2L, isPartOfVoiceCall = true,
                    ),
                )
            }
        }
        return PromptBuilder.buildMessages(
            character = character,
            sortedMessages = messages,
            userProfile = user,
            appSettings = AppSettings(),
            strings = strings,
            now = Instant.ofEpochMilli(1_700_000_000_000L),
        ).filter { it.role == "system" }.joinToString("\n") { it.content.orEmpty() }
    }

    @Test fun assembly_injects_hint_with_real_name_when_history_has_voice_call() {
        val s = assembledSystemText(withVoiceCallMsg = true, nickname = "小明")
        assertTrue("历史含通话消息 → 应注入历史提示", s.contains("【历史提示】"))
        assertTrue("名字穿透：应嵌真实昵称", s.contains("来自之前与小明语音通话的消息"))
    }

    @Test fun assembly_falls_back_to_generic_when_no_nickname() {
        val s = assembledSystemText(withVoiceCallMsg = true, nickname = "")
        assertTrue("无昵称 → 历史提示回退「对方」", s.contains("来自之前与对方语音通话的消息"))
    }

    @Test fun assembly_omits_hint_when_no_voice_call_history() {
        val s = assembledSystemText(withVoiceCallMsg = false, nickname = "小明")
        assertFalse("历史无通话消息 → 绝不注入历史提示", s.contains("【历史提示】"))
    }
}
