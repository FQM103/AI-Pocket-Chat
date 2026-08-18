package com.situ.aichat.tts.provider

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * MiniMax regional T2A v2 endpoints (1:1 iOS `MiniMaxRegion`). Latency and egress IPs differ:
 * - [GLOBAL]   `api.minimax.io`     — overseas
 * - [US_WEST]  `api-uw.minimax.io`  — North/South America (lower time-to-first-audio)
 * - [MAINLAND] `api.minimaxi.com`   — mainland China (default)
 *
 * History: the mainland host was once wrongly `api.minimaxi.chat`; [migrateDeprecatedHost] fixes
 * old configs to the `.com` host (host-only replacement, preserving the rest of the URL).
 */
enum class MiniMaxRegion(val raw: String) {
    GLOBAL("global"),
    US_WEST("us_west"),
    MAINLAND("mainland_cn");

    /** Full T2A v2 endpoint; the TTS-synth and voice APIs derive from this base URL. */
    val baseUrl: String
        get() = when (this) {
            GLOBAL -> "https://api.minimax.io/v1/t2a_v2"
            US_WEST -> "https://api-uw.minimax.io/v1/t2a_v2"
            MAINLAND -> "https://api.minimaxi.com/v1/t2a_v2"
        }

    val localizedLabel: String
        get() = when (this) {
            GLOBAL -> "全球区 (api.minimax.io)"
            US_WEST -> "美西区 (首包更快)"
            MAINLAND -> "中国大陆区"
        }

    /** One-line hint shown under the region picker. */
    val localizedHint: String
        get() = when (this) {
            GLOBAL -> "面向海外用户的全球端点。"
            US_WEST -> "北美/南美用户首包延迟更低。"
            // Mainland uses a different account system: overseas keys (minimax.io) return 1004 here.
            MAINLAND -> "国内用户默认端点。需使用 minimaxi.com 账号——海外 key（来自 minimax.io）在此无效。"
        }

    companion object {
        /**
         * Detect the region from a base URL. Unknown → null (UI shows "custom").
         * The deprecated `api.minimaxi.chat` host is still recognized as mainland for old configs.
         */
        fun detect(baseUrl: String): MiniMaxRegion? {
            val normalized = baseUrl.trim().lowercase()
            entries.firstOrNull { it.baseUrl.lowercase() == normalized }?.let { return it }
            return when {
                normalized.contains("api.minimaxi.com") -> MAINLAND
                normalized.contains("api.minimaxi.chat") -> MAINLAND // legacy host
                normalized.contains("api-uw.minimax.io") -> US_WEST
                normalized.contains("api.minimax.io") -> GLOBAL
                else -> null
            }
        }

        /**
         * If the URL's host is exactly the deprecated `api.minimaxi.chat`, return the URL with the
         * host swapped to `api.minimaxi.com` (scheme/port/path/query preserved); otherwise null.
         * Host comparison is case-insensitive.
         */
        fun migrateDeprecatedHost(baseUrl: String): String? {
            val trimmed = baseUrl.trim()
            if (trimmed.isEmpty()) return null
            val url = trimmed.toHttpUrlOrNull() ?: return null
            if (!url.host.equals("api.minimaxi.chat", ignoreCase = true)) return null
            return url.newBuilder().host("api.minimaxi.com").build().toString()
        }
    }
}
