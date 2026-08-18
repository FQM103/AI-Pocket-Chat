package com.situ.aichat.world.stage

import com.situ.aichat.world.stage.WorldStageResolver.CityPlace
import com.situ.aichat.world.stage.WorldStageResolver.Resolution
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [WorldStageResolver] T1 纯函数测试（W9d 图纸 §7 T1-2·E2·断言从图纸 §4.3 表独立反推）。
 *
 * 地点清单照 `WorldCuratedCities.PLACES`（含 home/cove·验内部按型表滤）。覆盖：名称直中 / 关键词映射 /
 * home 永不产出 / 无命中 IN_TOWN / 程序城恒 IN_TOWN / 名称直中优先关键词 / 匹配序 / 型命中但本城无该地点。
 */
class WorldStageResolverTest {

    // 本城全地点清单（= WorldCuratedCities.PLACES·含 yunye_home / xiyu_cove 亦无妨）。
    private val yunye = listOf(
        CityPlace("yunye_home", "你的家"),
        CityPlace("yunye_cafe", "拾光咖啡馆"),
        CityPlace("yunye_book", "青苔书店"),
        CityPlace("yunye_park", "河畔公园"),
        CityPlace("yunye_dock", "渡口码头"),
        CityPlace("yunye_eat", "巷尾食铺"),
        CityPlace("yunye_square", "老槐树广场"),
    )
    private val taoqiu = listOf(
        CityPlace("taoqiu_kiln", "千窑坡"),
        CityPlace("taoqiu_market", "釉色市集"),
        CityPlace("taoqiu_shop", "陶心工坊"),
        CityPlace("taoqiu_tea", "火塘茶肆"),
        CityPlace("taoqiu_view", "望原台"),
    )
    private val xiyu = listOf(
        CityPlace("xiyu_beach", "落汐滩"),
        CityPlace("xiyu_market", "灯塔渔市"),
        CityPlace("xiyu_walk", "椰风栈道"),
        CityPlace("xiyu_hall", "潮声馆"),
        CityPlace("xiyu_cove", "星沙湾"),
    )

    private fun place(id: String) = Resolution.AtPlace(id)

    // ---- 名称直中（步骤 1·优先） ----

    @Test
    fun `E2 名称直中`() {
        assertEquals(place("yunye_cafe"), WorldStageResolver.resolve("在拾光咖啡馆画画", yunye))
        assertEquals(place("yunye_eat"), WorldStageResolver.resolve("巷尾食铺吃碗面", yunye))
        assertEquals(place("taoqiu_market"), WorldStageResolver.resolve("去釉色市集逛逛", taoqiu))
    }

    @Test
    fun `E2 名称直中优先于关键词`() {
        // 「拾光咖啡馆」是名称直中；同时含关键词也走名称（步骤 1 先）。
        assertEquals(place("yunye_cafe"), WorldStageResolver.resolve("拾光咖啡馆喝咖啡", yunye))
    }

    // ---- 关键词 → 型 → 本城该型地点（步骤 2） ----

    @Test
    fun `E2 关键词映射`() {
        assertEquals(place("yunye_cafe"), WorldStageResolver.resolve("去星巴克坐坐", yunye))     // CAFE
        assertEquals(place("yunye_book"), WorldStageResolver.resolve("图书馆看书", yunye))         // BOOKSTORE
        assertEquals(place("yunye_park"), WorldStageResolver.resolve("河边散步", yunye))           // PARK
        assertEquals(place("yunye_eat"), WorldStageResolver.resolve("路边小吃摊", yunye))          // RESTAURANT
        assertEquals(place("yunye_square"), WorldStageResolver.resolve("广场遛弯", yunye))         // SQUARE
        assertEquals(place("yunye_dock"), WorldStageResolver.resolve("渡口等船", yunye))           // DOCK
        assertEquals(place("taoqiu_kiln"), WorldStageResolver.resolve("窑边守火", taoqiu))         // KILN
        assertEquals(place("taoqiu_tea"), WorldStageResolver.resolve("茶肆喝茶", taoqiu))          // TEAHOUSE
        assertEquals(place("taoqiu_shop"), WorldStageResolver.resolve("工坊拉坯", taoqiu))         // WORKSHOP
        assertEquals(place("taoqiu_view"), WorldStageResolver.resolve("去高台看看", taoqiu))       // LOOKOUT
        assertEquals(place("xiyu_beach"), WorldStageResolver.resolve("海边赶海", xiyu))            // BEACH
        assertEquals(place("xiyu_walk"), WorldStageResolver.resolve("栈道散步", xiyu))             // BOARDWALK
        assertEquals(place("xiyu_hall"), WorldStageResolver.resolve("博物馆看展", xiyu))           // HALL
        assertEquals(place("xiyu_market"), WorldStageResolver.resolve("集市买鱼", xiyu))           // MARKET
    }

    @Test
    fun `E2 英文关键词大小写不敏感`() {
        assertEquals(place("yunye_cafe"), WorldStageResolver.resolve("去 Cafe 坐坐", yunye))
        assertEquals(place("yunye_cafe"), WorldStageResolver.resolve("COFFEE time", yunye))
    }

    @Test
    fun `E2 匹配序_声明序先者胜`() {
        // 「咖啡书店」同含 咖啡(CAFE·组1) 与 书店(BOOKSTORE·组3)——声明序 CAFE 先。
        assertEquals(place("yunye_cafe"), WorldStageResolver.resolve("咖啡书店都想去", yunye))
    }

    // ---- HOME → AT_HOME_HOUSE ----

    @Test
    fun `E2 home关键词落AtHomeHouse`() {
        assertEquals(Resolution.AtHomeHouse, WorldStageResolver.resolve("家里卧室睡觉", yunye))
        assertEquals(Resolution.AtHomeHouse, WorldStageResolver.resolve("在厨房做饭", yunye))
        assertEquals(Resolution.AtHomeHouse, WorldStageResolver.resolve("阳台晒太阳", taoqiu))
    }

    @Test
    fun `E2 你的家永不产出yunye_home`() {
        // 「你的家」含「家」→ HOME → AtHomeHouse；绝不产出 place(yunye_home)。
        val r = WorldStageResolver.resolve("你的家", yunye)
        assertEquals(Resolution.AtHomeHouse, r)
        // 同时钉死：即便文本正好等于用户家全名，也不落 yunye_home。
        assert(r !is Resolution.AtPlace || r.placeId != "yunye_home")
    }

    // ---- 无命中 / cove 不接站位 / 型命中但本城无该地点 ----

    @Test
    fun `E2 无命中IN_TOWN`() {
        assertEquals(Resolution.InTown, WorldStageResolver.resolve("发呆", yunye))
        assertEquals(Resolution.InTown, WorldStageResolver.resolve("到处走走", taoqiu))
    }

    @Test
    fun `E2 星沙湾不接站位`() {
        // xiyu_cove 不入型表：名称「星沙湾」不直中、无关键词 → IN_TOWN（留给风景）。
        assertEquals(Resolution.InTown, WorldStageResolver.resolve("去星沙湾看星星", xiyu))
    }

    @Test
    fun `E2 型命中但本城无该地点_IN_TOWN`() {
        // 云野无 TEAHOUSE：茶肆关键词命中型但本城无该地点 → IN_TOWN。
        assertEquals(Resolution.InTown, WorldStageResolver.resolve("找个茶馆", yunye))
    }

    // ---- 程序城恒 IN_TOWN（无地点表） ----

    @Test
    fun `E2 程序城恒IN_TOWN`() {
        val empty = emptyList<CityPlace>()
        assertEquals(Resolution.InTown, WorldStageResolver.resolve("去咖啡馆", empty))
        assertEquals(Resolution.InTown, WorldStageResolver.resolve("家里睡觉", empty)) // 无地点表连 home 也不解析
        assertEquals(Resolution.InTown, WorldStageResolver.resolve("窑边", empty))
    }
}
