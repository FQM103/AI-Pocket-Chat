package com.situ.aichat.data.worldbook

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 世界书仓库 T2（WB6a·Robolectric 真 Room + 真 codec 全链）：导入落库→导出往返、
 * 人话报错透传、删除级联、绑定与开关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldBookRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: WorldBookRepository

    private val stJson = """
        {"entries":{"1":{"uid":1,"key":["青云宗"],"content":"青云宗是北域第一门派。","comment":"门派"},
        "2":{"uid":2,"key":["灵田"],"content":"灵田在后山。","myCustom":"保留我"}}}
    """.trimIndent()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WorldBookRepository(db.worldBookDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun 导入_真库落地_导出往返不丢() = runBlocking {
        val result = repo.importFromJson(stJson, fallbackName = "青云录")

        assertEquals("青云录", result.bookName)
        assertEquals(2, result.entryCount)
        assertEquals(0, result.skippedEntryCount)
        assertEquals(WorldBookCodec.WorldBookFormat.ST_STANDALONE, result.format)
        assertEquals(2, db.worldBookDao().entryCountForBook(result.bookUuid))

        val exported = repo.exportBookAsJson(result.bookUuid)!!
        assertTrue("导出须含条目内容", exported.contains("青云宗是北域第一门派。"))
        assertTrue("未知字段须穿越 DB 往返存活", exported.contains("保留我"))

        // 再导入导出的 JSON：条目字段级一致（经真库的完整往返）
        val second = repo.importFromJson(exported, fallbackName = "副本")
        assertEquals(2, second.entryCount)
    }

    @Test
    fun 导入坏文件_人话异常透传() {
        val e = assertThrows(WorldBookParseException::class.java) {
            runBlocking { repo.importFromJson("不是JSON的东西", "书") }
        }
        assertTrue(e.message!!.contains("JSON"))
    }

    @Test
    fun 删除书_条目级联清() = runBlocking {
        val result = repo.importFromJson(stJson, "青云录")
        repo.deleteBook(result.bookUuid)
        assertNull(repo.getBook(result.bookUuid))
        assertEquals(0, db.worldBookDao().entryCountForBook(result.bookUuid))
    }

    @Test
    fun 绑定与解绑_经仓库直达() = runBlocking {
        db.characterDao().upsert(CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L))
        val result = repo.importFromJson(stJson, "青云录")

        repo.bind("c1", result.bookUuid)
        assertEquals(listOf(result.bookUuid), repo.boundBookUuids("c1"))

        repo.unbind("c1", result.bookUuid)
        assertTrue(repo.boundBookUuids("c1").isEmpty())
    }

    @Test
    fun 书开关与全局开关_影响激活候选() = runBlocking {
        val result = repo.importFromJson(stJson, "青云录")

        repo.setBookGlobal(result.bookUuid, true)
        assertEquals(1, db.worldBookDao().activeBooksForCharacter("任意角色").size)

        repo.setBookEnabled(result.bookUuid, false)
        assertTrue("停用书不得进激活候选", db.worldBookDao().activeBooksForCharacter("任意角色").isEmpty())
    }

    // MARK: - WB7a 管理面

    @Test
    fun 新建书与改名简介_经仓库() = runBlocking {
        val uuid = repo.createBook("修仙世界·青云录", "宗门设定")
        assertEquals("修仙世界·青云录", repo.getBook(uuid)!!.name)

        repo.updateBookMeta(uuid, "青云录 v2", "改过的简介")
        val book = repo.getBook(uuid)!!
        assertEquals("青云录 v2", book.name)
        assertEquals("改过的简介", book.description)
    }

    @Test
    fun 书架汇总_条目数与绑定数() = runBlocking {
        db.characterDao().upsert(CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L))
        val result = repo.importFromJson(stJson, "青云录")
        repo.bind("c1", result.bookUuid)

        val summaries = repo.observeBookSummaries().first()
        assertEquals(1, summaries.size)
        assertEquals(2, summaries[0].entryCount)
        assertEquals(1, summaries[0].boundCount)
    }

    @Test
    fun 保存条目_内容变清嵌入_未变保留库内现值() = runBlocking {
        val result = repo.importFromJson(stJson, "青云录")
        // 编辑器快照（此刻嵌入为空）
        val snapshot = db.worldBookDao().entriesForBook(result.bookUuid).first()
        // 编辑期间向量服务写入了嵌入（targeted UPDATE，模拟并发）
        db.worldBookDao().updateEntryEmbedding(snapshot.uuid, byteArrayOf(1, 2, 3), "sig-v1")

        // ① 内容没变的保存：不得用快照的 null 冲掉库内嵌入
        repo.saveEntry(snapshot.copy(insertionOrder = 50))
        val afterMetaSave = repo.getEntry(snapshot.uuid)!!
        assertEquals(50, afterMetaSave.insertionOrder)
        assertTrue("内容未变须保留库内嵌入", afterMetaSave.embedding!!.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("sig-v1", afterMetaSave.embeddingSignature)

        // ② 内容变了的保存：清嵌入两列，交给 WB5 懒补重嵌（热更新 §12.11）
        repo.saveEntry(afterMetaSave.copy(content = "青云宗改设定了。"))
        val afterContentSave = repo.getEntry(snapshot.uuid)!!
        assertNull("内容变更须清嵌入", afterContentSave.embedding)
        assertNull(afterContentSave.embeddingSignature)
    }

    @Test
    fun 条目开关_targeted更新不动嵌入() = runBlocking {
        val result = repo.importFromJson(stJson, "青云录")
        val entry = db.worldBookDao().entriesForBook(result.bookUuid).first()
        db.worldBookDao().updateEntryEmbedding(entry.uuid, byteArrayOf(9), "sig-v1")

        repo.setEntryEnabled(entry.uuid, false)
        val after = repo.getEntry(entry.uuid)!!
        assertTrue("开关不得动嵌入", after.embedding!!.contentEquals(byteArrayOf(9)))
        assertTrue(!after.enabled)
    }

    @Test
    fun 新条目草稿_uid与排序位接续() = runBlocking {
        val result = repo.importFromJson(stJson, "青云录")
        val draft = repo.newEntryDraft(result.bookUuid)
        val existing = db.worldBookDao().entriesForBook(result.bookUuid)
        assertEquals(existing.maxOf { it.uid } + 1, draft.uid)
        assertEquals(existing.maxOf { it.displayIndex } + 1, draft.displayIndex)
        assertEquals(result.bookUuid, draft.bookUuid)
    }

    @Test
    fun 开关不跳序_保存条目才顶更新时间() = runBlocking {
        val result = repo.importFromJson(stJson, "青云录")
        val before = repo.getBook(result.bookUuid)!!.updatedAt

        repo.setBookEnabled(result.bookUuid, false)
        repo.setBookGlobal(result.bookUuid, true)
        assertEquals("拨开关不得改 updatedAt（书架不跳序）", before, repo.getBook(result.bookUuid)!!.updatedAt)

        val entry = db.worldBookDao().entriesForBook(result.bookUuid).first()
        Thread.sleep(5)
        repo.saveEntry(entry.copy(content = "实改内容"))
        assertTrue("条目实变须顶书的 updatedAt", repo.getBook(result.bookUuid)!!.updatedAt > before)
    }
}
