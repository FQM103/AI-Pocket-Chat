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
 * ③ 记忆守则 `pb_mem_past_guide`（默认分支·真正运行时注入·② DEFAULT_INJECTION_PROMPT 的运行时孪生）
 * 里的用户称呼改用真实用户名（角色直读的提示词·真名更自然）；无昵称回退本地化「对方」
 * （非全局 pb_user_fallback=「用户」·与 ① VOICE_CALL_HISTORY_HINT 同口径）。
 *
 * 默认分支触发条件：character.memorySummary 非空 且 未设自定义注入模板（AppSettings 默认空）。
 * 断言用中文生产文案（qualifiers=zh-rCN）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class MemoryPastGuideNameTest {

    private val fixedNow = Instant.ofEpochMilli(1_750_000_000_000)

    private fun systemText(nickname: String): String {
        val character = CharacterEntity(
            uuid = "c1", name = "夏晴子", creationDate = 0L,
            memorySummary = "【长期事实】小明喜欢喝美式咖啡。",
        )
        return PromptBuilder.buildMessages(
            character = character,
            sortedMessages = listOf(
                MessageEntity(messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "在吗", timestamp = 1L),
            ),
            userProfile = UserProfileEntity(nickname = nickname),
            appSettings = AppSettings(),
            strings = PromptStrings(RuntimeEnvironment.getApplication()),
            now = fixedNow,
        ).filter { it.role == "system" }.joinToString("\n") { it.content.orEmpty() }
    }

    @Test fun guide_uses_real_user_name_when_nickname_set() {
        val sys = systemText(nickname = "小明")
        assertTrue("记忆守则应带真实用户名", sys.contains("如果小明现在说的和记忆矛盾，以小明当前的话为准"))
        assertFalse("有昵称时守则不应残留通用代称「对方」", sys.contains("如果对方现在说的和记忆矛盾"))
    }

    @Test fun guide_falls_back_to_generic_when_no_nickname() {
        val sys = systemText(nickname = "")
        assertTrue("无昵称 → 守则回退「对方」（非「用户」）", sys.contains("如果对方现在说的和记忆矛盾，以对方当前的话为准"))
    }
}
