package com.situ.aichat.prompt.memory

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.diagnostics.ContextLogService
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 滚动摘要收集/游标 T2（批1 1-1·Robolectric 真 Room·CHAT_CORE_HEALTH_PLAN.md）：
 * 断言从规格反推——「窗口外、游标后的每条消息终将且只会进一次摘要」，非照搬实现。
 *
 * 修复前缺陷（本测试组第一例在旧实现下必红）：`summarizableMessages` 恒取全会话**最旧** 500 条再在
 * Kotlin 侧过滤游标 → 会话超 500 条且游标越过第 500 条后，收集恒空，摘要永久停摆。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemorySummaryCollectionTest {

    private lateinit var db: AppDatabase
    private lateinit var memoryService: MemoryService

    private val charUuid = "char-1"
    private val convUuid = "conv-1"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        memoryService = MemoryService(db.messageDao(), db.conversationDao(), mockk<ContextLogService>(relaxed = true))
        runBlocking {
            db.characterDao().upsert(CharacterEntity(uuid = charUuid, name = "角色", creationDate = 0L))
            db.conversationDao().upsert(ConversationEntity(uuid = convUuid, title = "会话", characterUuid = charUuid, creationDate = 0L))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** ts 兼作序号：奇数 user / 偶数 assistant。 */
    private fun msg(ts: Long, conv: String = convUuid, offline: Boolean = false) = MessageEntity(
        messageUUID = "m-$conv-$ts",
        conversationUuid = conv,
        roleRaw = if (ts % 2 == 1L) "user" else "assistant",
        content = "这是第 $ts 条测试消息内容",
        timestamp = ts,
        isOfflineMode = offline,
    )

    private fun seed(range: LongRange, conv: String = convUuid, offline: (Long) -> Boolean = { false }) = runBlocking {
        for (ts in range) db.messageDao().upsert(msg(ts, conv, offline(ts)))
    }

    private fun setCursor(cursor: Long?, conv: String = convUuid) = runBlocking {
        if (cursor != null) db.conversationDao().updateSummaryCursor(conv, cursor)
    }

    private fun collect(shortTermLength: Int = 10): List<MessageEntity> = runBlocking {
        memoryService.collectMessagesOutsideWindow(charUuid, convUuid, shortTermLength)
    }

    // ---- 停摆修复 ----

    @Test
    fun `会话超500条且游标越过最旧500条后_游标之后的消息仍可收集`() {
        seed(1L..600L)
        setCursor(550L)
        // shortTermLength=10 → 窗口起点 = 第 10 近的 user 消息 ts=581（user=奇数 599..581）
        val collected = collect(shortTermLength = 10)
        assertTrue("停摆重现：收集不得为空", collected.isNotEmpty())
        assertEquals("应恰为游标后、窗口前的 551..580 共 30 条", 30, collected.size)
        assertTrue(collected.all { it.timestamp in 551L..580L })
    }

    @Test
    fun `积压超过500条时分轮消化且游标不过冲`() = runBlocking {
        seed(1L..1200L)
        // 窗口起点 = 第 10 近 user ts=1181；游标空 → 第一轮取最旧 500 条
        val round1 = collect(shortTermLength = 10)
        assertEquals(500, round1.size)
        assertEquals(1L..500L, round1.minOf { it.timestamp }..round1.maxOf { it.timestamp })

        memoryService.markSummarized(round1)
        assertEquals(500L, db.conversationDao().getByUuid(convUuid)?.lastSummarizedMessageDate)

        val round2 = collect(shortTermLength = 10)
        assertEquals("第二轮应从 501 接续（游标不过冲不跳段）", 501L..1000L, round2.minOf { it.timestamp }..round2.maxOf { it.timestamp })

        memoryService.markSummarized(round2)
        val round3 = collect(shortTermLength = 10)
        assertEquals("第三轮消化至窗口起点为止", 1001L..1180L, round3.minOf { it.timestamp }..round3.maxOf { it.timestamp })
    }

    // ---- 线下隔离 ----

    @Test
    fun `线下叙事消息不进常规摘要收集`() {
        seed(1L..60L, offline = { it in 21L..40L })
        // shortTermLength=5 → 窗口起点 = 第 5 近 user ts=51
        val collected = collect(shortTermLength = 5)
        assertTrue("线下消息必须被谓词隔离", collected.none { it.isOfflineMode })
        assertEquals("1..20 ∪ 41..50 共 30 条", 30, collected.size)
    }

    @Test
    fun `未总结轮数统计排除线下消息`() = runBlocking {
        seed(1L..60L, offline = { it in 21L..40L })
        val conv = db.conversationDao().getByUuid(convUuid)!!
        // 窗口起点 51 前的 user=25 条，其中线下 10 条 → 15
        assertEquals(15, memoryService.countUnsummarizedRoundsOutsideBaseWindow(conv, 5))
    }

    // ---- 游标推进语义 ----

    @Test
    fun `游标按实际喂入批次推进_未贡献会话不动`() = runBlocking {
        val convB = "conv-2"
        db.conversationDao().upsert(ConversationEntity(uuid = convB, title = "会话2", characterUuid = charUuid, creationDate = 0L))
        seed(1L..10L)
        seed(101L..110L, conv = convB)

        memoryService.markSummarized((1L..6L).map { msg(it) })

        assertEquals(6L, db.conversationDao().getByUuid(convUuid)?.lastSummarizedMessageDate)
        assertNull("未喂入任何消息的会话游标必须保持原状", db.conversationDao().getByUuid(convB)?.lastSummarizedMessageDate)
    }
}
