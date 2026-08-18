package com.situ.aichat.tts.provider

import com.situ.aichat.tts.TtsProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * URL building + region detection + provider-type migration. Assertions reverse-derived from iOS
 * `MiniMaxTTSProvider` / `CompatibleTTSProvider` / `MiniMaxRegion` / `TTSConfiguration`.
 */
class TtsUrlBuildingTest {

    // MARK: - MiniMax T2A endpoint

    @Test
    fun `minimax endpoint completion`() {
        assertEquals("https://api.minimaxi.com/v1/t2a_v2", MiniMaxTtsProvider.buildEndpointUrl("https://api.minimaxi.com/v1/t2a_v2"))
        assertEquals("https://api.minimaxi.com/v1/t2a_v2", MiniMaxTtsProvider.buildEndpointUrl("https://api.minimaxi.com/v1/t2a_v2/"))
        assertEquals("https://api.minimaxi.com/v1/t2a_v2", MiniMaxTtsProvider.buildEndpointUrl("https://api.minimaxi.com/v1"))
        assertEquals("https://api.minimaxi.com/v1/t2a_v2", MiniMaxTtsProvider.buildEndpointUrl("https://api.minimaxi.com"))
        // custom proxy path is left as-is
        assertEquals("https://proxy.example.com/foo", MiniMaxTtsProvider.buildEndpointUrl("https://proxy.example.com/foo"))
    }

    @Test
    fun `minimax endpoint upgrades non-local http but keeps localhost`() {
        assertEquals("https://api.minimax.io/v1/t2a_v2", MiniMaxTtsProvider.buildEndpointUrl("http://api.minimax.io"))
        assertEquals("http://localhost:8080/v1/t2a_v2", MiniMaxTtsProvider.buildEndpointUrl("http://localhost:8080/v1"))
    }

    @Test
    fun `minimax get_voice endpoint`() {
        assertEquals("https://api.minimaxi.com/v1/get_voice", MiniMaxTtsProvider.buildGetVoiceUrl("https://api.minimaxi.com/v1/t2a_v2"))
        assertEquals("https://api.minimaxi.com/v1/get_voice", MiniMaxTtsProvider.buildGetVoiceUrl("https://api.minimaxi.com/v1"))
        assertEquals("https://api.minimaxi.com/v1/get_voice", MiniMaxTtsProvider.buildGetVoiceUrl("https://api.minimaxi.com"))
        assertEquals("https://api.minimaxi.com/v1/get_voice", MiniMaxTtsProvider.buildGetVoiceUrl("https://api.minimaxi.com/v1/get_voice"))
    }

    // MARK: - OpenAI-compatible speech endpoint

    @Test
    fun `compatible speech endpoint per provider`() {
        // Volink documented native endpoint kept as-is
        assertEquals(
            "https://api.volink.org/v1/tts/speech",
            CompatibleTtsProvider.buildSpeechUrl(TtsProviderType.VOLINK, "https://api.volink.org/v1/tts/speech"),
        )
        // stored legacy default is migrated to the documented endpoint
        assertEquals(
            "https://api.volink.org/v1/tts/speech",
            CompatibleTtsProvider.buildSpeechUrl(TtsProviderType.VOLINK, "https://api.volink.org/api/v1/tts"),
        )
        assertEquals(
            "https://api.volink.org/v1/tts/speech",
            CompatibleTtsProvider.buildSpeechUrl(TtsProviderType.VOLINK, "https://api.volink.org/api/v1/tts/"),
        )
        // bare host completes to the documented endpoint
        assertEquals(
            "https://api.volink.org/v1/tts/speech",
            CompatibleTtsProvider.buildSpeechUrl(TtsProviderType.VOLINK, "https://api.volink.org"),
        )
        // a deliberate custom /tts endpoint (not the legacy default) is respected as-is
        assertEquals(
            "https://relay.example.com/custom/tts",
            CompatibleTtsProvider.buildSpeechUrl(TtsProviderType.VOLINK, "https://relay.example.com/custom/tts"),
        )
        // legacy URL carrying a query is NOT migrated (the cut would corrupt it) — kept as-is,
        // which is exactly its pre-migration behavior
        assertEquals(
            "https://api.volink.org/api/v1/tts?x=1",
            CompatibleTtsProvider.buildSpeechUrl(TtsProviderType.VOLINK, "https://api.volink.org/api/v1/tts?x=1"),
        )
        // migration suffix match is case-insensitive on the URL string
        assertEquals(
            "https://api.volink.org/v1/tts/speech",
            CompatibleTtsProvider.buildSpeechUrl(TtsProviderType.VOLINK, "https://api.volink.org/API/v1/TTS"),
        )
        // OpenAI / Custom complete to /v1/audio/speech
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            CompatibleTtsProvider.buildSpeechUrl(TtsProviderType.OPENAI, "https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1/audio/speech",
            CompatibleTtsProvider.buildSpeechUrl(TtsProviderType.OPENAI, "https://api.openai.com/v1/audio/speech"),
        )
        assertEquals(
            "https://x.example.com/v1/audio/speech",
            CompatibleTtsProvider.buildSpeechUrl(TtsProviderType.CUSTOM_OPENAI_COMPATIBLE, "https://x.example.com"),
        )
    }

    @Test
    fun `compatible catalog url replaces path and adds query`() {
        assertEquals(
            "https://api.volink.org/api/v1/voices",
            CompatibleTtsProvider.buildCatalogUrl("https://api.volink.org/api/v1/tts", "/api/v1/voices"),
        )
        assertEquals(
            "https://api.volink.org/api/v1/voices?model=x&model_id=x",
            CompatibleTtsProvider.buildCatalogUrl("https://api.volink.org/api/v1/tts", "/api/v1/voices", listOf("model" to "x", "model_id" to "x")),
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            CompatibleTtsProvider.buildCatalogUrl("https://api.openai.com/v1", "/v1/models"),
        )
    }

    // MARK: - MiniMaxRegion

    @Test
    fun `region detect`() {
        assertEquals(MiniMaxRegion.MAINLAND, MiniMaxRegion.detect("https://api.minimaxi.com/v1/t2a_v2"))
        assertEquals(MiniMaxRegion.GLOBAL, MiniMaxRegion.detect("https://api.minimax.io/v1/t2a_v2"))
        assertEquals(MiniMaxRegion.US_WEST, MiniMaxRegion.detect("https://api-uw.minimax.io/v1/t2a_v2"))
        assertEquals(MiniMaxRegion.MAINLAND, MiniMaxRegion.detect("https://api.minimaxi.chat/v1/t2a_v2")) // legacy host
        assertNull(MiniMaxRegion.detect("https://other.example.com"))
    }

    @Test
    fun `region migrates deprecated chat host only`() {
        assertEquals(
            "https://api.minimaxi.com/v1/t2a_v2",
            MiniMaxRegion.migrateDeprecatedHost("https://api.minimaxi.chat/v1/t2a_v2"),
        )
        // case-insensitive host
        assertEquals(
            "https://api.minimaxi.com/v1/t2a_v2",
            MiniMaxRegion.migrateDeprecatedHost("https://API.MINIMAXI.CHAT/v1/t2a_v2"),
        )
        // not the deprecated host → no migration
        assertNull(MiniMaxRegion.migrateDeprecatedHost("https://api.minimaxi.com/v1/t2a_v2"))
        assertNull(MiniMaxRegion.migrateDeprecatedHost(""))
    }

    // MARK: - TtsProviderType.fromRaw (legacy migration)

    @Test
    fun `fromRaw resolves known and migrates legacy openai_compatible`() {
        assertEquals(TtsProviderType.SYSTEM, TtsProviderType.fromRaw("system"))
        assertEquals(TtsProviderType.MINIMAX, TtsProviderType.fromRaw("minimax"))
        assertEquals(TtsProviderType.VOLINK, TtsProviderType.fromRaw("volink"))
        // legacy "openai_compatible" → volink when hinted, else custom
        assertEquals(TtsProviderType.VOLINK, TtsProviderType.fromRaw("openai_compatible", baseUrl = "https://api.volink.org"))
        assertEquals(TtsProviderType.VOLINK, TtsProviderType.fromRaw("openai_compatible", providerName = "Volink"))
        assertEquals(TtsProviderType.CUSTOM_OPENAI_COMPATIBLE, TtsProviderType.fromRaw("openai_compatible"))
        // unknown → system
        assertEquals(TtsProviderType.SYSTEM, TtsProviderType.fromRaw("garbage"))
    }
}
