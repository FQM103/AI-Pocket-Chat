package com.situ.aichat.data.remote.llm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 一条聊天消息 content 的多模态分段（OpenAI Chat Completions「content 数组」元素，1:1 iOS `LLMServiceTypes.ContentPart`）。
 * 纯文本消息仍用 [ChatMessageDto.content] 的裸字符串；带媒体的消息改用 [ChatMessageDto.contentParts] 数组式。
 * 由 [ChatMessageDtoSerializer] 手工编码为 OpenAI 线格式（键 `type`/`text`/`input_audio`/`image_url` 与 iOS、与
 * [CapabilityDetector] 探针逐字一致），**不走 kotlinx 多态机制**（避免注入类判别字段污染线格式）。
 */
sealed interface ChatContentPart {
    /** `{"type":"text","text":…}`——音频/图片消息的文字段（承载 buildAudioPrompt 文案或图片描述）。 */
    data class Text(val text: String) : ChatContentPart

    /** `{"type":"input_audio","input_audio":{"data":<base64>,"format":"wav"}}`——语音消息音频段（1:1 iOS audioPart）。 */
    data class InputAudio(val base64: String, val format: String = "wav") : ChatContentPart

    /** `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…"}}`——视觉地基（与音频共用数组式，1:1 iOS imagePart）。 */
    data class ImageUrl(val url: String) : ChatContentPart
}

/**
 * [ChatMessageDto] 的手工 JSON 序列化器：content 槽位「字符串 OR 数组」二选一——
 *   · [ChatMessageDto.contentParts] 非空 → content 编码为 OpenAI 数组式（多模态）；
 *   · 否则 [ChatMessageDto.content] 非空 → content 编码为裸字符串（纯文本，沿用旧线格式）；
 *   · 两者皆空 → 省略 content（对齐共享 Json 的 explicitNulls=false）。
 * 其余字段（reasoning_content/tool_calls/tool_call_id）非空才发（= iOS `encodeIfPresent`）。仅支持 JSON 编解码。
 * 保留 [ChatMessageDto] 为 data class（LlmClient MiniMax map 用 `.copy`），所有既有 `content = <String>` 构造点零改动。
 */
object ChatMessageDtoSerializer : KSerializer<ChatMessageDto> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ChatMessageDto") {
        element<String>("role")
        element<String>("content", isOptional = true)
        element<String>("reasoning_content", isOptional = true)
        element<String>("tool_calls", isOptional = true)
        element<String>("tool_call_id", isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: ChatMessageDto) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ChatMessageDto 仅支持 JSON 序列化")
        val obj = buildJsonObject {
            put("role", value.role)
            when {
                value.contentParts != null ->
                    put("content", buildJsonArray { value.contentParts.forEach { add(partToJson(it)) } })
                value.content != null -> put("content", JsonPrimitive(value.content))
                // 两者皆空：省略 content（对齐 explicitNulls=false）。
            }
            value.reasoningContent?.let { put("reasoning_content", JsonPrimitive(it)) }
            value.toolCalls?.let {
                put("tool_calls", jsonEncoder.json.encodeToJsonElement(ListSerializer(RequestToolCallDto.serializer()), it))
            }
            value.toolCallId?.let { put("tool_call_id", JsonPrimitive(it)) }
        }
        jsonEncoder.encodeJsonElement(obj)
    }

    override fun deserialize(decoder: Decoder): ChatMessageDto {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ChatMessageDto 仅支持 JSON 反序列化")
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        val role = obj["role"]?.jsonPrimitive?.content.orEmpty()
        var contentStr: String? = null
        var parts: List<ChatContentPart>? = null
        when (val contentEl = obj["content"]) {
            is JsonPrimitive -> contentStr = contentEl.contentOrNull
            is JsonArray -> parts = contentEl.map { partFromJson(it.jsonObject) }
            else -> {}
        }
        return ChatMessageDto(
            role = role,
            content = contentStr,
            contentParts = parts,
            reasoningContent = obj["reasoning_content"]?.jsonPrimitive?.contentOrNull,
            toolCalls = obj["tool_calls"]?.let {
                jsonDecoder.json.decodeFromJsonElement(ListSerializer(RequestToolCallDto.serializer()), it)
            },
            toolCallId = obj["tool_call_id"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun partToJson(part: ChatContentPart): JsonObject = when (part) {
        is ChatContentPart.Text -> buildJsonObject {
            put("type", "text")
            put("text", part.text)
        }
        is ChatContentPart.InputAudio -> buildJsonObject {
            put("type", "input_audio")
            put("input_audio", buildJsonObject {
                put("data", part.base64)
                put("format", part.format)
            })
        }
        is ChatContentPart.ImageUrl -> buildJsonObject {
            put("type", "image_url")
            put("image_url", buildJsonObject { put("url", part.url) })
        }
    }

    private fun partFromJson(obj: JsonObject): ChatContentPart =
        when (obj["type"]?.jsonPrimitive?.content) {
            "input_audio" -> {
                val data = obj["input_audio"]?.jsonObject
                ChatContentPart.InputAudio(
                    base64 = data?.get("data")?.jsonPrimitive?.content.orEmpty(),
                    format = data?.get("format")?.jsonPrimitive?.content ?: "wav",
                )
            }
            "image_url" ->
                ChatContentPart.ImageUrl(obj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content.orEmpty())
            else -> ChatContentPart.Text(obj["text"]?.jsonPrimitive?.content.orEmpty())
        }
}
