package com.situ.aichat.data.remote.llm.tooldetection

import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.model.ToolDetectionResult
import com.situ.aichat.data.remote.llm.ApiConfigValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One tool-calling capability probe per protocol family — faithful port of iOS
 * `ToolDetection/` (ToolCallingDetector.swift + the four detectors).
 *
 * Detectors are stateless; the OkHttp client + Json are passed in (mirrors iOS passing the
 * shared `URLSession`).
 */
interface ToolCallingDetector {
    suspend fun detect(
        config: ApiConfigValues,
        isThinkingModel: Boolean,
        client: OkHttpClient,
        json: Json,
    ): ToolDetectionResult
}

object ToolCallingDetectorFactory {
    /**
     * 选探针（H6·#8 探针对齐运行时）：运行时对**所有** provider 都发 OpenAI 形状到 `/v1/chat/completions`
     * （`LlmHttp` 归一化 URL + Bearer 鉴权），故检测也必须探"运行时真正会发的那套"。**绝不再用原生 Anthropic/
     * Gemini 探针**——它们打 `/v1/messages`、`:generateContent`、`x-api-key`，运行时从不走，会"验证"一条永不
     * 使用的协议、甚至把 Claude 中转误判成不支持工具而永久关闭。DeepSeek 走专属探针：本就 OpenAI 形状、但额外
     * 探思考模型的工具支持。
     */
    fun make(providerType: ApiProviderType): ToolCallingDetector = when (providerType) {
        ApiProviderType.DEEPSEEK -> DeepSeekToolDetector()
        else -> OpenAiCompatibleToolDetector()
    }
}

/** Raw JSON probe response (mirrors iOS ToolDetectionHTTPResponse). */
data class ToolDetectionHttpResponse(
    val statusCode: Int,
    val bodyText: String,
)

/** Minimal JSON POST helper for the detectors (mirrors iOS ToolDetectionHTTPClient). */
object ToolDetectionHttp {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    suspend fun jsonRequest(
        client: OkHttpClient,
        url: String,
        method: String = "POST",
        headers: Map<String, String>,
        body: JsonObject,
        json: Json,
        timeoutSec: Long = 20,
        maxAttempts: Int = 3,
    ): ToolDetectionHttpResponse = withContext(Dispatchers.IO) {
        val bodyJson = json.encodeToString(JsonObject.serializer(), body)
        val builder = Request.Builder()
            .url(url)
            .method(method, bodyJson.toRequestBody(JSON_MEDIA))
            .addHeader("Content-Type", "application/json")
        for ((k, v) in headers) builder.addHeader(k, v)
        val request = builder.build()

        val timedClient = client.newBuilder()
            .callTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
        // 瞬时失败重试（H6·#2）：存配置那一刻网抖（超时/掉线/429/5xx）不该被当成"不支持工具"永久持久化。
        requestWithRetry(maxAttempts, backoff = { attempt -> delay(retryBackoffMs(attempt)) }) {
            timedClient.newCall(request).execute().use { resp ->
                ToolDetectionHttpResponse(
                    statusCode = resp.code,
                    bodyText = runCatching { resp.body.string() }.getOrNull().orEmpty(),
                )
            }
        }
    }

    /** 退避时长：250ms / 500ms / …（首次失败即等再试）。 */
    private fun retryBackoffMs(attempt: Int): Long = 250L * (attempt + 1)

    /**
     * 瞬时失败重试编排（抽出便于单测·不依赖真 OkHttp）：[attempt] 执行一次请求，瞬时网络错误抛 [IOException]。
     * 408 / 425 / 429 / 5xx / 网络异常 = 瞬时 → 退避后重试；2xx 及其它 4xx = 确定性 → 立即返回、不浪费重试。
     * 重试耗尽：网络异常向上抛（调用方 catch → unknown）、瞬时状态码返回最后一次（调用方按其分类）。
     */
    internal suspend fun requestWithRetry(
        maxAttempts: Int,
        backoff: suspend (attempt: Int) -> Unit,
        attempt: suspend () -> ToolDetectionHttpResponse,
    ): ToolDetectionHttpResponse {
        var lastTransient: ToolDetectionHttpResponse? = null
        repeat(maxAttempts) { i ->
            val resp = try {
                attempt()
            } catch (e: IOException) {
                if (i == maxAttempts - 1) throw e
                backoff(i)
                return@repeat
            }
            // 408 Request Timeout / 425 Too Early 本质瞬时（与 429/5xx 同类），退避重试而非立即判 UNSUPPORTED。
            val transient = resp.statusCode == 408 || resp.statusCode == 425 ||
                resp.statusCode == 429 || resp.statusCode in 500..599
            if (transient && i < maxAttempts - 1) {
                lastTransient = resp
                backoff(i)
                return@repeat
            }
            return resp
        }
        return lastTransient ?: throw IOException("工具检测请求耗尽重试仍失败")
    }

    fun summarizeBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return "服务端未返回错误详情"
        return trimmed.take(160)
    }
}
