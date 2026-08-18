package com.situ.aichat.offline

import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.util.StringListJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * 行 → 注入文本渲染看门（图纸 §3.6 / T1-1）：E3 存档+完整分组、E4 预算降级、标题行与 [OfflineSummaryRegenerator]
 * 现格式字节对比、难忘行格式、段间 `\n\n`。**约定行已移除**（记忆改造一期·一事一形态·图纸 §3.9）——
 * 见 [render_highlightsLineFormat_noPromisesLine]。
 */
class OfflineMeetingMemoryRendererTest {

    private val zone = ZoneOffset.UTC
    private val day = 86_400_000L
    private val base = 1_700_000_000_000L // 固定基准·避免真时钟

    private fun meeting(
        idx: Int,
        startMillis: Long,
        loc: String,
        summary: String,
        activity: String = "",
        highlights: List<String> = emptyList(),
        promises: List<String> = emptyList(),
    ) = OfflineMeetingMemoryEntity(
        uuid = "m$idx",
        characterUuid = "c",
        sessionId = "s$idx",
        kindRaw = "meeting",
        startedAtMillis = startMillis,
        location = loc,
        activity = activity,
        summary = summary,
        highlightsJson = StringListJson.encode(highlights).ifEmpty { "[]" },
        promisesJson = StringListJson.encode(promises).ifEmpty { "[]" },
        sourceRaw = "llm",
        createdAtMillis = startMillis,
        updatedAtMillis = startMillis,
    )

    private fun fiveMeetings() = (0 until 5).map {
        meeting(it, base + it * day, "地点$it", "第 $it 次见面的摘要正文。", activity = "活动$it")
    }

    @Test
    fun render_e3_archivesOldestAndKeepsLatestInjectCount() {
        val out = OfflineMeetingMemoryRenderer.render(fiveMeetings(), injectCount = 3, budget = 10_000, zone = zone)
        // 存档组 = 最老 2 次 → 一行【早期见面合并】共 2 次；完整组 = 最新 3 次。
        assertTrue(out.contains("【早期见面合并】共 2 次"))
        assertEquals(3, Regex("【见面 · ").findAll(out).count())
        // 完整段老→新：地点2 在地点3 之前。
        assertTrue(out.indexOf("地点2") < out.indexOf("地点3"))
        assertTrue(out.indexOf("地点3") < out.indexOf("地点4"))
        // 段间 \n\n 连接。
        assertTrue(out.contains("\n\n"))
    }

    @Test
    fun render_e4_tinyBudgetDemotesToSingleFull() {
        val longSummaries = (0 until 5).map {
            meeting(it, base + it * day, "地点$it", "这是一段相当长的见面摘要正文用来撑爆字数预算逼迫渲染器降级最老的完整段进入合并行第 $it 次。")
        }
        val out = OfflineMeetingMemoryRenderer.render(longSummaries, injectCount = 3, budget = 100, zone = zone)
        // 至多降到只剩最新 1 段完整；其余 4 次并入合并行。
        assertEquals(1, Regex("【见面 · ").findAll(out).count())
        assertTrue(out.contains("【早期见面合并】共 4 次"))
        // 绝不截断段中：最新一段摘要完整出现。
        assertTrue(out.contains("第 4 次"))
    }

    @Test
    fun render_titleByteMatchesFallbackParagraphHeading() {
        val startMillis = base
        val loc = "公园"
        val rendererTitle = OfflineMeetingMemoryRenderer
            .render(listOf(meeting(1, startMillis, loc, "正文")), injectCount = 3, budget = 10_000, zone = zone)
            .lineSequence().first()
        val fallbackTitle = OfflineSummaryRegenerator
            .buildFallbackParagraph(startMillis, loc, "", "", 0, zone = zone)
            .lineSequence().first()
        assertEquals(fallbackTitle, rendererTitle)
    }

    @Test
    fun render_highlightsLineFormat_noPromisesLine() {
        val out = OfflineMeetingMemoryRenderer.render(
            listOf(
                meeting(
                    1, base, "公园", "散步聊天。",
                    highlights = listOf("你说的那句话", "路边的猫"),
                    promises = listOf("下次去看电影"), // promisesJson 保留，但渲染不再吐「约定：」行（§3.9）
                ),
            ),
            injectCount = 3, budget = 10_000, zone = zone,
        )
        // 难忘行照旧；约定改由【我们的约定】账本块单源呈现，此处不再出现「约定：」（一事一形态）。
        assertTrue(out.contains("难忘：你说的那句话；路边的猫。"))
        assertTrue("见面渲染不再含「约定：」行", !out.contains("约定："))
        assertTrue("不复述约定内容", !out.contains("下次去看电影"))
    }

    @Test
    fun render_emptyLocationUsesMouDi() {
        val out = OfflineMeetingMemoryRenderer.render(
            listOf(meeting(1, base, "", "正文")), injectCount = 3, budget = 10_000, zone = zone,
        )
        assertTrue(out.contains("· 某地】"))
    }

    @Test
    fun render_legacyRowsRenderedAsVerbatimParagraphsFirst() {
        val legacy = OfflineMeetingMemoryEntity(
            uuid = "l1", characterUuid = "c", kindRaw = "legacy", startedAtMillis = 0,
            summary = "旧的一段回忆逐字。", sourceRaw = "legacy", createdAtMillis = 0, updatedAtMillis = 0,
        )
        val out = OfflineMeetingMemoryRenderer.render(
            listOf(legacy, meeting(1, base, "公园", "新摘要。")), injectCount = 3, budget = 10_000, zone = zone,
        )
        // legacy 段落在最前。
        assertTrue(out.startsWith("旧的一段回忆逐字。"))
        assertTrue(out.indexOf("旧的一段回忆逐字。") < out.indexOf("【见面 · "))
    }
}
