package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryChapterEntity

/**
 * 章节解锁判定 + 倒计时（11.1h-3，1:1 iOS `StoryChapter.isUnlocked` 计算属性 + `StoryUnlockCountdownText`）。
 * 纯函数（不入库），便于单测；阅读器（11.1i）锁判定同样复用。
 */

/** 是否已解锁：unlockAt==null（自由模式 / 已解锁）或 now ≥ unlockAt（1:1 iOS）。 */
fun StoryChapterEntity.isUnlocked(nowMillis: Long): Boolean = unlockAt == null || nowMillis >= unlockAt

/** 距解锁剩余整分钟（向下取整，丢秒 = iOS DateComponentsFormatter [.hour,.minute]）；已解锁 / 无解锁时间 → 0。 */
fun unlockRemainingMinutes(unlockAt: Long?, nowMillis: Long): Long {
    if (unlockAt == null || nowMillis >= unlockAt) return 0
    return (unlockAt - nowMillis) / 60_000L
}
