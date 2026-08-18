package com.situ.aichat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.repository.MessageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 钉死 12.3 嵌入回填的【载荷假设】于真实 SQLite（设备/模拟器批末期跑，对齐 [MigrationTest] 的 androidTest 约定）：
 *
 * 1. **空 ByteArray sentinel = 非 NULL 空 blob**：[MessageDao.updateEmbedding] 写空数组后，该行 `embedding IS NULL`
 *    必须为假（移出「缺失集」、不再探测）——这是回填循环【终止性】的根基。若某天 Room/SQLite 把空数组绑成 NULL，
 *    回填会死循环，本测试立即报红。
 * 2. **列级写**：updateEmbedding 只改 embedding 列，不动其它列。
 * 3. **held 行排除**：未投递（isHeldForDelivery=1）的忙碌延迟消息不进 [MessageDao.messagesMissingEmbedding] /
 *    [MessageDao.hasMissingEmbedding]（不可成为可检索记忆、且回填不与 BusyReply 释放整行写竞争）；释放后才纳入。
 */
@RunWith(AndroidJUnit4::class)
class MessageEmbeddingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao

    private val convUuid = "conv-1"

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.messageDao()
        // FK 父链：character → conversation → messages。
        db.characterDao().upsert(CharacterEntity(uuid = "char-1", name = "测试", creationDate = 0L))
        db.conversationDao().upsert(
            ConversationEntity(uuid = convUuid, title = "t", characterUuid = "char-1", creationDate = 0L),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insert(uuid: String, ts: Long, held: Boolean = false, content: String = "一段够长的可嵌入内容") =
        dao.upsert(
            MessageEntity(
                messageUUID = uuid,
                conversationUuid = convUuid,
                roleRaw = "assistant",
                content = content,
                timestamp = ts,
                isHeldForDelivery = held,
            ),
        )

    @Test
    fun emptyByteArraySentinel_isNonNullBlob_andLeavesMissingSet() = runBlocking {
        insert("m1", 1L)
        assertTrue("插入后应有缺失", dao.hasMissingEmbedding())

        dao.updateEmbedding("m1", ByteArray(0)) // sentinel

        // 关键：空数组存成「非 NULL 空 blob」→ embedding IS NULL 为假 → 不再缺失。
        assertFalse("写空 sentinel 后不应再缺失（否则回填死循环）", dao.hasMissingEmbedding())
        assertTrue("messagesMissingEmbedding 不应再含该行", dao.messagesMissingEmbedding(100).none { it.messageUUID == "m1" })
        val row = dao.getByUuid("m1")
        assertNotNull(row)
        assertNotNull("sentinel 行 embedding 应为非 NULL", row!!.embedding)
        assertEquals("sentinel 应是空 blob", 0, row.embedding!!.size)
    }

    @Test
    fun updateEmbedding_writesOnlyEmbeddingColumn() = runBlocking {
        insert("m2", 2L, content = "原始内容")
        val bytes = byteArrayOf(1, 2, 3, 4)
        dao.updateEmbedding("m2", bytes)

        val row = dao.getByUuid("m2")!!
        assertTrue("embedding 应被写入", row.embedding!!.contentEquals(bytes))
        assertEquals("content 列不应被改动", "原始内容", row.content)
        assertEquals("roleRaw 列不应被改动", "assistant", row.roleRaw)
        assertFalse("写真向量后不应再缺失", dao.messagesMissingEmbedding(100).any { it.messageUUID == "m2" })
    }

    @Test
    fun heldMessage_excludedFromBackfill_untilReleased() = runBlocking {
        insert("held", 3L, held = true)
        // 仅有一条 held 缺失 → 不应被回填看见。
        assertFalse("held 消息不应使 hasMissingEmbedding 为真", dao.hasMissingEmbedding())
        assertTrue("held 消息不应进缺失批", dao.messagesMissingEmbedding(100).isEmpty())

        // 释放（投递）后纳入。
        dao.upsert(dao.getByUuid("held")!!.copy(isHeldForDelivery = false))
        assertTrue("释放后应被回填纳入", dao.hasMissingEmbedding())
        assertTrue("释放后应进缺失批", dao.messagesMissingEmbedding(100).any { it.messageUUID == "held" })
    }

    @Test
    fun windowedQuery_returnsNewestNDescending_excludesHeld_andRepoReversesToAsc() = runBlocking {
        (1..6).forEach { insert("d$it", it.toLong()) }
        insert("held", 100L, held = true) // 最新但暂扣 → 不应入可见窗口

        // DAO：最新 3 条已投递消息，DESC（newest first）。
        val windowDesc = dao.observeVisibleWindowed(convUuid, 3).first()
        assertEquals(listOf("d6", "d5", "d4"), windowDesc.map { it.messageUUID })
        assertTrue("暂扣消息不应入窗口", windowDesc.none { it.messageUUID == "held" })

        // 仓库层 reverse 成 ASC 显示顺序（最旧在前、最新在后）。
        val windowAsc = MessageRepository(dao).observeVisibleWindowed(convUuid, 3).first()
        assertEquals(listOf("d4", "d5", "d6"), windowAsc.map { it.messageUUID })
    }
}
