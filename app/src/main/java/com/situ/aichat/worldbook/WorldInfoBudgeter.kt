package com.situ.aichat.worldbook

/**
 * 预算裁剪 + 分桶输出（WB3·契约 §4.2 第 7/8 步）。
 * - 预算顺位：常驻优先保，再按 insertionOrder **降序**（数值大 = 优先级高，ST 文档语义）；
 *   ignoreBudget 者永不裁（仍计入用量）；被裁者进诊断清单；
 * - 分桶按契约 §2.2 锚点映射：0→before / 1·5·6→after / 2·3→suffix / 4→atDepth（position 7 已在候选期剔除）；
 * - 桶内 insertionOrder **升序**拼接（数值大靠后 = 离上下文末尾近 = 影响强，与 ST 一致）；
 *   同 order 平手按插入策略破（角色书优先 = 角色书条目更靠后更强）。
 */
internal object WorldInfoBudgeter {

    data class BudgetOutcome(
        val kept: List<ActivatedCandidate>,
        val dropped: List<ActivatedCandidate>,
    )

    fun applyBudget(candidates: List<ActivatedCandidate>, budgetChars: Int): BudgetOutcome {
        val ordered = candidates.sortedWith(
            compareByDescending<ActivatedCandidate> { it.entry.constant }
                .thenByDescending { it.entry.insertionOrder }
                .thenBy { it.entry.uid },
        )
        val kept = mutableListOf<ActivatedCandidate>()
        val dropped = mutableListOf<ActivatedCandidate>()
        var used = 0
        for (c in ordered) {
            val length = c.entry.content.length
            when {
                c.entry.ignoreBudget -> {
                    kept += c
                    used += length
                }
                used + length <= budgetChars -> {
                    kept += c
                    used += length
                }
                else -> dropped += c
            }
        }
        return BudgetOutcome(kept, dropped)
    }

    fun assembleBuckets(
        kept: List<ActivatedCandidate>,
        strategy: WorldInfoInsertionStrategy,
    ): Triple<String, String, String> {
        val sorted = kept.sortedWith(bucketComparator(strategy))
        val before = joinContents(sorted.filter { it.entry.position == 0 })
        val after = joinContents(sorted.filter { it.entry.position in setOf(1, 5, 6) })
        val suffix = joinContents(sorted.filter { it.entry.position in setOf(2, 3) })
        return Triple(before, after, suffix)
    }

    fun assembleAtDepth(
        kept: List<ActivatedCandidate>,
        strategy: WorldInfoInsertionStrategy,
    ): List<AtDepthInjection> =
        kept.filter { it.entry.position == 4 }
            .groupBy { it.entry.depth to it.entry.role }
            .map { (key, members) ->
                AtDepthInjection(
                    depth = key.first,
                    role = key.second,
                    content = joinContents(members.sortedWith(bucketComparator(strategy))),
                )
            }
            .sortedWith(compareByDescending<AtDepthInjection> { it.depth }.thenBy { it.role })

    private fun joinContents(members: List<ActivatedCandidate>): String =
        members.map { it.entry.content }.filter { it.isNotBlank() }.joinToString("\n")

    private fun bucketComparator(strategy: WorldInfoInsertionStrategy) =
        compareBy<ActivatedCandidate>(
            { it.entry.insertionOrder },
            { strategyRank(it, strategy) },
            { it.entry.uid },
        )

    /** 平手时的策略位次：角色书优先 ⇒ 角色书条目排后（更近末尾 = 更强），全局书优先则相反。 */
    private fun strategyRank(c: ActivatedCandidate, strategy: WorldInfoInsertionStrategy): Int = when (strategy) {
        WorldInfoInsertionStrategy.EVENLY -> 0
        WorldInfoInsertionStrategy.CHARACTER_FIRST -> if (c.book.isGlobal) 0 else 1
        WorldInfoInsertionStrategy.GLOBAL_FIRST -> if (c.book.isGlobal) 1 else 0
    }
}
