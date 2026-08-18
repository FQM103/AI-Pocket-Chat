package com.situ.aichat.tts.provider

import com.situ.aichat.tts.HexDataDecoder
import com.situ.aichat.tts.TtsProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * MiniMax T2A v2 HTTP provider (1:1 iOS `MiniMaxTTSProvider`). Differs from OpenAI-compatible:
 * 1. nested request body (`voice_setting` / `audio_setting`), not flat
 * 2. response is JSON-wrapped hex audio, not raw bytes
 * 3. HTTP 200 may still carry a business error (`base_resp.status_code != 0`)
 * 4. fixed path `/v1/t2a_v2`, not auto-completed like `/v1/audio/speech`
 */
class MiniMaxTtsProvider : TtsRemoteProvider {

    override val providerType: TtsProviderType = TtsProviderType.MINIMAX

    // MARK: - Request / response DTOs (no Kotlin defaults on request fields, so encodeDefaults=false
    // never drops them; only the nullable `emotion` is omitted when null via explicitNulls=false).

    @Serializable
    private data class Req(
        val model: String,
        val text: String,
        val stream: Boolean,
        @SerialName("voice_setting") val voiceSetting: VoiceSetting,
        @SerialName("audio_setting") val audioSetting: AudioSetting,
        @SerialName("language_boost") val languageBoost: String,
        @SerialName("output_format") val outputFormat: String,
    ) {
        @Serializable
        data class VoiceSetting(
            @SerialName("voice_id") val voiceId: String,
            val speed: Double,
            val vol: Double,
            val pitch: Int,
            val emotion: String?,
        )

        @Serializable
        data class AudioSetting(
            @SerialName("sample_rate") val sampleRate: Int,
            val bitrate: Int,
            val format: String,
            val channel: Int,
        )
    }

    @Serializable
    private data class Resp(
        val data: ResponseData? = null,
        @SerialName("base_resp") val baseResp: BaseResp? = null,
    ) {
        @Serializable data class ResponseData(val audio: String? = null, val status: Int? = null)

        @Serializable
        data class BaseResp(
            @SerialName("status_code") val statusCode: Int = 0,
            @SerialName("status_msg") val statusMsg: String = "",
        )
    }

    @Serializable private data class GetVoiceRequestBody(@SerialName("voice_type") val voiceType: String)

    @Serializable
    private data class GetVoiceResponse(
        @SerialName("system_voice") val systemVoice: List<VoiceEntry>? = null,
        @SerialName("voice_cloning") val voiceCloning: List<VoiceEntry>? = null,
        @SerialName("voice_generation") val voiceGeneration: List<VoiceEntry>? = null,
        @SerialName("base_resp") val baseResp: Resp.BaseResp? = null,
    ) {
        @Serializable
        data class VoiceEntry(
            @SerialName("voice_id") val voiceId: String? = null,
            @SerialName("voice_name") val voiceName: String? = null,
            val description: List<String>? = null,
            @SerialName("created_time") val createdTime: String? = null,
        )
    }

    // MARK: - TtsRemoteProvider

    override suspend fun synthesize(
        text: String,
        voiceId: String,
        config: TtsRemoteConfigValues,
        client: OkHttpClient,
        json: Json,
    ): ByteArray = withContext(Dispatchers.IO) {
        val url = buildEndpointUrl(config.baseUrl)
        // emotion was already clamped upstream (TtsService.buildMiniMaxOverridesFromRawValues).
        val overrides = config.miniMaxVoiceOverrides
        val body = Req(
            model = config.modelName,
            text = text,
            stream = false,
            voiceSetting = Req.VoiceSetting(
                voiceId = voiceId,
                speed = overrides?.speed ?: 1.0,
                vol = 1.0,
                pitch = overrides?.pitch ?: 0,
                emotion = overrides?.emotion,
            ),
            audioSetting = DEFAULT_MP3,
            languageBoost = "auto",
            outputFormat = "hex",
        )
        val request = Request.Builder()
            .url(url)
            .post(json.encodeToString(Req.serializer(), body).toRequestBody(TtsProviderHelpers.JSON_MEDIA))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .build()
        client.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()
            .newCall(request).execute().use { response ->
                TtsProviderHelpers.validate(response)
                val raw = response.body.string()
                decodeAudioResponse(raw, json)
            }
    }

    override suspend fun fetchVoices(
        config: TtsRemoteConfigValues,
        client: OkHttpClient,
        json: Json,
    ): List<TtsRemoteVoiceOption> = withContext(Dispatchers.IO) {
        val url = buildGetVoiceUrl(config.baseUrl)
        val request = Request.Builder()
            .url(url)
            .post(json.encodeToString(GetVoiceRequestBody.serializer(), GetVoiceRequestBody("all"))
                .toRequestBody(TtsProviderHelpers.JSON_MEDIA))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .build()
        client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
            .newCall(request).execute().use { response ->
                TtsProviderHelpers.validate(response)
                val raw = response.body.string()
                parseGetVoiceResponse(raw, json)
            }
    }

    // Model list is hardcoded (the catalog), not a remote call.
    override suspend fun fetchModels(config: TtsRemoteConfigValues, client: OkHttpClient, json: Json): List<TtsRemoteModelOption> =
        MiniMaxCatalog.builtInModels

    // null → the caller hits /v1/get_voice; UI falls back to MiniMaxCatalog.fallbackVoices on failure.
    override fun builtInVoices(modelName: String): List<TtsRemoteVoiceOption>? = null

    override fun builtInModels(): List<TtsRemoteModelOption> = MiniMaxCatalog.builtInModels

    companion object {
        private val DEFAULT_MP3 = Req.AudioSetting(sampleRate = 32000, bitrate = 128000, format = "mp3", channel = 1)

        /**
         * T2A v2 endpoint (iOS `buildEndpointURL`). Fixed path `/v1/t2a_v2`:
         * - ends `/v1/t2a_v2` → as-is
         * - empty path → append `/v1/t2a_v2`
         * - ends `/v1` → append `/t2a_v2`
         * - otherwise → as-is (user's custom proxy)
         */
        fun buildEndpointUrl(baseUrl: String): String {
            val trimmed = TtsProviderHelpers.normalizedUrlString(baseUrl).trimEnd('/')
            val path = TtsProviderHelpers.pathOf(trimmed)
            return when {
                path.endsWith("/v1/t2a_v2") -> trimmed
                path.isEmpty() -> "$trimmed/v1/t2a_v2"
                path.endsWith("/v1") -> "$trimmed/t2a_v2"
                else -> trimmed
            }
        }

        /**
         * get_voice endpoint (iOS `buildGetVoiceURL`). `/v1/get_voice` lives beside `/v1/t2a_v2`:
         * - ends `/v1/get_voice` → as-is
         * - ends `/v1/t2a_v2` → swap the last segment to `get_voice`
         * - ends `/v1` → append `/get_voice`
         * - empty path → `/v1/get_voice`
         * - otherwise → append `/v1/get_voice`
         */
        fun buildGetVoiceUrl(baseUrl: String): String {
            val trimmed = TtsProviderHelpers.normalizedUrlString(baseUrl).trimEnd('/')
            val path = TtsProviderHelpers.pathOf(trimmed)
            return when {
                path.endsWith("/v1/get_voice") -> trimmed
                path.endsWith("/v1/t2a_v2") -> trimmed.dropLast("t2a_v2".length) + "get_voice"
                path.endsWith("/v1") -> "$trimmed/get_voice"
                path.isEmpty() -> "$trimmed/v1/get_voice"
                else -> "$trimmed/v1/get_voice"
            }
        }

        /**
         * Decode MiniMax's JSON response to audio bytes. Validation chain: parseable JSON →
         * base_resp.status_code == 0 → non-empty hex → non-empty decoded bytes.
         */
        fun decodeAudioResponse(raw: String, json: Json): ByteArray {
            val parsed = runCatching { json.decodeFromString(Resp.serializer(), raw) }
                .getOrElse { throw TtsRemoteException.InvalidResponse }
            parsed.baseResp?.let { base ->
                if (base.statusCode != 0) throw TtsRemoteException.BusinessError(base.statusCode, base.statusMsg)
            }
            val hex = parsed.data?.audio
            if (hex.isNullOrEmpty()) throw TtsRemoteException.InvalidResponse
            val bytes = HexDataDecoder.dataFromHexString(hex)
            if (bytes == null || bytes.isEmpty()) throw TtsRemoteException.InvalidResponse
            return bytes
        }

        /** Merge system + cloning + generation voices (iOS `parseGetVoiceResponse`). */
        fun parseGetVoiceResponse(raw: String, json: Json): List<TtsRemoteVoiceOption> {
            val parsed = runCatching { json.decodeFromString(GetVoiceResponse.serializer(), raw) }
                .getOrElse { throw TtsRemoteException.InvalidResponse }
            parsed.baseResp?.let { base ->
                if (base.statusCode != 0) throw TtsRemoteException.BusinessError(base.statusCode, base.statusMsg)
            }
            val result = buildList {
                parsed.systemVoice?.forEach { makeOption(it, kindLabel = null, kind = TtsVoiceKind.SYSTEM)?.let(::add) }
                parsed.voiceCloning?.forEach { makeOption(it, kindLabel = "克隆", kind = TtsVoiceKind.CLONED)?.let(::add) }
                parsed.voiceGeneration?.forEach { makeOption(it, kindLabel = "生成", kind = TtsVoiceKind.GENERATED)?.let(::add) }
            }
            return TtsProviderHelpers.deduplicated(result) { it.id }
        }

        private fun makeOption(entry: GetVoiceResponse.VoiceEntry, kindLabel: String?, kind: TtsVoiceKind): TtsRemoteVoiceOption? {
            val id = entry.voiceId?.trim()
            if (id.isNullOrEmpty()) return null
            val rawName = entry.voiceName?.trim()
            val displayName = if (!rawName.isNullOrEmpty()) rawName else id
            val descriptionPart = entry.description
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.joinToString(" · ")
            val detailParts = buildList {
                if (!kindLabel.isNullOrEmpty()) add(kindLabel)
                if (!descriptionPart.isNullOrEmpty()) add(descriptionPart)
            }
            val detail = detailParts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            return TtsRemoteVoiceOption(id = id, name = displayName, detail = detail, kind = kind)
        }
    }
}
