package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 场内前情提要注入位置 T2（记忆改造二期·部件⑤·图纸 §3.2-D·T2-9）：走完整 [PromptBuilder.buildMessages] 验
 * 「2.15」注入——inSceneRecap 非空 → 一条 system 消息紧跟截断提示之后、内容 = 锁定模板；null → 不出现。
 * 锁定模板在测试里重新打字成字面量（防自证）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptBuilderInSceneRecapInjectionTest {

    private val recapBody = "两人聊起周末的展览，情绪从拘谨转为放松。"
    private val lockedInjected = "【前情提要】（本场更早部分的浓缩，下面的正文只保留了最近的对话）\n$recapBody"

    /** 走 buildMessages（shortTermMemoryLength=1 → specialBlockLimit=4，令 6 条通话块产出截断提示）。 */
    private fun build(inSceneRecap: String?): List<String> {
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        // 6 条通话消息（一个 VOICE_CALL 特殊块·超上限 4 → 产「通话更早部分已省略」截断提示）。
        val calls = (1..6).map { i ->
            MessageEntity(
                messageUUID = "v$i", conversationUuid = "c1",
                roleRaw = if (i % 2 == 1) "user" else "assistant", content = "话$i",
                timestamp = i.toLong(), isPartOfVoiceCall = true,
            )
        }
        return PromptBuilder.buildMessages(
            character = character,
            sortedMessages = calls,
            userProfile = null,
            appSettings = AppSettings(shortTermMemoryLength = 1),
            strings = strings,
            inSceneRecap = inSceneRecap,
        ).map { it.content.orEmpty() }
    }

    @Test fun `recap injected immediately after truncation note with locked content (T2-9)`() {
        val contents = build(recapBody)
        val noteIdx = contents.indexOfFirst { it.contains("通话更早部分已省略") }
        val recapIdx = contents.indexOfFirst { it.startsWith("【前情提要】") }

        assertTrue("应产出截断提示", noteIdx >= 0)
        assertTrue("应注入前情提要", recapIdx >= 0)
        assertEquals("前情提要紧跟截断提示之后", noteIdx + 1, recapIdx)
        assertEquals("注入内容 = 锁定模板", lockedInjected, contents[recapIdx])
    }

    @Test fun `null recap yields no injection`() {
        val contents = build(null)
        assertTrue("inSceneRecap=null → 无前情提要 system 消息", contents.none { it.startsWith("【前情提要】") })
    }

    @Test fun `blank recap yields no injection`() {
        val contents = build("   ")
        assertTrue("空白 recap → 不注入", contents.none { it.startsWith("【前情提要】") })
    }
}
