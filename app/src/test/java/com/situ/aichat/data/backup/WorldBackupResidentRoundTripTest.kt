package com.situ.aichat.data.backup

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.local.entity.WorldUserResidentEntity
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
 * 用户自建居民备份往返 T2-6（战役 B·图纸 §7·E10·Robolectric）：采集 → JSON 往返 → 恢复，
 * `userResidents` 段逐字段相等；旧备份无该段 → 恢复不崩、表空。断言从图纸 §3.1/§6 独立反推。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldBackupResidentRoundTripTest {

    private lateinit var src: AppDatabase
    private lateinit var dst: AppDatabase
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Before
    fun setUp() {
        src = newDb()
        dst = newDb()
    }

    @After
    fun tearDown() {
        src.close()
        dst.close()
    }

    private fun newDb() =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()

    @Test
    fun `E10 userResidents 段 JSON 往返逐字段相等`() = runBlocking {
        src.worldDao().upsertState(WorldStateEntity(id = 1, seed = 42L, createdAt = 1L))
        src.worldUserResidentDao().upsert(
            WorldUserResidentEntity(
                slug = "resident_ab12cd34", name = "江晚棠", gender = "female", age = 26, cityId = "city_yunye",
                occupation = "旧书店店主", personaBrief = "安静，记性好得吓人", traitsJson = "[\"温吞\",\"毒舌\"]",
                freeformLore = "旧怀表", initialRelationText = "是老板娘的表妹", fuelBias = "narrative",
                avatarPath = "/data/av/j.png", createdAt = 100L,
            ),
        )
        src.worldUserResidentDao().upsert(
            WorldUserResidentEntity(
                slug = "resident_ff00aa11", name = "严真", gender = "自定义直男", age = 999, cityId = "city_taoqiu",
                occupation = "陶艺匠", personaBrief = "话少", traitsJson = "", freeformLore = "", initialRelationText = "",
                fuelBias = "gift", avatarPath = null, createdAt = 200L,
            ),
        )

        val collected = collectWorld(
            src.worldDao(), src.worldSocialDao(), src.worldNativeDao(), src.worldMemoryDao(), src.worldUserResidentDao(),
        )
        assertEquals("导出两位居民", 2, collected!!.userResidents!!.size)
        val decoded = json.decodeFromString(
            WorldBackupData.serializer(),
            json.encodeToString(WorldBackupData.serializer(), collected),
        )
        restoreWorld(
            dst.worldDao(), dst.worldSocialDao(), dst.worldNativeDao(), dst.worldMemoryDao(), dst.worldUserResidentDao(),
            decoded, emptySet(),
        )

        assertEquals(
            "userResidents 逐字段相等（含自定义性别/空 traits/null 头像/边界年龄）",
            src.worldUserResidentDao().getAll().toSet(),
            dst.worldUserResidentDao().getAll().toSet(),
        )
    }

    @Test
    fun `E10 旧备份无 userResidents 段恢复不崩表空`() = runBlocking {
        // worldMemories/userResidents 缺省 null（旧格式）。
        val legacy = WorldBackupData(state = WorldStateExport(id = 1, seed = 1L, createdAt = 1L))
        restoreWorld(
            dst.worldDao(), dst.worldSocialDao(), dst.worldNativeDao(), dst.worldMemoryDao(), dst.worldUserResidentDao(),
            legacy, emptySet(),
        )
        assertTrue("旧备份无居民段 → 表空", dst.worldUserResidentDao().getAll().isEmpty())
        assertTrue("state 照恢复", dst.worldDao().getState() != null)
    }
}
