package com.situ.aichat.world.link

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.WorldRelationshipEventEntity
import com.situ.aichat.data.local.entity.WorldStateEntity
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.MoodHistoryEntry
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.world.cast.WorldResidentService
import com.situ.aichat.world.SettlementDay
import com.situ.aichat.world.SettlementWindow
import com.situ.aichat.world.WorldIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [WorldMoodSettler] T2-4（Robolectric 真 Room + 真 CharacterRepository/SettingsRepository·图纸 §7·E7/E8）：
 * 映射表 / 衰减链 / 幂等 / 不抢话。真库替代 MockK（W4 GatesTest 先例：MockK 在 Robolectric 下 gateway 初始化失败）。
 * 断言从图纸 §3.3 衰减链 + §4.3 映射表独立反推。UTC 令 epochDay 与本地日无歧义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldMoodSettlerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var repo: CharacterRepository
    private lateinit var settler: WorldMoodSettler
    private lateinit var dsScope: CoroutineScope
    private val zone = ZoneOffset.UTC

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = CharacterRepository(
            db.characterDao(), db.milestoneDao(), db,
            WorldResidentService(db.worldUserResidentDao(), db.worldNativeDao(), db.worldDao(), db),
            io.mockk.mockk(relaxed = true),
        )
        dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val ds = PreferenceDataStoreFactory.create(
            scope = dsScope,
            produceFile = { File(tmp.newFolder(), "settings.preferences_pb") },
        )
        settler = WorldMoodSettler(db.worldSocialDao(), db.characterDao(), repo, SettingsRepository(ds))
    }

    @After
    fun tearDown() {
        db.close()
        dsScope.cancel()
    }

    private fun char(uuid: String) = runBlocking {
        db.characterDao().upsert(CharacterEntity(uuid = uuid, name = uuid, creationDate = 0L, joinedWorld = true))
    }

    private fun noon(epochDay: Long) =
        LocalDate.ofEpochDay(epochDay).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun day(epochDay: Long) = SettlementDay(LocalDate.ofEpochDay(epochDay), epochDay, 0L)

    private fun window(vararg epochDays: Long) =
        SettlementWindow(days = epochDays.map { day(it) }, truncatedDays = 0, absenceMs = 0L, firstRun = false)

    private fun state() = WorldStateEntity(seed = 1L, userTimezoneId = "UTC", createdAt = 0L)

    private fun evt(uuid: String, actor: String, target: String, kind: String, epochDay: Long) =
        WorldRelationshipEventEntity(
            uuid = uuid, pairKey = WorldIds.pairKey(actor, target), actorId = actor, targetId = target,
            kindRaw = kind, arcId = null, summary = "s", happenedAt = noon(epochDay), settledAt = noon(epochDay),
        )

    // MARK: - E7 映射表（净负/净正/净 −2）+ 幂等

    @Test
    fun `E7 净负一格_低落yellow_历史world前缀条目_重跑不重复`() = runBlocking {
        char("c1"); char("c2")
        db.worldSocialDao().upsertEvent(evt("q1", "c1", "c2", "rel_quarrel_start", 0L)) // moodHint −1

        settler.settle(state(), window(0L), zone)

        val c1 = db.characterDao().getByUuid("c1")!!
        assertEquals("😕", c1.lastMoodEmoji)
        assertEquals("yellow", c1.lastMoodColorName)
        assertEquals("有点低落", c1.lastMoodText)
        val history = GrowthJson.decodeMoodHistory(c1.moodHistoryJSON)
        assertEquals("恰一条", 1, history.size)
        assertEquals("id = world:{uuid}:{窗末日epochDay}", "world:c1:0", history.first().id)
        assertEquals(noon(0L), history.first().timestamp)

        // 同窗重跑：幂等门（同 id）→ 不重复追加。
        settler.settle(state(), window(0L), zone)
        assertEquals("重跑不重复追加", 1, GrowthJson.decodeMoodHistory(db.characterDao().getByUuid("c1")!!.moodHistoryJSON).size)
    }

    @Test
    fun `E7 净正一格_不错green`() = runBlocking {
        char("c1"); char("c2")
        db.worldSocialDao().upsertEvent(evt("h1", "c1", "c2", "rel_help", 0L)) // moodHint +1
        settler.settle(state(), window(0L), zone)
        val c1 = db.characterDao().getByUuid("c1")!!
        assertEquals("🙂", c1.lastMoodEmoji)
        assertEquals("green", c1.lastMoodColorName)
        assertEquals("心情不错", c1.lastMoodText)
    }

    @Test
    fun `E7 净负两格_闷闷不乐red`() = runBlocking {
        char("c1"); char("c2")
        // 同日两条 quarrel（各 −1）→ dayMoodDelta = −2 → 😞 red 闷闷不乐。
        db.worldSocialDao().upsertEvent(evt("q1", "c1", "c2", "rel_quarrel_start", 0L))
        db.worldSocialDao().upsertEvent(evt("q2", "c1", "c2", "rel_quarrel_cold", 0L))
        settler.settle(state(), window(0L), zone)
        val c1 = db.characterDao().getByUuid("c1")!!
        assertEquals("😞", c1.lastMoodEmoji)
        assertEquals("red", c1.lastMoodColorName)
        assertEquals("闷闷不乐", c1.lastMoodText)
    }

    // MARK: - 衰减链（净 0 不写）

    @Test
    fun `衰减链_事件在首日次日回落到0_净0不写`() = runBlocking {
        char("c1"); char("c2")
        db.worldSocialDao().upsertEvent(evt("q1", "c1", "c2", "rel_quarrel_start", 0L)) // day0: −1
        // 窗 [0,1]：day0 = −1，day1 = decay(−1)=0 → 净 0 → 不写。
        settler.settle(state(), window(0L, 1L), zone)
        val c1 = db.characterDao().getByUuid("c1")!!
        assertEquals("净 0 不写 lastMood", "", c1.lastMoodEmoji)
        assertEquals("净 0 不追加历史", 0, GrowthJson.decodeMoodHistory(c1.moodHistoryJSON).size)
    }

    // MARK: - E8 不抢话（聊天心情更新鲜 → 世界不覆盖不追加）

    @Test
    fun `E8 聊天更新更鲜_世界不抢话`() = runBlocking {
        char("c1"); char("c2")
        // 预置一条「聊天」心情历史：timestamp 比窗末日正午更新（更鲜）。
        val chatTs = noon(0L) + 3_600_000L // 窗末日 13:00
        repo.updateMood("c1", "😊", "很开心", "green")
        repo.appendMoodHistory("c1", MoodHistoryEntry(id = "chat-1", timestamp = chatTs, emoji = "😊", colorName = "green", text = "很开心"), 200)
        db.worldSocialDao().upsertEvent(evt("q1", "c1", "c2", "rel_quarrel_start", 0L))

        settler.settle(state(), window(0L), zone)

        val c1 = db.characterDao().getByUuid("c1")!!
        assertEquals("世界不覆盖聊天心情", "😊", c1.lastMoodEmoji)
        val history = GrowthJson.decodeMoodHistory(c1.moodHistoryJSON)
        assertEquals("世界不追加（不抢话）", 1, history.size)
        assertEquals("chat-1", history.first().id)
    }
}
