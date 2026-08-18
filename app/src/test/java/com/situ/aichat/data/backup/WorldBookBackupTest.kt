package com.situ.aichat.data.backup

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldBookEntity
import com.situ.aichat.data.local.entity.WorldBookEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 世界书备份段 T1/T2（WB6b）：DTO 双向映射字段级一致（嵌入除外=有意不进备份）、
 * 恢复幂等（重复导入不叠书）、旧条目清理、绑定过滤幽灵角色、采集↔恢复真库全链往返。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldBookBackupTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun fullEntry(uuid: String, book: String) = WorldBookEntryEntity(
        uuid = uuid, bookUuid = book, uid = 7, displayIndex = 3, keysJson = """["青云宗"]""",
        secondaryKeysJson = """["宗主"]""", selective = false, selectiveLogic = 3, constant = true,
        vectorized = true, comment = "门派", content = "青云宗设定", enabled = false,
        insertionOrder = 250, position = 4, depth = 2, role = 2, ignoreBudget = true,
        probability = 75, useProbability = true, scanDepth = 5, caseSensitive = true,
        matchWholeWords = false, useGroupScoring = true, excludeRecursion = true,
        preventRecursion = true, delayUntilRecursion = 2, groupName = "g", groupOverride = true,
        groupWeight = 60, sticky = 3, cooldown = 8, delay = 10, extraJson = """{"x":1}""",
        embedding = byteArrayOf(1, 2), embeddingSignature = "sig",
    )

    @Test
    fun DTO双向映射_字段级一致_嵌入有意不随包() {
        val entry = fullEntry("e1", "b1")
        val restored = entry.toExport().toEntity("b1")
        assertEquals("除嵌入两列外逐字段一致", entry.copy(embedding = null, embeddingSignature = null), restored)

        val book = WorldBookEntity(
            uuid = "b1", name = "青云录", description = "修仙", scanDepth = 3, tokenBudget = 1024,
            recursiveScanning = true, isGlobal = true, enabled = false, extraJson = """{"y":2}""",
            createdAt = 11L, updatedAt = 22L,
        )
        assertEquals(book, book.toExport().toEntity())
    }

    @Test
    fun 恢复_幂等_绑定过滤幽灵_旧条目清理() = runBlocking {
        val dao = db.worldBookDao()
        db.characterDao().upsert(CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L))

        val v1 = WorldBookBackupData(
            book = WorldBookEntity(uuid = "b1", name = "青云录").toExport(),
            entries = listOf(fullEntry("e1", "b1").toExport(), fullEntry("e2", "b1").toExport()),
            boundCharacterUuids = listOf("c1", "幽灵角色"),
        )

        restoreWorldBooks(dao, listOf(v1), setOf("c1"))
        restoreWorldBooks(dao, listOf(v1), setOf("c1")) // 幂等：重复导入不叠

        assertEquals(1, dao.getAllBooks().size)
        assertEquals(2, dao.entryCountForBook("b1"))
        assertEquals("幽灵角色绑定须被过滤、c1 绑定去重为一条", 1, dao.bindingCountForBook("b1"))

        // 新版本备份少了一条条目 → 恢复须清掉旧残留
        val v2 = v1.copy(entries = listOf(fullEntry("e1", "b1").toExport()))
        restoreWorldBooks(dao, listOf(v2), setOf("c1"))
        assertEquals(1, dao.entryCountForBook("b1"))
    }

    @Test
    fun 采集与恢复_真库全链往返() = runBlocking {
        val dao = db.worldBookDao()
        db.characterDao().upsert(CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L))
        dao.upsertBook(WorldBookEntity(uuid = "b1", name = "青云录", isGlobal = true))
        dao.upsertEntry(fullEntry("e1", "b1"))
        dao.bind(com.situ.aichat.data.local.entity.WorldBookBindingEntity("c1", "b1"))

        val collected = collectWorldBooks(dao)!!

        // 清库 → 恢复 → 与原库一致（嵌入除外）
        dao.deleteBook("b1")
        restoreWorldBooks(dao, collected, setOf("c1"))

        assertEquals("青云录", dao.getBook("b1")?.name)
        assertEquals(
            fullEntry("e1", "b1").copy(embedding = null, embeddingSignature = null),
            dao.getEntry("e1"),
        )
        assertEquals(listOf("b1"), dao.boundBookUuids("c1"))
    }

    @Test
    fun 空段_采集null_恢复null都安静() = runBlocking {
        val dao = db.worldBookDao()
        assertEquals(null, collectWorldBooks(dao))
        restoreWorldBooks(dao, null, emptySet()) // 不抛即过
        assertEquals(0, dao.getAllBooks().size)
    }
}
