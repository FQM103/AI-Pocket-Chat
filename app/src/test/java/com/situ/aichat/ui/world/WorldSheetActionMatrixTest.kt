package com.situ.aichat.ui.world

import com.situ.aichat.ui.world.WorldSheetActionMatrix.CityBtn
import com.situ.aichat.ui.world.WorldSheetActionMatrix.PlaceBtn
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [WorldSheetActionMatrix] T2-5（W9d 图纸 §7·E11 站点卡按钮矩阵·§4.7 表逐行）。
 */
class WorldSheetActionMatrixTest {

    // ---- 小镇建筑/环境地点 ----

    @Test
    fun `E11 建筑地点_在场走进去_在途无钮_不在场出发来`() {
        assertEquals(listOf(PlaceBtn.ENTER), WorldSheetActionMatrix.placeButtons(hasInterior = true, present = true, traveling = false))
        assertEquals(emptyList<PlaceBtn>(), WorldSheetActionMatrix.placeButtons(hasInterior = true, present = false, traveling = true))
        assertEquals(listOf(PlaceBtn.COME_HERE), WorldSheetActionMatrix.placeButtons(hasInterior = true, present = false, traveling = false))
    }

    @Test
    fun `E11 环境地点恒无钮`() {
        assertEquals(emptyList<PlaceBtn>(), WorldSheetActionMatrix.placeButtons(hasInterior = false, present = true, traveling = false))
        assertEquals(emptyList<PlaceBtn>(), WorldSheetActionMatrix.placeButtons(hasInterior = false, present = false, traveling = false))
    }

    @Test
    fun `E11 建筑地点在途_正文追加在途提示行（R1返工）`() {
        // 建筑地点在途 → 无钮的同时正文追加在途提示行。
        assertEquals("咖啡馆\n提示", WorldSheetActionMatrix.placeBody("咖啡馆", hasInterior = true, traveling = true, inTransitHint = "提示"))
        // 未在途 / 非建筑 → 正文不变。
        assertEquals("咖啡馆", WorldSheetActionMatrix.placeBody("咖啡馆", hasInterior = true, traveling = false, inTransitHint = "提示"))
        assertEquals("公园", WorldSheetActionMatrix.placeBody("公园", hasInterior = false, traveling = true, inTransitHint = "提示"))
    }

    // ---- 大陆城市 ----

    @Test
    fun `E11 城市_本城单钮_异地未在途双钮`() {
        // 在该城（presence == thisCity）→ 只走进小镇。
        assertEquals(listOf(CityBtn.ENTER_TOWN), WorldSheetActionMatrix.cityButtons("city_a", "city_a", traveling = false, homeCityId = "city_a"))
        // 异地未在途 → 走进小镇 + 出发去这里。
        assertEquals(listOf(CityBtn.ENTER_TOWN, CityBtn.TRAVEL_HERE), WorldSheetActionMatrix.cityButtons("city_a", "city_b", traveling = false, homeCityId = "city_a"))
    }

    @Test
    fun `E11 城市_家乡城且不在家乡_出发回家`() {
        // thisCity = 家乡·presence ≠ 家乡 → 出发回家。
        assertEquals(listOf(CityBtn.ENTER_TOWN, CityBtn.TRAVEL_HOME), WorldSheetActionMatrix.cityButtons("city_b", "city_home", traveling = false, homeCityId = "city_home"))
    }

    @Test
    fun `E11 城市_在途无第二钮`() {
        assertEquals(listOf(CityBtn.ENTER_TOWN), WorldSheetActionMatrix.cityButtons("city_a", "city_b", traveling = true, homeCityId = "city_a"))
    }
}
