package com.situ.aichat.data.backup

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.UserStoryTemplateEntity
import com.situ.aichat.data.model.UserStoryTemplatePayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 「我的模板」备份往返 + 真 Room DAO 看门（图纸四 §3.2 / §5 E10-E11 / §7 T1-3·照 [PromiseBackupTest] 范式）。
 * 断言从图纸独立反推：
 * - toExport/toEntity JSON 往返 → 行级相等；再次恢复按 uuid REPLACE 幂等
 * - 老备份无此段（null）恢复不崩、表空（E11）
 * - payload 串原样往返：忌口与两个格式开关不丢不串（E6）
 * - 真 DAO：observeAll 按 createdAt 倒序、rename 只动名字、delete/count 行为（E8/E10）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserStoryTemplateBackupTest {

    private lateinit var src: AppDatabase
    private lateinit var dst: AppDatabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Before fun setUp() { src = newDb(); dst = newDb() }
    @After fun tearDown() { src.close(); dst.close() }

    private fun newDb() =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun row(uuid: String, name: String = "模板$uuid", created: Long = 1000) = UserStoryTemplateEntity(
        uuid = uuid,
        name = name,
        createdAt = created,
        payloadJson = UserStoryTemplatePayload.encode(
            UserStoryTemplatePayload(
                genre = "赛博修真",
                isCustomGenre = true,
                writingStyle = "哥特暗黑",
                chapterLengthPreference = 2600,
                worldSetting = "民国上海",
                customPromptsJson = """{"bannedExpressions":"忌口","chapterChoicesEnabled":false}""",
            ),
        ),
    )

    @Test
    fun jsonRoundtrip_rowLevelEqual() = runBlocking {
        src.userStoryTemplateDao().insert(row("t1", created = 1000))
        src.userStoryTemplateDao().insert(row("t2", created = 2000))
        val collected = collectUserStoryTemplates(src.userStoryTemplateDao())!!
        val decoded = json.decodeFromString(
            ListSerializer(UserStoryTemplateExport.serializer()),
            json.encodeToString(ListSerializer(UserStoryTemplateExport.serializer()), collected),
        )
        restoreUserStoryTemplates(dst.userStoryTemplateDao(), decoded)

        assertEquals(src.userStoryTemplateDao().getAll().toSet(), dst.userStoryTemplateDao().getAll().toSet())
    }

    @Test
    fun roundtrip_payloadStringSurvivesVerbatim() = runBlocking {
        src.userStoryTemplateDao().insert(row("t1"))
        restoreUserStoryTemplates(dst.userStoryTemplateDao(), collectUserStoryTemplates(src.userStoryTemplateDao()))
        val back = UserStoryTemplatePayload.decode(dst.userStoryTemplateDao().byUuid("t1")!!.payloadJson)
        assertNotNull(back)
        assertEquals("赛博修真", back!!.genre)
        assertEquals(2600, back.chapterLengthPreference)
        assertEquals(
            "忌口与格式开关住在这一串里，经备份也必须一个字不差",
            """{"bannedExpressions":"忌口","chapterChoicesEnabled":false}""",
            back.customPromptsJson,
        )
    }

    @Test
    fun restoreTwice_replaceIdempotent() = runBlocking {
        val export = listOf(row("t1").toExport())
        restoreUserStoryTemplates(dst.userStoryTemplateDao(), export)
        restoreUserStoryTemplates(dst.userStoryTemplateDao(), export)
        assertEquals("同 uuid REPLACE 不产生重复", 1, dst.userStoryTemplateDao().getAll().size)
    }

    @Test
    fun restore_nullSection_doesNotCrash_tableEmpty() = runBlocking {
        // 老备份（图纸四之前导出的包）没有这一段 → null，恢复后表空、内置 12 套模板不受影响。
        restoreUserStoryTemplates(dst.userStoryTemplateDao(), null)
        assertTrue(dst.userStoryTemplateDao().getAll().isEmpty())
    }

    @Test
    fun oldPackage_missingFields_decodesToDefaults() {
        val minimal = """[{"uuid":"t1"}]"""
        val e = json.decodeFromString(ListSerializer(UserStoryTemplateExport.serializer()), minimal).single()
        assertEquals("", e.name)
        assertEquals(0L, e.createdAt)
        assertEquals("", e.payloadJson)
    }

    @Test
    fun collect_emptyTable_yieldsNullSection() = runBlocking {
        assertTrue(collectUserStoryTemplates(src.userStoryTemplateDao()) == null)
    }

    @Test
    fun dao_observeAll_newestFirst() = runBlocking {
        val dao = src.userStoryTemplateDao()
        dao.insert(row("t1", created = 1000))
        dao.insert(row("t3", created = 3000))
        dao.insert(row("t2", created = 2000))
        assertEquals(listOf("t3", "t2", "t1"), dao.observeAll().first().map { it.uuid })
    }

    @Test
    fun dao_renameKeepsPayloadAndCreatedAt() = runBlocking {
        val dao = src.userStoryTemplateDao()
        dao.insert(row("t1", name = "旧名", created = 1234))
        dao.rename("t1", "新名")
        val after = dao.byUuid("t1")!!
        assertEquals("新名", after.name)
        assertEquals("重命名不许动存下的设定", row("t1").payloadJson, after.payloadJson)
        assertEquals("重命名不许动存入时刻（模板墙排序靠它）", 1234L, after.createdAt)
    }

    @Test
    fun dao_deleteAndCount() = runBlocking {
        val dao = src.userStoryTemplateDao()
        dao.insert(row("t1"))
        dao.insert(row("t2"))
        assertEquals(2, dao.count())
        dao.delete("t1")
        assertEquals(1, dao.count())
        assertEquals(listOf("t2"), dao.getAll().map { it.uuid })
    }
}
