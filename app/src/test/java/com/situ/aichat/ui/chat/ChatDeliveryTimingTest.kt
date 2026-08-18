package com.situ.aichat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * chat-logic-2 / D6：多气泡段间「阅读停顿」纯函数（1:1 iOS interBubblePause / effectiveBubbleLength）。
 * 断言从 iOS 公式反推：effLen = trim 后字符数 + 换行*6 + 标点*2；pause = (2.0 + sqrt(clamp((effLen-6)/42,0,1)))*1000。
 */
class ChatDeliveryTimingTest {

    @Test
    fun effectiveBubbleLength_empty() {
        assertEquals(0, effectiveBubbleLength(""))
        assertEquals(0, effectiveBubbleLength("   \n  "))
    }

    @Test
    fun effectiveBubbleLength_plain() {
        assertEquals(5, effectiveBubbleLength("abcde")) // 5 字符，无标点/换行
        assertEquals(5, effectiveBubbleLength("  abcde  ")) // 先 trim
    }

    @Test
    fun effectiveBubbleLength_punctuationPenalty() {
        // "你好。" = 3 字符 + 句号 1*2 = 5
        assertEquals(5, effectiveBubbleLength("你好。"))
        // "a.b!" = 4 字符 + 标点 2*2 = 8
        assertEquals(8, effectiveBubbleLength("a.b!"))
    }

    @Test
    fun effectiveBubbleLength_newlinePenalty() {
        // "a\nb" = 3 字符（含 \n）+ 换行 1*6 = 9
        assertEquals(9, effectiveBubbleLength("a\nb"))
    }

    @Test
    fun interBubblePause_floorAt2s() {
        // effLen<=6 → normalized 钳 0 → 2.0s
        assertEquals(2000L, interBubblePauseMillis("abcde")) // effLen 5
        assertEquals(2000L, interBubblePauseMillis("六个字符吗")) // effLen 5
    }

    @Test
    fun interBubblePause_ceilingAt3s() {
        // effLen>=48 → normalized 1 → 3.0s（48 个无标点字符）
        assertEquals(3000L, interBubblePauseMillis("a".repeat(48)))
        assertEquals(3000L, interBubblePauseMillis("a".repeat(100)))
    }

    @Test
    fun interBubblePause_midEased() {
        // effLen=27 → normalized 0.5 → 2.0+sqrt(0.5)=2.7071… → 2707ms
        assertEquals(2707L, interBubblePauseMillis("a".repeat(27)))
    }

    // chat-logic-3：语音分条延迟的长度加成（1:1 iOS deliverVoiceChunks，与文字版 min(0.4, len*0.015) 有意不同）。
    @Test
    fun voiceChunkLengthBonus_firstChunk() {
        // 首条 = min(0.5, len*0.01)
        assertEquals(0.0, voiceChunkLengthBonusSeconds(0, 0), 1e-9)
        assertEquals(0.06, voiceChunkLengthBonusSeconds(0, 6), 1e-9)
        assertEquals(0.5, voiceChunkLengthBonusSeconds(0, 50), 1e-9) // 50*0.01=0.5 命中上限
        assertEquals(0.5, voiceChunkLengthBonusSeconds(0, 100), 1e-9) // 钳到 0.5
    }

    @Test
    fun voiceChunkLengthBonus_laterChunks() {
        // 非首条 = min(0.35, len*0.006)
        assertEquals(0.0, voiceChunkLengthBonusSeconds(1, 0), 1e-9)
        assertEquals(0.06, voiceChunkLengthBonusSeconds(1, 10), 1e-9)
        assertEquals(0.3, voiceChunkLengthBonusSeconds(1, 50), 1e-9)
        assertEquals(0.35, voiceChunkLengthBonusSeconds(2, 100), 1e-9) // 钳到 0.35
    }
}
