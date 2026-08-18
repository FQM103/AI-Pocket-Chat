package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 锁定宠物独白消息进入 LLM 历史时的前缀使用「宠物真实名」，而非硬编码字面量 "宠物"
 * （1:1 iOS PromptBuilder.swift:606 `let petName = character?.pet?.name ?? "宠物"`）。
 *
 * 宠物名只在私有 `appendConversationMessages` 路径成型，必须端到端走 [PromptBuilder.buildMessages]
 * 才能验证 `pet` 是否被 thread 到该函数。回归方向：曾硬编码 "宠物" 致历史里恒为 `[宠物·宠物 说]`，
 * 用户给宠物起的名字（如「球球」）在每条宠物独白上对 LLM 丢失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptBuilderPetNameTest {

    /** 走完整 buildMessages，返回所有 chat 消息拼接后的整段提示词文本。 */
    private fun petMonologuePrompt(pet: CharacterPetEntity?): String {
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val petMessage = MessageEntity(
            messageUUID = "m1",
            conversationUuid = "c1",
            roleRaw = "assistant",
            content = "蹭了蹭你的手",
            timestamp = 1L,
            isPetMessage = true,
        )
        val messages = PromptBuilder.buildMessages(
            character = character,
            sortedMessages = listOf(petMessage),
            userProfile = null,
            appSettings = AppSettings(),
            strings = strings,
            pet = pet,
        )
        return messages.joinToString("\n") { it.content.orEmpty() }
    }

    @Test
    fun `pet monologue prefix uses the real pet name`() {
        val prompt = petMonologuePrompt(CharacterPetEntity(name = "球球"))
        assertTrue(
            "宠物独白前缀应带真实宠物名「球球」，实际：$prompt",
            prompt.contains("[宠物·球球 说]: 蹭了蹭你的手"),
        )
        // 回归守卫：旧代码硬编码 "宠物" → "[宠物·宠物 说]"。
        assertFalse(
            "不应再出现硬编码的 [宠物·宠物 说]，实际：$prompt",
            prompt.contains("[宠物·宠物 说]"),
        )
    }

    @Test
    fun `falls back to default when pet is null`() {
        val prompt = petMonologuePrompt(null)
        // 1:1 iOS `?? "宠物"`：无宠物时回退字面量。
        assertTrue(
            "无宠物时应回退为字面量「宠物」，实际：$prompt",
            prompt.contains("[宠物·宠物 说]: 蹭了蹭你的手"),
        )
    }
}
