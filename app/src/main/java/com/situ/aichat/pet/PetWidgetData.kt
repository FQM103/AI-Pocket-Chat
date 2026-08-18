package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity

/**
 * 桌面小组件宠物心情（1:1 iOS `SharedPetStore.PetWidgetData.moodText`/`moodGradientColors` 的 6 分支判定）。
 *
 * 纯逻辑、无 Android/Glance 依赖，便于单测反推 iOS 阈值。心情文案/底色由 widget 视图按枚举取（解耦资源）。
 */
enum class PetWidgetMood {
    WALKING, HUNGRY, DIRTY, HAPPY, SAD, CALM;

    companion object {
        /** 1:1 iOS 6 分支优先级：散步 > 饿(≥70) > 脏(≤30) > 开心(≥80) > 不开心(≤30) > 安静。 */
        fun of(isWalking: Boolean, hunger: Int, cleanliness: Int, happiness: Int): PetWidgetMood = when {
            isWalking -> WALKING
            hunger >= 70 -> HUNGRY
            cleanliness <= 30 -> DIRTY
            happiness >= 80 -> HAPPY
            happiness <= 30 -> SAD
            else -> CALM
        }
    }
}

/**
 * 小组件展示所需的宠物快照（轻量）。**安卓地道适配**：小组件与 App 同进程，直接读 Room 组装，
 * 不照搬 iOS 的 App Group `SharedPetStore` UserDefaults 桥（那是 iOS 扩展跨进程的平台产物）。
 *
 * 注：iOS `PetWidgetData` 含 characterName/health 但两种尺寸视图均不展示，这里保留 health 仅作快照完整，
 * characterName 不取（省一次角色查表）。
 */
data class PetWidgetData(
    /** 所属角色 uuid（点击小组件 → 路由 petDetail/{characterUuid}；不展示）。 */
    val characterUuid: String,
    val petName: String,
    val speciesRaw: String,
    val growthStageRaw: String,
    val hunger: Int,
    val cleanliness: Int,
    val happiness: Int,
    val health: Int,
    val isWalking: Boolean,
) {
    val mood: PetWidgetMood get() = PetWidgetMood.of(isWalking, hunger, cleanliness, happiness)
}

/**
 * 从 Room 实体组装小组件快照。
 *
 * C1#3：isWalking **现算**（walkStartTime 非空 **且** 散步未到点 elapsed < [PetWalkService.WALK_DURATION_MS]）——
 * walkStartTime 在散步完成结算前不会清空，旧的「!= null」判定会让小组件在 30 分散步早已结束后仍长期显示「散步中」
 * （App 被 HyperOS 杀后无人结算）。与 [PetWalkService.walkState] 同口径。[now] 由调用方传入便于现算/测试。
 */
fun CharacterPetEntity.toPetWidgetData(now: Long = System.currentTimeMillis()): PetWidgetData = PetWidgetData(
    characterUuid = characterUuid,
    petName = name,
    speciesRaw = speciesRaw,
    growthStageRaw = growthStageRaw,
    hunger = hunger,
    cleanliness = cleanliness,
    happiness = happiness,
    health = health,
    isWalking = metadata.walkStartTime?.let { now - it < PetWalkService.WALK_DURATION_MS } ?: false,
)
