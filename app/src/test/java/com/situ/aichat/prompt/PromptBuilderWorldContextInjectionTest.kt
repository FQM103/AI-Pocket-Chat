package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ChatMessageDto
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W5 世界联动上下文注入 T2-6（图纸 §7·E12）：`buildMessages(worldContext=…)` 让世界块作为【角色记忆】第四层
 * 出现在系统提示；null/空 = 字节级零回归（§5 additive 红线的守护）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptBuilderWorldContextInjectionTest {

    private val fixedNow = Instant.ofEpochMilli(1_750_000_000_000)
    private fun strings() = PromptStrings(RuntimeEnvironment.getApplication())

    private fun build(worldContext: String?): List<ChatMessageDto> {
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val messages = listOf(
            MessageEntity(messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "第一句聊天", timestamp = 1L),
        )
        return PromptBuilder.buildMessages(
            character = character,
            sortedMessages = messages,
            userProfile = null,
            appSettings = AppSettings(),
            strings = strings(),
            worldContext = worldContext,
            now = fixedNow,
        )
    }

    @Test
    fun worldContext注入到系统提示第四层() {
        val block = "以下是你在这座小城生活的人际近况与经历（背景信息：可在聊天里自然提起，绝不逐条播报；若与你们对话中已确认的事实冲突，一律以对话为准）：\n" +
            "【与阿哲｜相识·朋友】你们关系不错，眼下和 TA 有点别扭\n- [2026-07-04] 你和阿哲和好了"
        val sys = build(block).first().content.orEmpty()
        assertTrue("块头必须出现在系统提示", sys.contains("以下是你在这座小城生活的人际近况与经历"))
        assertTrue("提炼行必须出现", sys.contains("【与阿哲｜相识·朋友】你们关系不错，眼下和 TA 有点别扭"))
        assertTrue("记忆行必须出现", sys.contains("- [2026-07-04] 你和阿哲和好了"))
    }

    @Test
    fun 零回归_null与空串世界上下文等价于无() {
        val baseline = build(null)
        assertEquals("空串不注入·等价于 null", baseline, build(""))
        assertFalse("baseline 系统提示不含世界块头", baseline.first().content.orEmpty().contains("以下是你在这座小城生活的人际近况与经历"))
    }
}
