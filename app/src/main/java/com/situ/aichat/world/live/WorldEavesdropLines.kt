package com.situ.aichat.world.live

import com.situ.aichat.world.WorldSeeds

/** 偷听一句气泡（说话人名 + 台词）。 */
data class EavesLine(val speaker: String, val text: String)

/** 偷听 LLM 输出解析结果（对话行 2–4 + 一句动静摘要）。 */
data class ParsedEavesdrop(val lines: List<EavesLine>, val summary: String)

/**
 * 偷听输出解析器 + 模板台词池（W12 图纸 §3/§9·纯函数·`internal`/`object` 便于 T1-1）。
 *
 * **§6 新增耦合对**：偷听 prompt 输出格式 = 每行「名字：台词」×3–4 + 末行「【动静】<摘要>」 ↔ 本解析器；
 * prompt 全文在 [WorldEavesdropService]（服务内 private const·zh），改一侧必同步另一侧（图纸 §9 锁）。
 */
object WorldEavesdropLines {

    private const val WHISPER_TAG = "【动静】"
    private const val MAX_LINES = 4 // >4 句钳 4（§9 锁）
    private const val MIN_LINES = 2 // 有效行 <2 → 弃稿（E11）

    /**
     * 解析 LLM 输出为 [ParsedEavesdrop]（图纸 §3/E11）：对话行「名字：台词」（钳前 [MAX_LINES] 行）+ 一行「【动静】摘要」。
     * **弃稿返 null**：有效行 < [MIN_LINES]（纯乱文/太短）/ 任一说话人名不在 {[nameA],[nameB]}（名字不匹配）/
     * 缺【动静】或摘要空白（无摘要 = 无法「只留一句摘要入世界事件」）——服务据此退模板（额度已扣不退·先扣后调）。
     */
    fun parse(raw: String, nameA: String, nameB: String): ParsedEavesdrop? {
        var summary: String? = null
        val lines = mutableListOf<EavesLine>()
        for (row in raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }) {
            if (row.startsWith(WHISPER_TAG)) {
                if (summary == null) summary = row.removePrefix(WHISPER_TAG).trimStart('：', ':', ' ').trim()
                continue
            }
            val sep = row.indexOfFirst { it == '：' || it == ':' }
            if (sep <= 0) continue // 无冒号或空说话人 = 非对话行（乱文），跳过
            val speaker = row.substring(0, sep).trim()
            val text = row.substring(sep + 1).trim()
            if (speaker.isEmpty() || text.isEmpty()) continue
            lines.add(EavesLine(speaker, text))
        }
        val clamped = lines.take(MAX_LINES)
        if (clamped.size < MIN_LINES) return null // 纯乱文 / 行数<2
        if (clamped.any { it.speaker != nameA && it.speaker != nameB }) return null // 名字不匹配
        val sum = summary?.takeIf { it.isNotBlank() } ?: return null // 缺【动静】/摘要空白
        return ParsedEavesdrop(clamped, sum)
    }

    /**
     * 模板台词（图纸 §9 六组逐字·省档/预算尽/断网/解析失败照出·气泡不空白）：按 (对键,本地日,种子) 确定性选组
     * → 首句给 [nameA]、次句给 [nameB]。同 (种子,日,对) 恒同组、跨日/跨对变化（T1-1）。
     */
    fun templateLines(nameA: String, nameB: String, pairKey: String, epochDay: Long, seed: Long): List<EavesLine> {
        val h = WorldSeeds.derive(seed, "eavestpl:$pairKey", epochDay)
        val group = POOL[Math.floorMod(h, POOL.size.toLong()).toInt()]
        return listOf(EavesLine(nameA, group[0]), EavesLine(nameB, group[1]))
    }

    /** 六组模板台词（zh-rCN 逐字·图纸 §9 锁死·每组两句）。 */
    private val POOL: List<List<String>> = listOf(
        listOf("这雨下得可真够久的。", "是啊，不过听着还挺舒服。"),
        listOf("你最近忙什么呢？", "老样子——不过日子过得还算有滋味。"),
        listOf("回头一起吃个饭？", "行啊，就这几天。"),
        listOf("今天这儿人不多。", "清净点也好。"),
        listOf("我跟你说件小事。", "哦？说来听听。"),
        listOf("时间过得真快。", "可不是嘛——一晃又是一季。"),
    )
}
