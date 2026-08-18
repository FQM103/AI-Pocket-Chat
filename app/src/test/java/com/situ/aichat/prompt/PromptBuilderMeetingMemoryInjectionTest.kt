package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ChatMessageDto
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 见面记忆注入接线（图纸 §3.6·B3 收尾 + 2026-07-11 前置改造 T2-1）：{{见面记忆}} 宏来源 =
 * [PromptBuilder.buildMessages] 的 `offlineMeetingMemoryText` 参数（调用方经 `renderedForInjection` 行渲染传入），
 * **不再**读 `character.offlineMeetingMemorySummary`（blob 冻结·原文通道退役）；且注入时经
 * [buildOfflineMeetingMemoryContent] 相框包装、默认落**前置区**（PREFIX·首条大 system 内），治「系统『你』=角色
 * × 日记『你』=对方」的指代翻转。qualifiers=zh-rCN：相框断言用中文生产文案（`[你的见面日记]` / `用户` 回退）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PromptBuilderMeetingMemoryInjectionTest {

    private val fixedNow = Instant.ofEpochMilli(1_750_000_000_000)
    private val blobSentinel = "旧BLOB见面绝不出现Sentinel"
    private val rawDiary = "【见面 · 2026-04-18 15:30 · 公园】\n一次很好的见面。"

    private fun character() = CharacterEntity(
        uuid = "c1",
        name = "小雨",
        creationDate = 0L,
        offlineMeetingMemorySummary = blobSentinel, // 旧 blob 有内容——验证宏不再读它
    )

    private fun messages() = listOf(
        MessageEntity(messageUUID = "u1", conversationUuid = "c1", roleRaw = "user", content = "在吗", timestamp = 1L),
    )

    private fun buildMsgs(offlineMeetingMemoryText: String): List<ChatMessageDto> =
        PromptBuilder.buildMessages(
            character = character(),
            sortedMessages = messages(),
            userProfile = null,
            appSettings = AppSettings(),
            strings = PromptStrings(RuntimeEnvironment.getApplication()),
            offlineMeetingMemoryText = offlineMeetingMemoryText,
            now = fixedNow,
        )

    /** 首条大 system（前置区拼装成的一条）。 */
    private fun headSystem(offlineMeetingMemoryText: String): String =
        buildMsgs(offlineMeetingMemoryText).first { it.role == "system" }.content.orEmpty()

    private fun allSystemText(offlineMeetingMemoryText: String): String =
        buildMsgs(offlineMeetingMemoryText).joinToString("\n") { it.content.orEmpty() }

    // MARK: - 既有：宏读参数、不回读 blob

    @Test
    fun 宏读参数_注入渲染行产物_不含blob() {
        val sys = allSystemText(rawDiary)
        assertTrue("注入应含 renderer 产物", sys.contains("一次很好的见面。"))
        assertFalse("绝不含 character.offlineMeetingMemorySummary（blob 直读已退役）", sys.contains(blobSentinel))
    }

    @Test
    fun 空参数_不注入见面记忆_也不读blob() {
        val sys = allSystemText("")
        assertFalse("空参数时既不注入行、也不回读 blob", sys.contains(blobSentinel))
    }

    // MARK: - T2-1 前置改造：相框 + 默认落前置区（首条大 system）

    @Test
    fun 非空_首条system含相框与见面标题_后续system不含见面标题() {
        val msgs = buildMsgs(rawDiary)
        val head = msgs.first { it.role == "system" }.content.orEmpty()
        // (a) 首条大 system（前置区）同时含：相框标题、角色名(相框 %1$s)、对方名回退串(相框 %2$s)、原日记标题。
        assertTrue("首条 system 应含相框标题", head.contains("[你的见面日记]"))
        assertTrue("首条 system 相框应嵌入角色名", head.contains("下面是你（小雨）"))
        assertTrue("首条 system 相框应嵌入对方名回退串", head.contains("对方（用户）"))
        assertTrue("首条 system 应含原日记标题(相框后紧随原文)", head.contains("【见面 · "))
        // (a) 续：见面记忆已在前置区——任何后续 system 都不该再出现独立的见面标题（原后置独立条消失）。
        val laterSystem = msgs.drop(1).filter { it.role == "system" }.joinToString("\n") { it.content.orEmpty() }
        assertFalse("见面记忆默认在前置,后续 system 不应含独立见面标题", laterSystem.contains("【见面 · "))
    }

    @Test
    fun 空参数_全上下文无相框标题() {
        // (c) E1：空文本 → buildOfflineMeetingMemoryContent 返回 ""，模块整体跳过，无孤框。
        assertFalse("空文本不应出现相框标题", allSystemText("").contains("[你的见面日记]"))
    }

    @Test
    fun userProfile为空_相框对方名回退为用户() {
        // (d) E2：userProfile=null → 相框括号名 = pb_user_fallback「用户」（全库 {{user}} 回退同口径）。
        assertTrue("无用户档案时相框应回退为「（用户）」", headSystem(rawDiary).contains("（用户）"))
    }

    @Test
    fun 分段_见面记忆落前置区() {
        // (e) 上下文日志分段：offlineMeetingMemory 分段 position = prefix（原 suffix 独立条已消失）。
        val result = PromptBuilder.buildMessagesWithSegments(
            character = character(),
            sortedMessages = messages(),
            userProfile = null,
            appSettings = AppSettings(),
            strings = PromptStrings(RuntimeEnvironment.getApplication()),
            offlineMeetingMemoryText = rawDiary,
            now = fixedNow,
        )
        val seg = result.segments.firstOrNull {
            it.systemModuleType == SystemModuleType.OFFLINE_MEETING_MEMORY.rawValue
        }
        assertNotNull("应有见面记忆分段", seg)
        assertEquals("见面记忆分段应在前置区", ContextSegment.POSITION_PREFIX, seg!!.position)
    }
}
