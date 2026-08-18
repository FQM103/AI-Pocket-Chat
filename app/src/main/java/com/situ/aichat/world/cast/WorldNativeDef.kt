package com.situ.aichat.world.cast

import com.situ.aichat.world.atlas.WorldAtlas

/**
 * 原住民「演员表」定义（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §11 / W6 图纸 §3.1·§4.2 逐字锁死·图纸 §9 禁改）。
 *
 * 一位官方原住民的**全部静态人设** —— 花名册（[WorldNativeRoster.ALL]）由这些常量 def 聚合，运行态（发现/眼缘/
 * 招募指针）另存 `world_native_state`（W1 表）。slug = 罗马字恒定标识（`WorldIds.nativeId(slug)` 拼 `native:<slug>`）；
 * [cityId] 是精修城 id 或生成城 `city_g_<region>_0`（生成城 **id 稳定、名字随种子**，故可静态引用）。
 *
 * @property recruitThreshold 招募门槛（affinity ≥ 此值 = 愿意）。
 * @property narrativeWeight 叙事燃料权重 / @property giftWeight 心意燃料权重（合计乘在原始燃料上·图纸 §3.2）。
 */
data class WorldNativeDef(
    val slug: String,
    val name: String,
    /** "male" / "female"。 */
    val gender: String,
    val fixedAge: Int,
    /** 所属大区 id（∈ [WorldNativeDef.regionIdOf] 的十大区·= cityId 所在区）。 */
    val regionId: String,
    val cityId: String,
    /** 常驻地点 id（仅精修城非空·生成城 null）。 */
    val placeId: String?,
    val occupation: String,
    /** 剪影副标（W9 地图用）。 */
    val oneLiner: String,
    val personality: String,
    val appearance: String,
    val backstory: String,
    val speakingStyle: String,
    /** 口头禅（原样含 `｜` 分隔·招募时整串进 CharacterEntity.catchphrases）。 */
    val catchphrases: String,
    /** 兴趣（逗号分隔·招募时进 CharacterEntity.initialInterests）。 */
    val interests: String,
    /** 初遇开场白（W12 用）。 */
    val greeting: String,
    val recruitThreshold: Int,
    val narrativeWeight: Double,
    val giftWeight: Double,
) {
    companion object {
        /**
         * 城市解析纯函数（图纸 §2/§4.6·复用 [WorldAtlas]）：把 [cityId] 在 [seed] 的图集里解析成城名——
         * 生成城名随种子（运行时才定），故须带 seed 现算。城 id 查无（不应发生）→ 回退「远方」（§4.6 锁死）。
         * 招募世界事件文案（§4.6）用此拼「你们的缘分从{城名}开始」。
         */
        fun cityNameOf(cityId: String, seed: Long): String =
            WorldAtlas.of(seed).cityById(cityId)?.name ?: "远方"
    }
}

/**
 * 出厂关系边（图纸 §3.1 / §4.3 逐字锁死）：**无向声明、双向落地**。招募时若两端皆已招募，按此把两向
 * [com.situ.aichat.data.local.entity.WorldRelationshipEntity] 落库（`A→B` 取 `*AB`、`B→A` 取 `*BA`）。
 *
 * @property types 关系类型（两向同·closeness ≥35 必含「朋友」）。@property origin 渊源句（两向同）。
 */
data class WorldFactoryEdge(
    val slugA: String,
    val slugB: String,
    val types: List<String>,
    // A→B 三元（clo/tru/色·= §4.3 表「A→B」格）、B→A 三元（= 表「B→A」格）——分向成组防转录错位。
    val closenessAB: Int,
    val trustAB: Int,
    val colorAB: String,
    val closenessBA: Int,
    val trustBA: Int,
    val colorBA: String,
    val origin: String,
)
