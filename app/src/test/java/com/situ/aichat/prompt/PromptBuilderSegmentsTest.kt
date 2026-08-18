package com.situ.aichat.prompt

import com.situ.aichat.data.remote.llm.ChatMessageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批 D 上下文日志·结构化分段构建的纯函数单测（[PromptBuilder.finalizeSegments] / [PromptBuilder.moduleSegment]）。
 * 断言从规格 §3.3 反推：分段顺序 **前置 → 对话历史 → 后置**；历史段字符/token 自非 system 消息正文拼接、多模态
 * `content=null` 按空算、历史为空不产段；模块段 name=模块名、systemModuleType=系统模块 rawValue（自定义=null）。
 */
class PromptBuilderSegmentsTest {

    private fun seg(name: String, position: String, type: String? = null) =
        ContextSegment(name = name, systemModuleType = type, charCount = 1, estimatedTokens = 1, position = position)

    private fun promptModule(
        name: String,
        type: SystemModuleType?,
        position: PromptModulePosition = PromptModulePosition.PREFIX,
    ) = PromptModule(id = "id-$name", name = name, sortOrder = 0, systemModuleType = type, position = position)

    // MARK: - finalizeSegments：顺序 + 历史段

    @Test fun `reorders to prefix then history then suffix and inserts history`() {
        // 故意打乱 sink 顺序（后置先、两前置在后），验证按 position 重排且各组内相对序保留。
        val sink = mutableListOf(
            seg("S1", ContextSegment.POSITION_SUFFIX),
            seg("P1", ContextSegment.POSITION_PREFIX),
            seg("P2", ContextSegment.POSITION_PREFIX),
        )
        val messages = listOf(
            ChatMessageDto(role = "system", content = "系统提示"),
            ChatMessageDto(role = "user", content = "你好"),
            ChatMessageDto(role = "assistant", content = "嗨呀"),
        )

        PromptBuilder.finalizeSegments(sink, messages)

        assertEquals(listOf("P1", "P2", "对话历史", "S1"), sink.map { it.name })
        assertEquals(
            listOf(
                ContextSegment.POSITION_PREFIX,
                ContextSegment.POSITION_PREFIX,
                ContextSegment.POSITION_HISTORY,
                ContextSegment.POSITION_SUFFIX,
            ),
            sink.map { it.position },
        )
        val history = sink[2]
        assertEquals("你好嗨呀".length, history.charCount)          // 仅非 system 消息正文拼接
        assertEquals(TokenEstimator.estimate("你好嗨呀"), history.estimatedTokens)
        assertNull(history.systemModuleType)
    }

    @Test fun `no history segment when only system messages`() {
        val sink = mutableListOf(
            seg("P1", ContextSegment.POSITION_PREFIX),
            seg("S1", ContextSegment.POSITION_SUFFIX),
        )
        PromptBuilder.finalizeSegments(sink, listOf(ChatMessageDto(role = "system", content = "只有系统")))
        assertEquals(listOf("P1", "S1"), sink.map { it.name })       // 无对话历史段
        assertTrue(sink.none { it.position == ContextSegment.POSITION_HISTORY })
    }

    @Test fun `multimodal null content counts as empty in history`() {
        val sink = mutableListOf<ContextSegment>()
        val messages = listOf(
            ChatMessageDto(role = "system", content = "sys"),
            ChatMessageDto(role = "user", content = "hi"),
            ChatMessageDto(role = "user", content = null),           // 多模态：无文本部分
        )
        PromptBuilder.finalizeSegments(sink, messages)
        assertEquals(1, sink.size)
        assertEquals("hi".length, sink[0].charCount)                  // null content 不计入
    }

    @Test fun `history-only when no modules`() {
        val sink = mutableListOf<ContextSegment>()
        PromptBuilder.finalizeSegments(
            sink,
            listOf(ChatMessageDto(role = "user", content = "唯一一句")),
        )
        assertEquals(1, sink.size)
        assertEquals(ContextSegment.POSITION_HISTORY, sink[0].position)
        assertEquals("对话历史", sink[0].name)
    }

    // MARK: - moduleSegment：模块 → 分段映射

    @Test fun `system module segment carries rawValue and stats`() {
        val module = promptModule("核心规则", SystemModuleType.CORE_RULES)
        val seg = PromptBuilder.moduleSegment(module, "abc内容", ContextSegment.POSITION_PREFIX)
        assertEquals("核心规则", seg.name)
        assertEquals("coreRules", seg.systemModuleType)
        assertEquals("abc内容".length, seg.charCount)
        assertEquals(TokenEstimator.estimate("abc内容"), seg.estimatedTokens)  // 估算用的是 content
        assertEquals(ContextSegment.POSITION_PREFIX, seg.position)
    }

    @Test fun `custom module segment has null systemModuleType`() {
        val module = promptModule("我的自定义模块", type = null, position = PromptModulePosition.SUFFIX)
        val seg = PromptBuilder.moduleSegment(module, "自定义正文", ContextSegment.POSITION_SUFFIX)
        assertNull(seg.systemModuleType)
        assertEquals("我的自定义模块", seg.name)
        assertEquals(ContextSegment.POSITION_SUFFIX, seg.position)
    }
}
