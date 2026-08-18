package com.situ.aichat.data.repository

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.OfflineMeetingMemoryDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.model.AppSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 见面回忆仓库编排看门（图纸 §3.3 / T2-1）：E1 幂等播种（count 门）/ E2 blob 兜底（行空且 blob 非空原样返回）/
 * E15 删除清理委派 / upsertMeeting 保 uuid+createdAt。DAO/设置用 MockK 假掉（编排逻辑主力）；真 Room SQL 由 T3-1
 * MigrationTest + 备份往返覆盖。
 */
class OfflineMeetingMemoryRepositoryTest {

    private val dao = mockk<OfflineMeetingMemoryDao>(relaxed = true)
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val settings = mockk<SettingsRepository>()
    private lateinit var repo: OfflineMeetingMemoryRepository

    @Before
    fun setup() {
        repo = OfflineMeetingMemoryRepository(dao, characterDao, settings)
        coEvery { settings.getAppSettings() } returns
            AppSettings(meetingMemoryInjectCount = 3, meetingMemoryMaxLength = 1200)
    }

    private fun charWithBlob(blob: String): CharacterEntity =
        mockk { every { offlineMeetingMemorySummary } returns blob }

    private fun meetingRow(sessionId: String, summary: String) = OfflineMeetingMemoryEntity(
        uuid = "u-$sessionId", characterUuid = "c", sessionId = sessionId, kindRaw = "meeting",
        startedAtMillis = 1_700_000_000_000L, location = "公园", summary = summary,
        sourceRaw = "llm", createdAtMillis = 1_700_000_000_000L, updatedAtMillis = 1_700_000_000_000L,
    )

    @Test
    fun e1_ensureSeeded_seedsOnceThenGuardedByCount() = runBlocking {
        val blob = "【见面 · 2026-04-18 15:30 · 公园】\n摘要正文。\n\n散落的一句话。"
        coEvery { characterDao.getByUuid("c") } returns charWithBlob(blob)
        coEvery { dao.countByCharacter("c") } returnsMany listOf(0, 3)

        repo.ensureSeeded("c") // count=0 → 播种
        repo.ensureSeeded("c") // count>0 → 跳过

        coVerify(exactly = 1) { dao.upsertAll(match { it.isNotEmpty() }) }
    }

    @Test
    fun e1_ensureSeeded_blankBlob_doesNotSeed() = runBlocking {
        coEvery { characterDao.getByUuid("c") } returns charWithBlob("   \n\n  ")
        coEvery { dao.countByCharacter("c") } returns 0
        repo.ensureSeeded("c")
        coVerify(exactly = 0) { dao.upsertAll(any()) }
    }

    @Test
    fun e2_rowsEmptyButBlobNonEmpty_returnsBlobVerbatim() = runBlocking {
        val blob = "旧备份里的见面回忆全文（尚未播种成功）。"
        coEvery { characterDao.getByUuid("c") } returns charWithBlob(blob)
        coEvery { dao.countByCharacter("c") } returns 0
        coEvery { dao.byCharacter("c") } returns emptyList() // 行仍空 → 兜底返回 blob

        assertEquals(blob, repo.renderedForInjection("c"))
    }

    @Test
    fun renderedForInjection_withRows_rendersMeetingFormat() = runBlocking {
        coEvery { dao.countByCharacter("c") } returns 1 // 已播种
        coEvery { dao.byCharacter("c") } returns listOf(meetingRow("s1", "一次很好的见面。"))
        val out = repo.renderedForInjection("c")
        assertTrue(out.contains("【见面 · "))
        assertTrue(out.contains("一次很好的见面。"))
    }

    @Test
    fun e15_deleteByCharacter_delegatesToDao() = runBlocking {
        repo.deleteByCharacter("c")
        coVerify(exactly = 1) { dao.deleteByCharacter("c") }
    }

    @Test
    fun upsertMeeting_existingSession_preservesUuidAndCreatedAt() = runBlocking {
        val existing = meetingRow("s1", "旧摘要。").copy(uuid = "old-uuid", createdAtMillis = 100L)
        coEvery { dao.findBySessionId("s1") } returns existing
        val incoming = meetingRow("s1", "新摘要。").copy(uuid = "new-uuid", createdAtMillis = 999L)

        val result = repo.upsertMeeting(incoming)

        assertEquals("old-uuid", result.uuid)
        assertEquals(100L, result.createdAtMillis)
        assertEquals("新摘要。", result.summary)
        coVerify { dao.upsert(match { it.uuid == "old-uuid" && it.createdAtMillis == 100L && it.summary == "新摘要。" }) }
    }

    // ── 播种收口 + 手动编辑（B3 收尾：注入宏直读行·行是唯一真相源） ──

    @Test
    fun upsertMeeting_seedsOldBlobBeforeUpsert_soInjectionKeepsOldMeetings() = runBlocking {
        // 旧 blob 有一次见面·未播种（count=0）→ upsertMeeting 前先 ensureSeeded 把旧见面落成行，
        // 新行落库后 renderedForInjection 才含旧+新（防注入丢旧摘要）。
        coEvery { characterDao.getByUuid("c") } returns charWithBlob("【见面 · 2026-04-18 15:30 · 公园】\n旧见面摘要。")
        coEvery { dao.countByCharacter("c") } returns 0
        coEvery { dao.findBySessionId("s2") } returns null

        repo.upsertMeeting(meetingRow("s2", "新见面摘要。"))

        coVerify(exactly = 1) { dao.upsertAll(match { it.isNotEmpty() }) } // 先播种旧 blob
        coVerify { dao.upsert(match { it.sessionId == "s2" && it.summary == "新见面摘要。" }) } // 再落新行
    }

    @Test
    fun updateEdited_updatesRowManual() = runBlocking {
        val existing = meetingRow("s1", "旧摘要。").copy(uuid = "u1")
        coEvery { dao.findByUuid("u1") } returns existing

        repo.updateEdited("u1", "新地点", "新活动", "编辑后。", 555L)

        coVerify {
            dao.upsert(
                match {
                    it.uuid == "u1" && it.location == "新地点" && it.activity == "新活动" &&
                        it.summary == "编辑后。" && it.sourceRaw == "manual" && it.updatedAtMillis == 555L
                },
            )
        }
    }

    @Test
    fun updateEdited_rowMissing_noOp() = runBlocking {
        coEvery { dao.findByUuid("missing") } returns null
        repo.updateEdited("missing", "x", "y", "z", 1L)
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    // ── 记忆改造四期·部件⑥（图纸 §3.2·T2-5·E3）：编辑正文置 embedding=null，令旧向量失效待重嵌 ──

    @Test
    fun updateEdited_dropsStaleEmbedding_e3() = runBlocking {
        // 已建向量的档案被编辑正文 → 旧向量不再匹配新文本 → 必须置 null（下次 worker 重嵌）。
        val existing = meetingRow("s1", "旧摘要。").copy(uuid = "u1", embedding = byteArrayOf(1, 2, 3, 4))
        coEvery { dao.findByUuid("u1") } returns existing

        repo.updateEdited("u1", "新地点", "新活动", "编辑后。", 555L)

        coVerify {
            dao.upsert(
                match {
                    it.uuid == "u1" && it.summary == "编辑后。" && it.sourceRaw == "manual" && it.embedding == null
                },
            )
        }
    }
}
