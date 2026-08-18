package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 每日**开机小报**（W5 图纸 §3.1 / 契约 §7.A）：把「离开期间」的世界事件拼成模板小报（恒有值），再按鲜活度
 * 档位 + 每日预算做一次 LLM 润色缓存进 [polishedText]。断网/失败/超预算一律退模板——世界永不死机。
 *
 * **设备本地缓存·不入备份**（W5 图纸 §3.4）：小报正文可含角色名，跨设备恢复无意义，删角后下次结算重生成。
 * [eventsHash] = 窗内事件 uuid 升序拼串的 `WorldSeeds.fnv1a64`——变了才重润色（同窗重结算 hash 未变不重烧 token）。
 * 呈现（世界卡）归 W11，本块只存。
 */
@Entity(tableName = "world_bulletin")
data class WorldBulletinEntity(
    /** 结算窗末日的本地 epochDay（PK·一天一报）。 */
    @PrimaryKey val epochDay: Long,
    /** 小报覆盖时间窗起。 */
    val windowStartMs: Long,
    /** 小报覆盖时间窗末。 */
    val windowEndMs: Long,
    /** 窗内事件 uuid 升序拼串的 fnv1a64（变了才重润色）。 */
    val eventsHash: Long,
    /** 模板小报（恒有值）。 */
    val templateText: String,
    /** LLM 润色稿（null = 未润色 / 已失效）。 */
    val polishedText: String? = null,
    /** 润色时刻（取 windowEndMs·禁真时钟；null = 未润色）。 */
    val polishedAt: Long? = null,
    /** 更新时刻（= windowEndMs）。 */
    val updatedAt: Long,
)
