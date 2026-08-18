package com.situ.aichat.data.remote.llm.modelcatalog

import android.util.Log
import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.remote.llm.ApiConfigValues
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * MiniMax model catalog — faithful port of iOS MiniMaxModelCatalogProvider.
 * Tries /models; falls back to a hardcoded list when there's no key or the fetch fails.
 */
class MiniMaxModelCatalogProvider : ModelCatalogProvider {
    override suspend fun fetchModels(
        config: ApiConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<APIModelOption> {
        val key = config.apiKey.trim()
        if (key.isEmpty()) return FALLBACK_MODELS
        return try {
            val url = openAiStyleModelsUrl(config.baseUrl)
            val body = ModelCatalogHttp.get(client, url, mapOf("Authorization" to "Bearer $key"))
            val decoded = json.decodeFromString(OpenAiModelsResponse.serializer(), body)
            val models = decoded.data.map { APIModelOption(id = it.id, name = it.id, subtitle = it.ownedBy) }
            models.ifEmpty { FALLBACK_MODELS }
        } catch (e: Exception) {
            Log.w(TAG, "模型目录拉取失败,退兜底列表: ${e.message}")
            FALLBACK_MODELS
        }
    }

    private companion object {
        const val TAG = "MiniMaxCatalog"

        val FALLBACK_MODELS = listOf(
            APIModelOption("MiniMax-M2.7", "MiniMax M2.7", "最新旗舰模型，204K 上下文"),
            APIModelOption("MiniMax-M2.7-highspeed", "MiniMax M2.7 Highspeed", "M2.7 高速版，约 100 tps"),
            APIModelOption("MiniMax-M2.5", "MiniMax M2.5", "思考模型，204K 上下文"),
            APIModelOption("MiniMax-M2.5-highspeed", "MiniMax M2.5 Highspeed", "M2.5 高速版，约 100 tps"),
            APIModelOption("MiniMax-M2.1", "MiniMax M2.1", "通用模型，204K 上下文"),
            APIModelOption("MiniMax-M2.1-highspeed", "MiniMax M2.1 Highspeed", "M2.1 高速版，约 100 tps"),
            APIModelOption("MiniMax-M2", "MiniMax M2", "基础模型，204K 上下文"),
        )
    }
}
