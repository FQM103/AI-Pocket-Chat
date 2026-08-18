package com.situ.aichat.tts.provider

import com.situ.aichat.data.remote.llm.LlmHttp
import com.situ.aichat.tts.TtsProviderType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.URI

/**
 * Shared abstraction for remote TTS providers (1:1 iOS `TTSRemoteProvider`). TTSService routes by
 * [providerType]; each provider owns its URL building / request shape / response parsing. Providers
 * are stateless — the OkHttp client and Json are passed in per call (mirrors iOS's per-call session).
 */
interface TtsRemoteProvider {

    val providerType: TtsProviderType

    /** Synthesize one segment → audio bytes. Throws [TtsRemoteException] on failure. */
    suspend fun synthesize(
        text: String,
        voiceId: String,
        config: TtsRemoteConfigValues,
        client: OkHttpClient,
        json: Json,
    ): ByteArray

    /** Fetch the remote voice list (skipped by the caller when [builtInVoices] returns non-null). */
    suspend fun fetchVoices(config: TtsRemoteConfigValues, client: OkHttpClient, json: Json): List<TtsRemoteVoiceOption>

    /** Fetch the remote model list (skipped when [builtInModels] returns non-null). */
    suspend fun fetchModels(config: TtsRemoteConfigValues, client: OkHttpClient, json: Json): List<TtsRemoteModelOption>

    /** Built-in voices, or null to force a [fetchVoices] network call. */
    fun builtInVoices(modelName: String): List<TtsRemoteVoiceOption>?

    /** Built-in models, or null to force a [fetchModels] network call. */
    fun builtInModels(): List<TtsRemoteModelOption>?
}

/**
 * Shared helpers for the provider layer (1:1 iOS `TTSProviderHelpers`): URL normalization, HTTP
 * status validation, and generic OpenAI-compatible voice/model JSON parsing. All pure / stateless.
 */
object TtsProviderHelpers {

    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** HTTP 200 only; any other status throws [TtsRemoteException.HttpError]. */
    fun validate(response: Response) {
        if (response.code != 200) throw TtsRemoteException.HttpError(response.code)
    }

    /**
     * Normalize a user-entered base URL: require http/https; auto-upgrade non-local plaintext http
     * to https (so the Bearer key + audio aren't sent in clear). Reuses [LlmHttp.shouldUpgradeInsecureHost]
     * so TTS and chat share one rule. Returns the normalized URL string (trailing slash preserved).
     */
    fun normalizedUrlString(baseUrl: String): String {
        var s = baseUrl.trim()
        if (s.isEmpty()) throw TtsRemoteException.InvalidUrl
        val uri = runCatching { URI(s) }.getOrNull() ?: throw TtsRemoteException.InvalidUrl
        val scheme = uri.scheme?.lowercase() ?: throw TtsRemoteException.InvalidUrl
        if (scheme != "http" && scheme != "https") throw TtsRemoteException.InvalidUrl
        if (scheme == "http" && LlmHttp.shouldUpgradeInsecureHost(uri.host)) {
            s = "https://" + s.substringAfter("://")
        }
        return s
    }

    /** Lowercased path of a URL string ("" if unparseable / pathless). */
    fun pathOf(url: String): String = (runCatching { URI(url).path }.getOrNull() ?: "").lowercase()

    /** Voice-catalog query params — some providers filter available voices by `model` / `model_id`. */
    fun voiceCatalogQuery(modelName: String): List<Pair<String, String>> {
        val m = modelName.trim()
        if (m.isEmpty()) return emptyList()
        return listOf("model" to m, "model_id" to m)
    }

    fun <T> deduplicated(items: List<T>, key: (T) -> String): List<T> {
        val seen = HashSet<String>()
        val out = ArrayList<T>(items.size)
        for (item in items) if (seen.add(key(item))) out.add(item)
        return out
    }

    // MARK: - Generic OpenAI-compatible voice/model parsing

    /**
     * Strip raw C0 control characters. Volink's live catalog has at least one voice name containing
     * an unescaped control character (verified 2026-07-11, page 28), which strict JSON parsing
     * rejects wholesale — removing them never changes the meaning of otherwise-valid JSON.
     */
    fun sanitizedJson(raw: String): String =
        if (raw.any { it.code < 0x20 }) raw.filterNot { it.code < 0x20 } else raw

    /**
     * One page of Volink's `/api/voices` catalog: `{data:[{id, name, i18n:{"zh-CN":{name},"en-US":{name}}, …}], total}`.
     * Option name prefers the Chinese `i18n` name (what the Volink console shows) and keeps the
     * English name as detail for cross-reference. Missing `total` → MAX_VALUE (the caller's
     * empty-page break then terminates pagination).
     */
    fun parseVolinkVoicePage(raw: String, json: Json): Pair<List<TtsRemoteVoiceOption>, Int> {
        val root = runCatching { json.parseToJsonElement(sanitizedJson(raw)) }
            .getOrElse { throw TtsRemoteException.InvalidResponse } as? JsonObject
            ?: throw TtsRemoteException.InvalidResponse
        val voices = ((root["data"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj.strOrNull("id") ?: return@mapNotNull null
            if (id.isEmpty()) return@mapNotNull null
            val i18n = obj["i18n"] as? JsonObject
            val zhName = (i18n?.get("zh-CN") as? JsonObject)?.strOrNull("name")
            val enName = (i18n?.get("en-US") as? JsonObject)?.strOrNull("name") ?: obj.strOrNull("name")
            val name = zhName?.takeIf { it.isNotEmpty() } ?: enName?.takeIf { it.isNotEmpty() } ?: id
            val detail = enName?.takeIf { it.isNotEmpty() && it != name }
            TtsRemoteVoiceOption(id = id, name = name, detail = detail)
        }
        val total = (root["total"] as? JsonPrimitive)?.content?.toIntOrNull() ?: Int.MAX_VALUE
        return voices to total
    }

    /** Parse voices from `data`/`voices`/`items` arrays, filtering by [modelName] when the entry declares one. */
    fun parseVoiceOptions(raw: String, modelName: String, json: Json): List<TtsRemoteVoiceOption> {
        val root = runCatching { json.parseToJsonElement(sanitizedJson(raw)) }.getOrElse { throw TtsRemoteException.InvalidResponse }
        val voices = extractObjectArray(root).mapNotNull { obj ->
            if (!voiceSupportsModel(obj, modelName)) return@mapNotNull null
            val id = obj.strOrNull("voice_id") ?: obj.strOrNull("id") ?: obj.strOrNull("name")
            if (id.isNullOrEmpty()) return@mapNotNull null
            val name = obj.strOrNull("display_name") ?: obj.strOrNull("short_name") ?: obj.strOrNull("name") ?: id
            val detail = obj.strOrNull("language") ?: obj.strOrNull("gender") ?: obj.strOrNull("description")
            TtsRemoteVoiceOption(id = id, name = name, detail = detail)
        }
        return deduplicated(voices) { it.id }
    }

    /** Parse models from `data`/`models`/`items` arrays. */
    fun parseModelOptions(raw: String, json: Json): List<TtsRemoteModelOption> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrElse { throw TtsRemoteException.InvalidResponse }
        val models = extractObjectArray(root).mapNotNull { obj ->
            val id = obj.strOrNull("id") ?: obj.strOrNull("model") ?: obj.strOrNull("name")
            if (id.isNullOrEmpty()) return@mapNotNull null
            val name = obj.strOrNull("name") ?: id
            TtsRemoteModelOption(id = id, name = name)
        }
        return deduplicated(models) { it.id }
    }

    private fun extractObjectArray(root: kotlinx.serialization.json.JsonElement): List<JsonObject> {
        val array: JsonArray? = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] ?: root["voices"] ?: root["models"] ?: root["items"]) as? JsonArray
            else -> null
        }
        return array?.mapNotNull { it as? JsonObject } ?: emptyList()
    }

    /** Present string value (including ""), or null if absent / not a string — matches iOS `as? String`. */
    private fun JsonObject.strOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun voiceSupportsModel(obj: JsonObject, modelName: String): Boolean {
        val m = modelName.trim()
        if (m.isEmpty()) return true
        val normalized = m.lowercase()
        for (key in listOf("model", "model_id", "modelId", "voice_model", "voiceModel")) {
            val value = obj.strOrNull(key)
            if (value != null && value.lowercase() != normalized) return false
        }
        for (key in listOf("models", "model_ids", "modelIds", "supported_models", "supportedModels")) {
            val array = obj[key] as? JsonArray ?: continue
            if (array.isEmpty()) continue
            val values = array.mapNotNull { modelIdentifier(it) }.map { it.lowercase() }
            if (values.isNotEmpty()) return values.contains(normalized)
        }
        return true
    }

    private fun modelIdentifier(element: kotlinx.serialization.json.JsonElement): String? = when (element) {
        is JsonPrimitive -> if (element.isString) element.content else null
        is JsonObject -> element.strOrNull("id") ?: element.strOrNull("model") ?: element.strOrNull("name")
        else -> null
    }
}
