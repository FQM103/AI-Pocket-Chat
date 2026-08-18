package com.situ.aichat.ui.offline

import com.situ.aichat.offline.OfflineContentBlock

/**
 * 线下剧场内容块「阅读驱动」揭示节奏（D1·2026-07-06 拍板，取代旧 index×650ms 机械节拍）。
 *
 * 纯函数（T1）：第 i 块的揭示时刻 = 首块起手延迟 + 前面各块的估算阅读时长之和；台词块（角色对话）
 * 额外多一拍停顿再亮（聚光灯感）。目标体感 = 「读完上一块，下一块正好来」，像有人在讲故事。
 * 落值全部「起始值·真机可调」（梦剧场契约的回合制惯例）。
 */
object OfflineRevealPacing {

    /** 首块起手延迟（进场先呼吸一拍再开演）。 */
    const val FIRST_BLOCK_DELAY_MS = 250L

    /** 台词块（角色对话）前的额外停顿——话音落下前的一口气。 */
    const val DIALOGUE_PAUSE_MS = 250L

    /** 场景过渡装饰线（无文字）快速带过。 */
    const val SCENE_TRANSITION_READ_MS = 450L

    /** 文本块阅读时长 = 基底 + 字数×每字，钳位 [下限, 上限]。 */
    const val READ_BASE_MS = 300L
    const val READ_PER_CHAR_MS = 45L
    const val READ_MIN_MS = 500L
    const val READ_MAX_MS = 2200L

    /**
     * 每块相对「本条消息揭示起点」的延迟（毫秒），与 [blocks] 一一对应、单调不减。
     * 流式期间块数增长时对已有前缀重算结果不变（只依赖前缀），调用方可安全按索引取值。
     */
    fun revealDelays(blocks: List<OfflineContentBlock>): List<Long> {
        val delays = ArrayList<Long>(blocks.size)
        var acc = FIRST_BLOCK_DELAY_MS
        blocks.forEachIndexed { index, block ->
            if (index > 0) acc += readTimeMs(blocks[index - 1])
            if (index > 0 && block is OfflineContentBlock.CharacterDialogue) acc += DIALOGUE_PAUSE_MS
            delays.add(acc)
        }
        return delays
    }

    /** 单块估算阅读时长（毫秒）。 */
    internal fun readTimeMs(block: OfflineContentBlock): Long {
        val text = when (block) {
            is OfflineContentBlock.SceneHeader -> block.location + block.time
            is OfflineContentBlock.Environment -> block.text
            is OfflineContentBlock.Narration -> block.text
            is OfflineContentBlock.CharacterDialogue -> block.text
            is OfflineContentBlock.Action -> block.text
            is OfflineContentBlock.InnerMonologue -> block.text
            is OfflineContentBlock.Emotion -> block.text
            is OfflineContentBlock.UserAction -> block.text
            is OfflineContentBlock.TimeSkip -> block.text
            OfflineContentBlock.SceneTransition -> return SCENE_TRANSITION_READ_MS
        }
        return (READ_BASE_MS + text.length * READ_PER_CHAR_MS).coerceIn(READ_MIN_MS, READ_MAX_MS)
    }
}
