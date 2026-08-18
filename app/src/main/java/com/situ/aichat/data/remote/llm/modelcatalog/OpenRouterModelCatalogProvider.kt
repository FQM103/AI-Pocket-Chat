package com.situ.aichat.data.remote.llm.modelcatalog

import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.LlmHttp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.net.URI

/** OpenRouter model catalog — faithful port of iOS OpenRouterModelCatalogProvider. */
class OpenRouterModelCatalogProvider : ModelCatalogProvider {
    override suspend fun fetchModels(
        config: ApiConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<APIModelOption> {
        if (config.apiKey.trim().isEmpty()) throw ModelCatalogException.MissingApiKey
        val url = buildModelsUrl(config.baseUrl)
        val body = ModelCatalogHttp.get(client, url, LlmHttp.authHeaders(config))
        val decoded = runCatching { json.decodeFromString(Response.serializer(), body) }
            .getOrNull() ?: throw ModelCatalogException.InvalidResponse
        return decoded.data.map {
            APIModelOption(
                id = it.id,
                name = it.name ?: it.id,
                subtitle = it.subtitle(),
                supportedParameters = it.supportedParameters,
            )
        }
    }

    private fun buildModelsUrl(baseUrl: String): String {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: throw ModelCatalogException.InvalidUrl
        val scheme = uri.scheme ?: throw ModelCatalogException.InvalidUrl
        val authority = uri.authority ?: throw ModelCatalogException.InvalidUrl
        val segs = (uri.path ?: "").split("/").filter { it.isNotEmpty() }.toMutableList()
        val lower = "/" + segs.joinToString("/").lowercase()
        when {
            lower.endsWith("/models") -> Unit
            lower.endsWith("/chat/completions") -> {
                repeat(2) { segs.removeAt(segs.lastIndex) } // drop completions, chat
                segs.add("models")
            }
            else -> {
                if (!lower.endsWith("/api/v1") && !lower.endsWith("/v1")) {
                    segs.add("api"); segs.add("v1")
                }
                segs.add("models")
            }
        }
        return "$scheme://$authority/" + segs.joinToString("/")
    }

    @Serializable
    private data class Response(val data: List<Item> = emptyList())

    @Serializable
    private data class Item(
        val id: String,
        val name: String? = null,
        val description: String? = null,
        @SerialName("context_length") val contextLength: Int? = null,
        @SerialName("supported_parameters") val supportedParameters: List<String>? = null,
    ) {
        fun subtitle(): String? {
            val parts = buildList {
                contextLength?.let { add("${formatContextLength(it)} ctx") }
                if (supportsReasoningControl()) add("reasoning")
            }
            if (parts.isNotEmpty()) return parts.joinToString(" · ")
            return description?.takeIf { it.isNotEmpty() }
        }

        private fun supportsReasoningControl(): Boolean {
            val p = supportedParameters ?: return false
            return p.contains("reasoning") || p.contains("reasoning.max_tokens") || p.contains("reasoning.effort")
        }

        private fun formatContextLength(value: Int): String = when {
            value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0).replace(".0", "")
            value >= 1_000 -> "%.0fK".format(value / 1_000.0)
            else -> "$value"
        }
    }
}
