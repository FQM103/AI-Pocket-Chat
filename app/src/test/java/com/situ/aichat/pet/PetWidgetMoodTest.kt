package com.situ.aichat.pet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 反推 iOS `SharedPetStore.PetWidgetData.moodText` 的 6 分支优先级与边界阈值。
 */
class PetWidgetMoodTest {

    // 中性基线：不饿、干净、心情中等 → CALM
    private fun mood(isWalking: Boolean = false, hunger: Int = 30, cleanliness: Int = 80, happiness: Int = 50) =
        PetWidgetMood.of(isWalking, hunger, cleanliness, happiness)

    @Test
    fun walking_takesTopPriority_overEverything() {
        // 即使又饿又脏又不开心，散步优先（1:1 iOS isWalking 首判）
        assertEquals(PetWidgetMood.WALKING, mood(isWalking = true, hunger = 100, cleanliness = 0, happiness = 0))
    }

    @Test
    fun hungry_beatsDirtyAndHappy() {
        // hunger≥70 优先于 cleanliness≤30 与 happiness≥80
        assertEquals(PetWidgetMood.HUNGRY, mood(hunger = 70, cleanliness = 10, happiness = 90))
    }

    @Test
    fun hunger_boundaryAt70() {
        assertEquals(PetWidgetMood.HUNGRY, mood(hunger = 70))
        assertEquals(PetWidgetMood.CALM, mood(hunger = 69))
    }

    @Test
    fun dirty_beatsHappy() {
        assertEquals(PetWidgetMood.DIRTY, mood(hunger = 30, cleanliness = 30, happiness = 90))
    }

    @Test
    fun cleanliness_boundaryAt30() {
        assertEquals(PetWidgetMood.DIRTY, mood(cleanliness = 30))
        assertEquals(PetWidgetMood.CALM, mood(cleanliness = 31, happiness = 50))
    }

    @Test
    fun happy_boundaryAt80() {
        assertEquals(PetWidgetMood.HAPPY, mood(happiness = 80))
        assertEquals(PetWidgetMood.CALM, mood(happiness = 79))
    }

    @Test
    fun sad_boundaryAt30() {
        assertEquals(PetWidgetMood.SAD, mood(happiness = 30))
        assertEquals(PetWidgetMood.SAD, mood(happiness = 0))
        // 31~79 之间 → 既不开心也不悲伤 → CALM
        assertEquals(PetWidgetMood.CALM, mood(happiness = 31))
    }

    @Test
    fun toPetWidgetData_mood_isComputed() {
        val data = PetWidgetData(
            characterUuid = "c1", petName = "小橘子", speciesRaw = "cat", growthStageRaw = "baby",
            hunger = 80, cleanliness = 90, happiness = 90, health = 100, isWalking = false,
        )
        // hunger=80≥70 → HUNGRY（即便 happiness 高）
        assertEquals(PetWidgetMood.HUNGRY, data.mood)
    }
}
