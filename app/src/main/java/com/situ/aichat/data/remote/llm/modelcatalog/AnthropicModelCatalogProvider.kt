package com.situ.aichat.data.remote.llm.modelcatalog

import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.remote.llm.ApiConfigValues
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.net.URI

/** Anthropic model catalog — faithful port of iOS AnthropicModelCatalogProvider (native /v1/models). */
class AnthropicModelCatalogProvider : ModelCatalogProvider {
    override suspend fun fetchModels(
        config: ApiConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<APIModelOption> {
        if (config.apiKey.trim().isEmpty()) throw ModelCatalogException.MissingApiKey
        val url = buildModelsUrl(config.baseUrl)
        val body = ModelCatalogHttp.get(
            client,
            url,
            mapOf("x-api-key" to config.apiKey, "anthropic-version" to "2023-06-01"),
        )
        val decoded = runCatching { json.decodeFromString(Response.serializer(), body) }
            .getOrNull() ?: throw ModelCatalogException.InvalidResponse
        return decoded.data.map { APIModelOption(id = it.id, name = it.displayName ?: it.id, subtitle = it.type) }
    }

    private fun buildModelsUrl(baseUrl: String): String {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: throw ModelCatalogException.InvalidUrl
        val scheme = uri.scheme ?: throw ModelCatalogException.InvalidUrl
        val authority = uri.authority ?: throw ModelCatalogException.InvalidUrl
        val segs = (uri.path ?: "").split("/").filter { it.isNotEmpty() }.toMutableList()
        val lower = "/" + segs.joinToString("/").lowercase()
        when {
            lower.endsWith("/v1/models") -> Unit
            lower.endsWith("/v1") -> segs.add("models")
            else -> { segs.add("v1"); segs.add("models") }
        }
        return "$scheme://$authority/" + segs.joinToString("/")
    }

    @Serializable
    private data class Response(val data: List<Item> = emptyList())

    @Serializable
    private data class Item(
        val id: String,
        @SerialName("display_name") val displayName: String? = null,
        val type: String? = null,
    )
}
