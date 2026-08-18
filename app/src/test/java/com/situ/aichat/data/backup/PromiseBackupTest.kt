package com.situ.aichat.data.backup

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.PromiseEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
 * 承诺账本备份往返 + 真 Room DAO 看门（图纸 §3.1 / T2-8·照 [OfflineMeetingMemoryBackupTest] 范式）。
 * 断言从图纸 §3.1/§5 独立反推：
 * - toExport/toEntity JSON(encodeDefaults=false) 往返 → 行级相等；再次恢复按 uuid REPLACE 幂等（E12 幂等）
 * - 幽灵 characterUuid 行整行跳过（E15）；旧备份无此段（null）恢复不崩、表空
 * - 旧包缺新字段反序列化 → kotlinx 默认值兜底（statusRaw=open / sourceRaw=chat / 各可空=null·E12）
 * - 真 DAO：openByCharacter 只出 open 行、deleteByCharacter 清角（E15）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromiseBackupTest {

    private lateinit var src: AppDatabase
    private lateinit var dst: AppDatabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Before fun setUp() { src = newDb(); dst = newDb() }
    @After fun tearDown() { src.close(); dst.close() }

    private fun newDb() =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun row(
        uuid: String,
        cid: String,
        status: String = "open",
        due: Long? = null,
        source: String = "chat",
        created: Long = 1000,
    ) = PromiseEntity(
        uuid = uuid, characterUuid = cid, conversationUuid = "conv", content = "约定$uuid",
        statusRaw = status, dueAtMillis = due, sourceRaw = source, sourceSessionId = "sess-$uuid",
        openLoopUuid = if (due != null) "loop-$uuid" else null,
        resolvedAtMillis = if (status != "open") created + 500 else null,
        resolutionEvidence = if (status != "open") "原话证据$uuid" else "",
        createdAtMillis = created, updatedAtMillis = created,
    )

    @Test
    fun jsonRoundtrip_rowLevelEqual() = runBlocking {
        val a = "charA"
        src.promiseDao().upsertAll(
            listOf(
                row("p1", a, created = 1000),
                row("p2", a, status = "fulfilled", created = 2000),
                row("p3", a, due = 9_000_000, created = 3000),
            ),
        )
        val collected = collectPromises(src.promiseDao())!!
        val decoded = json.decodeFromString(
            ListSerializer(PromiseExport.serializer()),
            json.encodeToString(ListSerializer(PromiseExport.serializer()), collected),
        )
        restorePromises(dst.promiseDao(), decoded, setOf(a))

        assertEquals(src.promiseDao().getAll().toSet(), dst.promiseDao().getAll().toSet())
    }

    @Test
    fun restoreTwice_replaceIdempotent() = runBlocking {
        val a = "charA"
        val export = listOf(row("p1", a).toExport())
        restorePromises(dst.promiseDao(), export, setOf(a))
        restorePromises(dst.promiseDao(), export, setOf(a))
        assertEquals("同 uuid REPLACE 不产生重复", 1, dst.promiseDao().getAll().size)
    }

    @Test
    fun restore_ghostCharacterRowsSkipped() = runBlocking {
        val a = "charA"
        val ghost = "ghostX"
        val export = listOf(row("p1", a).toExport(), row("p2", ghost).toExport())
        restorePromises(dst.promiseDao(), export, setOf(a))
        assertEquals(listOf("p1"), dst.promiseDao().getAll().map { it.uuid })
    }

    @Test
    fun restore_nullSection_doesNotCrash_tableEmpty() = runBlocking {
        restorePromises(dst.promiseDao(), null, emptySet())
        assertTrue(dst.promiseDao().getAll().isEmpty())
    }

    @Test
    fun oldPackage_missingNewFields_decodesToDefaults() {
        // 旧包只带最小字段（缺 statusRaw / sourceRaw / 各可空 / 审计时间）→ kotlinx 默认值兜底（E12）。
        val minimal = """[{"uuid":"p1","characterUuid":"a","content":"约定"}]"""
        val decoded = json.decodeFromString(ListSerializer(PromiseExport.serializer()), minimal)
        val e = decoded.single()
        assertEquals("open", e.statusRaw)
        assertEquals("chat", e.sourceRaw)
        assertEquals("", e.sourceSessionId)
        assertEquals("", e.resolutionEvidence)
        assertNull(e.dueAtMillis)
        assertNull(e.openLoopUuid)
        assertNull(e.resolvedAtMillis)
        assertEquals(0L, e.createdAtMillis)
        assertEquals(0L, e.updatedAtMillis)
        // 转实体同样兜底默认。
        val entity = e.toEntity()
        assertEquals("open", entity.statusRaw)
        assertEquals("chat", entity.sourceRaw)
        assertNull(entity.dueAtMillis)
    }

    @Test
    fun dao_openByCharacter_onlyOpenRows() = runBlocking {
        val a = "charA"
        src.promiseDao().upsertAll(
            listOf(
                row("p1", a, created = 1000),
                row("p2", a, status = "fulfilled", created = 2000),
                row("p3", a, status = "cancelled", created = 3000),
            ),
        )
        assertEquals(listOf("p1"), src.promiseDao().openByCharacter(a).map { it.uuid })
    }

    @Test
    fun dao_deleteByCharacter_clearsOnlyThatCharacter_e15() = runBlocking {
        val a = "charA"
        val b = "charB"
        val dao = src.promiseDao()
        dao.upsertAll(listOf(row("p1", a), row("p2", b)))
        dao.deleteByCharacter(a)
        assertTrue(dao.openByCharacter(a).isEmpty())
        assertEquals(listOf("p2"), dao.getAll().map { it.uuid })
    }
}
