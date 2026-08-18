package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 在途旅行（契约 `FABLE5_WORLD_SYSTEM_PROPOSAL.md` §5 / W1 图纸 §3）。**一 owner 至多一行在途**
 * （PK = [ownerId]，再次出发 = 替换前一行）。owner 为混合域：`WorldIds.USER_ID` / 角色 uuid / 原住民 id。
 *
 * [costGold] 只是**记录列**（账面花费）；真扣款在 W7（+ T5 复核），本块绝不调用 `CurrencyService`。
 * 旅行公式 / 到达结算属 W7，本块只存起讫/时刻/方式。
 */
@Entity(tableName = "world_travel")
data class WorldTravelEntity(
    @PrimaryKey val ownerId: String,
    val fromCityId: String,
    val toCityId: String,
    /** 出发时刻（epoch ms）。 */
    val departAt: Long,
    /** 预计到达时刻（epoch ms）。 */
    val arriveAt: Long,
    /** 旅行方式（`WorldIds.TravelModes` 五值之一）。 */
    val modeRaw: String,
    /** 花费记录（扣款在 W7·此处只记账面）。 */
    val costGold: Long = 0L,
)
