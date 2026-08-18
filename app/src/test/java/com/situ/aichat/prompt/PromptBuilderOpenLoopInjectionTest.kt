package com.situ.aichat.prompt

import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OpenLoopType
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ChatMessageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * 活人感一期 P2 · chunk 7 注入装配（§3.2/§4.3·E9/E15）：`buildMessages(openLoops=…)` 让惦记的事块作为【角色记忆】
 * 第五层出现在系统提示；空 openLoops = 字节级零回归（§6 additive 红线守护）。
 * 默认 locale=en → 用解析出的 `pb_loop_*` 资源值比对（不钉死中英文）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptBuilderOpenLoopInjectionTest {

    private val fixedNow = Instant.ofEpochMilli(1_750_000_000_000)
    private fun strings() = PromptStrings(RuntimeEnvironment.getApplication())

    private fun loop(uuid: String, content: String, dueAt: Long? = null, createdAt: Long = 0L) = OpenLoopEntity(
        uuid = uuid, conversationUuid = "c1", characterUuid = "c1", content = content,
        typeRaw = OpenLoopType.OPEN_TOPIC, dueAt = dueAt, createdAt = createdAt,
    )

    /** 单条 user 消息 → lastAssistantTime=null → 今天首轮（branch ②注入最新 1 条）。 */
    private fun build(openLoops: List<OpenLoopEntity>, settings: AppSettings = AppSettings()): List<ChatMessageDto> {
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val messages = listOf(
            MessageEntity(messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "第一句聊天", timestamp = 1L),
        )
        return PromptBuilder.buildMessages(
            character = character,
            sortedMessages = messages,
            userProfile = null,
            appSettings = settings,
            strings = strings(),
            openLoops = openLoops,
            now = fixedNow,
        )
    }

    private fun fullText(msgs: List<ChatMessageDto>) = msgs.joinToString("\n") { it.content.orEmpty() }

    @Test
    fun 今天首轮_非到期项注入普通行() {
        val s = strings()
        val text = fullText(build(listOf(loop("l1", "在纠结换工作", dueAt = null, createdAt = 100))))
        assertTrue("段标题出现", text.contains(s.s(R.string.pb_loop_head)))
        assertTrue("普通行出现", text.contains(s.s(R.string.pb_loop_line, "在纠结换工作")))
        assertTrue("指引出现", text.contains(s.s(R.string.pb_loop_guide)))
    }

    @Test
    fun 到期项注入就是今天行() {
        val s = strings()
        val due = fixedNow.toEpochMilli() - 1000
        val text = fullText(build(listOf(loop("l1", "面试结果", dueAt = due))))
        assertTrue("到期项走「就是今天」行", text.contains(s.s(R.string.pb_loop_due_line, "面试结果")))
    }

    @Test
    fun 零回归_空openLoops等价于无() {
        val s = strings()
        val baseline = build(emptyList())
        assertEquals("空 openLoops 不注入·等价于默认", baseline, build(emptyList()))
        assertFalse("baseline 不含惦记块头", fullText(baseline).contains(s.s(R.string.pb_loop_head)))
    }
}
