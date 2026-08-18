package com.situ.aichat.data.remote.llm.modelcatalog

import android.util.Log
import com.situ.aichat.data.model.APIModelOption
import com.situ.aichat.data.model.KnownModelCapabilityTable
import com.situ.aichat.data.remote.llm.ApiConfigValues
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * DeepSeek model catalog — faithful port of iOS DeepSeekModelCatalogProvider.
 * Tries the OpenAI-compatible /models endpoint; falls back to a hardcoded list (with
 * past-deprecation entries filtered out) when there's no key or the fetch fails.
 */
class DeepSeekModelCatalogProvider : ModelCatalogProvider {
    override suspend fun fetchModels(
        config: ApiConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<APIModelOption> {
        val key = config.apiKey.trim()
        if (key.isEmpty()) return fallbackModels()
        return try {
            val url = openAiStyleModelsUrl(config.baseUrl)
            val body = ModelCatalogHttp.get(client, url, mapOf("Authorization" to "Bearer $key"))
            val decoded = json.decodeFromString(OpenAiModelsResponse.serializer(), body)
            val models = decoded.data.map { APIModelOption(id = it.id, name = it.id, subtitle = it.ownedBy) }
            models.ifEmpty { fallbackModels() }
        } catch (e: Exception) {
            Log.w(TAG, "模型目录拉取失败,退兜底列表: ${e.message}")
            fallbackModels()
        }
    }

    companion object {
        private const val TAG = "DeepSeekCatalog"

        // Subtitles kept inline (Chinese, matching iOS base strings) — these are fallback hints,
        // not part of the resource-localized UI chrome.
        private val rawFallbackModels = listOf(
            APIModelOption("deepseek-v4-flash", "DeepSeek V4 Flash", "通用主力 · 1M 上下文 · 可开关思考"),
            APIModelOption("deepseek-v4-pro", "DeepSeek V4 Pro", "复杂任务 · 支持思考分档"),
            APIModelOption("deepseek-chat", "DeepSeek Chat", "⚠️ 2026-07-24 弃用 · 建议改用 deepseek-v4-flash"),
            APIModelOption("deepseek-reasoner", "DeepSeek Reasoner", "⚠️ 2026-07-24 弃用 · 建议改用 deepseek-v4-flash 并开启思考"),
        )

        fun fallbackModels(): List<APIModelOption> =
            filterOutPastDeprecation(rawFallbackModels, System.currentTimeMillis())

        /** Drop entries whose deprecationDate has passed (strict <, so the day itself counts as deprecated). */
        fun filterOutPastDeprecation(models: List<APIModelOption>, nowMillis: Long): List<APIModelOption> =
            models.filter { option ->
                val cap = KnownModelCapabilityTable.lookup(option.id)
                val dateStr = cap?.takeIf { it.isDeprecated }?.deprecationDate ?: return@filter true
                val deprecateMillis = parseDeprecationDateMillis(dateStr) ?: return@filter true
                nowMillis < deprecateMillis
            }

        /** Parse "yyyy-MM-dd" as UTC midnight epoch millis. */
        private fun parseDeprecationDateMillis(dateStr: String): Long? = runCatching {
            LocalDate.parse(dateStr).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
