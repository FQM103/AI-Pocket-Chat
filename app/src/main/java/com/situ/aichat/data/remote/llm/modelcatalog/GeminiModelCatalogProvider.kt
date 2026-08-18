package com.situ.aichat.data.remote.llm.modelcatalog

import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.remote.llm.ApiConfigValues
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.net.URI
import java.net.URLEncoder

/**
 * Gemini model catalog — faithful port of iOS GeminiModelCatalogProvider.
 * Uses the native `/v1beta/models?key=` endpoint (not the OpenAI-compat layer); keeps only models
 * that support `generateContent` and strips the `models/` id prefix.
 */
class GeminiModelCatalogProvider : ModelCatalogProvider {
    override suspend fun fetchModels(
        config: ApiConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<APIModelOption> {
        if (config.apiKey.trim().isEmpty()) throw ModelCatalogException.MissingApiKey
        val url = buildModelsUrl(config.baseUrl, config.apiKey)
        val body = ModelCatalogHttp.get(client, url, emptyMap())
        val decoded = runCatching { json.decodeFromString(Response.serializer(), body) }
            .getOrNull() ?: throw ModelCatalogException.InvalidResponse
        return decoded.models
            .filter { it.supportedGenerationMethods.contains("generateContent") }
            .map {
                val id = it.name.replace("models/", "")
                APIModelOption(id = id, name = it.displayName ?: id, subtitle = it.description)
            }
    }

    private fun buildModelsUrl(baseUrl: String, apiKey: String): String {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: throw ModelCatalogException.InvalidUrl
        val scheme = uri.scheme ?: throw ModelCatalogException.InvalidUrl
        val authority = uri.authority ?: throw ModelCatalogException.InvalidUrl
        val segs = (uri.path ?: "").split("/").filter { it.isNotEmpty() }.toMutableList()
        normalizeToModelsEndpoint(segs)
        val newPath = "/" + segs.joinToString("/")

        val keptQuery = (uri.query ?: "").split("&").filter { it.isNotEmpty() && !it.startsWith("key=") }
        val query = (keptQuery + "key=${URLEncoder.encode(apiKey, "UTF-8")}").joinToString("&")
        return "$scheme://$authority$newPath?$query"
    }

    private fun normalizeToModelsEndpoint(segs: MutableList<String>) {
        val lower = "/" + segs.joinToString("/").lowercase()
        when {
            // /openai/models → strip /openai (only accepts Bearer); native path uses ?key=.
            lower.endsWith("/openai/models") -> {
                repeat(2) { segs.removeAt(segs.lastIndex) } // models, openai
                segs.add("models")
            }
            lower.endsWith("/models") || lower.contains("/models/") -> Unit
            lower.endsWith("/openai/chat/completions") -> {
                repeat(3) { segs.removeAt(segs.lastIndex) } // completions, chat, openai
                segs.add("models")
            }
            lower.endsWith("/openai") -> {
                segs.removeAt(segs.lastIndex) // openai
                segs.add("models")
            }
            lower.endsWith("/v1beta") || lower.endsWith("/v1") -> segs.add("models")
            else -> { segs.add("v1beta"); segs.add("models") }
        }
    }

    @Serializable
    private data class Response(val models: List<Item> = emptyList())

    @Serializable
    private data class Item(
        val name: String,
        val displayName: String? = null,
        val description: String? = null,
        val supportedGenerationMethods: List<String> = emptyList(),
    )
}
