package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户自建居民静态人设（世界二期战役 B·契约 `FABLE5_WORLD_ART_RESIDENTS_PROPOSAL.md` §3 / 图纸
 * `docs/handoff/2026-07-07-世界二期战役B-用户自建居民.md` §3.1）。
 *
 * 与官方原住民（[com.situ.aichat.world.cast.WorldNativeDef]·代码常量）**平权**：都上地图、跑零 LLM 模板模拟、
 * 可偶遇/招募——区别只在数据源（官方 = 常量、用户 = 本表随备份）。运行态（发现/眼缘/招募指针）另存
 * `world_native_state`（与官方共用）；本表只存静态人设。[slug] = `resident_<8hex>`（`resident_` 前缀恒不撞官方人名拼音）。
 *
 * 字段镜像 [WorldNativeDef] 的可编辑子集；def 映射（含性格底色/自由设定拼进人设 prompt）见
 * `WorldResidentService.defOf`（图纸 §3.1·§9 禁改）。
 */
@Entity(tableName = "world_user_resident")
data class WorldUserResidentEntity(
    @PrimaryKey val slug: String,
    val name: String,
    /** "male" / "female" / 自定义原文（[WorldNativeDef.gender] 是自由 String·消费点仅提示词用）。 */
    val gender: String,
    val age: Int,
    val cityId: String,
    val occupation: String,
    /** 人设简介（「TA 是个什么样的人」）。 */
    val personaBrief: String,
    /** 性格底色词 JSON 数组（1–3 个·含自定义词·[com.situ.aichat.util.StringListJson] 编码）。 */
    val traitsJson: String,
    /** 自由补充设定（可空串·直进人设 prompt 的 backstory）。 */
    val freeformLore: String,
    /** 初始关系（可空串·如「是老板娘的表妹」·进 backstory）。 */
    val initialRelationText: String,
    /** 眼缘倾向："balanced" / "narrative" / "gift"（映射双燃料权重·图纸 §3.1）。 */
    val fuelBias: String,
    val avatarPath: String?,
    val createdAt: Long,
)
