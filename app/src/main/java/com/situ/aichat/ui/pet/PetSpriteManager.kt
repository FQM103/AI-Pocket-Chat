package com.situ.aichat.ui.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.pet.PetNeglectPhase
import com.situ.aichat.pet.neglectPhase
import java.util.Locale

/**
 * 宠物精灵帧规格与状态映射（1:1 iOS `PetSpriteManager`）。纯逻辑（无 Compose/Android），便于单测。
 *
 * 精灵帧打进 `assets/petsprites/`，扁平命名 `{species}_{stage}_{state}_{NN}.png`（NN 01 起 2 位补零）；
 * 7 种类 × 5 阶段 × 8 状态 = 1225 帧。加载/动画在 [PetAnimationView]。
 *
 * **P15·P0-20**：这些 `.png` 文件实际承载**无损 WebP 字节**（一次性 `tools/convert_sprites_to_webp.py` 转换，
 * 省 APK ~51%）。BitmapFactory 按内容魔数解码、不看扩展名，故沿用 .png 名加载零改动、缓存键不变。
 */
object PetSpriteManager {

    /** 8 种动画状态及帧数（1:1 iOS `AnimationState` + `frameCount`）。 */
    enum class AnimationState(val raw: String, val frameCount: Int) {
        IDLE("idle", 6),
        HAPPY("happy", 6),
        SAD("sad", 3),
        SLEEP("sleep", 3),
        EAT("eat", 4),
        WALK("walk", 6),
        SICK("sick", 3),
        CLEAN("clean", 4),
    }

    /** 精灵帧 asset 路径（扁平名，对应 iOS `assetName` 的 `{prefix}/{prefix}_{state}_{NN}`；帧号钉 Locale.ROOT——asset 文件名是 ASCII，绝不随设备语言变）。 */
    fun assetPath(speciesRaw: String, stageRaw: String, stateRaw: String, frame: Int): String =
        "petsprites/${speciesRaw}_${stageRaw}_${stateRaw}_${"%02d".format(Locale.ROOT, frame)}.png"

    /**
     * 由宠物状态自动选动画状态（1:1 iOS `animationState(from:)`）。
     * 优先级：离家出走/生病 > 闹情绪/不开心 > 饥饿(≥70)/脏(≤30) > 开心(≥80) > 待机。
     */
    fun animationStateFor(pet: CharacterPetEntity): AnimationState {
        when (pet.neglectPhase) {
            PetNeglectPhase.SICK, PetNeglectPhase.RAN_AWAY -> return AnimationState.SICK
            PetNeglectPhase.UPSET -> return AnimationState.SAD
            PetNeglectPhase.UNHAPPY -> return AnimationState.SAD
            PetNeglectPhase.NONE -> Unit
        }
        if (pet.hunger >= 70) return AnimationState.SAD
        if (pet.cleanliness <= 30) return AnimationState.SAD
        if (pet.happiness >= 80) return AnimationState.HAPPY
        return AnimationState.IDLE
    }
}
