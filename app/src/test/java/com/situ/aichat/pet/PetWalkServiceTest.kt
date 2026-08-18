package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlin.random.Random

/**
 * 宠物散步服务（1:1 iOS PetWalkService）。断言全部从 iOS 真实数值反推：walkState 30min 边界、canStartWalk
 * 忽略阶段门槛、startWalk 跨天计数重置、事件物种过滤（156 总数/132 候选）、结算数值（moodBonus/growthBonus+8/
 * 纪念品去重/清 walkStartTime）。用固定 now + 固定 RNG（强制选定事件索引）保证确定性。
 */
class PetWalkServiceTest {

    private val settings = AppSettings()
    private val zone = ZoneId.of("UTC")
    private val MIN = 60_000L
    private val DAY = 86_400_000L
    private val NOW = 1_700_000_000_000L
    private val DURATION = PetWalkService.WALK_DURATION_MS // 30min

    /** 强制 `randomOrNull`/`IntRange.random` 选定下标，使结算确定（仅覆盖 nextInt 路径）。 */
    private class FixedIndexRandom(private val index: Int) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextInt(until: Int): Int = index.coerceIn(0, until - 1)
        override fun nextInt(from: Int, until: Int): Int = (from + index).coerceIn(from, until - 1)
    }

    private fun pet(
        speciesRaw: String = "cat",
        happiness: Int = 80,
        growthPoints: Int = 0,
        totalInteractions: Int = 0,
        neglectRaw: String = "none",
        lastInteractionDate: Long? = NOW,
        adoptedDate: Long = NOW,
        metadata: PetMetadata = PetMetadata.EMPTY,
    ) = CharacterPetEntity(
        uuid = "p", name = "球球", speciesRaw = speciesRaw, personalityTypeRaw = "lively",
        adoptedDate = adoptedDate, happiness = happiness, growthPoints = growthPoints,
        totalInteractions = totalInteractions, lastInteractionDate = lastInteractionDate,
        neglectPhaseRaw = neglectRaw, petMetadataJson = PetJson.encodeMetadata(metadata), characterUuid = "c",
    )

    // ---- walkState ----

    @Test fun `walkState idle when no start time`() {
        assertEquals(PetWalkService.WalkState.Idle, PetWalkService.walkState(pet(), NOW))
    }

    @Test fun `walkState walking with remaining`() {
        val p = pet(metadata = PetMetadata.EMPTY.copy(walkStartTime = NOW - 10 * MIN))
        val s = PetWalkService.walkState(p, NOW)
        assertTrue(s is PetWalkService.WalkState.Walking)
        assertEquals(DURATION - 10 * MIN, (s as PetWalkService.WalkState.Walking).remainingMs) // 20min 剩余
    }

    @Test fun `walkState completed at exactly 30min`() {
        val p = pet(metadata = PetMetadata.EMPTY.copy(walkStartTime = NOW - DURATION))
        assertTrue(PetWalkService.walkState(p, NOW) is PetWalkService.WalkState.Completed)
    }

    @Test fun `walkState still walking just before 30min`() {
        val p = pet(metadata = PetMetadata.EMPTY.copy(walkStartTime = NOW - DURATION + 1))
        assertTrue(PetWalkService.walkState(p, NOW) is PetWalkService.WalkState.Walking)
    }

    // ---- canStartWalk ----

    @Test fun `canStartWalk true for none and unhappy`() {
        assertTrue(PetWalkService.canStartWalk(pet(neglectRaw = "none")))
        assertTrue(PetWalkService.canStartWalk(pet(neglectRaw = "unhappy")))
    }

    @Test fun `canStartWalk false for upset sick ranAway`() {
        assertFalse(PetWalkService.canStartWalk(pet(neglectRaw = "upset")))
        assertFalse(PetWalkService.canStartWalk(pet(neglectRaw = "sick")))
        assertFalse(PetWalkService.canStartWalk(pet(neglectRaw = "ranAway")))
    }

    @Test fun `canStartWalk false while already walking`() {
        val p = pet(metadata = PetMetadata.EMPTY.copy(walkStartTime = NOW - MIN))
        assertFalse(PetWalkService.canStartWalk(p))
    }

    // ---- startWalk ----

    @Test fun `startWalk returns null when cannot start`() {
        assertNull(PetWalkService.startWalk(pet(neglectRaw = "sick"), NOW, zone))
    }

    @Test fun `startWalk fresh sets state and count 1`() {
        val r = PetWalkService.startWalk(pet(), NOW, zone)!!
        assertEquals(NOW, r.metadata.walkStartTime)
        assertEquals(NOW, r.metadata.lastWalkDate)
        assertEquals(1, r.metadata.dailyWalkCount)
        assertEquals(NOW, r.metadata.lastWalkCountDate)
        assertEquals(NOW, r.lastInteractionDate)
    }

    @Test fun `startWalk same day increments count`() {
        val p = pet(metadata = PetMetadata.EMPTY.copy(dailyWalkCount = 2, lastWalkCountDate = NOW))
        val r = PetWalkService.startWalk(p, NOW, zone)!!
        assertEquals(3, r.metadata.dailyWalkCount)
        assertEquals(NOW, r.metadata.lastWalkCountDate) // 同日不更新计数日期
    }

    @Test fun `startWalk cross day resets count to 1`() {
        val p = pet(metadata = PetMetadata.EMPTY.copy(dailyWalkCount = 5, lastWalkCountDate = NOW - 2 * DAY))
        val r = PetWalkService.startWalk(p, NOW, zone)!!
        assertEquals(1, r.metadata.dailyWalkCount)
        assertEquals(NOW, r.metadata.lastWalkCountDate)
    }

    // ---- 事件池 / 物种过滤 ----

    @Test fun `walkEvents total is 156`() {
        assertEquals(156, PetWalkService.walkEvents.size)
    }

    @Test fun `first event matches iOS values`() {
        val e = PetWalkService.walkEvents[0]
        assertEquals("在公园长椅上晒了会儿太阳", e.description)
        assertEquals(10, e.moodBonus)
        assertEquals(3, e.growthBonus)
        assertNull(e.souvenir)
        assertNull(e.speciesFilter)
    }

    @Test fun `every species has 132 candidates`() {
        // 124 通用（40 无纪念品 + 84 通用纪念品）+ 8 物种专属
        assertEquals(132, PetWalkService.candidatesFor(PetSpecies.CAT).size)
        assertEquals(132, PetWalkService.candidatesFor(PetSpecies.DOG).size)
        assertEquals(132, PetWalkService.candidatesFor(PetSpecies.RABBIT).size)
        assertEquals(132, PetWalkService.candidatesFor(PetSpecies.HAMSTER).size)
        assertEquals(132, PetWalkService.candidatesFor(PetSpecies.SPIRIT).size)
    }

    @Test fun `cat candidates include cat-specific and exclude dog and hamster events`() {
        val cats = PetWalkService.candidatesFor(PetSpecies.CAT).map { it.description }
        assertTrue("追着一只蝴蝶跑了半天" in cats)         // 猫/精灵专属
        assertFalse("在草地上挖到了一根骨头" in cats)        // 狗/龙专属
        assertFalse("发现了一颗亮晶晶的种子" in cats)        // 仓鼠专属
    }

    @Test fun `hamster candidates include hamster-specific and exclude cat events`() {
        val hams = PetWalkService.candidatesFor(PetSpecies.HAMSTER).map { it.description }
        assertTrue("发现了一颗亮晶晶的种子" in hams)
        assertFalse("追着一只蝴蝶跑了半天" in hams)
    }

    @Test fun `allSouvenirTypes are deduped by name`() {
        val names = PetWalkService.allSouvenirTypes.map { it.name }
        assertEquals(names.size, names.toSet().size) // 无重复
        assertTrue(PetWalkService.allSouvenirTypes.any { it.name == "蝴蝶标本" && it.emoji == "🦋" })
    }

    // ---- checkAndSettle ----

    @Test fun `checkAndSettle null when not completed`() {
        val p = pet(metadata = PetMetadata.EMPTY.copy(walkStartTime = NOW - 10 * MIN)) // 仍在走
        assertNull(PetWalkService.checkAndSettle(p, settings, NOW, Random(0)))
    }

    @Test fun `checkAndSettle first event applies mood and growth plus 8`() {
        val p = pet(happiness = 50, growthPoints = 100, totalInteractions = 4,
            metadata = PetMetadata.EMPTY.copy(walkStartTime = NOW - DURATION))
        val r = PetWalkService.checkAndSettle(p, settings, NOW, FixedIndexRandom(0))!!
        // 首事件 mood 10 / growth 3 → +8(petGrowthPointsPerPlay)
        assertEquals(60, r.pet.happiness)
        assertEquals(100 + 3 + 8, r.pet.growthPoints)
        assertEquals(5, r.pet.totalInteractions)
        assertEquals(NOW, r.pet.lastInteractionDate)
        assertEquals(10, r.moodBonus)
        assertEquals(11, r.growthBonus)
        assertNull(r.souvenir)
        assertNull(r.pet.metadata.walkStartTime) // 清除散步状态
        assertTrue(r.coinsReward in 3..15)
        assertTrue(r.pet.growthLog.any { it.type == PetGrowthEventType.WALK_COMPLETED.raw })
    }

    @Test fun `checkAndSettle clamps happiness at 100`() {
        val p = pet(happiness = 95, metadata = PetMetadata.EMPTY.copy(walkStartTime = NOW - DURATION))
        val r = PetWalkService.checkAndSettle(p, settings, NOW, FixedIndexRandom(0))!! // mood 10
        assertEquals(100, r.pet.happiness)
    }

    @Test fun `checkAndSettle collects new souvenir`() {
        val cats = PetWalkService.candidatesFor(PetSpecies.CAT)
        val idx = cats.indexOfFirst { it.souvenir?.name == "小石头" }
        val event = cats[idx]
        assertEquals(8, event.moodBonus)  // iOS 小石头 mood 8 / growth 5
        assertEquals(5, event.growthBonus)
        val p = pet(metadata = PetMetadata.EMPTY.copy(walkStartTime = NOW - DURATION))
        val r = PetWalkService.checkAndSettle(p, settings, NOW, FixedIndexRandom(idx))!!
        assertNotNull(r.souvenir)
        assertEquals("小石头", r.souvenir!!.name)
        assertEquals("🪨", r.souvenir.emoji)
        assertEquals(13, r.growthBonus) // 5 + 8
        assertEquals(1, r.pet.metadata.souvenirs.size)
        assertEquals("小石头", r.pet.metadata.souvenirs[0].name)
    }

    @Test fun `checkAndSettle dedups already-collected souvenir`() {
        val cats = PetWalkService.candidatesFor(PetSpecies.CAT)
        val idx = cats.indexOfFirst { it.souvenir?.name == "小石头" }
        val existing = PetSouvenir(id = "s1", name = "小石头", emoji = "🪨", obtainedDate = NOW - DAY)
        val p = pet(metadata = PetMetadata.EMPTY.copy(walkStartTime = NOW - DURATION, souvenirs = listOf(existing)))
        val r = PetWalkService.checkAndSettle(p, settings, NOW, FixedIndexRandom(idx))!!
        assertNull(r.souvenir) // 已收集 → 不重复
        assertEquals(1, r.pet.metadata.souvenirs.size) // 仍只有 1 个
    }
}
