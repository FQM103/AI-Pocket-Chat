package com.situ.aichat.tts.provider

import com.situ.aichat.tts.TtsProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * One provider serving all three OpenAI-compatible TTS protocols (1:1 iOS `CompatibleTTSProvider`):
 * [TtsProviderType.OPENAI] / [TtsProviderType.VOLINK] / [TtsProviderType.CUSTOM_OPENAI_COMPATIBLE].
 * Identical flat request body `{model, input, voice, response_format}`; only the endpoint path
 * differs (OpenAI/Custom → `/v1/audio/speech`, Volink → `/v1/tts/speech`, the documented native
 * endpoint). Volink's voice catalog (`/api/v1/voices`) is paginated 20/page — [fetchVoices] walks
 * all pages (verified live 2026-07-11: 559 voices / 4 models). Response is raw audio bytes.
 */
class CompatibleTtsProvider(override val providerType: TtsProviderType) : TtsRemoteProvider {

    @Serializable
    private data class OpenAiCompatibleTtsRequestBody(
        val model: String,
        val input: String,
        val voice: String,
        @SerialName("response_format") val responseFormat: String,
    )

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        config: TtsRemoteConfigValues,
        client: OkHttpClient,
        json: Json,
    ): ByteArray = withContext(Dispatchers.IO) {
        val url = buildSpeechUrl(providerType, config.baseUrl)
        val bodyJson = json.encodeToString(
            OpenAiCompatibleTtsRequestBody.serializer(),
            OpenAiCompatibleTtsRequestBody(
                model = config.modelName,
                input = text,
                voice = voiceId,
                responseFormat = config.responseFormat.raw,
            ),
        )
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody(TtsProviderHelpers.JSON_MEDIA))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .build()
        client.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()
            .newCall(request).execute().use { response ->
                TtsProviderHelpers.validate(response)
                response.body.bytes()
            }
    }

    override suspend fun fetchVoices(
        config: TtsRemoteConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<TtsRemoteVoiceOption> = withContext(Dispatchers.IO) {
        if (providerType == TtsProviderType.VOLINK) fetchVolinkVoices(config, client, json)
        else fetchGenericVoices(config, client, json)
    }

    /**
     * Volink voice catalog via the dashboard-backed `/api/voices` (verified live 2026-07-11): rich
     * entries carrying `i18n["zh-CN"].name` — the exact Chinese names the Volink console shows —
     * plus `{data:[], total:N}` pagination with `page`/`page_size` (server 500s above 500/page).
     * The older `/api/v1/voices` only serves English names, which users can't match against the
     * console; neither endpoint is in the public docs, so both carry the same stability caveat.
     */
    private fun fetchVolinkVoices(
        config: TtsRemoteConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<TtsRemoteVoiceOption> {
        val all = ArrayList<TtsRemoteVoiceOption>()
        for (page in 1..VOLINK_VOICE_PAGE_CAP) {
            val query = mutableListOf("page" to page.toString(), "page_size" to VOLINK_VOICE_PAGE_SIZE.toString())
            config.modelName.trim().takeIf { it.isNotEmpty() }?.let { query.add(0, "model" to it) }
            val url = buildCatalogUrl(config.baseUrl, VOLINK_VOICE_CATALOG_PATH, query)
            val request = Request.Builder().url(url).get()
                .addHeader("Authorization", "Bearer ${config.apiKey}").build()
            val raw = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
                .newCall(request).execute().use { response ->
                    TtsProviderHelpers.validate(response)
                    response.body.string()
                }
            val (batch, total) = TtsProviderHelpers.parseVolinkVoicePage(raw, json)
            all += batch
            if (batch.isEmpty() || all.size >= total) break
            if (page == VOLINK_VOICE_PAGE_CAP) {
                // No silent caps: 50 × 100 ≫ the 559-voice live catalog; reaching this means it
                // outgrew the guard and the tail was dropped — say so instead of pretending fullness.
                android.util.Log.w("TtsService", "Volink voice catalog exceeds $VOLINK_VOICE_PAGE_CAP pages; list truncated")
            }
        }
        return TtsProviderHelpers.deduplicated(all) { it.id }
    }

    private fun fetchGenericVoices(
        config: TtsRemoteConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<TtsRemoteVoiceOption> {
        val url = buildCatalogUrl(
            baseUrl = config.baseUrl,
            path = voiceCatalogPath(providerType),
            query = TtsProviderHelpers.voiceCatalogQuery(config.modelName),
        )
        val request = Request.Builder().url(url).get()
            .addHeader("Authorization", "Bearer ${config.apiKey}").build()
        return client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
            .newCall(request).execute().use { response ->
                TtsProviderHelpers.validate(response)
                TtsProviderHelpers.parseVoiceOptions(response.body.string(), config.modelName, json)
            }
    }

    override suspend fun fetchModels(
        config: TtsRemoteConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<TtsRemoteModelOption> = withContext(Dispatchers.IO) {
        val url = buildCatalogUrl(baseUrl = config.baseUrl, path = modelCatalogPath(providerType))
        val request = Request.Builder().url(url).get()
            .addHeader("Authorization", "Bearer ${config.apiKey}").build()
        client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
            .newCall(request).execute().use { response ->
                TtsProviderHelpers.validate(response)
                val raw = response.body.string()
                TtsProviderHelpers.parseModelOptions(raw, json)
            }
    }

    override fun builtInVoices(modelName: String): List<TtsRemoteVoiceOption>? = when (providerType) {
        TtsProviderType.OPENAI -> {
            val model = modelName.trim().lowercase()
            val standard = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")
            val expressive = listOf("ash", "ballad", "coral", "sage", "verse")
            val voices = if (model == "gpt-4o-mini-tts") standard + expressive else standard
            voices.map { TtsRemoteVoiceOption(id = it, name = it, detail = null) }
        }
        // Volink / Custom have no built-in list → fetch from the live API (the user's own catalog).
        else -> null
    }

    override fun builtInModels(): List<TtsRemoteModelOption>? = when (providerType) {
        TtsProviderType.OPENAI -> listOf(
            TtsRemoteModelOption("gpt-4o-mini-tts", "gpt-4o-mini-tts"),
            TtsRemoteModelOption("tts-1", "tts-1"),
            TtsRemoteModelOption("tts-1-hd", "tts-1-hd"),
        )
        else -> null
    }

    companion object {

        /**
         * Synthesis endpoint (extends iOS `buildSpeechURL`):
         * - Volink + path ends `/tts/speech` → as-is (documented native endpoint)
         * - Volink + path ends `/api/v1/tts` → migrate stored legacy URL to `/v1/tts/speech`
         *   (same behavior verified live 2026-07-11, but only the new path is documented)
         * - Volink + path ends `/tts` → as-is (deliberate custom endpoint, respected)
         * - path ends `/audio/speech` → as-is
         * - path ends `/v1` → append `/audio/speech`
         * - otherwise → Volink appends `/v1/tts/speech`, others `/v1/audio/speech`
         */
        fun buildSpeechUrl(providerType: TtsProviderType, baseUrl: String): String {
            val normalized = TtsProviderHelpers.normalizedUrlString(baseUrl)
            val trimmed = normalized.trimEnd('/')
            val path = TtsProviderHelpers.pathOf(trimmed)
            val isVolink = providerType == TtsProviderType.VOLINK
            return when {
                isVolink && path.endsWith("/tts/speech") -> trimmed
                // Suffix-match the whole URL string (not just the URI path) so the cut below is
                // provably safe: a legacy URL carrying a query/fragment doesn't match and falls
                // through to the `/tts` as-is branch — exactly its pre-migration behavior.
                isVolink && trimmed.lowercase().endsWith("/api/v1/tts") ->
                    trimmed.dropLast("/api/v1/tts".length) + "/v1/tts/speech"
                isVolink && path.endsWith("/tts") -> trimmed
                path.endsWith("/audio/speech") -> trimmed
                path.endsWith("/v1") -> "$trimmed/audio/speech"
                isVolink -> "$trimmed/v1/tts/speech"
                else -> "$trimmed/v1/audio/speech"
            }
        }

        /** Catalog endpoint (iOS `buildCatalogURL`): replace the whole path, then add query params. */
        fun buildCatalogUrl(baseUrl: String, path: String, query: List<Pair<String, String>> = emptyList()): String {
            val normalized = TtsProviderHelpers.normalizedUrlString(baseUrl)
            val httpUrl = normalized.toHttpUrlOrNull() ?: throw TtsRemoteException.InvalidUrl
            val builder = httpUrl.newBuilder().encodedPath(if (path.startsWith("/")) path else "/$path")
            for ((key, value) in query) builder.addQueryParameter(key, value)
            return builder.build().toString()
        }

        fun modelCatalogPath(providerType: TtsProviderType): String =
            if (providerType == TtsProviderType.VOLINK) "/api/v1/models" else "/v1/models"

        fun voiceCatalogPath(providerType: TtsProviderType): String =
            if (providerType == TtsProviderType.VOLINK) VOLINK_VOICE_CATALOG_PATH else "/v1/voices"

        /** Dashboard-backed catalog with i18n names — see [fetchVolinkVoices]. */
        internal const val VOLINK_VOICE_CATALOG_PATH = "/api/voices"

        /** Verified safe (server 500s above 500/page); 100 keeps single-model fetches to 1–3 requests. */
        internal const val VOLINK_VOICE_PAGE_SIZE = 100

        /** Safety cap on Volink voice pagination: 50 pages × 100 ≫ the 559-voice live catalog. */
        internal const val VOLINK_VOICE_PAGE_CAP = 50
    }
}
