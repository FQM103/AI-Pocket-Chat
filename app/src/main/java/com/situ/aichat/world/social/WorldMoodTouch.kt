package com.situ.aichat.world.social

/**
 * 情绪轻碰三则的**纯函数**（契约 §8.B 情绪轻碰三则 / W4 图纸 §3.7·锁死·图纸 §9 禁改）。
 *
 * 世界事件对角色情绪只能**温和浮动、封顶封底**（绝不把角色搞成持续抑郁）；情绪**自然衰减**；同地点
 * **情绪传染偏正向**（快乐全传、低落减半）。三函数纯到底——无 IO / 时钟 / 随机。
 *
 * ⚠️ **本块只提供纯函数，不写任何角色 mood 列**——真正把 delta 写进 CharacterEntity 的 mood 在 W5 接线消费。
 * 输入 hints 的值域见 [WorldRelationshipBeats.BeatAxes.moodHint]（±1 / 0）。
 */
object WorldMoodTouch {

    /** 当日情绪合计（封顶封底 ±2·护栏「轻碰」）。 */
    fun dayMoodDelta(hints: List<Int>): Int = hints.sum().coerceIn(-2, 2)

    /** 次日自然回落一格（正向 −1、负向 +1、0 不动）。 */
    fun decay(delta: Int): Int = when {
        delta > 0 -> delta - 1
        delta < 0 -> delta + 1
        else -> 0
    }

    /** 同地点传染偏正向：快乐全传、低落减半（负值加 1 后仍封顶到 0）。 */
    fun contagion(delta: Int): Int = if (delta > 0) delta else (delta + 1).coerceAtMost(0)
}
