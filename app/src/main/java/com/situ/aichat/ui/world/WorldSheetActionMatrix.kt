package com.situ.aichat.ui.world

/**
 * 站点卡按钮矩阵单源（W9d 图纸 §4.7·纯函数·§9 锁死）。SceneView 据此决定显示哪些动作按钮·T2-5（E11）直测。
 * 环境地点/奇观恒无钮；建筑地点在场→走进去·在途→无钮·不在场→出发来；城市恒走进小镇 +（异地未在途）出发去/回家。
 */
internal object WorldSheetActionMatrix {

    /** 小镇建筑/环境地点卡按钮（§4.7）。 */
    enum class PlaceBtn { ENTER, COME_HERE }

    fun placeButtons(hasInterior: Boolean, present: Boolean, traveling: Boolean): List<PlaceBtn> = when {
        !hasInterior -> emptyList()      // 环境地点恒无钮
        present -> listOf(PlaceBtn.ENTER) // 在场 → 走进去
        traveling -> emptyList()          // 在途 → 无钮（正文追加提示行·见 [placeBody]）
        else -> listOf(PlaceBtn.COME_HERE) // 不在场未在途 → 出发来这座城
    }

    /** 地点站点卡正文（§4.7·🟡-5·建筑地点在途 → 无钮的同时正文追加在途提示行·纯函数=E11 可测）。 */
    fun placeBody(base: String, hasInterior: Boolean, traveling: Boolean, inTransitHint: String): String =
        if (hasInterior && traveling) "$base\n$inTransitHint" else base

    /** 大陆城市卡按钮（§4.7）。 */
    enum class CityBtn { ENTER_TOWN, TRAVEL_HERE, TRAVEL_HOME }

    fun cityButtons(presenceCityId: String?, thisCityId: String, traveling: Boolean, homeCityId: String?): List<CityBtn> {
        val out = mutableListOf(CityBtn.ENTER_TOWN)
        val away = presenceCityId != null && presenceCityId != thisCityId && !traveling
        if (away) out += if (thisCityId == homeCityId && presenceCityId != homeCityId) CityBtn.TRAVEL_HOME else CityBtn.TRAVEL_HERE
        return out
    }
}
