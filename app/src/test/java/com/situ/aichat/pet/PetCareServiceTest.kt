package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 宠物养护引擎 ★算法核心★（1:1 iOS PetCareService）。断言全部从 iOS 真实数值反推：照顾收益/性格加成、
 * 惰性衰减整数公式、independent 心情减半、健康连锁、散步惩罚、忽略阶段只恶化、治疗 3 次/寻找 5 次、
 * 信任倍率 0.5+0.5t、技能门槛。用固定 UTC zone + 固定 now 保证确定性。
 */
class PetCareServiceTest {

    private val settings = AppSettings()
    private val zone = ZoneId.of("UTC")
    private val HOUR = 3_600_000L
    private val DAY = 86_400_000L
    private val NOW = 1_700_000_000_000L

    private fun pet(
        hunger: Int = 0,
        cleanliness: Int = 100,
        happiness: Int = 80,
        health: Int = 100,
        growthPoints: Int = 0,
        totalInteractions: Int = 0,
        speciesRaw: String = "cat",
        isHidden: Boolean = false,
        growthStageRaw: String = "baby",
        personalityRaw: String = "lively",
        neglectRaw: String = "none",
        lastInteractionDate: Long? = null,
        adoptedDate: Long = NOW,
        metadata: PetMetadata = PetMetadata.EMPTY,
    ) = CharacterPetEntity(
        uuid = "p", name = "球球", speciesRaw = speciesRaw, isHiddenSpecies = isHidden,
        personalityTypeRaw = personalityRaw, adoptedDate = adoptedDate,
        hunger = hunger, cleanliness = cleanliness, happiness = happiness, health = health,
        growthStageRaw = growthStageRaw, growthPoints = growthPoints, totalInteractions = totalInteractions,
        lastInteractionDate = lastInteractionDate, neglectPhaseRaw = neglectRaw,
        petMetadataJson = PetJson.encodeMetadata(metadata), characterUuid = "c",
    )

    // ---- 照顾收益 ----

    @Test fun `feed reduces hunger 30 + growth 5`() {
        val r = PetCareService.feed(pet(hunger = 50), settings, NOW)
        assertEquals(20, r.hunger)
        assertEquals(5, r.growthPoints)
        assertEquals(1, r.totalInteractions)
        assertEquals(NOW, r.lastFedDate)
        assertEquals(NOW, r.lastInteractionDate)
    }

    @Test fun `feed clamps hunger at 0`() {
        assertEquals(0, PetCareService.feed(pet(hunger = 10), settings, NOW).hunger)
    }

    @Test fun `clean adds 30 + growth 3`() {
        val r = PetCareService.clean(pet(cleanliness = 50), settings, NOW)
        assertEquals(80, r.cleanliness)
        assertEquals(3, r.growthPoints)
    }

    @Test fun `clean lazy gets +10 cleanliness`() {
        assertEquals(90, PetCareService.clean(pet(cleanliness = 50, personalityRaw = "lazy"), settings, NOW).cleanliness)
    }

    @Test fun `clean timid gets +5 happiness`() {
        val r = PetCareService.clean(pet(cleanliness = 50, happiness = 50, personalityRaw = "timid"), settings, NOW)
        assertEquals(80, r.cleanliness)
        assertEquals(55, r.happiness)
    }

    @Test fun `play adds 25 happiness + growth 8 + playCount`() {
        // 用 lazy（玩耍无性格加成）测基础值；lively/clingy 的玩耍加成另有专测。
        val r = PetCareService.play(pet(happiness = 50, personalityRaw = "lazy"), settings, NOW)
        assertEquals(75, r.happiness)
        assertEquals(8, r.growthPoints)
        assertEquals(1, r.metadata.playCount)
    }

    @Test fun `play clingy +10 happiness`() {
        assertEquals(85, PetCareService.play(pet(happiness = 50, personalityRaw = "clingy"), settings, NOW).happiness)
    }

    @Test fun `play lively +5 happiness +2 growth`() {
        val r = PetCareService.play(pet(happiness = 50, personalityRaw = "lively"), settings, NOW)
        assertEquals(80, r.happiness)
        assertEquals(10, r.growthPoints)
    }

    @Test fun `chatInteraction +1 growth`() {
        val r = PetCareService.chatInteraction(pet(growthPoints = 0), settings, NOW)
        assertEquals(1, r.growthPoints)
        assertEquals(NOW, r.lastInteractionDate)
    }

    // ---- 忽略恢复 ----

    @Test fun `feed recovers unhappy to none`() {
        assertEquals("none", PetCareService.feed(pet(neglectRaw = "unhappy"), settings, NOW).neglectPhaseRaw)
    }

    @Test fun `feed does not heal sick`() {
        assertEquals("sick", PetCareService.feed(pet(neglectRaw = "sick"), settings, NOW).neglectPhaseRaw)
    }

    // ---- 衰减 ----

    @Test fun `decay 5h default rates`() {
        val r = PetCareService.applyDecay(pet(lastInteractionDate = NOW - 5 * HOUR), settings, NOW, zone)
        assertEquals(10, r.hunger)        // 5*2
        assertEquals(95, r.cleanliness)   // 100-5*1
        assertEquals(75, r.happiness)     // 80-5*1
        assertEquals(100, r.health)       // hunger<80, clean>20 → no drop
        assertEquals(NOW, r.metadata.lastDecayDate)
        assertEquals("none", r.neglectPhaseRaw)
    }

    @Test fun `decay independent halves happiness loss`() {
        val r = PetCareService.applyDecay(pet(happiness = 80, personalityRaw = "independent", lastInteractionDate = NOW - 10 * HOUR), settings, NOW, zone)
        assertEquals(75, r.happiness) // 80 - max(10/2,1)=5
    }

    @Test fun `decay health drops when hunger reaches 80`() {
        val r = PetCareService.applyDecay(pet(hunger = 75, lastInteractionDate = NOW - 5 * HOUR), settings, NOW, zone)
        assertEquals(85, r.hunger)   // 75+10
        assertEquals(98, r.health)   // 100 - max(5/2,1)=2
    }

    @Test fun `decay under 1 hour is no-op`() {
        val p = pet(lastInteractionDate = NOW - 1800_000L) // 30 min
        assertEquals(p, PetCareService.applyDecay(p, settings, NOW, zone))
    }

    @Test fun `decay with no interaction date is no-op`() {
        val p = pet(lastInteractionDate = null)
        assertEquals(p, PetCareService.applyDecay(p, settings, NOW, zone))
    }

    @Test fun `decay caps at 720 hours`() {
        val r = PetCareService.applyDecay(pet(lastInteractionDate = NOW - 1000 * DAY), settings, NOW, zone)
        assertEquals(100, r.hunger) // min(100, 720*2)
    }

    @Test fun `decay advances neglect to ranAway at 7+ days`() {
        val r = PetCareService.applyDecay(
            pet(lastInteractionDate = NOW - 8 * DAY, metadata = PetMetadata(lastDecayDate = NOW - 2 * HOUR)),
            settings, NOW, zone,
        )
        assertEquals("ranAway", r.neglectPhaseRaw)
    }

    @Test fun `decay neglect only worsens never lightens`() {
        // 已 sick，1.5 天对应 unhappy（更轻）→ 保持 sick
        val r = PetCareService.applyDecay(
            pet(neglectRaw = "sick", lastInteractionDate = NOW - (3 * DAY) / 2, metadata = PetMetadata(lastDecayDate = NOW - 2 * HOUR)),
            settings, NOW, zone,
        )
        assertEquals("sick", r.neglectPhaseRaw)
    }

    @Test fun `decay no-walk penalty after 2+ days`() {
        // lastDecay 1h 前→只跑 1h 衰减；从未散步→lastWalk=adoptedDate=NOW-5天 → daysSinceWalk=5 → penaltyDays=4
        val r = PetCareService.applyDecay(
            pet(lastInteractionDate = NOW - 1 * HOUR, adoptedDate = NOW - 5 * DAY, metadata = PetMetadata(lastDecayDate = NOW - 1 * HOUR)),
            settings, NOW, zone,
        )
        assertEquals(67, r.happiness) // 80 -1(decay) -4*3
        assertEquals(92, r.health)    // 100 -4*2
    }

    @Test fun `decay over-walk penalty when today walks exceed 3`() {
        val r = PetCareService.applyDecay(
            pet(lastInteractionDate = NOW - 1 * HOUR, metadata = PetMetadata(lastDecayDate = NOW - 1 * HOUR, lastWalkDate = NOW, lastWalkCountDate = NOW, dailyWalkCount = 5)),
            settings, NOW, zone,
        )
        assertEquals(8, r.hunger)       // 1*2 + (5-3)*3
        assertEquals(95, r.cleanliness) // 100 -1 -(5-3)*2
    }

    // ---- 信任 ----

    @Test fun `trust multiplier discounts care + advances recovery`() {
        val r = PetCareService.feed(pet(hunger = 50, metadata = PetMetadata(trustRecovery = 0.5)), settings, NOW)
        assertEquals(28, r.hunger) // 50 - max(1, (30*0.75).toInt()=22)
        assertEquals(0.7, r.metadata.trustRecovery, 0.0001)
    }

    @Test fun `trust reaches full at or above 1`() {
        val r = PetCareService.feed(pet(metadata = PetMetadata(trustRecovery = 0.9)), settings, NOW)
        assertEquals(1.0, r.metadata.trustRecovery, 0.0001)
    }

    // ---- 治疗 / 寻找 ----

    @Test fun `treat sick adds 25 health + count`() {
        val r = PetCareService.treat(pet(neglectRaw = "sick", health = 30, metadata = PetMetadata(treatmentCount = 0)), settings, NOW)
        assertEquals(55, r.health)
        assertEquals(1, r.metadata.treatmentCount)
        assertEquals("sick", r.neglectPhaseRaw)
    }

    @Test fun `treat third time heals to none with health floor 60`() {
        val r = PetCareService.treat(pet(neglectRaw = "sick", health = 30, metadata = PetMetadata(treatmentCount = 2)), settings, NOW)
        assertEquals("none", r.neglectPhaseRaw)
        assertEquals(60, r.health) // max(30+25=55, 60)
        assertEquals(0, r.metadata.treatmentCount)
    }

    @Test fun `treat non-sick is no-op`() {
        val p = pet(neglectRaw = "none")
        assertEquals(p, PetCareService.treat(p, settings, NOW))
    }

    @Test fun `search increments attempts`() {
        val r = PetCareService.searchForPet(pet(neglectRaw = "ranAway", metadata = PetMetadata(searchAttempts = 0)), NOW)
        assertTrue(r is PetCareService.SearchResult.Searching)
        r as PetCareService.SearchResult.Searching
        assertEquals(1, r.attempts)
        assertEquals(NOW, r.pet.metadata.searchStartDate)
    }

    @Test fun `search fifth attempt finds pet sick`() {
        val r = PetCareService.searchForPet(pet(neglectRaw = "ranAway", metadata = PetMetadata(searchAttempts = 4)), NOW)
        assertTrue(r is PetCareService.SearchResult.Found)
        val p = r.pet
        assertEquals("sick", p.neglectPhaseRaw)
        assertEquals(30, p.health)
        assertEquals(20, p.happiness)
        assertEquals(0.01, p.metadata.trustRecovery, 0.0001)
        assertEquals(0, p.metadata.searchAttempts)
    }

    @Test fun `search non-ranAway is searching with zero`() {
        val r = PetCareService.searchForPet(pet(neglectRaw = "none"), NOW)
        assertTrue(r is PetCareService.SearchResult.Searching)
    }

    // ---- 技能 ----

    @Test fun `learn trick at play threshold`() {
        assertEquals("坐下", PetCareService.learnTrickIfUnlocked(pet(metadata = PetMetadata(playCount = 10)), NOW).learnedName)
        assertEquals(null, PetCareService.learnTrickIfUnlocked(pet(metadata = PetMetadata(playCount = 9)), NOW).learnedName)
    }

    @Test fun `learn next unlearned trick`() {
        val r = PetCareService.learnTrickIfUnlocked(
            pet(metadata = PetMetadata(playCount = 100, learnedTricks = listOf("sit", "shake", "roll"))), NOW,
        )
        assertEquals("跳舞", r.learnedName)
    }
}
