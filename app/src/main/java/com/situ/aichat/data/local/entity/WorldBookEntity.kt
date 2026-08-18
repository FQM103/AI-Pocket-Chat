package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 世界书（酒馆 SillyTavern World Info / Lorebook 的「书」层）——一组设定条目的集合，
 * 契约 = `FABLE5_WORLDBOOK_PROPOSAL.md`（字段语义 §2.3 书级设置 / 数据层 §4.1）。
 *
 * 绑定模型（用户拍板 2026-07-02 维持多对多）：角色 × 书经 [WorldBookBindingEntity] 多对多；
 * [isGlobal] = true 的书对所有角色生效、无需绑定。条目在 [WorldBookEntryEntity]（FK 级联，删书清条目）。
 *
 * 书级三个可空设置 = 「null 即跟全局」的覆盖语义（与 ST character_book 的可选字段一致）；
 * [tokenBudget] 存 ST 原值（token 数），引擎侧换算成字符预算（契约 D4），存储端不做换算。
 */
@Entity(tableName = "world_books")
data class WorldBookEntity(
    @PrimaryKey val uuid: String = UUID.randomUUID().toString(),

    /** 书名（如「修仙世界·青云录」）。 */
    val name: String = "",
    /** 书的简介（给用户看，不进提示词）。 */
    val description: String = "",

    // ── 书级设置（null = 用全局设置，ST character_book 可选字段语义） ──
    /** 书级扫描深度覆盖。 */
    val scanDepth: Int? = null,
    /** 书级 token 预算（ST `token_budget` 原值）。 */
    val tokenBudget: Int? = null,
    /** 书级递归扫描覆盖。 */
    val recursiveScanning: Boolean? = null,

    /** 全局书：true = 对所有角色的对话生效（不需绑定）。 */
    val isGlobal: Boolean = false,
    /** 书总开关（关 = 整本书不参与激活）。 */
    val enabled: Boolean = true,

    /** 导入格式的 extensions + 未知书级字段整包 JSON（round-trip 不丢数据；"" = 无）。 */
    val extraJson: String = "",

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
