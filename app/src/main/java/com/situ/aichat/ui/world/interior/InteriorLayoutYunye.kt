package com.situ.aichat.ui.world.interior

import com.situ.aichat.ui.world.continent.TriStream
import com.situ.aichat.ui.world.continent.rgb

/**
 * 云野镇 4 间室内布局（W9d 图纸 §4.2A/B·§9 锁死）。
 * **拾光咖啡馆 = demo `interior-3d-demo.html` 逐字段转录**（L138-251·金标 T1-4 看门·禁改）；
 * 你的家/青苔书店/巷尾食铺 = 图纸作者按 demo 框架落值（§4.2B 表·装机复审）。房壳/窗景/降水由
 * [InteriorSceneData.of] 统一补，本表 build 只摆家具。
 */
internal object InteriorLayoutYunye {

    private const val RZ0 = InteriorKit.RZ0

    private fun a(x: Double, y: Double, z: Double) = InteriorAnchor(x.toFloat(), y.toFloat(), z.toFloat())

    /** 书报架 + 书五（cafe/home 共用·demo:L239-241）。 */
    private fun bookRack(lit: TriStream, cx: Double, cz: Double) = with(InteriorKit) {
        litBox(lit, cx, 0.0, cz, 0.9, 1.7, 0.4, WOOD)
        val books = arrayOf(0.35 to 0x8E9AA6, 0.35 to 0xB07E68, 0.95 to 0x7E926E, 0.95 to 0xC2A26B, 1.4 to 0x9A8570)
        books.forEachIndexed { i, (by, bc) -> litBox(lit, cx - 0.22 + (i % 2) * 0.42, by, cz, 0.16, 0.34, 0.3, rgb(bc)) }
    }

    val ROOMS: Map<String, InteriorRoomDef> = mapOf(
        // ───────────── 拾光咖啡馆（demo 逐值·§4.2A 金标）─────────────
        "yunye_cafe" to InteriorRoomDef(
            cityId = "city_yunye", placeName = "拾光咖啡馆",
            plankA = InteriorKit.WOOD, plankB = InteriorKit.WOOD_L, skirt = InteriorKit.CLAY_D,
            nativeAnchor = a(1.55, 2.0, -2.75),
            guestSlots = listOf(a(-4.35, 1.75, -0.2), a(0.9, 1.75, 1.6)),
            petSpots = emptyList(),
            flavorSpots = listOf(InteriorFlavorSpot(a(-3.6, 0.6, -0.2), "cafe_seat"), InteriorFlavorSpot(a(2.6, 0.9, -2.4), "cafe_bar")),
            steamSpots = listOf(a(-3.75, 0.95, -0.35), a(-3.44, 0.95, -0.02)),
        ) { lit, emis ->
            with(InteriorKit) {
                litQuad(lit, p(-4.9, 0.012, 1.6), p(-2.2, 0.012, 1.6), p(-2.2, 0.012, -1.4), p(-4.9, 0.012, -1.4), rgb(0x9A8570)) // 踢脚暖毯
                litQuad(lit, p(-0.6, 0.012, 2.0), p(2.4, 0.012, 2.0), p(2.4, 0.012, -0.4), p(-0.6, 0.012, -0.4), rgb(0x8F9B7E)) // 中央毯
                // 吧台
                litBox(lit, 2.1, 0.0, -2.55, 3.8, 1.0, 1.05, CLAY)
                litBox(lit, 2.1, 1.0, -2.55, 4.1, 0.1, 1.25, DARKCLAY)
                litBox(lit, 2.1, 0.32, -1.99, 3.8, 0.06, 0.05, BRASS)
                litBox(lit, 1.3, 1.1, -2.75, 0.9, 0.62, 0.55, rgb(0xCFC6BA)) // 意式机
                litBox(lit, 1.3, 1.28, -2.44, 0.5, 0.12, 0.1, rgb(0x9C938A)) // 出杯口
                emisQuad(emis, p(1.06, 1.32, -2.42), p(1.18, 1.32, -2.42), p(1.18, 1.42, -2.42), p(1.06, 1.42, -2.42), LAMP) // 压力表
                litBox(lit, 2.35, 1.1, -2.8, 0.3, 0.5, 0.3, rgb(0x8E8377)) // 磨豆机
                for (c in arrayOf(p(3.0, 0.0, -2.8), p(3.22, 0.0, -2.8), p(3.11, 0.0, -2.62))) litBox(lit, c[0], 1.1, c[2], 0.16, 0.14, 0.16, CREAM) // 底三杯
                litBox(lit, 3.05, 1.24, -2.71, 0.42, 0.05, 0.42, CREAM) // 托盘
                litBox(lit, 3.0, 1.29, -2.8, 0.16, 0.14, 0.16, CREAM); litBox(lit, 3.22, 1.29, -2.8, 0.16, 0.14, 0.16, CREAM) // 顶两杯
                // 吊架 + 罐五
                litBox(lit, 2.1, 2.0, RZ0 + 0.12, 3.4, 0.08, 0.3, WOOD)
                for ((jx, jc) in arrayOf(0.8 to 0x9A8570, 1.35 to 0xB07E68, 1.9 to 0x7E926E, 2.45 to 0x8E9AA6, 3.0 to 0xC2A26B)) litBox(lit, jx, 2.08, RZ0 + 0.12, 0.3, 0.38, 0.26, rgb(jc))
                // 黑板 + 粉痕三
                litBox(lit, -0.9, 1.5, RZ0 + 0.06, 1.7, 1.2, 0.08, rgb(0x2F2A26))
                for ((my, mw) in arrayOf(1.95 to 0.9, 2.25 to 1.2, 1.65 to 0.7)) emisQuad(emis, p(-1.55, my, RZ0 + 0.11), p(-1.55 + mw, my, RZ0 + 0.11), p(-1.55 + mw, my + 0.05, RZ0 + 0.11), p(-1.55, my + 0.05, RZ0 + 0.11), rgb(0xCBBFAE))
                art(lit, -3.4, 1.7, 0.72, 0.9, rgb(0x8F9B7E)); art(lit, -2.5, 1.95, 0.5, 0.62, rgb(0xA5B4C4))
                // 窗边长椅 + 双人桌
                litBox(lit, -4.55, 0.0, -0.2, 0.5, 0.45, 2.6, WOOD)
                litBox(lit, -4.55, 0.45, -0.2, 0.5, 0.12, 2.6, CLAY)
                litBox(lit, -4.78, 0.57, -0.2, 0.1, 0.5, 2.6, WOOD)
                table(lit, -3.6, -0.2, 0.95); cup(lit, -3.75, -0.35, 0.79); cup(lit, -3.44, -0.02, 0.79); candle(lit, emis, -3.55, -0.2, 0.79); chair(lit, -2.85, -0.2, 3)
                // 中央桌 ×2
                table(lit, 0.9, 0.85, 0.9); chair(lit, 0.9, 1.6, 0); chair(lit, 0.18, 0.85, 2); cup(lit, 0.82, 0.75, 0.79); candle(lit, emis, 1.08, 0.98, 0.79)
                table(lit, 3.2, 1.3, 0.85); chair(lit, 3.9, 1.3, 3); candle(lit, emis, 3.2, 1.3, 0.79)
                cornerPlant(lit, -4.6, -2.9)
                bookRack(lit, 4.6, -2.7)
                pendant(lit, emis, 0.9, 0.85, 1.15); pendant(lit, emis, -3.6, -0.2, 1.05); pendant(lit, emis, 2.1, -2.2, 1.3)
            }
        },
        // ───────────── 你的家（§4.2B·作者落值）─────────────
        "yunye_home" to InteriorRoomDef(
            cityId = "city_yunye", placeName = "你的家",
            plankA = InteriorKit.WOOD_L, plankB = InteriorKit.WOOD, skirt = InteriorKit.CLAY_D,
            nativeAnchor = null, guestSlots = emptyList(),
            petSpots = listOf(a(-3.7, 0.85, -1.55), a(-2.6, 0.28, 0.4), a(-3.6, 0.75, 1.8)),
            flavorSpots = listOf(InteriorFlavorSpot(a(-3.2, 0.8, -1.6), "home_sofa")),
            steamSpots = listOf(a(2.2, 1.35, -2.9)),
        ) { lit, emis ->
            with(InteriorKit) {
                sofa(lit, -3.2, -1.6, 2.6)
                litBox(lit, -3.9, 0.62, -1.6, 0.8, 0.06, 0.9, MOSS) // 毛毯搭角
                // 矮桌
                litBox(lit, -3.0, 0.4, 0.6, 1.2, 0.07, 0.8, WOOD_L)
                for (lx in arrayOf(-3.5, -2.5)) for (lz in arrayOf(0.28, 0.92)) litBox(lit, lx, 0.0, lz, 0.08, 0.4, 0.08, WOOD_D)
                cup(lit, -3.2, 0.45, 0.47); cup(lit, -2.8, 0.72, 0.47)
                litQuad(lit, p(-4.4, 0.012, 1.2), p(-1.4, 0.012, 1.2), p(-1.4, 0.012, -0.6), p(-4.4, 0.012, -0.6), rgb(0x8F9B7E)) // 中央毯
                floorLamp(lit, emis, -4.6, -2.8)
                bookRack(lit, 4.6, -2.7)
                desk(lit, -4.3, 1.8); chair(lit, -3.6, 1.8, 2)
                emisBox(emis, -4.5, 0.82, 1.6, 0.14, 0.18, 0.14, LAMP) // 桌灯（§4.2B【R1 订正】y0=0.82 落桌面·原 0.82+0.18 为笔误浮空）
                counterUnit(lit, 2.8, -2.9, 2.6, 0.8, CREAM, DARKCLAY) // 厨房条台
                litBox(lit, 2.2, 1.03, -2.9, 0.3, 0.28, 0.3, rgb(0x8E8377)) // 水壶
                cup(lit, 2.6, -2.9, 1.03); cup(lit, 3.0, -2.9, 1.03)
                art(lit, 0.6, 1.8, 0.72, 0.9, rgb(0xA5B4C4))
                cornerPlant(lit, 4.6, 2.4)
                pendant(lit, emis, -3.0, -0.6, 1.15); pendant(lit, emis, 2.6, -1.6, 1.05)
            }
        },
        // ───────────── 青苔书店（§4.2B·作者落值）─────────────
        "yunye_book" to InteriorRoomDef(
            cityId = "city_yunye", placeName = "青苔书店",
            plankA = InteriorKit.WOOD, plankB = rgb(0x7A5E45), skirt = rgb(0x5C6B7C),
            nativeAnchor = a(-0.4, 1.9, -0.9),
            guestSlots = listOf(a(-4.3, 1.7, 0.2), a(3.0, 1.8, 2.0)),
            petSpots = emptyList(),
            flavorSpots = listOf(InteriorFlavorSpot(a(3.6, 0.9, 2.2), "book_counter")),
            steamSpots = emptyList(),
        ) { lit, emis ->
            with(InteriorKit) {
                val bookCols = listOf(rgb(0x8E9AA6), rgb(0xB07E68), rgb(0x7E926E), rgb(0xC2A26B), rgb(0x9A8570))
                for (sx in arrayOf(-4.0, -2.1, -0.2, 1.7)) shelfUnit(lit, sx, -3.0, 1.7, 2.4, 0.35, listOf(0.4, 1.0, 1.6), bookCols) // 后墙高架四
                shelfUnit(lit, -1.2, 0.4, 2.2, 1.5, 0.5, listOf(0.35, 0.95), bookCols) // 岛架二
                shelfUnit(lit, 1.6, 0.4, 2.2, 1.5, 0.5, listOf(0.35, 0.95), bookCols)
                counterUnit(lit, 3.6, 2.2, 1.8, 0.7, WOOD, WOOD_D)
                emisBox(emis, 3.2, 1.21, 2.2, 0.14, 0.18, 0.14, LAMP) // 台灯
                litBox(lit, 4.0, 1.03, 2.2, 0.34, 0.22, 0.26, rgb(0x8E9AA6)) // 书摞
                // 窗边扶手椅
                litBox(lit, -4.3, 0.0, 0.2, 0.8, 0.5, 0.8, CLAY)
                litBox(lit, -4.3, 0.5, -0.12, 0.8, 0.6, 0.16, CLAY)
                table(lit, -3.5, 0.9, 0.7); cup(lit, -3.5, 0.9, 0.79); candle(lit, emis, -3.62, 1.04, 0.79) // 小圆桌（§4.2B【R1 订正】cup/candle 按 (cx,cz,y0)·y0=0.79 落桌面）
                // 梯：双轨 + 横档四
                litBox(lit, -2.55, 0.0, -3.1, 0.06, 2.2, 0.06, WOOD_D); litBox(lit, -2.15, 0.0, -3.1, 0.06, 2.2, 0.06, WOOD_D)
                for (ry in arrayOf(0.5, 1.0, 1.5, 2.0)) litBox(lit, -2.35, ry, -3.1, 0.46, 0.06, 0.06, WOOD_D)
                litQuad(lit, p(-1.4, 0.012, 1.4), p(2.0, 0.012, 1.4), p(2.0, 0.012, -0.2), p(-1.4, 0.012, -0.2), rgb(0x9A8570)) // 中央毯
                cornerPlant(lit, 4.6, -2.9)
                pendant(lit, emis, -1.2, 0.4, 1.0); pendant(lit, emis, 2.2, -0.6, 1.0)
            }
        },
        // ───────────── 巷尾食铺（§4.2B·作者落值）─────────────
        "yunye_eat" to InteriorRoomDef(
            cityId = "city_yunye", placeName = "巷尾食铺",
            plankA = InteriorKit.WOOD_L, plankB = InteriorKit.WOOD, skirt = InteriorKit.CLAY_D,
            nativeAnchor = null,
            guestSlots = listOf(a(0.6, 1.7, 0.6), a(2.8, 1.7, 1.2)),
            petSpots = emptyList(),
            flavorSpots = listOf(InteriorFlavorSpot(a(-3.4, 1.1, -2.8), "eat_stove")),
            steamSpots = listOf(a(-3.7, 1.5, -2.8), a(-3.5, 1.5, -2.7)),
        ) { lit, emis ->
            with(InteriorKit) {
                stove(lit, -3.4, -2.8)
                counterUnit(lit, -1.2, -2.9, 2.0, 0.75, CREAM, WOOD_L)
                cup(lit, -1.7, -2.85, 1.03); cup(lit, -1.2, -2.95, 1.03); cup(lit, -0.7, -2.85, 1.03) // 三碗
                litBox(lit, 1.6, 1.5, RZ0 + 0.06, 1.5, 1.1, 0.08, rgb(0x2F2A26)) // 菜单黑板
                for ((my, mw) in arrayOf(1.85 to 0.8, 2.1 to 1.0, 1.6 to 0.6)) emisQuad(emis, p(0.95, my, RZ0 + 0.11), p(0.95 + mw, my, RZ0 + 0.11), p(0.95 + mw, my + 0.05, RZ0 + 0.11), p(0.95, my + 0.05, RZ0 + 0.11), rgb(0xCBBFAE))
                litBox(lit, -2.4, 2.2, -2.6, 1.6, 0.06, 0.06, BRASS) // 腊味杆
                for ((hx, hc) in arrayOf(-2.9 to 0xA9563F, -2.4 to 0x8A4E33, -1.9 to 0xA9563F)) litBox(lit, hx, 1.8, -2.6, 0.14, 0.4, 0.14, rgb(hc)) // 挂物三
                // 方桌三 + 条凳 + 杯烛
                for (t in arrayOf(p(0.6, 0.0, 0.6), p(2.8, 0.0, 1.2), p(-1.6, 0.0, 1.2))) {
                    val tx = t[0]; val tz = t[2]
                    table(lit, tx, tz, 0.95)
                    bench(lit, tx - 0.75, tz, 1.3); bench(lit, tx + 0.75, tz, 1.3)
                    cup(lit, tx - 0.14, tz, 0.79); candle(lit, emis, tx + 0.14, tz, 0.79)
                }
                for (lx in arrayOf(-2.0, 0.0, 2.0)) emisBox(emis, lx, 2.6, 3.1, 0.22, 0.22, 0.22, LAMP) // 灯笼串三
                pendant(lit, emis, 0.8, 0.9, 1.3)
            }
        },
    )
}
