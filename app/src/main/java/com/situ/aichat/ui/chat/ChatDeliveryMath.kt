package com.situ.aichat.ui.chat

/*
 * 聊天消息「递送节奏 / 分段」纯函数——从 ChatViewModel.kt 抽出，函数体字节级不变。
 * 全部顶层 internal（便于单测）；与 ChatViewModel 同包，故所有调用处与测试无需改动。
 * 测试：ChatDeliveryTimingTest。
 */

/**
 * 有效气泡长度（chat-logic-2 / D6，对齐 iOS effectiveBubbleLength）：去首尾空白后
 * 字符数 + 换行数*6 + 标点数*2（标点集 = 全/半角逗号句号叹问号 + 中文分号冒号 + 省略号 U+2026）。
 * 空串返回 0。顶层 internal 便于单测。注：用 length(UTF-16) 近似 iOS grapheme count，对 BMP 文本等价，
 * 罕见 emoji 气泡有可忽略偏差（停顿仅 2-3s 装饰性）。
 */
internal fun effectiveBubbleLength(text: String): Int {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return 0
    val newlinePenalty = trimmed.count { it == '\n' } * 6
    val punct = "，,。！？；：.!?…"
    val punctPenalty = trimmed.count { it in punct } * 2
    return trimmed.length + newlinePenalty + punctPenalty
}

/**
 * 段间「阅读停顿」毫秒（chat-logic-2 / D6，对齐 iOS interBubblePause）：2.0..3.0s，
 * 按 [effectiveBubbleLength] 归一化到 (len-6)/42 钳 [0,1] 后 sqrt 缓动。顶层 internal 便于单测。
 */
internal fun interBubblePauseMillis(text: String): Long {
    val normalized = ((effectiveBubbleLength(text) - 6) / 42.0).coerceIn(0.0, 1.0)
    return ((2.0 + kotlin.math.sqrt(normalized)) * 1000).toLong()
}

/**
 * chat-logic-3：语音分条延迟的「长度加成」秒数（1:1 iOS deliverVoiceChunks）——首条 min(0.5, len*0.01)、
 * 非首条 min(0.35, len*0.006)；与文字版 [segmentDelayMillis] 的 min(0.4, len*0.015) 有意不同。顶层 internal 便于单测。
 */
internal fun voiceChunkLengthBonusSeconds(index: Int, chunkLen: Int): Double =
    if (index == 0) minOf(0.5, chunkLen * 0.01) else minOf(0.35, chunkLen * 0.006)
