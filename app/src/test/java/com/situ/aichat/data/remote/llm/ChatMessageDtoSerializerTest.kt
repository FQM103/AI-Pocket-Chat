package com.situ.aichat.data.remote.llm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 锁定多模态 content 线格式（P13.4b 地基）。断言逐字反推自 iOS `LLMServiceTypes`（text 裸字符串 / 媒体数组式）
 * 与 CapabilityDetector 音频探针（`input_audio:{data,format:"wav"}`）。Json 配置与生产 [NetworkModule] 一致。
 */
class ChatMessageDtoSerializerTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = true
    }

    private fun encode(msg: ChatMessageDto): String = json.encodeToString(ChatMessageDto.serializer(), msg)

    @Test fun `text-only message stays a bare string`() {
        // iOS: .text(content) → 裸 JSON 字符串，不包数组。
        assertEquals(
            """{"role":"user","content":"你好呀"}""",
            encode(ChatMessageDto(role = "user", content = "你好呀")),
        )
    }

    @Test fun `text plus audio message becomes content array`() {
        // iOS audioPart：{"type":"input_audio","input_audio":{"data":<base64>,"format":"wav"}}，文字段在前。
        val msg = ChatMessageDto(
            role = "user",
            contentParts = listOf(
                ChatContentPart.Text("听我说"),
                ChatContentPart.InputAudio(base64 = "QUJD", format = "wav"),
            ),
        )
        assertEquals(
            """{"role":"user","content":[{"type":"text","text":"听我说"},""" +
                """{"type":"input_audio","input_audio":{"data":"QUJD","format":"wav"}}]}""",
            encode(msg),
        )
    }

    @Test fun `text plus image message becomes content array`() {
        // iOS imagePart：{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…"}}（视觉地基）。
        val msg = ChatMessageDto(
            role = "user",
            contentParts = listOf(
                ChatContentPart.Text("看这张"),
                ChatContentPart.ImageUrl("data:image/jpeg;base64,QQ=="),
            ),
        )
        assertEquals(
            """{"role":"user","content":[{"type":"text","text":"看这张"},""" +
                """{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,QQ=="}}]}""",
            encode(msg),
        )
    }

    @Test fun `content omitted when both content and parts are null`() {
        // explicitNulls=false：content 为空两路皆空时整键省略。
        assertEquals("""{"role":"user"}""", encode(ChatMessageDto(role = "user")))
    }

    @Test fun `contentParts wins over content when both present`() {
        val msg = ChatMessageDto(
            role = "user",
            content = "应被忽略",
            contentParts = listOf(ChatContentPart.Text("生效")),
        )
        assertEquals("""{"role":"user","content":[{"type":"text","text":"生效"}]}""", encode(msg))
    }

    @Test fun `reasoning_content omitted when null, tool_call_id kept when set`() {
        // 既有行为不退化：纯文本路 reasoning_content 省略；tool 角色 tool_call_id 保留。
        assertEquals(
            """{"role":"tool","content":"工具结果","tool_call_id":"call_1"}""",
            encode(ChatMessageDto(role = "tool", content = "工具结果", toolCallId = "call_1")),
        )
    }

    @Test fun `tool_calls array preserved through custom serializer`() {
        val msg = ChatMessageDto(
            role = "assistant",
            toolCalls = listOf(
                RequestToolCallDto(
                    id = "call_1",
                    type = "function",
                    function = RequestToolCallFunctionDto(name = "addEvent", arguments = """{"x":1}"""),
                ),
            ),
        )
        assertEquals(
            """{"role":"assistant","tool_calls":[{"id":"call_1","type":"function",""" +
                """"function":{"name":"addEvent","arguments":"{\"x\":1}"}}]}""",
            encode(msg),
        )
    }

    @Test fun `multimodal message composes inside a full ChatRequestDto`() {
        // 证明自定义序列化器在真实路径（ChatRequestDto.serializer() → List<ChatMessageDto>）里正确嵌套。
        val req = ChatRequestDto(
            model = "gpt-4o-audio",
            messages = listOf(
                ChatMessageDto(role = "system", content = "你是助手"),
                ChatMessageDto(
                    role = "user",
                    contentParts = listOf(
                        ChatContentPart.Text("hi"),
                        ChatContentPart.InputAudio(base64 = "QQ==", format = "wav"),
                    ),
                ),
            ),
            stream = true,
        )
        assertEquals(
            """{"model":"gpt-4o-audio","messages":[{"role":"system","content":"你是助手"},""" +
                """{"role":"user","content":[{"type":"text","text":"hi"},""" +
                """{"type":"input_audio","input_audio":{"data":"QQ==","format":"wav"}}]}],"stream":true}""",
            json.encodeToString(ChatRequestDto.serializer(), req),
        )
    }

    @Test fun `round-trips audio message back to typed parts`() {
        val original = ChatMessageDto(
            role = "user",
            contentParts = listOf(
                ChatContentPart.Text("听"),
                ChatContentPart.InputAudio(base64 = "QUJD", format = "wav"),
            ),
        )
        val decoded = json.decodeFromString(ChatMessageDto.serializer(), encode(original))
        assertEquals("user", decoded.role)
        assertNull(decoded.content)
        assertEquals(
            listOf(
                ChatContentPart.Text("听"),
                ChatContentPart.InputAudio(base64 = "QUJD", format = "wav"),
            ),
            decoded.contentParts,
        )
    }

    @Test fun `round-trips bare-string content`() {
        val decoded = json.decodeFromString(
            ChatMessageDto.serializer(),
            """{"role":"user","content":"你好"}""",
        )
        assertEquals("你好", decoded.content)
        assertNull(decoded.contentParts)
    }
}
