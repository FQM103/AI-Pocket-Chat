package com.situ.aichat.ui.world

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.world.WorldIds
import com.situ.aichat.world.atlas.WorldAtlas
import com.situ.aichat.world.travel.DepartResult
import com.situ.aichat.world.travel.TravelOption
import com.situ.aichat.world.travel.WorldTravelPlanner
import com.situ.aichat.world.travel.WorldTravelService
import javax.inject.Inject

/** 一次旅行报价（W9d 图纸 §3.4/§4.8·只读·零钱路）。 */
data class TravelQuote(
    val destCityId: String,
    val destName: String,
    val fromName: String,
    val distanceLi: Int,
    val options: List<TravelOption>,
    /** 有他人正来用户当前城且未到 → 扑空提示的角色名（否则 null·E10）。 */
    val visitorName: String?,
)

/**
 * 旅行单报价 + 出发转调（W9d 图纸 §3.4·**钱路只调不改**）：报价读 [WorldDao]/[WorldAtlas]/[WorldTravelPlanner]
 * （全只读·零扣款）；出发唯一经 [WorldTravelService.depart]（W7 已过 T5 复核的唯一扣款口）。UI 的选中/二次确认为
 * Compose 态·本类保持纯逻辑可 T2 直测（真库 + 真 CurrencyService）。[nowMs] 由调用方注入（确定性）。
 */
class WorldTravelSheetViewModel @Inject constructor(
    private val worldDao: WorldDao,
    private val characterDao: CharacterDao,
    private val travelService: WorldTravelService,
) {

    /**
     * 报价（§3.4·只读）：无世界态 / 目的地 = 当前城 → null；否则 from = 用户当前城·distanceLi → optionsFor（序即呈现序）·
     * 城名 atlas 解析·扑空检测（他人 toCity == 当前城 && 未到）。
     */
    suspend fun quote(destCityId: String, nowMs: Long): TravelQuote? {
        val state = worldDao.getState() ?: return null
        val from = state.userCurrentCityId
        if (destCityId == from) return null
        val atlas = WorldAtlas.of(state.seed)
        val distance = atlas.distanceLi(from, destCityId)
        val options = WorldTravelPlanner.optionsFor(distance)
        val visitor = worldDao.getAllTravels().firstOrNull {
            it.ownerId != WorldIds.USER_ID && it.toCityId == from && nowMs < it.arriveAt
        }
        val visitorName = visitor?.let { characterDao.getByUuid(it.ownerId)?.name }
        return TravelQuote(
            destCityId = destCityId,
            destName = atlas.cityById(destCityId)?.name ?: "远方",
            fromName = atlas.cityById(from)?.name ?: "远方",
            distanceLi = distance,
            options = options,
            visitorName = visitorName,
        )
    }

    /** 出发（§3.4·**唯一扣款口** [WorldTravelService.depart]·本类零新增扣款/退款）。 */
    suspend fun depart(destCityId: String, mode: String, nowMs: Long): DepartResult =
        travelService.depart(destCityId, mode, nowMs)
}
