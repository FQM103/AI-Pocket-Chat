package com.situ.aichat.offline

/**
 * 线下见面摘要的**重试策略**（指数退避 + 兜底阈值）。1:1 port of iOS `OfflineSummaryRetryPolicy`。
 *
 * 目的：LLM 摘要请求失败时自动重试，但要避免
 * ① 每次用户进对话 / App 切前台都立刻重试，浪费 token；
 * ② 对永久性坏掉的配置（如 API key 错）反复请求。
 *
 * 策略：失败计数越多，下一次允许尝试的间隔越长（指数退避）；达到 [MAX_ATTEMPTS_BEFORE_FALLBACK] 次仍失败
 * → 触发规则兜底（[OfflineSummaryRegenerator]），不再尝试 LLM。
 *
 * **退避状态持久化在 Room**（ConversationEntity.pendingOfflineSummaryFailCount/LastAttemptAt），**不靠
 * WorkManager BackoffPolicy**（那是 Worker 重试级、粒度不对且不跨业务实体）。Worker 只负责「何时跑一次扫描」，
 * 退避判断用本纯函数读 Room 字段（spec §3.3 / 坑 §4#2）。
 *
 * 纯函数 object，无 IO，便于单测反推 iOS 边界值。
 */
object OfflineSummaryRetryPolicy {

    /** 连续失败多少次之后触发规则兜底（1:1 iOS maxAttemptsBeforeFallback = 5）。 */
    const val MAX_ATTEMPTS_BEFORE_FALLBACK = 5

    /**
     * 每次失败后的退避等待时长（**毫秒**），按失败次数对应索引取值（= iOS backoffWindows 秒 × 1000）：
     * failCount=1 → 1 分钟；=2 → 5 分钟；=3 → 30 分钟；=4 → 2 小时；≥5 → 1 天（兜底后应已清零，这里只是保险）。
     */
    private val BACKOFF_WINDOWS_MS = longArrayOf(
        60_000L,        // 失败 1 次后等 1 分钟
        300_000L,       // 失败 2 次后等 5 分钟
        1_800_000L,     // 失败 3 次后等 30 分钟
        7_200_000L,     // 失败 4 次后等 2 小时
        86_400_000L,    // 失败 ≥5 次后等 1 天（兜底后应清零，保险项）
    )

    /**
     * 判断当前是否允许尝试 LLM 摘要（1:1 iOS shouldAttempt）。
     *
     * @param failCount 当前已失败次数
     * @param lastAttemptAt 最近一次尝试时间（首次尝试为 null）
     * @param now 当前时间（毫秒，传参便于测试）
     * @return true=允许尝试；false=还在退避窗口内，本次跳过
     */
    fun shouldAttempt(failCount: Int, lastAttemptAt: Long?, now: Long): Boolean {
        // 从未尝试过 → 允许（= iOS guard let lastAttemptAt）。
        if (lastAttemptAt == null) return true
        // failCount==0（含理论负值，对齐 iOS `guard failCount > 0`）→ 允许。
        if (failCount <= 0) return true

        val index = minOf(failCount - 1, BACKOFF_WINDOWS_MS.size - 1)
        val window = BACKOFF_WINDOWS_MS[index]
        return now - lastAttemptAt >= window
    }

    /** 是否已达到触发规则兜底的阈值（1:1 iOS shouldFallbackNow = failCount >= 5）。 */
    fun shouldFallbackNow(failCount: Int): Boolean = failCount >= MAX_ATTEMPTS_BEFORE_FALLBACK
}
