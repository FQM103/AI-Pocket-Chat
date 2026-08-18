package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1#3 · [toPetWidgetData] 的 isWalking 现算（与 [PetWalkService.walkState] 同口径）。
 * 规格：walkStartTime 非空**且**散步未到点（elapsed < 30min）才算「散步中」——治 walkStartTime 在散步完成
 * 结算前不清空、旧 `!= null` 判定让 App 被杀后小组件长期显示「散步中」。
 */
class PetWidgetDataWalkTest {

    private val now = 1_000_000_000_000L
    private val duration = PetWalkService.WALK_DURATION_MS

    private fun pet(walkStartTime: Long?) = CharacterPetEntity(
        uuid = "p", name = "球球", speciesRaw = "cat", personalityTypeRaw = "lively",
        adoptedDate = now, happiness = 80, growthPoints = 0, totalInteractions = 0,
        lastInteractionDate = now, neglectPhaseRaw = "none",
        petMetadataJson = PetJson.encodeMetadata(PetMetadata.EMPTY.copy(walkStartTime = walkStartTime)),
        characterUuid = "c",
    )

    @Test fun noWalkStartTime_notWalking() {
        assertFalse(pet(walkStartTime = null).toPetWidgetData(now).isWalking)
    }

    @Test fun walkInProgress_isWalking() {
        // 开始 10 分钟，未到 30 分钟 → 散步中。
        assertTrue(pet(walkStartTime = now - 10 * 60_000L).toPetWidgetData(now).isWalking)
    }

    @Test fun walkJustBeforeEnd_stillWalking() {
        assertTrue(pet(walkStartTime = now - duration + 1).toPetWidgetData(now).isWalking)
    }

    @Test fun walkCompletedButNotCleared_notWalking() {
        // 到点（elapsed == duration）即不再「散步中」——旧 `!= null` 会误报，这是 C1#3 的核心。
        assertFalse(pet(walkStartTime = now - duration).toPetWidgetData(now).isWalking)
    }

    @Test fun walkLongPastEnd_processKilledScenario_notWalking() {
        // App 被杀、无人结算，散步早已结束 2 小时 → 小组件不再显示「散步中」。
        assertFalse(pet(walkStartTime = now - 2 * 60 * 60_000L).toPetWidgetData(now).isWalking)
    }
}
