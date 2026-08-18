package com.situ.aichat.pet

import androidx.room.Room
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.MessageDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.model.GrowthJson
import com.situ.aichat.data.model.RelationshipQuality
import androidx.datastore.preferences.core.emptyPreferences
import com.situ.aichat.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
 * [EggNestService] T2-1（Robolectric 真 Room[characterDao/petDao] + MockK settings/messageDao·图纸 §7·E1/E2/E3）：
 * observeState 派生随之约键/角色/宠物/eligibility 配置产出正确态；自愈路径 coVerify clearPact；Incubating 路径零清键（E3）。
 * 断言从图纸 §3 派生矩阵独立反推。每态取 observeState().first() 快照（确定性·不吃收集时序）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EggNestServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var messageDao: MessageDao
    private lateinit var service: EggNestService

    private val pactFlow = MutableStateFlow<EggNestPact?>(null)

    private val DAY = 86_400_000L
    private val NOW = 1_700_000_000_000L
    private val uuid = "char-egg-1"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        settingsRepo = mockk()
        messageDao = mockk()
        every { settingsRepo.eggNestPactFlow } returns pactFlow
        coEvery { settingsRepo.clearEggNestPact() } returns emptyPreferences()
        coEvery { settingsRepo.setEggNestPact(any(), any()) } returns emptyPreferences()
        service = EggNestService(settingsRepo, db.characterDao(), db.petDao(), messageDao)
    }

    @After fun tearDown() = db.close()

    // ── 高关系角色（陪伴≥14/trust≥40/fam≥35/close≥30；messageCount 由 mock 供）──
    private fun eligibleCharacter() = CharacterEntity(
        uuid = uuid, name = "苏晚", creationDate = NOW - 13 * DAY,
        relationshipQualityJSON = GrowthJson.encode(RelationshipQuality(familiarity = 35, trust = 40, closeness = 30)),
    )

    private fun lowCharacter() = CharacterEntity(uuid = uuid, name = "苏晚", creationDate = NOW)

    private fun state() = runBlocking { service.observeState { NOW }.first() }

    // ── 无之约 → Empty（不清键）──
    @Test fun `no pact yields empty`() {
        pactFlow.value = null
        assertEquals(EggNestState.Empty, state())
        coVerify(exactly = 0) { settingsRepo.clearEggNestPact() }
    }

    // ── 定约 + 角色未达标 + 无宠 → Incubating（E3：绝不清键）──
    @Test fun `pact with low character yields incubating and never clears`() {
        runBlocking { db.characterDao().upsert(lowCharacter()) }
        coEvery { messageDao.countAllForCharacter(uuid) } returns 0
        pactFlow.value = EggNestPact(uuid, NOW)
        assertEquals(EggNestState.Incubating(uuid, "苏晚"), state())
        coVerify(exactly = 0) { settingsRepo.clearEggNestPact() }
    }

    // ── 定约 + eligibility 达标 + 无宠 → Hatchable ──
    @Test fun `pact with eligible character yields hatchable`() {
        runBlocking { db.characterDao().upsert(eligibleCharacter()) }
        coEvery { messageDao.countAllForCharacter(uuid) } returns 100
        pactFlow.value = EggNestPact(uuid, NOW)
        assertEquals(EggNestState.Hatchable(uuid, "苏晚"), state())
    }

    // ── E2：定约 + 该角色已有宠物 → Empty + 自愈清键（即便达标也兑现优先）──
    @Test fun `pact with existing pet self-heals empty and clears`() {
        runBlocking {
            db.characterDao().upsert(eligibleCharacter())
            db.petDao().upsert(CharacterPetEntity(uuid = "pet-1", name = "团子", characterUuid = uuid))
        }
        coEvery { messageDao.countAllForCharacter(uuid) } returns 100
        pactFlow.value = EggNestPact(uuid, NOW)
        assertEquals(EggNestState.Empty, state())
        coVerify(exactly = 1) { settingsRepo.clearEggNestPact() }
    }

    // ── E1：定约角色被删除（无角色行）→ Empty + 自愈清键 ──
    @Test fun `pact with deleted character self-heals empty and clears`() {
        coEvery { messageDao.countAllForCharacter(uuid) } returns 0
        pactFlow.value = EggNestPact(uuid, NOW)
        assertEquals(EggNestState.Empty, state())
        coVerify(exactly = 1) { settingsRepo.clearEggNestPact() }
    }

    // ── setPact/clearPact 透传 settingsRepo（E4 幂等由 DataStore 单次 edit 保证）──
    @Test fun `setPact and clearPact delegate to settings`() {
        runBlocking {
            service.setPact(uuid, NOW)
            service.clearPact()
        }
        coVerify(exactly = 1) { settingsRepo.setEggNestPact(uuid, NOW) }
        coVerify(exactly = 1) { settingsRepo.clearEggNestPact() }
    }
}
