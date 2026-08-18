package com.situ.aichat.prompt.growth

import com.situ.aichat.data.model.RelationshipQuality

/**
 * 成长原型校准（图纸 docs/handoff/2026-07-11-成长原型校准.md §3.1）。
 *
 * 关系原型 = 名分背后的「宪法」：19 类按 10 族组织，各带八维**地板**（棘轮的抬分目标，只升不降），
 * 仅对立三族（前任 / 竞争 / 宿敌）带**天花板**（手动降级一次性回拉的上界 + 读侧水位上界）。
 *
 * 本文件纯数据 + 纯函数：**无 Android / 词表 / DB 依赖**（渲染热路径零加载，D-2）。
 * 地板 / 天花板均按 [RelationshipQuality.DIMENSION_KEYS] 维序：熟悉/信任/亲近/默契/尊重/趣味/张力/依恋。
 */

/** 台词族（10 族 · 原型→族见 [RelationshipArchetype.family]）。[com.situ.aichat.prompt.RelationshipBehaviorScripts] 按族取台词。 */
enum class ScriptFamily { STRANGERS, CASUAL, FRIENDS, CONFIDANTS, FLUTTER, ROMANCE, KIN, MENTORS, EXES, FOES }

/** 水位档（变速箱转速表 · 静默档不在枚举内，由 null 表达）。[index] 对齐台词数组下标 L1=0/L3=1/L4=2。 */
enum class Band(val index: Int) { L1(0), L3(1), L4(2) }

/**
 * 关系原型。[floors] 恒 8 长；[ceilings] == null 表示该原型无任何天花板，数组内 -1 = 该维无天花板。
 * 维序均为熟悉/信任/亲近/默契/尊重/趣味/张力/依恋（= [RelationshipQuality.DIMENSION_KEYS]）。
 */
data class RelationshipArchetype(
    val id: String,
    val displayName: String,
    val family: ScriptFamily,
    val floors: IntArray,
    val ceilings: IntArray?,
) {
    companion object {
        // 维序：熟悉 信任 亲近 默契 尊重 趣味 张力 依恋。张力/依恋地板恒 0（拍板③：不挡戏剧冲突）。
        val ALL: List<RelationshipArchetype> = listOf(
            RelationshipArchetype("STRANGER", "陌生人", ScriptFamily.STRANGERS, intArrayOf(0, 0, 0, 0, 0, 0, 0, 0), null),
            RelationshipArchetype("ACQUAINTANCE", "点头之交", ScriptFamily.CASUAL, intArrayOf(15, 10, 5, 5, 20, 10, 0, 0), null),
            RelationshipArchetype("NETFRIEND", "网友", ScriptFamily.CASUAL, intArrayOf(25, 20, 20, 25, 25, 35, 0, 0), null),
            RelationshipArchetype("IDOL", "偶像与粉丝", ScriptFamily.CASUAL, intArrayOf(15, 20, 10, 5, 50, 30, 0, 0), null),
            RelationshipArchetype("COLLEAGUE", "同事搭档", ScriptFamily.FRIENDS, intArrayOf(35, 25, 15, 30, 35, 15, 0, 0), null),
            RelationshipArchetype("FRIEND", "朋友", ScriptFamily.FRIENDS, intArrayOf(35, 30, 25, 25, 35, 30, 0, 0), null),
            RelationshipArchetype("CLOSE_FRIEND", "好朋友/死党", ScriptFamily.FRIENDS, intArrayOf(50, 45, 40, 40, 45, 45, 0, 0), null),
            RelationshipArchetype("BEST_FRIEND", "挚友/闺蜜/知己", ScriptFamily.CONFIDANTS, intArrayOf(65, 55, 60, 55, 55, 50, 0, 0), null),
            RelationshipArchetype("CHILDHOOD", "青梅竹马/发小", ScriptFamily.CONFIDANTS, intArrayOf(65, 45, 45, 55, 35, 45, 0, 0), null),
            RelationshipArchetype("CRUSH", "暗恋", ScriptFamily.FLUTTER, intArrayOf(20, 15, 15, 10, 30, 15, 0, 0), null),
            RelationshipArchetype("AMBIGUOUS", "暧昧", ScriptFamily.FLUTTER, intArrayOf(40, 30, 35, 35, 35, 40, 0, 0), null),
            RelationshipArchetype("LOVER", "恋人", ScriptFamily.ROMANCE, intArrayOf(55, 30, 50, 45, 45, 40, 0, 0), null),
            RelationshipArchetype("SPOUSE", "伴侣/夫妻/老夫老妻", ScriptFamily.ROMANCE, intArrayOf(75, 40, 60, 60, 50, 30, 0, 0), null),
            RelationshipArchetype("FAMILY", "家人", ScriptFamily.KIN, intArrayOf(70, 45, 40, 45, 35, 25, 0, 0), null),
            RelationshipArchetype("MENTORSHIP", "师徒/师生", ScriptFamily.MENTORS, intArrayOf(45, 40, 25, 35, 55, 15, 0, 0), null),
            RelationshipArchetype("SERVANT", "主仆/契约", ScriptFamily.MENTORS, intArrayOf(45, 35, 20, 40, 40, 10, 0, 0), null),
            // 对立三族：天花板逐维，-1 = 无顶。EX 依恋无顶=藕断丝连合法；RIVAL 尊重无顶=惺惺相惜；NEMESIS 依恋无顶=恨的执念。
            RelationshipArchetype("EX", "前任", ScriptFamily.EXES, intArrayOf(60, 15, 10, 35, 20, 5, 0, 0), intArrayOf(-1, 45, 45, -1, -1, 55, -1, -1)),
            RelationshipArchetype("RIVAL", "竞争对手/死对头", ScriptFamily.FOES, intArrayOf(40, 10, 5, 30, 30, 10, 0, 0), intArrayOf(-1, 50, 40, -1, -1, -1, -1, -1)),
            RelationshipArchetype("NEMESIS", "宿敌/仇人", ScriptFamily.FOES, intArrayOf(50, 5, 0, 25, 15, 5, 0, 0), intArrayOf(-1, 35, 30, -1, -1, 45, -1, -1)),
        )

        private val byIdMap: Map<String, RelationshipArchetype> = ALL.associateBy { it.id }

        /** 未知 id → null（前向兼容旧数据 / 降版本；调度侧据此走闭嘴分支）。 */
        fun byId(id: String): RelationshipArchetype? = byIdMap[id]

        /**
         * 水位（图纸 §3.5 锁定公式）：分数在 [floor, hi] 内归一化，`hi = 天花板（≥0）否则 100`。
         * 表级不变量③保证 hi > floor 恒成立（无除零）；界外分数 coerce 到 [0,1]（E6 穿地板钳 0）。
         */
        fun waterLevel(score: Int, floor: Int, ceiling: Int): Float {
            val hi = if (ceiling >= 0) ceiling else 100
            return ((score - floor) / (hi - floor).toFloat()).coerceIn(0f, 1f)
        }
    }
}
