package com.situ.aichat.offline

/**
 * 线下叙事「导演指令」相关类型（1:1 iOS `NarrativeDirectiveService` 的内嵌类型）。
 * 引擎（analyze/generate/select）在 10.2b-2；本文件只放预设([OfflineNarrativePreset])与引擎共用的类型。
 */

/** 对应 [OfflineContentBlock] 的 10 种类型，用于统计分析（rawValue = 中文标签名，1:1 iOS BlockType）。 */
enum class BlockType(val raw: String) {
    SCENE_HEADER("场景"),
    ENVIRONMENT("环境"),
    NARRATION("叙述"),
    DIALOGUE("对话"),
    ACTION("动作"),
    INNER_MONOLOGUE("内心"),
    EMOTION("情绪"),
    USER_ACTION("你"),
    TIME_SKIP("时间"),
    SCENE_TRANSITION("过渡"),
}

/** 块偏向指令：关联目标块类型，便于按缺失/低频类型筛选（1:1 iOS BlockEmphasisDirective）。 */
data class BlockEmphasisDirective(
    val targets: Set<BlockType>,
    val text: String,
)

/** 最近若干轮线下回复的块分布分析结果（1:1 iOS BlockUsageProfile）。 */
data class BlockUsageProfile(
    val counts: Map<BlockType, Int>,
    val totalBlocks: Int,
    /** 分析到的 AI 线下回复条数（判断当前第几轮）。 */
    val assistantTurnCount: Int,
    /** 最近 N 轮中 0 次出现的（易退化）块类型。 */
    val missingTypes: Set<BlockType>,
    /** 出现次数 < 总块数 10% 的（易退化）块类型。 */
    val underrepresentedTypes: Set<BlockType>,
)
