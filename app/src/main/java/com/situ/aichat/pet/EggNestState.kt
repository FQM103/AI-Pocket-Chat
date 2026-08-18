package com.situ.aichat.pet

/**
 * 「家的蛋巢」纯派生态（W12.5 图纸 §3·决策 42）。巢态**不存、是派生**的（之约键 + 该角色是否已有宠物 +
 * [PetAdoptionRules] canAdopt）——角色被删 / 用户绕道 petDetail 直接领养，巢自动回正确态并清键，永不出「幽灵蛋」。
 * 纯函数（[deriveEggNest]/[eggNestPhrase]）便 T1 独立反推；无 Compose / 无字符串资源依赖（UI 侧映射色与文案）。
 */

/** 之约持久化载体（DataStore 双键：角色 uuid + 定约时刻）。uuid 空 = 无之约。 */
data class EggNestPact(val characterUuid: String, val pactAtMs: Long)

/** 巢的三态（Empty/Incubating/Hatchable）。名牌/蛋 speckle 用的头像色由 UI 从 [characterName] 派生（存 uuid 不冻结对象·W9d 教训）。 */
sealed interface EggNestState {
    /** 空巢（无之约·或自愈后）。 */
    data object Empty : EggNestState
    /** 在孵（之约在·无宠·未达领养门槛）。 */
    data class Incubating(val characterUuid: String, val characterName: String) : EggNestState
    /** 可孵化（之约在·无宠·canAdopt 达标）。 */
    data class Hatchable(val characterUuid: String, val characterName: String) : EggNestState
}

/** 候选行朦胧短语四档（映射锁死·§3；UI 映射到 world_nest_phrase_*）。 */
enum class EggNestPhrase { READY, CLOSE, WARMING, FAR }

/** 派生中间结果：巢态 + 是否需自愈清键（幂等·§3）。 */
data class EggNestDerivation(val state: EggNestState, val clearPact: Boolean)

/**
 * 巢态派生矩阵（锁死·图纸 §3）：
 * - 之约键空 → Empty（不清键）
 * - 角色已不存在 → Empty + 自愈清键
 * - 该角色已有宠物 → Empty + 自愈清键（= 已出壳 / 或绕道领养，均视为兑现）
 * - 无宠且 canAdopt==false → Incubating
 * - 无宠且 canAdopt==true → Hatchable
 */
internal fun deriveEggNest(
    pactUuid: String?,
    characterExists: Boolean,
    characterName: String,
    hasPet: Boolean,
    canAdopt: Boolean,
): EggNestDerivation = when {
    pactUuid.isNullOrBlank() -> EggNestDerivation(EggNestState.Empty, clearPact = false)
    !characterExists -> EggNestDerivation(EggNestState.Empty, clearPact = true)
    hasPet -> EggNestDerivation(EggNestState.Empty, clearPact = true)
    canAdopt -> EggNestDerivation(EggNestState.Hatchable(pactUuid, characterName), clearPact = false)
    else -> EggNestDerivation(EggNestState.Incubating(pactUuid, characterName), clearPact = false)
}

/**
 * 候选朦胧短语映射（阈值锁死·§3）：canAdopt →「已经准备好了」；≥0.75 →「就快可以了」；
 * ≥0.35 →「热络起来了」；否则「还在慢慢靠近」。等值走高档（≥）。
 */
internal fun eggNestPhrase(canAdopt: Boolean, overallPercent: Float): EggNestPhrase = when {
    canAdopt -> EggNestPhrase.READY
    overallPercent >= 0.75f -> EggNestPhrase.CLOSE
    overallPercent >= 0.35f -> EggNestPhrase.WARMING
    else -> EggNestPhrase.FAR
}

/**
 * 孵蛋之约候选行（§3/§4.3）：[petName] 非 null = 该角色已养宠物（禁选·沉底）；无宠者带 [phrase]（朦胧短语）+
 * [overallPercent]（细进度条）。UI 从 [name] 派生头像色（AvatarColor·存 uuid 不冻结对象）。
 */
data class EggNestCandidate(
    val characterUuid: String,
    val name: String,
    val avatarPath: String?,
    val petName: String?,
    val phrase: EggNestPhrase?,
    val overallPercent: Float,
)

/**
 * 候选排序（锁死·§3）：无宠者按 [EggNestCandidate.overallPercent] 降序在前，有宠者沉底；组内按名字拼音升序
 * （现有列表排序惯例·中文 Collator）。纯函数便 T1-2。
 */
internal fun sortEggNestCandidates(candidates: List<EggNestCandidate>): List<EggNestCandidate> {
    val collator = java.text.Collator.getInstance(java.util.Locale.CHINESE)
    return candidates.sortedWith(
        compareBy<EggNestCandidate> { it.petName != null } // false(无宠) 在前
            .thenByDescending { if (it.petName == null) it.overallPercent else 0f } // 无宠：percent 降序
            .thenBy(collator) { it.name }, // 组内 / tiebreak：名字拼音升序
    )
}
