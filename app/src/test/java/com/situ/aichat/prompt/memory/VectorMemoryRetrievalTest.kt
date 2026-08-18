package com.situ.aichat.prompt.memory

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 向量检索全量扫描 T2（批1 1-2·Robolectric 真 Room + MockK 嵌入器·CHAT_CORE_HEALTH_PLAN.md）：
 * 规格——语义检索候选必须覆盖会话【全部】已嵌入消息。修复前实现只取每会话最新 200 条，
 * 第 201 条之外的老消息永久不可召回（本测试第一例在旧实现下必红）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VectorMemoryRetrievalTest {

    private lateinit var db: AppDatabase
    private lateinit var service: VectorMemoryService
    private val embedder = mockk<TextEmbedder>()

    /** 第二路候选（记忆改造四期·E15-④）：既有消息路用例默认返回空 Retrieval → 消息路行为逐字节不变。 */
    private val archiveIndex = mockk<MeetingArchiveVectorService>()

    private val charUuid = "char-1"
    private val currentConv = "conv-current"
    private val historyConv = "conv-history"

    /** 与查询同向（相似度 1.0）/ 正交（相似度 0）的四维向量。 */
    private val queryVec = floatArrayOf(1f, 0f, 0f, 0f)
    private val orthogonalVec = floatArrayOf(0f, 1f, 0f, 0f)

    private val queryText = "还记得我们聊过的那件重要的事情吗"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        service = VectorMemoryService(db.messageDao(), db.conversationDao(), embedder, archiveIndex)
        every { embedder.embed(queryText) } returns queryVec
        // 默认第二路空（既有断言零改）；档案合并/排除专测各自覆盖 retrieval 桩。
        coEvery { archiveIndex.retrieval(any(), any(), any()) } returns
            MeetingArchiveVectorService.Retrieval(emptyList(), emptySet())
        runBlocking {
            db.characterDao().upsert(CharacterEntity(uuid = charUuid, name = "角色", creationDate = 0L))
            db.conversationDao().upsert(ConversationEntity(uuid = currentConv, title = "当前", characterUuid = charUuid, creationDate = 0L))
            db.conversationDao().upsert(ConversationEntity(uuid = historyConv, title = "历史", characterUuid = charUuid, creationDate = 0L))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun embeddedMsg(ts: Long, content: String, vec: FloatArray) = MessageEntity(
        messageUUID = "m-$ts",
        conversationUuid = historyConv,
        roleRaw = if (ts % 2 == 1L) "user" else "assistant",
        content = content,
        timestamp = ts,
        embedding = service.serializeEmbedding(vec),
    )

    @Test
    fun `第201条之外的老消息可被召回`() = runBlocking {
        // 最旧一条（ts=1）与查询同向；其后 249 条全部正交 → 旧实现（最新 200 条窗口）永远看不到 ts=1。
        db.messageDao().upsert(embeddedMsg(1L, "两百条之外的目标老消息内容", queryVec))
        for (ts in 2L..250L) {
            db.messageDao().upsert(embeddedMsg(ts, "这是第 $ts 条无关的普通消息", orthogonalVec))
        }

        val result = service.searchRelevantMemories(
            query = queryText,
            characterUuid = charUuid,
            currentConversationUuid = currentConv,
            userName = "司徒",
            characterName = "夏晴子",
            shortTermLength = 20,
            thresholdPercent = 65,
        )

        assertEquals("只有目标老消息过阈值", 1, result.size)
        assertTrue("召回的必须是第 201+ 条之外的那条老消息", result.single().contains("两百条之外的目标老消息内容"))
        // 真名标注（2026-07-12 拍板）：ts=1 为奇数 → user 消息 → 标注用传入的用户名，绝非「用户」。
        assertTrue("说话人标注应为真名：${result.single()}", result.single().contains("司徒："))
    }

    @Test
    fun `阈值以下的候选不注入`() = runBlocking {
        for (ts in 1L..30L) {
            db.messageDao().upsert(embeddedMsg(ts, "这是第 $ts 条无关的普通消息", orthogonalVec))
        }
        val result = service.searchRelevantMemories(
            query = queryText,
            characterUuid = charUuid,
            currentConversationUuid = currentConv,
            userName = "司徒",
            characterName = "夏晴子",
            shortTermLength = 20,
            thresholdPercent = 65,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `TOP_K上限仍然生效`() = runBlocking {
        for (ts in 1L..300L) {
            db.messageDao().upsert(embeddedMsg(ts, "这是第 $ts 条全部同向的消息内容", queryVec))
        }
        val result = service.searchRelevantMemories(
            query = queryText,
            characterUuid = charUuid,
            currentConversationUuid = currentConv,
            userName = "司徒",
            characterName = "夏晴子",
            shortTermLength = 20,
            thresholdPercent = 65,
        )
        assertEquals(VectorMemoryService.TOP_K, result.size)
    }

    // ── 记忆改造四期·部件⑥（图纸 §3.2 / §7 T2-1/T2-2/T2-4）：单池合并 + session 排除 + 双清。既有消息路断言零改 ──

    /** 与查询夹角 cos=0.8 的向量（0.8² + 0.6² = 1·过 0.65 阈值·低于同向消息 1.0）。 */
    private val partialVec = floatArrayOf(0.8f, 0.6f, 0f, 0f)

    @Test fun `T2-1 档案候选与消息候选单池合并按相似度_formatArchiveSnippet格式`() = runBlocking {
        // 3 条 cos=0.8 消息 + 1 条 sim=0.95 档案候选（MockK 注入）→ 合并后档案凭更高相似度排最前。
        for (ts in 1L..3L) db.messageDao().upsert(embeddedMsg(ts, "普通消息内容$ts", partialVec))
        coEvery { archiveIndex.retrieval(any(), any(), any()) } returns MeetingArchiveVectorService.Retrieval(
            candidates = listOf(MeetingArchiveVectorService.ArchiveCandidate("那次见面的档案回忆", 1_700_000_000_000L, 0.95)),
            excludedSessionIds = emptySet(),
        )

        val result = service.searchRelevantMemories(queryText, charUuid, currentConv, "司徒", "夏晴子", 20, 65)

        assertEquals("合并池 = 3 消息 + 1 档案", 4, result.size)
        assertTrue("档案片段走 formatArchiveSnippet 格式", result.first().contains(" · 见面档案] 那次见面的档案回忆"))
        assertTrue("档案（0.95）排在消息（0.8）之前", result.first().contains("· 见面档案]"))
        assertTrue("消息候选仍在池内", result.any { it.contains("普通消息内容") })
    }

    @Test fun `T2-2 排除集命中的offlineSessionId消息跳过_null不受影响_e5`() = runBlocking {
        db.messageDao().upsert(embeddedMsg(1L, "被排除的见面原文消息", queryVec).copy(offlineSessionId = "excluded-sess"))
        db.messageDao().upsert(embeddedMsg(2L, "普通聊天消息不受影响", queryVec).copy(offlineSessionId = null))
        coEvery { archiveIndex.retrieval(any(), any(), any()) } returns
            MeetingArchiveVectorService.Retrieval(emptyList(), setOf("excluded-sess"))

        val result = service.searchRelevantMemories(queryText, charUuid, currentConv, "司徒", "夏晴子", 20, 65)

        assertTrue("排除集命中的 offlineSessionId 消息被跳过", result.none { it.contains("被排除的见面原文消息") })
        assertTrue("null offlineSessionId 不受排除影响", result.any { it.contains("普通聊天消息不受影响") })
    }

    @Test fun `T2-4 模型签名变更_CLEAR_AND_REEMBED分支双清含档案_e2`() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication()
        EmbeddingModelSignatureStore.set(ctx, "legacy-other-dim384") // 存量旧签名（非首装·与当前不同）
        every { embedder.isAvailable } returns true
        coEvery { archiveIndex.clearAll() } returns 3

        service.detectModelChangeAndClearIfNeeded(ctx)

        coVerify(exactly = 1) { archiveIndex.clearAll() } // 消息 + 档案双清（图纸 §3.2）
    }
}
