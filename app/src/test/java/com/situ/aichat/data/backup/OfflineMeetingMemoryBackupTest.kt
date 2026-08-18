package com.situ.aichat.data.backup

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
 * 见面回忆表备份往返 + 真 Room DAO 看门（图纸 §3.2 / T3-1 备份·照 WorldBackupRoundtripTest 范式）。断言从图纸独立反推：
 * - E16：采集 → JSON(encodeDefaults=false)往返 → 恢复 行级相等（源库=目标库），再次恢复按 uuid REPLACE 幂等（无重复）
 * - 幽灵 characterUuid 行整行跳过；旧备份无此段（null）恢复不崩、表空
 * - 真 DAO：byCharacter startedAt 升序（legacy=0 排最前）+ deleteByCharacter 清角（E15）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineMeetingMemoryBackupTest {

    private lateinit var src: AppDatabase
    private lateinit var dst: AppDatabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Before fun setUp() { src = newDb(); dst = newDb() }
    @After fun tearDown() { src.close(); dst.close() }

    private fun newDb() =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun row(uuid: String, cid: String, sid: String, started: Long, kind: String = "meeting") =
        OfflineMeetingMemoryEntity(
            uuid = uuid, characterUuid = cid, conversationUuid = "conv", sessionId = sid, kindRaw = kind,
            startedAtMillis = started, endedAtMillis = started + 100, location = "公园", activity = "散步",
            moodRaw = "warm", initiatedByUser = true, messageCount = 12, summary = "摘要正文$uuid",
            highlightsJson = "[\"细节\"]", promisesJson = "[\"下次\"]", sourceRaw = "llm",
            createdAtMillis = started, updatedAtMillis = started,
        )

    @Test
    fun e16_jsonRoundtrip_rowLevelEqual() = runBlocking {
        val a = "charA"
        src.offlineMeetingMemoryDao().upsertAll(
            listOf(row("m1", a, "s1", 1000), row("m2", a, "s2", 2000)),
        )
        val collected = collectOfflineMeetingMemories(src.offlineMeetingMemoryDao())!!
        val decoded = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(OfflineMeetingMemoryExport.serializer()),
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(OfflineMeetingMemoryExport.serializer()),
                collected,
            ),
        )
        restoreOfflineMeetingMemories(dst.offlineMeetingMemoryDao(), decoded, setOf(a))

        assertEquals(
            src.offlineMeetingMemoryDao().getAll().toSet(),
            dst.offlineMeetingMemoryDao().getAll().toSet(),
        )
    }

    @Test
    fun e16_restoreTwice_replaceIdempotent() = runBlocking {
        val a = "charA"
        val export = listOf(row("m1", a, "s1", 1000).toExport())
        restoreOfflineMeetingMemories(dst.offlineMeetingMemoryDao(), export, setOf(a))
        restoreOfflineMeetingMemories(dst.offlineMeetingMemoryDao(), export, setOf(a))
        assertEquals("同 uuid REPLACE 不产生重复", 1, dst.offlineMeetingMemoryDao().getAll().size)
    }

    @Test
    fun restore_ghostCharacterRowsSkipped() = runBlocking {
        val a = "charA"
        val ghost = "ghostX"
        val export = listOf(row("m1", a, "s1", 1000).toExport(), row("m2", ghost, "s2", 2000).toExport())
        restoreOfflineMeetingMemories(dst.offlineMeetingMemoryDao(), export, setOf(a))
        assertEquals(listOf("m1"), dst.offlineMeetingMemoryDao().getAll().map { it.uuid })
    }

    @Test
    fun restore_nullSection_doesNotCrash_tableEmpty() = runBlocking {
        restoreOfflineMeetingMemories(dst.offlineMeetingMemoryDao(), null, emptySet())
        assertTrue(dst.offlineMeetingMemoryDao().getAll().isEmpty())
    }

    @Test
    fun dao_byCharacter_ascByStartedAt_legacyFirst() = runBlocking {
        val a = "charA"
        src.offlineMeetingMemoryDao().upsertAll(
            listOf(
                row("m2", a, "s2", 2000),
                row("legacy", a, "", 0, kind = "legacy"),
                row("m1", a, "s1", 1000),
            ),
        )
        assertEquals(
            listOf("legacy", "m1", "m2"),
            src.offlineMeetingMemoryDao().byCharacter(a).map { it.uuid },
        )
    }

    @Test
    fun dao_deleteByCharacter_clearsOnlyThatCharacter_e15() = runBlocking {
        val a = "charA"
        val b = "charB"
        val dao = src.offlineMeetingMemoryDao()
        dao.upsertAll(listOf(row("m1", a, "s1", 1000), row("m2", b, "s2", 2000)))
        dao.deleteByCharacter(a)
        assertTrue(dao.byCharacter(a).isEmpty())
        assertEquals(listOf("m2"), dao.byCharacter(b).map { it.uuid })
    }
}
