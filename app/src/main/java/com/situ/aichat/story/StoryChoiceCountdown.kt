package com.situ.aichat.story

import kotlin.math.ceil

/**
 * 选择反悔窗口的纯计时逻辑（原 iOS `StoryReaderView+Choices.swift:151-200`·ST7d/J2 由 5s 调至 4s）。
 *
 * 用户做选择后进入约 4 秒反悔窗口：不立即落库，每 200ms 刷新剩余秒数，到期才提交（底部非阻塞撤销条
 * 呈现·契约 §8-J2）。剩余秒数向上取整（= iOS `Int(timeIntervalSinceNow.rounded(.up))`）。纯函数，单测反推。
 */
internal object StoryChoiceCountdown {
    /** 反悔窗口时长（秒·ST7d/J2 由 5 调至 4）。 */
    const val WINDOW_SECONDS = 4

    /** 反悔窗口时长（毫秒）。 */
    const val WINDOW_MS = 4_000L

    /** 倒计时刷新间隔（毫秒）。 */
    const val TICK_MS = 200L

    /** 距 deadline 的剩余秒数，向上取整并钳到 [0, ∞)。 */
    fun remainingSeconds(deadlineMs: Long, nowMs: Long): Int {
        val diffMs = deadlineMs - nowMs
        if (diffMs <= 0L) return 0
        return ceil(diffMs / 1000.0).toInt()
    }

    /** 是否已到期（到点才真正提交选择）。 */
    fun isExpired(deadlineMs: Long, nowMs: Long): Boolean = nowMs >= deadlineMs
}
