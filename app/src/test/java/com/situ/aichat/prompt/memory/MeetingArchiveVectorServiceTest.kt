package com.situ.aichat.prompt.memory

import com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.util.StringListJson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 见面档案向量服务纯逻辑（记忆改造四期·部件⑥·图纸 §3.2 / §7 T1-3/T1-4/T2-3）。断言从 §3.2/§5 规格独立反推
 * （MockK 假掉 dao/settings/embedder）：
 * - [MeetingArchiveVectorService.embedSource] 四态锁定输出（头行全空 / 难忘空 / 全量 / trim）；
 * - [MeetingArchiveVectorService.retrieval] top-N 排除 / n≤0 全候选零排除（E4）/ 阈值淘汰 / 维度不符跳过 /
 *   embedding null 跳过 / kindRaw 过滤（E6）；
 * - [MeetingArchiveVectorService.backfillMissing] 空批退（E13）/ 嵌入器不可用即停（E1）/ 批间推进。
 */
class MeetingArchiveVectorServiceTest {

    private val dao = mockk<OfflineMeetingMemoryDao>(relaxed = true)
    private val settings = mockk<SettingsRepository>()
    private val embedder = mockk<TextEmbedder>()
    private val service = MeetingArchiveVectorService(dao, settings, embedder)

    /** 与查询同向（相似度 1.0）/ 正交（相似度 0）/ 维度不符 的四维/二维向量。 */
    private val queryVec = floatArrayOf(1f, 0f, 0f, 0f)
    private fun sameDir() = VectorMemoryService.serializeEmbedding(floatArrayOf(1f, 0f, 0f, 0f))
    private fun orthogonal() = VectorMemoryService.serializeEmbedding(floatArrayOf(0f, 1f, 0f, 0f))
    private fun wrongDim() = VectorMemoryService.serializeEmbedding(floatArrayOf(1f, 0f))

    private fun meeting(
        uuid: String,
        startedAt: Long,
        sessionId: String = "sess-$uuid",
        summary: String = "一次很好的见面。",
        location: String = "",
        activity: String = "",
        highlightsJson: String = "[]",
        embedding: ByteArray? = null,
        kindRaw: String = "meeting",
    ) = OfflineMeetingMemoryEntity(
        uuid = uuid,
        characterUuid = "c",
        sessionId = sessionId,
        kindRaw = kindRaw,
        startedAtMillis = startedAt,
        summary = summary,
        location = location,
        activity = activity,
        highlightsJson = highlightsJson,
        embedding = embedding,
        createdAtMillis = 0,
        updatedAtMillis = 0,
    )

    // ── T1-3 embedSource 四态锁定 ──

    @Test fun embedSource_headAllBlank_noHighlights_returnsSummaryOnly() {
        val m = meeting("m1", startedAt = 1, location = "", activity = "", summary = "只有摘要", highlightsJson = "[]")
        assertEquals("只有摘要", service.embedSource(m))
    }

    @Test fun embedSource_headPresent_noHighlights() {
        val m = meeting("m1", startedAt = 1, location = "公园", activity = "散步", summary = "摘要正文", highlightsJson = "[]")
        assertEquals("公园 · 散步\n摘要正文", service.embedSource(m))
    }

    @Test fun embedSource_full_headSummaryHighlights() {
        val m = meeting(
            "m1", startedAt = 1, location = "公园", activity = "散步", summary = "摘要正文",
            highlightsJson = StringListJson.encode(listOf("笑得很开心", "一起看了日落")),
        )
        assertEquals("公园 · 散步\n摘要正文\n难忘：笑得很开心；一起看了日落", service.embedSource(m))
    }

    @Test fun embedSource_trimsSurroundingWhitespace() {
        val m = meeting("m1", startedAt = 1, location = "", activity = "", summary = "  摘要带空白  ", highlightsJson = "[]")
        assertEquals("摘要带空白", service.embedSource(m))
    }

    // ── T1-4 retrieval ──

    @Test fun retrieval_topN_excludedFromCandidatesAndSessionInExcludeSet() = runBlocking {
        coEvery { settings.getAppSettings() } returns AppSettings(meetingMemoryInjectCount = 1)
        val old = meeting("old", startedAt = 100, sessionId = "sess-old", summary = "老见面", embedding = sameDir())
        val latest = meeting("latest", startedAt = 200, sessionId = "sess-latest", summary = "最新见面", embedding = sameDir())
        coEvery { dao.byCharacter("c") } returns listOf(old, latest)

        val r = service.retrieval(queryVec, "c", threshold = 0.65)
        assertEquals("只有 old 出候选（latest 在 topN 完整卡已在场）", listOf("老见面"), r.candidates.map { it.content })
        assertEquals("latest 的 sessionId 进排除集", setOf("sess-latest"), r.excludedSessionIds)
    }

    @Test fun retrieval_injectCountZero_noExclusionAllCandidates_e4() = runBlocking {
        coEvery { settings.getAppSettings() } returns AppSettings(meetingMemoryInjectCount = 0)
        val a = meeting("a", startedAt = 100, summary = "见面A", embedding = sameDir())
        val b = meeting("b", startedAt = 200, summary = "见面B", embedding = sameDir())
        coEvery { dao.byCharacter("c") } returns listOf(a, b)

        val r = service.retrieval(queryVec, "c", threshold = 0.65)
        assertEquals("n≤0 → topN 空 → 全部出候选", setOf("见面A", "见面B"), r.candidates.map { it.content }.toSet())
        assertTrue("零排除", r.excludedSessionIds.isEmpty())
    }

    @Test fun retrieval_belowThreshold_dropped() = runBlocking {
        coEvery { settings.getAppSettings() } returns AppSettings(meetingMemoryInjectCount = 0)
        coEvery { dao.byCharacter("c") } returns listOf(meeting("o", startedAt = 100, summary = "正交", embedding = orthogonal()))
        val r = service.retrieval(queryVec, "c", threshold = 0.65)
        assertTrue("正交向量相似度 0 < 阈值 → 淘汰", r.candidates.isEmpty())
    }

    @Test fun retrieval_dimensionMismatch_skipped() = runBlocking {
        coEvery { settings.getAppSettings() } returns AppSettings(meetingMemoryInjectCount = 0)
        coEvery { dao.byCharacter("c") } returns listOf(meeting("w", startedAt = 100, summary = "维度不符", embedding = wrongDim()))
        val r = service.retrieval(queryVec, "c", threshold = 0.0) // 阈值 0 也不救：维度检查在阈值之前
        assertTrue("维度不符 → 跳过", r.candidates.isEmpty())
    }

    @Test fun retrieval_nullEmbedding_skipped() = runBlocking {
        coEvery { settings.getAppSettings() } returns AppSettings(meetingMemoryInjectCount = 0)
        coEvery { dao.byCharacter("c") } returns listOf(meeting("n", startedAt = 100, summary = "无向量", embedding = null))
        val r = service.retrieval(queryVec, "c", threshold = 0.0)
        assertTrue("embedding null → 跳过", r.candidates.isEmpty())
    }

    @Test fun retrieval_legacyKind_filteredOut_e6() = runBlocking {
        coEvery { settings.getAppSettings() } returns AppSettings(meetingMemoryInjectCount = 0)
        // legacy 行即便带向量也永不入候选（kindRaw 过滤）。
        coEvery { dao.byCharacter("c") } returns listOf(
            meeting("legacy", startedAt = 100, summary = "旧 blob 行", embedding = sameDir(), kindRaw = "legacy"),
        )
        val r = service.retrieval(queryVec, "c", threshold = 0.0)
        assertTrue("legacy kind → 过滤出候选池", r.candidates.isEmpty())
    }

    // ── T2-3 backfillMissing ──

    @Test fun backfill_emptyBatch_returnsWithoutWrite_e13() = runBlocking {
        every { embedder.isAvailable } returns true
        coEvery { dao.missingEmbedding(any()) } returns emptyList()
        service.backfillMissing()
        coVerify(exactly = 0) { dao.updateEmbedding(any(), any()) }
    }

    @Test fun backfill_embedderUnavailable_stopsImmediately_e1() = runBlocking {
        every { embedder.isAvailable } returns false
        service.backfillMissing()
        coVerify(exactly = 0) { dao.missingEmbedding(any()) } // 可用性先探 → 不可用直接返回
        coVerify(exactly = 0) { dao.updateEmbedding(any(), any()) }
    }

    @Test fun backfill_processesBatchThenAdvancesToEmpty() = runBlocking {
        every { embedder.isAvailable } returns true
        every { embedder.embed(any()) } returns floatArrayOf(1f, 0f, 0f, 0f)
        val a = meeting("a", startedAt = 1, summary = "档案A", embedding = null)
        val b = meeting("b", startedAt = 2, summary = "档案B", embedding = null)
        coEvery { dao.missingEmbedding(any()) } returnsMany listOf(listOf(a, b), emptyList())
        service.backfillMissing()
        coVerify(exactly = 1) { dao.updateEmbedding("a", any()) }
        coVerify(exactly = 1) { dao.updateEmbedding("b", any()) }
    }
}
