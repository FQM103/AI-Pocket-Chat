package com.situ.aichat.worldbook

import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import com.situ.aichat.data.worldbook.decodeStringList

/**
 * 世界书激活引擎（WB3·契约 §4.2 八步算法·纯逻辑零 Android 依赖）。
 * 每次发消息前跑一遍：输入 =（生效书 + 条目 + 最近消息 + 会话时效状态 + 设置），
 * 输出 = 按四锚点分桶的注入文本 + 时效状态增删 + 诊断（WB4 接进 PromptBuilder）。
 *
 * 流程：主扫（时效门 → 蓝灯/向量/关键词 → 概率）→ 分组决胜 → minActivations 扩窗 →
 * 递归轮（激活内容并入扫描源·delayUntilRecursion 按层级解锁·干涸或达步数上限即止）→
 * 时效状态产出 → 预算裁剪 → 分桶。
 *
 * 有意口径（KDoc 即事实源）：
 * - 递归轮的扫描源 = 聊天缓冲 + 已激活内容（超集语义：delayUntilRecursion 条目也能命中聊天文本）；
 * - 概率掷骰**每次生成至多一次**：掷输的条目本轮不再重掷（递归轮不给二次机会，防多掷偏置）；
 * - 书级 scan_depth / recursive_scanning / token_budget 只存不用（ST 自身亦不应用，行为对齐）。
 */
class WorldInfoActivator(private val rng: WorldInfoRng = WorldInfoRng.fromRandom()) {

    fun activate(input: WorldInfoActivationInput): WorldInfoActivationResult {
        val settings = input.settings
        val booksByUuid = input.books.filter { it.enabled }.associateBy { it.uuid }
        val candidates = input.entries.filter { it.enabled && it.bookUuid in booksByUuid }
        val outletSkipped = candidates.count { it.position == 7 }
        val eligible = candidates.filter { it.position != 7 }

        val matcher = WorldInfoMatcher(settings)
        val timed = WorldInfoTimedEffects(input.timedStates, input.conversationMessageCount)
        val groups = WorldInfoGroupResolver(rng, settings.useGroupScoring)

        val activated = LinkedHashMap<String, ActivatedCandidate>()
        val probabilityFailed = mutableSetOf<String>()
        val recursionText = StringBuilder()
        var sweeps = 0

        val bufferCache = HashMap<Int, String>()
        fun chatBuffer(depth: Int) = bufferCache.getOrPut(depth) { matcher.buildBuffer(input.messages, depth) }
        fun effectiveDepth(e: WorldBookEntryEntity) = e.scanDepth ?: settings.scanDepth

        /** 一轮扫描：时效门 → 命中判定 → 概率 → 分组决胜 → 落账 + 并入递归源。 */
        fun sweep(
            pool: List<WorldBookEntryEntity>,
            bufferFor: (WorldBookEntryEntity) -> String,
        ): List<ActivatedCandidate> {
            sweeps++
            val newly = mutableListOf<ActivatedCandidate>()
            for (e in pool) {
                if (activated.containsKey(e.uuid) || e.uuid in probabilityFailed) continue
                if (timed.delayBlocked(e)) continue
                val viaSticky = timed.stickyActive(e.uuid)
                if (!viaSticky && timed.cooldownBlocked(e.uuid)) continue
                var matchCount = 0
                val hit = when {
                    viaSticky -> true
                    e.constant -> true
                    e.vectorized -> e.uuid in input.vectorMatchedEntryUuids
                    else -> {
                        val m = matcher.matchEntry(e, bufferFor(e))
                        matchCount = m.matchCount
                        m.matched
                    }
                }
                if (!hit) continue
                if (!viaSticky && e.useProbability && e.probability < 100) {
                    if (e.probability <= 0 || rng.nextInt(100) >= e.probability) {
                        probabilityFailed.add(e.uuid)
                        continue
                    }
                }
                newly += ActivatedCandidate(e, booksByUuid.getValue(e.bookUuid), matchCount, viaSticky)
            }
            val winners = groups.filter(newly, activated.values)
            winners.forEach { c ->
                activated[c.entry.uuid] = c
                if (!c.entry.preventRecursion && c.entry.content.isNotBlank()) {
                    recursionText.append(c.entry.content).append('\n')
                }
            }
            return winners
        }

        // ── 第 1 步：主扫（delayUntilRecursion 条目按义只在递归轮参战） ──
        val primaryPool = eligible.filter { it.delayUntilRecursion <= 0 }
        sweep(primaryPool) { e -> chatBuffer(effectiveDepth(e)) }

        // ── 第 2 步：激活数不足 → 向更早历史逐级扩窗（受 maxDepth 上限） ──
        if (settings.minActivations > 0 && activated.size < settings.minActivations) {
            val depthCap = if (settings.minActivationsMaxDepth > 0) {
                minOf(settings.minActivationsMaxDepth, input.messages.size)
            } else {
                input.messages.size
            }
            var depth = settings.scanDepth + 1
            while (activated.size < settings.minActivations && depth <= depthCap) {
                sweep(primaryPool) { chatBuffer(depth) }
                depth++
            }
        }

        // ── 第 3 步：递归轮（层级解锁 + 步数上限） ──
        if (settings.recursiveScan) {
            var level = 1
            var steps = 0
            fun nextUnlockLevel(): Int? = eligible
                .filter { !activated.containsKey(it.uuid) && !it.excludeRecursion && it.delayUntilRecursion > level }
                .minOfOrNull { it.delayUntilRecursion }
            while (true) {
                val pool = eligible.filter {
                    !activated.containsKey(it.uuid) && !it.excludeRecursion && it.delayUntilRecursion <= level
                }
                val newly = if (pool.isEmpty()) {
                    emptyList()
                } else {
                    sweep(pool) { e ->
                        val base = chatBuffer(effectiveDepth(e))
                        if (recursionText.isEmpty()) base else base + "\n" + recursionText
                    }
                }
                if (newly.isNotEmpty()) {
                    steps++
                    if (settings.maxRecursionSteps > 0 && steps >= settings.maxRecursionSteps) break
                    continue
                }
                level = nextUnlockLevel() ?: break
            }
        }

        // ── 第 4 步：时效状态产出（续贴不重复产；冷却接在保持窗之后） ──
        val newTimedStates = activated.values
            .filter { !it.viaSticky }
            .flatMap { timed.statesForTrigger(it.entry, input.conversationUuid) }

        // ── 第 5/6 步：预算裁剪 + 分桶 ──
        val outcome = WorldInfoBudgeter.applyBudget(activated.values.toList(), settings.budgetChars)
        val (before, after, suffix) = WorldInfoBudgeter.assembleBuckets(outcome.kept, settings.insertionStrategy)
        val atDepth = WorldInfoBudgeter.assembleAtDepth(outcome.kept, settings.insertionStrategy)

        return WorldInfoActivationResult(
            before = before,
            after = after,
            suffix = suffix,
            atDepth = atDepth,
            newTimedStates = newTimedStates,
            expiredTimedStates = timed.expired,
            diagnostics = WorldInfoDiagnostics(
                activated = outcome.kept.map { it.summary() },
                droppedByBudget = outcome.dropped.map { it.summary() },
                badRegexKeys = matcher.badRegexKeys.toList(),
                sweepCount = sweeps,
                outletSkippedCount = outletSkipped,
            ),
        )
    }

    private fun ActivatedCandidate.summary() = ActivatedEntrySummary(
        uid = entry.uid,
        title = entry.comment.ifBlank { decodeStringList(entry.keysJson).firstOrNull() ?: "(无标题)" },
        bookName = book.name,
        contentLength = entry.content.length,
    )
}
