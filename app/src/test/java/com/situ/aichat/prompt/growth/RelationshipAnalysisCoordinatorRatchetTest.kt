package com.situ.aichat.prompt.growth

import android.util.Log
import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.MilestoneDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.MilestoneEntity
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.repository.CharacterWriteLock
import com.situ.aichat.economy.MilestoneRewardService
import com.situ.aichat.relationship.MilestoneCelebrationNotifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * T2-2（图纸 §7）：AI 路（[RelationshipAnalysisCoordinator.analyzeAndPersist]）升档后调
 * [RelationshipArchetypeCalibrator.calibrateHoldingLock]（applyFloors=true·applyCeilings=false）；
 * changed=false 不校准；E13 时期-only 同名仍走校准（幂等）。coordinator 打 Log → mockkStatic。
 */
class RelationshipAnalysisCoordinatorRatchetTest {

    private val service = mockk<RelationshipAnalysisService>()
    private val growthService = mockk<GrowthAnalysisService>()
    private val characterDao = mockk<CharacterDao>(relaxed = true)
    private val milestoneDao = mockk<MilestoneDao>(relaxed = true)
    private val reward = mockk<MilestoneRewardService>(relaxed = true)
    private val celebration = mockk<MilestoneCelebrationNotifier>(relaxed = true)
    private val calibrator = mockk<RelationshipArchetypeCalibrator>(relaxed = true)
    private val coordinator = RelationshipAnalysisCoordinator(
        service, growthService, characterDao, milestoneDao, reward, CharacterWriteLock(), celebration, calibrator,
    )

    @Before fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any(), any<Throwable>()) } returns 0
        coEvery { characterDao.getByUuid("u") } returns CharacterEntity(uuid = "u", name = "小雨", creationDate = 0L)
        coEvery { growthService.collectMessagesForAnalysis(any()) } returns listOf(mockk<MessageEntity>(relaxed = true))
        coEvery { milestoneDao.getForCharacter("u") } returns listOf(
            MilestoneEntity(uuid = "m", characterUuid = "u", relationshipName = "朋友", establishedDate = 0L, reason = "", triggerTypeRaw = "aiAutomatic"),
        )
        coEvery { calibrator.calibrateHoldingLock(any(), any(), any(), any()) } returns CalibrationOutcome(null, false)
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    private suspend fun run() = coordinator.analyzeAndPersist("u", mockk<ApiConfigValues>(relaxed = true), "小明", "aiAutomatic")

    @Test fun `AI 升档 changed=true 调 calibrateHoldingLock 只抬地板不回拉`() = runTest {
        coEvery { service.analyzeRelationship(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            RelationshipAnalysisResult(changed = true, newRelationship = "好朋友", newPhase = null, reason = "更亲密了")
        run()
        coVerify(exactly = 1) { calibrator.calibrateHoldingLock("u", "好朋友", true, false) }
    }

    @Test fun `changed=false 不校准`() = runTest {
        coEvery { service.analyzeRelationship(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            RelationshipAnalysisResult(changed = false, newRelationship = "", newPhase = null, reason = "")
        run()
        coVerify(exactly = 0) { calibrator.calibrateHoldingLock(any(), any(), any(), any()) }
    }

    @Test fun `E13 时期-only 同名 changed=true 仍走校准`() = runTest {
        coEvery { service.analyzeRelationship(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            RelationshipAnalysisResult(changed = true, newRelationship = "朋友", newPhase = "蜜月期", reason = "进入蜜月期")
        run()
        coVerify(exactly = 1) { calibrator.calibrateHoldingLock("u", "朋友", true, false) }
    }
}
