package com.situ.aichat.data.remote.llm.modelcatalog

import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.model.ApiProviderType
import com.situ.aichat.data.remote.llm.ApiConfigValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Model-catalog providers — faithful port of iOS `Services/ModelCatalog/`.
 * Each provider fetches the available models for one provider type (GET /models or native
 * variants). Stateless; OkHttp client + Json passed in (mirrors iOS passing the URLSession).
 */
interface ModelCatalogProvider {
    suspend fun fetchModels(config: ApiConfigValues, client: OkHttpClient, json: Json): List<APIModelOption>
}

object ModelCatalogProviderFactory {
    fun make(providerType: ApiProviderType): ModelCatalogProvider = when (providerType) {
        ApiProviderType.ANTHROPIC -> AnthropicModelCatalogProvider()
        ApiProviderType.GEMINI -> GeminiModelCatalogProvider()
        ApiProviderType.DEEPSEEK -> DeepSeekModelCatalogProvider()
        ApiProviderType.OPENROUTER -> OpenRouterModelCatalogProvider()
        ApiProviderType.MINIMAX -> MiniMaxModelCatalogProvider()
        ApiProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleModelCatalogProvider()
    }
}

/** Injectable wrapper (provided in NetworkModule) so VMs can fetch without touching OkHttp directly. */
class ModelCatalogService(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetchModels(config: ApiConfigValues): List<APIModelOption> =
        ModelCatalogProviderFactory.make(config.providerType).fetchModels(config, client, json)
}

/** User-facing catalog errors (messages mirror iOS APIModelCatalogError; shown in the picker status). */
sealed class ModelCatalogException(message: String) : Exception(message) {
    data object InvalidUrl : ModelCatalogException("模型列表接口地址无效") {
        private fun readResolve(): Any = InvalidUrl
    }
    data object MissingApiKey : ModelCatalogException("请先填写 API Key 再拉取模型列表") {
        private fun readResolve(): Any = MissingApiKey
    }
    data object InvalidResponse : ModelCatalogException("模型列表响应格式无法识别") {
        private fun readResolve(): Any = InvalidResponse
    }
    class HttpStatus(val code: Int, val body: String) : ModelCatalogException(
        body.trim().let { s ->
            if (s.isEmpty()) "拉取模型列表失败：HTTP $code" else "拉取模型列表失败：HTTP $code，${s.take(160)}"
        },
    )
}

/** Minimal authenticated GET that returns the body string, throwing [ModelCatalogException] on non-200. */
object ModelCatalogHttp {
    suspend fun get(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        timeoutSec: Long = 20,
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).get()
        for ((k, v) in headers) builder.addHeader(k, v)
        val timed = client.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).build()
        timed.newCall(builder.build()).execute().use { resp ->
            val body = runCatching { resp.body.string() }.getOrNull().orEmpty()
            if (resp.code != 200) throw ModelCatalogException.HttpStatus(resp.code, body)
            body
        }
    }
}

/**
 * Normalize a base URL into an OpenAI-style `/v1/models` endpoint (shared by OpenAICompatible /
 * DeepSeek / MiniMax). Mirrors the identical `buildModelsURL` in those three iOS providers.
 */
internal fun openAiStyleModelsUrl(baseUrl: String): String {
    val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: throw ModelCatalogException.InvalidUrl
    val scheme = uri.scheme ?: throw ModelCatalogException.InvalidUrl
    val authority = uri.authority ?: throw ModelCatalogException.InvalidUrl
    val segs = (uri.path ?: "").split("/").filter { it.isNotEmpty() }.toMutableList()
    val lower = "/" + segs.joinToString("/").lowercase()
    when {
        lower.endsWith("/v1/models") -> Unit
        lower.endsWith("/v1/chat/completions") -> {
            repeat(2) { segs.removeAt(segs.lastIndex) } // drop completions, chat
            segs.add("models")
        }
        lower.endsWith("/v1") -> segs.add("models")
        else -> { segs.add("v1"); segs.add("models") }
    }
    return "$scheme://$authority/" + segs.joinToString("/")
}

// Shared OpenAI-style /models response ({data:[{id, owned_by}]}) — used by
// OpenAICompatible / DeepSeek / MiniMax providers.
@Serializable
internal data class OpenAiModelsResponse(val data: List<OpenAiModelItem> = emptyList())

@Serializable
internal data class OpenAiModelItem(
    val id: String,
    @SerialName("owned_by") val ownedBy: String? = null,
)
