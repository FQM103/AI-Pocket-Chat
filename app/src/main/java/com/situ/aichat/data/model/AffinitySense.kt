package com.situ.aichat.data.model

import kotlinx.serialization.Serializable

/**
 * 心意反馈拟人化层（1:1 iOS `Models/AffinitySense.swift`）。
 *
 * 核心理念：送礼后端继续算具体 `affinityGain: 1-20`，但 UI **不显示数字**，改用符合角色性格的拟人化文案。
 * 后台分数映射到 3 档（[AffinitySenseTier.tier]），每档 8 条文案 + 6 条手作专属副标签；文案包由 AffinitySenseService
 * 喂角色人设给 LLM 批量生成（9.2b），14 天过期，过期/缺失/失败静默走默认包。
 */

/** 心意反馈档位：由后台 `affinityGain` 映射。 */
enum class AffinitySenseTier(val raw: String) {
    LOW("low"),     // 1-5：礼物普通 / 反应平淡
    MID("mid"),     // 6-12：送到心坎 / 明显开心
    HIGH("high");   // 13-20：非常贵 / 非常惊喜 / 手作心意

    companion object {
        /** 按 `affinityGain` 打分判档位（开区间设计，避免边界歧义）。 */
        fun tier(gain: Int): AffinitySenseTier = when {
            gain <= 5 -> LOW
            gain <= 12 -> MID
            else -> HIGH
        }
    }
}

/**
 * 角色的心意反馈文案包（1:1 iOS `AffinitySensePackage`）。3 档各 8 条 + 6 条手作副标签。
 * JSON 持久化到 `CharacterEntity.affinitySensePackageJSON`：`{"version":1,"low":[...],"mid":[...],"high":[...],"handmade":[...]}`
 */
@Serializable
data class AffinitySensePackage(
    /** 包格式版本号 */
    val version: Int = 1,
    /** 档 A 文案池（8 条） */
    val low: List<String>,
    /** 档 B 文案池（8 条） */
    val mid: List<String>,
    /** 档 C 文案池（8 条） */
    val high: List<String>,
    /** 手作礼物专属副标签（6 条），和上面三档叠加显示 */
    val handmade: List<String>,
) {
    /** 按档位取文案数组 */
    fun phrases(tier: AffinitySenseTier): List<String> = when (tier) {
        AffinitySenseTier.LOW -> low
        AffinitySenseTier.MID -> mid
        AffinitySenseTier.HIGH -> high
    }

    /** 三档 + 手作是否都至少有一条（结构完整性检查） */
    val isWellFormed: Boolean
        get() = low.isNotEmpty() && mid.isNotEmpty() && high.isNotEmpty() && handmade.isNotEmpty()
}

/**
 * `AffinitySenseService.currentSenseText(...)` 的返回值（1:1 iOS `AffinitySenseResult`），UI 直接使用。
 */
data class AffinitySenseResult(
    /** 主文案（档位池里随机一条） */
    val text: String,
    /** 手作礼物副标签（仅 isHandmade=true 才非 null），UI 在主文案下方灰色小字显示 */
    val handmadeBadge: String?,
)
