package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [appendMoodEntry] 纯函数单测：追加在尾、按上限截断保留最新、边界、空表、maxCount 兜底。
 *
 * 守住情绪历史「不丢最新、不超容」契约——下游消费者（送礼情绪加成 / 主动送礼 / 印象标签）依赖最新条目落在
 * 24h 窗口 / 近 N 条内。情绪历史此前全工程无写入方恒空，本批修复接上写入，此测锁住写入侧不变量。
 */
class MoodHistoryAppendTest {

    private fun entry(ts: Long, color: String = "green") =
        MoodHistoryEntry(timestamp = ts, emoji = "😊", colorName = color, text = "t$ts")

    @Test fun append_to_empty_keeps_single() {
        val out = appendMoodEntry(emptyList(), entry(100), maxCount = 200)
        assertEquals(1, out.size)
        assertEquals(100L, out.last().timestamp)
    }

    @Test fun append_puts_newest_last() {
        val out = appendMoodEntry(listOf(entry(1), entry(2)), entry(3), maxCount = 200)
        assertEquals(listOf(1L, 2L, 3L), out.map { it.timestamp })
    }

    @Test fun under_cap_keeps_all() {
        val existing = (1..199).map { entry(it.toLong()) }
        val out = appendMoodEntry(existing, entry(200), maxCount = 200)
        assertEquals(200, out.size)
        assertEquals(200L, out.last().timestamp)
    }

    @Test fun over_cap_drops_oldest_keeps_newest() {
        val existing = (1..200).map { entry(it.toLong()) }
        val out = appendMoodEntry(existing, entry(201), maxCount = 200)
        assertEquals(200, out.size)
        // 最老的 ts=1 被丢，最新 ts=201 保留
        assertEquals(2L, out.first().timestamp)
        assertEquals(201L, out.last().timestamp)
    }

    @Test fun maxCount_below_one_coerced_to_one() {
        val out = appendMoodEntry(listOf(entry(1), entry(2)), entry(3), maxCount = 0)
        assertEquals(1, out.size)
        assertEquals(3L, out.last().timestamp)
    }
}
