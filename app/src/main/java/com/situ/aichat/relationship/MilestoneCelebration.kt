package com.situ.aichat.relationship

/**
 * 关系里程碑庆祝决策（P1-33·拍板 A·纯函数）。**安卓超越 iOS**：iOS 自动评估路径零通知（唯一反馈是
 * 手动推进的聊天内 toast），庆祝通知为安卓新增。
 *
 * mini design pass 裁定（2026-06-10·勘察推翻 §9.1 两假设后）：
 * - 代码/iOS 均无 tier 层级定义（relationshipName 为自由文本）→ 自定「庆祝阶梯」[MILESTONE_CELEBRATION_RANK]，
 *   取 LLM 提示词参考名单（iOS/安卓逐字一致）的**正向递进子集**；负向/回退态（冷战中/前任/藕断丝连/
 *   相爱相杀/若即若离）与自创未知名无 rank=永不庆祝（不为分手/冷战弹「庆祝」）。
 * - 「历史最高 tier」零新增持久化：里程碑表本就是 append-only 全量历史（升序·随角色级联删·进备份），
 *   钩子点（coordinator changed 分支）插入新条前已在手旧历史快照 → 「首次达到 + 高于历史最高」现算。
 * - phase-only 过滤基于**同名**（changed 旗标含 phase-only 真值：标签未变仅时期变也 changed=true）。
 */
internal val MILESTONE_CELEBRATION_RANK: Map<String, Int> = mapOf(
    "陌生人" to 1,
    "网友" to 2,
    "点头之交" to 3,
    "普通朋友" to 4,
    "损友" to 5,
    "好朋友" to 6,
    "死党" to 7,
    "知己" to 8,
    "暧昧对象" to 9,
    "暗恋中" to 10,
    "恋人" to 11,
    "复合期" to 11, // 复合=回到恋人高度，不算新高（恋人在历史时 11>11 不成立 → 不重复庆祝）
    "热恋期" to 12,
    "老夫老妻" to 13,
    "灵魂伴侣" to 14,
)

/**
 * 是否庆祝这次里程碑：
 * - 仅 AI 自动评估（`aiAutomatic`）——将来移植手动推进时其自有结果 toast，不可双提示；
 * - 新名必须**首次出现**（同名 phase-only 与「复升回历史名」都不庆祝）；
 * - 新名必须在庆祝阶梯上且**高于历史最高已知 rank**（防降级：恋人→普通朋友即便首见也不庆祝）。
 *
 * @param historyNames 插入新里程碑**之前**的全量历史关系名（升序无妨，只看集合与最高 rank）。
 */
internal fun milestoneCelebrationDecision(
    historyNames: List<String>,
    newName: String,
    triggerTypeRaw: String,
): Boolean {
    if (triggerTypeRaw != "aiAutomatic") return false
    if (newName in historyNames) return false
    val newRank = MILESTONE_CELEBRATION_RANK[newName] ?: return false
    val maxHistoryRank = historyNames.mapNotNull { MILESTONE_CELEBRATION_RANK[it] }.maxOrNull() ?: 0
    return newRank > maxHistoryRank
}
