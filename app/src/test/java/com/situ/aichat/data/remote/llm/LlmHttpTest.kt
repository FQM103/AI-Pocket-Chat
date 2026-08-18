package com.situ.aichat.data.remote.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H3#1 测试网 · LlmHttp（URL 归一化 + http→https 自动升级·**安全分支**：Bearer key 绝不明文
 * 发往公网 http）。规格：三形输入（裸 host / …/v1 / …/chat/completions）都归一到完整端点；
 * 尾斜杠剥除；公网 http 升 https，本机/私网/.local/IPv6 唯一本地保留 http（自托管场景）；
 * 空串/非法/非 http(s) scheme 抛 InvalidUrl。
 */
class LlmHttpTest {

    // MARK: - 三形归一

    @Test
    fun bareHost_appendsV1ChatCompletions() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.example.com"),
        )
    }

    @Test
    fun v1Suffix_appendsChatCompletions() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.example.com/v1"),
        )
    }

    @Test
    fun fullEndpoint_passedThrough() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun trailingSlashes_trimmed() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://api.example.com/v1/"),
        )
    }

    @Test
    fun customPrefixPath_keptAndExtended() {
        // 中转站常见自定义前缀：…/api → …/api/v1/chat/completions。
        assertEquals(
            "https://relay.example.com/api/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("https://relay.example.com/api"),
        )
    }

    // MARK: - 安全分支：http→https 升级

    @Test
    fun publicHttpHost_upgradedToHttps() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            LlmHttp.buildChatCompletionsUrl("http://api.example.com"),
        )
    }

    @Test
    fun localAndPrivateHosts_keepHttp() {
        val keep = listOf(
            "http://localhost:8080",
            "http://127.0.0.1:1234",
            "http://10.0.0.5",
            "http://192.168.1.10:11434",
            "http://172.16.0.1",
            "http://172.31.255.254",
            "http://myserver.local",
        )
        for (url in keep) {
            assertTrue("url=$url", LlmHttp.buildChatCompletionsUrl(url).startsWith("http://"))
        }
    }

    @Test
    fun nearMissPrivateRanges_stillUpgraded() {
        // 172.32.x 不在 172.16-31 私网段；11.x 不是 10.x —— 防「看着像私网」误放行。
        assertTrue(LlmHttp.buildChatCompletionsUrl("http://172.32.0.1").startsWith("https://"))
        assertTrue(LlmHttp.buildChatCompletionsUrl("http://11.0.0.1").startsWith("https://"))
    }

    @Test
    fun shouldUpgrade_fullTable() {
        // 公网/未知 → 升级（true）；本机/私网族 → 保留（false）。
        assertTrue(LlmHttp.shouldUpgradeInsecureHost("api.example.com"))
        assertTrue(LlmHttp.shouldUpgradeInsecureHost(null))
        assertTrue(LlmHttp.shouldUpgradeInsecureHost(""))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("localhost"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("LOCALHOST"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("::1"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("nas.local"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("127.0.0.1"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("10.1.2.3"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("192.168.0.1"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("172.16.0.1"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("172.31.9.9"))
        // IPv6 唯一本地地址（fc00::/7）。
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("fd12:3456::1"))
        assertFalse(LlmHttp.shouldUpgradeInsecureHost("fc00::1"))
    }

    // MARK: - 非法输入

    @Test
    fun invalidInputs_throwInvalidUrl() {
        assertThrows(LlmError.InvalidUrl::class.java) { LlmHttp.buildChatCompletionsUrl("") }
        assertThrows(LlmError.InvalidUrl::class.java) { LlmHttp.buildChatCompletionsUrl("   ") }
        assertThrows(LlmError.InvalidUrl::class.java) { LlmHttp.buildChatCompletionsUrl("ftp://example.com") }
        assertThrows(LlmError.InvalidUrl::class.java) { LlmHttp.buildChatCompletionsUrl("api.example.com") } // 无 scheme
        assertThrows(LlmError.InvalidUrl::class.java) { LlmHttp.buildChatCompletionsUrl("http://[bad url") }
    }
}
