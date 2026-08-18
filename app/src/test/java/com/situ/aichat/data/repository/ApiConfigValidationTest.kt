package com.situ.aichat.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 13.2 / settings-api-1：Base URL 仅 https 校验（对齐 iOS hasValidURLScheme）。 */
class ApiConfigValidationTest {

    @Test
    fun acceptsHttps() {
        assertTrue(isHttpsBaseUrl("https://api.deepseek.com"))
        assertTrue(isHttpsBaseUrl("https://api.example.com/v1"))
        assertTrue(isHttpsBaseUrl("  https://api.example.com/v1  ")) // 前后空白被 trim
        assertTrue(isHttpsBaseUrl("HTTPS://API.EXAMPLE.COM")) // scheme 大小写不敏感
    }

    @Test
    fun rejectsInsecureOrBlank() {
        assertFalse(isHttpsBaseUrl("http://api.example.com")) // 明文 http
        assertFalse(isHttpsBaseUrl("http://localhost:8080")) // 本地代理也拒（对齐 iOS）
        assertFalse(isHttpsBaseUrl("")) // 空
        assertFalse(isHttpsBaseUrl("   ")) // 纯空白
        assertFalse(isHttpsBaseUrl("ftp://example.com")) // 其它协议
        assertFalse(isHttpsBaseUrl("api.example.com")) // 无 scheme
        assertFalse(isHttpsBaseUrl("httpsx://example.com")) // 防前缀误判
    }
}
