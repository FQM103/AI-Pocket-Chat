package com.situ.aichat.ui.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.testutil.withDefaultLocale
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * 精灵动画状态映射 + 心情背景类型（1:1 iOS PetSpriteManager.animationState / PetMoodType.from）。
 * 断言反推 iOS 优先级与阈值。纯逻辑（不触 Compose 运行时）。
 */
class PetSpriteAndMoodTest {

    private fun pet(
        hunger: Int = 0,
        cleanliness: Int = 100,
        happiness: Int = 50,
        neglectRaw: String = "none",
    ) = CharacterPetEntity(
        uuid = "p", speciesRaw = "cat", hunger = hunger, cleanliness = cleanliness,
        happiness = happiness, neglectPhaseRaw = neglectRaw, characterUuid = "c",
    )

    // ---- animationStateFor ----

    @Test fun `anim sick for sick and ranAway`() {
        assertEquals(PetSpriteManager.AnimationState.SICK, PetSpriteManager.animationStateFor(pet(neglectRaw = "sick")))
        assertEquals(PetSpriteManager.AnimationState.SICK, PetSpriteManager.animationStateFor(pet(neglectRaw = "ranAway")))
    }

    @Test fun `anim sad for upset and unhappy`() {
        assertEquals(PetSpriteManager.AnimationState.SAD, PetSpriteManager.animationStateFor(pet(neglectRaw = "upset")))
        assertEquals(PetSpriteManager.AnimationState.SAD, PetSpriteManager.animationStateFor(pet(neglectRaw = "unhappy")))
    }

    @Test fun `anim sad for hungry or dirty when phase none`() {
        assertEquals(PetSpriteManager.AnimationState.SAD, PetSpriteManager.animationStateFor(pet(hunger = 70)))
        assertEquals(PetSpriteManager.AnimationState.SAD, PetSpriteManager.animationStateFor(pet(cleanliness = 30)))
    }

    @Test fun `anim happy then idle`() {
        assertEquals(PetSpriteManager.AnimationState.HAPPY, PetSpriteManager.animationStateFor(pet(happiness = 80)))
        assertEquals(PetSpriteManager.AnimationState.IDLE, PetSpriteManager.animationStateFor(pet(happiness = 50)))
    }

    @Test fun `anim sick beats hunger`() {
        assertEquals(PetSpriteManager.AnimationState.SICK, PetSpriteManager.animationStateFor(pet(hunger = 0, neglectRaw = "sick")))
    }

    @Test fun `assetPath format`() {
        assertEquals("petsprites/cat_baby_idle_01.png", PetSpriteManager.assetPath("cat", "baby", "idle", 1))
        assertEquals("petsprites/dog_adult_walk_12.png", PetSpriteManager.assetPath("dog", "adult", "walk", 12))
    }

    // %02d 若走默认 Locale，阿语系设备帧号会本地化成非 ASCII 数字 → 文件名失配、精灵帧加载失败
    //（与钱路 K1 同类，机制已由 K1 红跑实证；2026-07-12 性能线程专项 K2）。
    @Test fun `assetPath ascii under arabic digit locale`() = withDefaultLocale(Locale.forLanguageTag("ar")) {
        assertEquals("petsprites/cat_baby_idle_01.png", PetSpriteManager.assetPath("cat", "baby", "idle", 1))
        assertEquals("petsprites/dog_adult_walk_12.png", PetSpriteManager.assetPath("dog", "adult", "walk", 12))
    }

    // ---- PetMoodType.from ----

    @Test fun `mood sick hungry sad happy content`() {
        assertEquals(PetMoodType.SICK, PetMoodType.from(pet(neglectRaw = "ranAway")))
        assertEquals(PetMoodType.HUNGRY, PetMoodType.from(pet(hunger = 70)))
        assertEquals(PetMoodType.SAD, PetMoodType.from(pet(happiness = 29)))
        assertEquals(PetMoodType.HAPPY, PetMoodType.from(pet(happiness = 80)))
        assertEquals(PetMoodType.CONTENT, PetMoodType.from(pet(happiness = 50)))
    }

    @Test fun `mood sick beats hunger`() {
        assertEquals(PetMoodType.SICK, PetMoodType.from(pet(hunger = 90, neglectRaw = "sick")))
    }
}
