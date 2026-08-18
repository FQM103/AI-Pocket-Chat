package com.situ.aichat.worldbook

/**
 * 互斥分组决胜（WB3·契约 §2.1 / §4.2 第 5 步）。同组同轮只出一条：
 * 1. 与**既有激活**（含前几轮）同组者直接出局（组内已有在场成员）；
 * 2. 组内决胜优先级：sticky 续贴者 > groupOverride 者 > 评分（有效开启时按关键词命中数取最高）> 按
 *    groupWeight 加权抽签（随机源注入，可测）；
 * 3. 条目可属多组（逗号分隔，ST 同义）；先输掉任一组即出局，组按首现序结算。
 */
internal class WorldInfoGroupResolver(
    private val rng: WorldInfoRng,
    private val globalUseGroupScoring: Boolean,
) {

    fun filter(newly: List<ActivatedCandidate>, alreadyActive: Collection<ActivatedCandidate>): List<ActivatedCandidate> {
        if (newly.isEmpty()) return newly
        val survivors = newly.toMutableList()

        // 1. 组内已有既存激活成员 → 新来者出局
        val activeGroups = alreadyActive.flatMap { groupsOf(it) }.toSet()
        if (activeGroups.isNotEmpty()) {
            survivors.removeAll { c -> groupsOf(c).any { it in activeGroups } }
        }

        // 2. 本轮内部逐组决胜（组按首现序）
        val byGroup = LinkedHashMap<String, MutableList<ActivatedCandidate>>()
        survivors.forEach { c -> groupsOf(c).forEach { g -> byGroup.getOrPut(g) { mutableListOf() }.add(c) } }
        for ((_, members) in byGroup) {
            val alive = members.filter { it in survivors }
            if (alive.size <= 1) continue
            val winner = pickWinner(alive)
            alive.forEach { if (it !== winner) survivors.remove(it) }
        }
        return survivors
    }

    private fun pickWinner(pool: List<ActivatedCandidate>): ActivatedCandidate {
        var p = pool
        // sticky 续贴者在组内优先（ST：保持期条目在其分组中优先）
        p.filter { it.viaSticky }.takeIf { it.isNotEmpty() }?.let { p = it }
        // groupOverride 优先胜出
        p.filter { it.entry.groupOverride }.takeIf { it.isNotEmpty() }?.let { p = it }
        // 评分：任一成员有效开启评分则按命中数取最高
        val scoringOn = p.any { it.entry.useGroupScoring ?: globalUseGroupScoring }
        if (scoringOn) {
            val max = p.maxOf { it.matchCount }
            p = p.filter { it.matchCount == max }
        }
        if (p.size == 1) return p.single()
        // groupWeight 加权抽签
        val weights = p.map { maxOf(1, it.entry.groupWeight) }
        var roll = rng.nextInt(weights.sum())
        p.forEachIndexed { i, candidate ->
            roll -= weights[i]
            if (roll < 0) return candidate
        }
        return p.last()
    }

    private fun groupsOf(c: ActivatedCandidate): List<String> =
        c.entry.groupName.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
