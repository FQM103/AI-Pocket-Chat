package com.situ.aichat.tts.provider

import com.situ.aichat.tts.TtsProviderType
import com.situ.aichat.tts.TtsResponseFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Volink 音色目录 T2（实测 2026-07-11：控制台同源 `GET /api/voices` → `{data:[…], total}`，
 * `page`/`page_size` 分页（>500/页 服务端 500）、`model=` 服务端过滤、entry 带
 * `i18n{"zh-CN"/"en-US"}.name` 双语名——中文名与 Volink 控制台逐字一致）。手法沿用
 * LlmCompletionEscalationTest：OkHttp 拦截器按序吐编排响应并记录请求 URL（零真网络）。
 * 验证：翻页合并到 total 为止、每页带 model+page_size、中文名优先/英文名兜底与 detail、
 * 跨页去重、音色名含非法控制字符不炸整页、非 Volink 提供商保持单次拉取。
 */
class VolinkVoicePagingTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val requestUrls = mutableListOf<String>()

    private fun config(provider: TtsProviderType = TtsProviderType.VOLINK) = TtsRemoteConfigValues(
        providerType = provider,
        providerName = "Volink",
        apiKey = "k",
        baseUrl = "https://api.volink.org/v1/tts/speech",
        modelName = "minimax/speech-02-turbo",
        responseFormat = TtsResponseFormat.MP3,
    )

    private fun clientReturning(vararg bodies: String): OkHttpClient {
        val queue = bodies.toMutableList()
        return OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            requestUrls.add(req.url.toString())
            Response.Builder()
                .request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(queue.removeAt(0).toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
    }

    /** 实测 entry 形状：顶层 name=英文，i18n 带 zh-CN/en-US 名。zh 传 null 可造「无中文名」条目。 */
    private fun voiceJson(id: String, zh: String?, en: String) = buildString {
        append("""{"id":"$id","name":"$en","source":"official","i18n":{""")
        if (zh != null) append(""""zh-CN":{"name":"$zh"},""")
        append(""""en-US":{"name":"$en"}},"config":{"model":"minimax/speech-02-turbo","sex":"female"}}""")
    }

    private fun pageJson(total: Int, vararg voices: String) =
        """{"total":$total,"data":[${voices.joinToString(",")}]}"""

    @Test
    fun `walks pages until total reached and merges in order`() = runBlocking {
        val client = clientReturning(
            pageJson(5, voiceJson("a", "音色甲", "Voice A"), voiceJson("b", "音色乙", "Voice B")),
            pageJson(5, voiceJson("c", "音色丙", "Voice C"), voiceJson("d", "音色丁", "Voice D")),
            pageJson(5, voiceJson("e", "音色戊", "Voice E")),
        )
        val voices = CompatibleTtsProvider(TtsProviderType.VOLINK).fetchVoices(config(), client, json)
        assertEquals(listOf("a", "b", "c", "d", "e"), voices.map { it.id })
        assertEquals(3, requestUrls.size)
        requestUrls.forEachIndexed { i, url ->
            assertTrue("page param on request ${i + 1}: $url", url.contains("page=${i + 1}"))
            assertTrue("page_size on request ${i + 1}: $url", url.contains("page_size=100"))
            assertTrue("model filter on request ${i + 1}: $url", url.contains("model=minimax%2Fspeech-02-turbo"))
            assertTrue("catalog path: $url", url.contains("/api/voices"))
        }
    }

    @Test
    fun `chinese name preferred with english detail, english fallback when zh missing`() = runBlocking {
        val client = clientReturning(
            pageJson(2, voiceJson("a", "生涩奶狗", "Shy Pup"), voiceJson("b", null, "English Only")),
        )
        val voices = CompatibleTtsProvider(TtsProviderType.VOLINK).fetchVoices(config(), client, json)
        assertEquals("生涩奶狗", voices[0].name)
        assertEquals("Shy Pup", voices[0].detail)
        assertEquals("English Only", voices[1].name)
        assertNull(voices[1].detail) // detail 与 name 相同则省略
    }

    @Test
    fun `stops on empty page even when total claims more`() = runBlocking {
        val client = clientReturning(
            pageJson(999, voiceJson("a", "音色甲", "Voice A")),
            pageJson(999), // defensive: empty page must terminate the loop
        )
        val voices = CompatibleTtsProvider(TtsProviderType.VOLINK).fetchVoices(config(), client, json)
        assertEquals(listOf("a"), voices.map { it.id })
        assertEquals(2, requestUrls.size)
    }

    @Test
    fun `deduplicates ids across pages`() = runBlocking {
        val client = clientReturning(
            pageJson(3, voiceJson("a", "音色甲", "Voice A")),
            pageJson(3, voiceJson("a", "音色甲重", "Voice A dup"), voiceJson("b", "音色乙", "Voice B")),
        )
        val voices = CompatibleTtsProvider(TtsProviderType.VOLINK).fetchVoices(config(), client, json)
        assertEquals(listOf("a", "b"), voices.map { it.id })
    }

    @Test
    fun `raw control character in a voice name does not break the page`() = runBlocking {
        // 实测线上目录存在音色名含未转义控制字符——严格 JSON 解析会整页崩，须先剥除。
        val client = clientReturning(
            pageJson(2, voiceJson("a", "坏\u0001名", "Bad\u0001Name"), voiceJson("b", "好名", "Good Name")),
        )
        val voices = CompatibleTtsProvider(TtsProviderType.VOLINK).fetchVoices(config(), client, json)
        assertEquals(listOf("a", "b"), voices.map { it.id })
        assertEquals("坏名", voices[0].name)
    }

    @Test
    fun `non-volink provider keeps single generic fetch without paging params`() = runBlocking {
        val client = clientReturning(
            """{"voices":[{"id":"a","name":"Voice A"}]}""",
        )
        val voices = CompatibleTtsProvider(TtsProviderType.CUSTOM_OPENAI_COMPATIBLE)
            .fetchVoices(config(TtsProviderType.CUSTOM_OPENAI_COMPATIBLE), client, json)
        assertEquals(listOf("a"), voices.map { it.id })
        assertEquals(1, requestUrls.size)
        assertFalse(requestUrls[0].contains("page"))
        assertTrue(requestUrls[0].contains("/v1/voices"))
    }
}
