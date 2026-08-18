package com.situ.aichat.data.remote.llm

import android.util.Log
import com.situ.aichat.data.model.ApiProviderType

/**
 * Logs API token-usage info to logcat — faithful port of iOS `LLMService.logUsageInfo` +
 * `cacheHitRate`. Local logging only (no Firebase / GMS, per project constraint).
 *
 * Only logs when there's something noteworthy (reasoning tokens or a cache hit), matching iOS,
 * to avoid spamming every plain request.
 */
object UsageLogger {
    private const val TAG = "LlmUsage"

    fun log(usage: UsageDto, providerType: ApiProviderType, modelName: String) {
        val reasoning = usage.completionTokensDetails?.reasoningTokens ?: 0
        val cacheHit = usage.promptCacheHitTokens ?: 0
        val cacheMiss = usage.promptCacheMissTokens ?: 0
        val prompt = usage.promptTokens ?: 0
        val completion = usage.completionTokens ?: 0

        if (reasoning > 0 || cacheHit > 0) {
            val rate = cacheHitRate(usage)?.let { "%.0f%%".format(it * 100) } ?: "-"
            Log.i(
                TAG,
                "usage provider=${providerType.raw} model=$modelName prompt=$prompt " +
                    "completion=$completion reasoning=$reasoning cache_hit=$cacheHit " +
                    "cache_miss=$cacheMiss cache_rate=$rate",
            )
        }
    }

    /**
     * DeepSeek cache hit rate (0.0–1.0); null when there are no cache metrics.
     * High rate ⇒ stable prompt prefix (system / fixed history) ⇒ much cheaper.
     */
    fun cacheHitRate(usage: UsageDto): Double? {
        val hit = usage.promptCacheHitTokens ?: 0
        val miss = usage.promptCacheMissTokens ?: 0
        val total = hit + miss
        if (total <= 0) return null
        return hit.toDouble() / total.toDouble()
    }
}
