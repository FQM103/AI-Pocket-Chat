package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 宠物饿/病提醒预测（13.7c）。断言反推 iOS 衰减模型：hunger≥70 触发饿、连续 5 天无互动进 sick、已饿/已病给
 * 30min 窗口、sick 优先于 hungry。衰减速率取 [AppSettings] 默认 petHungerDecayPerHour=2（/小时）。
 */
class PetReminderPredictorTest {

    private val NOW = 1_700_000_000_000L
    private val HOUR = 3_600_000L
    private val DAY = 86_400_000L
    private val WARMUP = PetReminderPredictor.WARMUP_MILLIS // 30min
    private val settings = AppSettings() // petHungerDecayPerHour=2

    private fun pet(
        hunger: Int,
        lastInteraction: Long?,
        phase: String = "none",
        lastDecayDate: Long? = NOW,
    ) = CharacterPetEntity(
        uuid = "p", name = "球球", characterUuid = "c",
        hunger = hunger,
        lastInteractionDate = lastInteraction,
        neglectPhaseRaw = phase,
        petMetadataJson = PetJson.encodeMetadata(PetMetadata(lastDecayDate = lastDecayDate)),
    )

    @Test fun `never interacted yields no plan`() {
        assertNull(PetReminderPredictor.computePlan(pet(hunger = 0, lastInteraction = null), settings, NOW))
    }

    @Test fun `fresh pet predicts hungry at 70 over 35 hours`() {
        // hunger 0 → 70 at rate 2/h = 35h；sick=5d 更晚 → 选 hungry。
        val plan = PetReminderPredictor.computePlan(pet(hunger = 0, lastInteraction = NOW), settings, NOW)!!
        assertEquals(PetReminderPredictor.CATEGORY_HUNGRY, plan.category)
        assertEquals(NOW + 35 * HOUR, plan.fireAtMillis)
    }

    @Test fun `half-hungry predicts ten hours out`() {
        val plan = PetReminderPredictor.computePlan(pet(hunger = 50, lastInteraction = NOW), settings, NOW)!!
        assertEquals(PetReminderPredictor.CATEGORY_HUNGRY, plan.category)
        assertEquals(NOW + 10 * HOUR, plan.fireAtMillis) // (70-50)/2 = 10h
    }

    @Test fun `already at threshold reminds in 30 min`() {
        val plan = PetReminderPredictor.computePlan(pet(hunger = 70, lastInteraction = NOW), settings, NOW)!!
        assertEquals(PetReminderPredictor.CATEGORY_HUNGRY, plan.category)
        assertEquals(NOW + WARMUP, plan.fireAtMillis)
    }

    @Test fun `already sick reminds in 30 min and preempts hunger`() {
        val plan = PetReminderPredictor.computePlan(pet(hunger = 90, lastInteraction = NOW, phase = "sick"), settings, NOW)!!
        assertEquals(PetReminderPredictor.CATEGORY_SICK, plan.category)
        assertEquals(NOW + WARMUP, plan.fireAtMillis) // sick 优先，不看 hunger
    }

    @Test fun `upset pet still prefers earlier hunger over sick`() {
        // 4 天没互动(upset)、hunger 30：hungry=(70-30)/2=20h；sick=lastInteraction+5d=now+1d=24h → hungry 更早。
        val plan = PetReminderPredictor.computePlan(
            pet(hunger = 30, lastInteraction = NOW - 4 * DAY, phase = "upset", lastDecayDate = NOW),
            settings, NOW,
        )!!
        assertEquals(PetReminderPredictor.CATEGORY_HUNGRY, plan.category)
        assertEquals(NOW + 20 * HOUR, plan.fireAtMillis)
    }

    @Test fun `ran away pet with high hunger still reminds hungry`() {
        // 1:1 iOS：ranAway 非 sick → 命中 hunger≥70 → pet_hungry（sick 不再预测）。
        val plan = PetReminderPredictor.computePlan(pet(hunger = 80, lastInteraction = NOW, phase = "ranAway"), settings, NOW)!!
        assertEquals(PetReminderPredictor.CATEGORY_HUNGRY, plan.category)
        assertEquals(NOW + WARMUP, plan.fireAtMillis)
    }

    @Test fun `zero hunger decay falls back to sick prediction`() {
        // 速率 0 → 永不饿 → 选 sick（5 天后）。
        val plan = PetReminderPredictor.computePlan(
            pet(hunger = 40, lastInteraction = NOW), settings.copy(petHungerDecayPerHour = 0), NOW,
        )!!
        assertEquals(PetReminderPredictor.CATEGORY_SICK, plan.category)
        assertEquals(NOW + PetReminderPredictor.SICK_DAYS * DAY, plan.fireAtMillis)
    }

    @Test fun `decay base in the past accounts for elapsed time`() {
        // lastDecayDate 5h 前、hunger 50：从衰减基准推 (70-50)/2=10h → now-5h+10h = now+5h。
        val plan = PetReminderPredictor.computePlan(
            pet(hunger = 50, lastInteraction = NOW, lastDecayDate = NOW - 5 * HOUR), settings, NOW,
        )!!
        assertEquals(PetReminderPredictor.CATEGORY_HUNGRY, plan.category)
        assertEquals(NOW + 5 * HOUR, plan.fireAtMillis)
    }

    @Test fun `stale snapshot predicting past hunger reminds in 30 min`() {
        // lastDecayDate 20h 前、hunger 50：推 now-20h+10h = now-10h（过去）→ 视为已饿 → now+30min。
        val plan = PetReminderPredictor.computePlan(
            pet(hunger = 50, lastInteraction = NOW, lastDecayDate = NOW - 20 * HOUR), settings, NOW,
        )!!
        assertEquals(PetReminderPredictor.CATEGORY_HUNGRY, plan.category)
        assertEquals(NOW + WARMUP, plan.fireAtMillis)
    }

    // MARK: - P1-25 删角色撤已弹宠物通知的候选 key 闭集

    @Test fun purgeRequestKeys_coverBothCategories() {
        assertEquals(
            listOf("aichat_pet_c1_pet_hungry", "aichat_pet_c1_pet_sick"),
            PetReminderScheduler.purgeRequestKeys("c1"),
        )
    }
}
