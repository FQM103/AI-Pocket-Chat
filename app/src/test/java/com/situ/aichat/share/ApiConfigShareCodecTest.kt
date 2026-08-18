package com.situ.aichat.share

import com.situ.aichat.data.model.ApiProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 扫码导入/导出 API 配置（13.10b · C7）负载编解码纯函数单测：往返保真 + 容错（魔数/版本/非 JSON → null）。
 */
class ApiConfigShareCodecTest {

    @Test
    fun `encode then decode round-trips all fields`() {
        val encoded = ApiConfigShareCodec.encode(
            provider = ApiProviderType.DEEPSEEK,
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-v4-flash",
            key = "sk-abc123",
        )
        val decoded = ApiConfigShareCodec.decode(encoded)
        assertEquals(ApiProviderType.DEEPSEEK, decoded?.provider)
        assertEquals("https://api.deepseek.com/v1", decoded?.baseUrl)
        assertEquals("deepseek-v4-flash", decoded?.model)
        assertEquals("sk-abc123", decoded?.key)
    }

    @Test
    fun `encode trims surrounding whitespace`() {
        val decoded = ApiConfigShareCodec.decode(
            ApiConfigShareCodec.encode(ApiProviderType.OPENROUTER, "  https://x.ai  ", "  m  ", "  k  "),
        )
        assertEquals("https://x.ai", decoded?.baseUrl)
        assertEquals("m", decoded?.model)
        assertEquals("k", decoded?.key)
    }

    @Test
    fun `unknown provider raw falls back to OPENAI_COMPATIBLE`() {
        val encoded = ApiConfigShareCodec.encode(ApiProviderType.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-4o", "k")
        // round-trips as OPENAI_COMPATIBLE; and a hand-crafted unknown raw also resolves to it via fromRaw
        assertEquals(ApiProviderType.OPENAI_COMPATIBLE, ApiConfigShareCodec.decode(encoded)?.provider)
        val unknown = """{"t":"aichat.apiconfig","v":1,"provider":"totally-unknown","baseUrl":"https://a.b","model":"m","key":"k"}"""
        assertEquals(ApiProviderType.OPENAI_COMPATIBLE, ApiConfigShareCodec.decode(unknown)?.provider)
    }

    @Test
    fun `random non-config text decodes to null`() {
        assertNull(ApiConfigShareCodec.decode("https://example.com"))
        assertNull(ApiConfigShareCodec.decode("hello world"))
        assertNull(ApiConfigShareCodec.decode(""))
    }

    @Test
    fun `json without the magic marker decodes to null`() {
        val notOurs = """{"t":"someone.else","v":1,"provider":"deepseek","baseUrl":"https://a.b","model":"m","key":"k"}"""
        assertNull(ApiConfigShareCodec.decode(notOurs))
    }

    @Test
    fun `future version is rejected`() {
        val future = """{"t":"aichat.apiconfig","v":99,"provider":"deepseek","baseUrl":"https://a.b","model":"m","key":"k"}"""
        assertNull(ApiConfigShareCodec.decode(future))
    }

    @Test
    fun `json missing a required field decodes to null`() {
        val missingKey = """{"t":"aichat.apiconfig","v":1,"provider":"deepseek","baseUrl":"https://a.b","model":"m"}"""
        assertNull(ApiConfigShareCodec.decode(missingKey))
    }
}
