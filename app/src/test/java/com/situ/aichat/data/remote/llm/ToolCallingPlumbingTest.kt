package com.situ.aichat.data.remote.llm

import com.situ.aichat.data.local.entity.ApiConfigEntity
import com.situ.aichat.data.local.entity.effectiveToolCallingEnabled
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3b 接线地基测试：① `ApiConfigEntity.effectiveToolCallingEnabled()` 综合判定（现已喂给 chat path 的
 * `ApiConfigValues.toolCallingEnabled`，断言反推 iOS `APIConfiguration.effectiveToolCallingEnabled(isThinkingModel:)`）；
 * ② follow-up 用的请求侧 tool_calls / tool_call_id 线序形状（与 iOS OpenAI 兼容请求字节对齐）。
 */
class ToolCallingPlumbingTest {

    // 与 NetworkModule.provideJson 同配置：null 字段省略、默认值不编码。
    private val wireJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    private fun entity(
        toolMode: String = "auto",
        toolLevel: String = "unknown",
        thinkingMode: String = "standard",
        detectedThinking: Int = -1,
        thinkingToolSupport: String = "unknown",
    ) = ApiConfigEntity(
        uuid = "c1",
        providerName = "DeepSeek",
        apiKeyId = "k1",
        baseURL = "https://api.deepseek.com",
        modelName = "deepseek-chat",
        creationDate = 0L,
        toolCallingModeRaw = toolMode,
        detectedToolSupportLevelRaw = toolLevel,
        thinkingModelModeRaw = thinkingMode,
        detectedThinkingModelType = detectedThinking,
        detectedThinkingToolSupportRaw = thinkingToolSupport,
    )

    // ── effectiveToolCallingEnabled（模式 + 能力综合） ──

    @Test fun mode_enabled_forces_true_regardless_of_detection() {
        assertTrue(entity(toolMode = "enabled", toolLevel = "unsupported").effectiveToolCallingEnabled())
    }

    @Test fun mode_disabled_forces_false_regardless_of_detection() {
        assertFalse(entity(toolMode = "disabled", toolLevel = "full").effectiveToolCallingEnabled())
    }

    @Test fun auto_nonthinking_basic_or_full_enables_else_not() {
        // 非思考模型：basic / full → 开；unsupported / unknown → 关。
        assertTrue(entity(toolMode = "auto", toolLevel = "basic", thinkingMode = "standard").effectiveToolCallingEnabled())
        assertTrue(entity(toolMode = "auto", toolLevel = "full", thinkingMode = "standard").effectiveToolCallingEnabled())
        assertFalse(entity(toolMode = "auto", toolLevel = "unsupported", thinkingMode = "standard").effectiveToolCallingEnabled())
        assertFalse(entity(toolMode = "auto", toolLevel = "unknown", thinkingMode = "standard").effectiveToolCallingEnabled())
    }

    @Test fun auto_thinking_requires_thinking_tool_support_or_full_level() {
        // 思考模型：thinkingToolSupport=supported → 开；unsupported → 关；
        // unknown 时回退到 detectedToolSupportLevel==FULL。
        assertTrue(entity(toolMode = "auto", thinkingMode = "thinking", thinkingToolSupport = "supported").effectiveToolCallingEnabled())
        assertFalse(entity(toolMode = "auto", thinkingMode = "thinking", thinkingToolSupport = "unsupported", toolLevel = "full").effectiveToolCallingEnabled())
        assertTrue(entity(toolMode = "auto", thinkingMode = "thinking", thinkingToolSupport = "unknown", toolLevel = "full").effectiveToolCallingEnabled())
        assertFalse(entity(toolMode = "auto", thinkingMode = "thinking", thinkingToolSupport = "unknown", toolLevel = "basic").effectiveToolCallingEnabled())
    }

    // ── follow-up 请求侧 tool_calls / tool_call_id 线序形状 ──

    @Test fun follow_up_messages_serialize_to_openai_tool_shape() {
        // 仿 iOS fetchToolCallFollowUp：assistant(content=null, tool_calls=[…]) + tool(content, tool_call_id)。
        val assistant = ChatMessageDto(
            role = "assistant",
            content = null,
            toolCalls = listOf(
                RequestToolCallDto(
                    id = "call_1",
                    type = "function",
                    function = RequestToolCallFunctionDto(
                        name = "calendar_action",
                        arguments = """{"action":"create_event","title":"开会"}""",
                    ),
                ),
            ),
        )
        val toolResult = ChatMessageDto(role = "tool", content = "已创建日历事件：开会", toolCallId = "call_1")

        val json = wireJson.encodeToString(ListSerializer(ChatMessageDto.serializer()), listOf(assistant, toolResult))

        assertTrue(json.contains("\"tool_calls\":[{\"id\":\"call_1\""))
        // type="function" 必须保留（encodeDefaults=false 不应丢，因 DTO 未给默认值）。
        assertTrue(json.contains("\"type\":\"function\""))
        assertTrue(json.contains("\"function\":{\"name\":\"calendar_action\""))
        assertTrue(json.contains("\"tool_call_id\":\"call_1\""))
        // assistant content=null 省略；不出现裸 null。
        assertFalse(json.contains(":null"))
    }

    @Test fun plain_chat_message_unaffected_no_tool_fields_emitted() {
        val plain = ChatMessageDto(role = "user", content = "你好")
        val json = wireJson.encodeToString(ChatMessageDto.serializer(), plain)
        assertEquals("""{"role":"user","content":"你好"}""", json)
    }
}
