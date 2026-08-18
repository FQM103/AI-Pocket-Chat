package com.situ.aichat.data.remote.llm.tooldetection

import com.situ.aichat.data.model.CapabilitySupportState
import com.situ.aichat.data.model.ToolDetectionResult
import com.situ.aichat.data.model.ToolProtocolFamily
import com.situ.aichat.data.model.ToolSupportLevel
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.LlmHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient

/**
 * DeepSeek tool-calling probe — faithful port of iOS `DeepSeekToolDetector`.
 * Runs the OpenAI-compatible baseline first; for thinking models with basic+ support, adds a
 * second probe verifying that tool_calls + reasoning_content survive the follow-up round-trip.
 */
class DeepSeekToolDetector : ToolCallingDetector {

    override suspend fun detect(
        config: ApiConfigValues,
        isThinkingModel: Boolean,
        client: OkHttpClient,
        json: Json,
    ): ToolDetectionResult {
        val baseline = OpenAiCompatibleToolDetector().detect(config, isThinkingModel, client, json)

        if (!isThinkingModel || !baseline.level.enablesBasicToolCalling) {
            val summary = baseline.summary.ifEmpty { "已按 DeepSeek 协议完成基础工具检测。" }
            return ToolDetectionResult(
                level = baseline.level,
                protocolFamily = ToolProtocolFamily.DEEPSEEK,
                streamingState = baseline.streamingState,
                thinkingState = if (isThinkingModel) CapabilitySupportState.UNKNOWN else CapabilitySupportState.UNSUPPORTED,
                summary = summary,
                checkedAt = baseline.checkedAt,
            )
        }

        return try {
            val url = LlmHttp.buildChatCompletionsUrl(config.baseUrl)
            val headers = LlmHttp.authHeaders(config)

            val firstBody = buildJsonObject {
                put("model", config.modelName)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", "Please think first, call test_ping, then answer with done.")
                    }
                }
                put("stream", false)
                put("max_tokens", 128)
                putJsonArray("tools") { add(OpenAiCompatibleToolDetector.testToolDefinition) }
            }

            val first = ToolDetectionHttp.jsonRequest(client, url, headers = headers, body = firstBody, json = json)
            if (first.statusCode != 200) {
                return ToolDetectionResult(
                    level = baseline.level,
                    protocolFamily = ToolProtocolFamily.DEEPSEEK,
                    streamingState = baseline.streamingState,
                    thinkingState = CapabilitySupportState.UNSUPPORTED,
                    summary = "DeepSeek 思考工具检测首轮被拒绝：HTTP ${first.statusCode}，${ToolDetectionHttp.summarizeBody(first.bodyText)}",
                    checkedAt = System.currentTimeMillis(),
                )
            }

            val message = runCatching {
                json.parseToJsonElement(first.bodyText).jsonObject["choices"]
                    ?.jsonArray?.getOrNull(0)?.jsonObject?.get("message")?.jsonObject
            }.getOrNull()
            val toolCalls = message?.get("tool_calls") as? JsonArray
            val toolCallId = (toolCalls?.getOrNull(0)?.jsonObject?.get("id") as? JsonPrimitive)?.contentOrNull

            if (message == null || toolCalls == null || toolCallId == null) {
                return ToolDetectionResult(
                    level = baseline.level,
                    protocolFamily = ToolProtocolFamily.DEEPSEEK,
                    streamingState = baseline.streamingState,
                    thinkingState = CapabilitySupportState.UNSUPPORTED,
                    summary = "DeepSeek 未返回可复用的 tool_calls，无法确认思考模型工具闭环。",
                    checkedAt = System.currentTimeMillis(),
                )
            }

            val assistantMessage = buildJsonObject {
                put("role", "assistant")
                put("content", (message["content"] as? JsonPrimitive)?.contentOrNull ?: "")
                put("reasoning_content", (message["reasoning_content"] as? JsonPrimitive)?.contentOrNull ?: "")
                put("tool_calls", toolCalls)
            }

            val secondBody = buildJsonObject {
                put("model", config.modelName)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", "Please think first, call test_ping, then answer with done.")
                    }
                    add(assistantMessage)
                    addJsonObject {
                        put("role", "tool")
                        put("tool_call_id", toolCallId)
                        put("content", "pong")
                    }
                }
                put("stream", false)
                put("max_tokens", 64)
            }

            val second = ToolDetectionHttp.jsonRequest(client, url, headers = headers, body = secondBody, json = json)

            ToolDetectionResult(
                level = if (second.statusCode == 200) ToolSupportLevel.FULL else baseline.level,
                protocolFamily = ToolProtocolFamily.DEEPSEEK,
                streamingState = baseline.streamingState,
                thinkingState = if (second.statusCode == 200) CapabilitySupportState.SUPPORTED else CapabilitySupportState.UNSUPPORTED,
                summary = if (second.statusCode == 200) {
                    "已验证 DeepSeek 思考模型的工具调用与 reasoning 续传闭环。"
                } else {
                    "DeepSeek 思考模型工具续传失败：HTTP ${second.statusCode}，${ToolDetectionHttp.summarizeBody(second.bodyText)}"
                },
                checkedAt = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            ToolDetectionResult(
                level = baseline.level,
                protocolFamily = ToolProtocolFamily.DEEPSEEK,
                streamingState = baseline.streamingState,
                thinkingState = CapabilitySupportState.UNKNOWN,
                summary = "DeepSeek 思考工具检测失败：${e.message ?: e.toString()}",
                checkedAt = System.currentTimeMillis(),
            )
        }
    }
}
