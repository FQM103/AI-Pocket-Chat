package com.situ.aichat.prompt

import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.prompt.growth.Band
import com.situ.aichat.prompt.growth.RelationshipArchetype

/**
 * 成长原型校准（图纸 docs/handoff/2026-07-11-成长原型校准.md §3.5）读侧二维渲染：
 * 名分（原型）× 分数（水位）→ 台词。取代旧 [buildRelationshipDescription] 的全局刻度台词，
 * **仅当角色已识别原型时启用**（调度分支见 [buildCharacterGrowthContent] 第 2 段·D-10 三分支）。
 *
 * 纯逻辑、零 Android / DB 依赖；台词文本全住 [RelationshipBehaviorScripts]。
 */

/** 水位分档（图纸 §3.5 / D-11 锁定阈值）。返回 null = 静默档（该维不输出，对齐旧 40–60 静默的 token 经济）。 */
internal fun bandFor(t: Float): Band? = when {
    t < 0.30f -> Band.L1
    t < 0.65f -> null
    t < 0.90f -> Band.L3
    else -> Band.L4
}

/**
 * 二维渲染：段头逐字复用 legacy「你和X的互动方式：\n」；按 [RelationshipQuality.DIMENSION_KEYS] 固定序
 * 逐维算水位分档，静默档跳过，其余从 [RelationshipBehaviorScripts.textFor] 取台词行拼接。全部静默 → ""。
 */
internal fun buildArchetypeRelationshipDescription(
    quality: RelationshipQuality,
    archetype: RelationshipArchetype,
    userName: String,
): String {
    val keys = RelationshipQuality.DIMENSION_KEYS
    val values = quality.values
    val lines = mutableListOf<String>()
    for (i in keys.indices) {
        val floor = archetype.floors[i]
        val ceiling = archetype.ceilings?.get(i) ?: -1
        val t = RelationshipArchetype.waterLevel(values[i], floor, ceiling)
        val band = bandFor(t) ?: continue
        val text = RelationshipBehaviorScripts.textFor(archetype.family, keys[i], band)
        if (text.isNotEmpty()) lines.add(text)
    }
    if (lines.isEmpty()) return ""
    return "你和${userName}的互动方式：\n" + lines.joinToString("\n")
}
