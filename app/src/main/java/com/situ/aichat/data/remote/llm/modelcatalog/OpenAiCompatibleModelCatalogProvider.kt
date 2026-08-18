package com.situ.aichat.data.remote.llm.modelcatalog

import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.remote.llm.ApiConfigValues
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** Generic OpenAI-compatible /v1/models — faithful port of iOS OpenAICompatibleModelCatalogProvider. */
class OpenAiCompatibleModelCatalogProvider : ModelCatalogProvider {
    override suspend fun fetchModels(
        config: ApiConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<APIModelOption> {
        if (config.apiKey.trim().isEmpty()) throw ModelCatalogException.MissingApiKey
        val url = openAiStyleModelsUrl(config.baseUrl)
        val body = ModelCatalogHttp.get(client, url, mapOf("Authorization" to "Bearer ${config.apiKey}"))
        val decoded = runCatching { json.decodeFromString(OpenAiModelsResponse.serializer(), body) }
            .getOrNull() ?: throw ModelCatalogException.InvalidResponse
        return decoded.data.map { APIModelOption(id = it.id, name = it.id, subtitle = it.ownedBy) }
    }
}
