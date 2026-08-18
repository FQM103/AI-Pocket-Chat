package com.situ.aichat.moments

/**
 * 朋友圈自动发帖的两道纯函数守卫（1:1 iOS `checkAndGeneratePosts`）：4 小时发帖冷却 + 每日条数上限。
 * 抽成 internal 纯函数便于单测（断言反推 iOS 常量/比较方向，防 4h 写成 4min、`>=` 写成 `>` 这类移植 bug）。
 */
internal object MomentPostGuards {

    /** 发帖最小间隔：iOS 硬编码 `4 * 3600`（秒）= 4 小时。 */
    const val POST_COOLDOWN_MS = 4L * 3600 * 1000

    /**
     * 距上次发帖是否仍在冷却内（应跳过）。1:1 iOS `Date().timeIntervalSince(lastPost.timestamp) < 4*3600`。
     * 无上次发帖（[lastPostTimestamp] == null）→ 不冷却。恰好 4h（边界）→ 不冷却（`<` 严格小于）。
     */
    fun isCooldownActive(lastPostTimestamp: Long?, nowMillis: Long): Boolean =
        lastPostTimestamp != null && nowMillis - lastPostTimestamp < POST_COOLDOWN_MS

    /**
     * 今日已达发帖上限（应跳过）。1:1 iOS `guard todayCount < momentAutoPostFrequency`（取反）：
     * 已发数 ≥ 上限 → true。
     */
    fun isDailyCapReached(todayCount: Int, frequency: Int): Boolean = todayCount >= frequency
}
