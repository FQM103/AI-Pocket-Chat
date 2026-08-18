package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.situ.aichat.world.WorldIds

/**
 * 世界单行状态（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §5 / W1 图纸 §3）。**恒单行 id=1**：
 * 世界种子（程序生成的根）+ 用户时区（W13 首启才写）+ 用户住址/当前城 + 懒结算单调锚。
 *
 * [lastSettledAt] = 世界钟单调锚（**只进不退**，契约 §7 决策：设备时间往回调 → 世界冻结）；
 * 推进只经 `WorldDao.advanceSettledAt` 的 `MAX()` 语义在 SQL 层保证。本块只存，不做任何结算/时钟逻辑（W2+）。
 */
@Entity(tableName = "world_state")
data class WorldStateEntity(
    @PrimaryKey val id: Int = 1,
    /** 世界种子（程序生成的根；建行时 `Random.nextLong()`）。 */
    val seed: Long,
    /** 用户时区 id（W13 首启才写，null = 未设）。 */
    val userTimezoneId: String? = null,
    /** 家乡城。 */
    val userHomeCityId: String = WorldIds.HOME_CITY_ID,
    /** 用户当前所在城。 */
    val userCurrentCityId: String = WorldIds.HOME_CITY_ID,
    /** 懒结算单调锚（epoch ms·只进不退）。 */
    val lastSettledAt: Long = 0L,
    /** 建世界时刻（epoch ms）。 */
    val createdAt: Long,
)
