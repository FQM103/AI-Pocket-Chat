package com.situ.aichat.ui.world.interior

import com.situ.aichat.ui.world.continent.rgb

/**
 * 陶丘 4 间室内布局（W9d 图纸 §4.2C·作者落值·装机复审）：千窑坡窑房 / 釉色市集 / 陶心工坊 / 火塘茶肆。
 * 房壳/窗景/降水由 [InteriorSceneData.of] 统一补，本表 build 只摆家具。坐标/尺寸/色逐值照 §4.2C 表。
 */
internal object InteriorLayoutTaoqiu {

    private fun a(x: Double, y: Double, z: Double) = InteriorAnchor(x.toFloat(), y.toFloat(), z.toFloat())

    val ROOMS: Map<String, InteriorRoomDef> = mapOf(
        // ───────────── 千窑坡窑房 ─────────────
        "taoqiu_kiln" to InteriorRoomDef(
            cityId = "city_taoqiu", placeName = "千窑坡",
            plankA = rgb(0xA88A68), plankB = rgb(0x99794F), skirt = rgb(0x8A4E33),
            nativeAnchor = a(1.7, 1.8, -2.0),
            guestSlots = listOf(a(-2.6, 1.7, 1.6), a(0.9, 1.5, 1.2)),
            petSpots = emptyList(),
            flavorSpots = listOf(InteriorFlavorSpot(a(0.0, 0.9, -1.3), "kiln_mouth")),
            steamSpots = emptyList(),
        ) { lit, emis ->
            with(InteriorKit) {
                litBox(lit, 0.0, 0.0, -2.4, 2.6, 1.8, 2.0, rgb(0xA67B5C)) // 主窑
                litBox(lit, 0.0, 1.8, -2.4, 1.8, 0.8, 1.4, rgb(0xA67B5C)) // 顶收
                kilnMouth(emis, 0.0, 0.5, -1.35, 0.5, 0.6)
                litQuad(lit, p(-0.8, 0.016, -0.3), p(0.8, 0.016, -0.3), p(0.8, 0.016, -1.9), p(-0.8, 0.016, -1.9), rgb(0xB87A4E)) // 火光池 1.6×1.6
                for (i in 0..2) litBox(lit, -2.2, i * 0.3, -2.8, 1.2 - 0.2 * i, 0.3, 0.8, WOOD_D) // 柴堆三层
                val ware = listOf(CLAY, CREAM, rgb(0x8E9AA6))
                shelfUnit(lit, 4.6, -1.0, 0.9, 2.0, 0.4, listOf(0.5, 1.1, 1.7), ware)
                shelfUnit(lit, 4.6, 1.4, 0.9, 2.0, 0.4, listOf(0.5, 1.1, 1.7), ware)
                counterUnit(lit, -2.6, 0.8, 2.2, 0.9, WOOD, WOOD_L) // 工作台
                litBox(lit, -3.1, 1.06, 0.8, 0.3, 0.3, 0.3, CLAY_D); litBox(lit, -2.2, 1.06, 0.7, 0.26, 0.24, 0.26, CLAY_D) // 陶坯二
                stool(lit, -1.4, 1.4); stool(lit, 0.9, 1.0)
                litBox(lit, -4.2, 0.0, -0.6, 1.2, 0.5, 0.8, DARKCLAY) // 泥槽
                pendant(lit, emis, 0.0, 0.2, 1.2)
            }
        },
        // ───────────── 釉色市集 ─────────────
        "taoqiu_market" to InteriorRoomDef(
            cityId = "city_taoqiu", placeName = "釉色市集",
            plankA = rgb(0xA88A68), plankB = rgb(0x99794F), skirt = rgb(0x9A5B3E),
            nativeAnchor = null,
            guestSlots = listOf(a(-0.6, 1.6, -0.4), a(1.8, 1.6, -0.2)),
            petSpots = emptyList(),
            flavorSpots = listOf(InteriorFlavorSpot(a(-0.6, 1.0, -1.2), "market_stall")),
            steamSpots = emptyList(),
        ) { lit, emis ->
            with(InteriorKit) {
                val glaze = listOf(0x7E926E, 0x8E9AA6, 0xB07E68, 0xC2A26B, 0xD9C3A3)
                for (m in arrayOf(p(-3.0, 0.0, -1.2), p(-0.6, 0.0, -1.2), p(1.8, 0.0, -1.2), p(-1.8, 0.0, 1.4))) {
                    stallTable(lit, m[0], m[2])
                    for (i in 0..4) litBox(lit, m[0] - 0.6 + i * 0.3, 1.0, m[2], 0.22, 0.2, 0.22, rgb(glaze[i])) // 釉器五（§11：两排落 5 只一行·复审）
                }
                litBox(lit, 4.3, 0.0, 2.4, 0.7, 0.5, 0.7, WOOD_D); litBox(lit, 4.3, 0.5, 2.4, 0.6, 0.45, 0.6, WOOD); litBox(lit, 3.6, 0.0, 2.5, 0.65, 0.45, 0.65, WOOD_D) // 货箱堆
                counterUnit(lit, 2.6, -2.9, 2.0, 0.8, CLAY, DARKCLAY) // 后柜
                litQuad(lit, p(-0.9, 0.012, 2.9), p(0.9, 0.012, 2.9), p(0.9, 0.012, -2.3), p(-0.9, 0.012, -2.3), rgb(0xC9B18A)) // 中央走道毯
                for (lx in arrayOf(-2.2, 0.0, 2.2)) emisBox(emis, lx, 2.6, 3.1, 0.22, 0.22, 0.22, LAMP) // 灯笼串三
                pendant(lit, emis, -1.5, -0.6, 1.1); pendant(lit, emis, 2.0, 0.8, 1.1)
            }
        },
        // ───────────── 陶心工坊 ─────────────
        "taoqiu_shop" to InteriorRoomDef(
            cityId = "city_taoqiu", placeName = "陶心工坊",
            plankA = InteriorKit.WOOD_L, plankB = InteriorKit.WOOD, skirt = rgb(0x8A4E33),
            nativeAnchor = a(0.3, 1.7, 0.4),
            guestSlots = listOf(a(2.6, 1.7, 2.4), a(-1.6, 1.5, 1.2)),
            petSpots = emptyList(),
            flavorSpots = listOf(InteriorFlavorSpot(a(-0.6, 0.7, 0.4), "shop_wheel")),
            steamSpots = emptyList(),
        ) { lit, emis ->
            with(InteriorKit) {
                litBox(lit, -0.6, 0.0, 0.4, 0.5, 0.4, 0.5, DARKCLAY) // 拉坯轮
                litBox(lit, -0.6, 0.4, 0.4, 0.8, 0.1, 0.8, rgb(0xB9AFA6)) // 盘
                litBox(lit, -0.6, 0.5, 0.4, 0.25, 0.3, 0.25, CLAY_D) // 坯
                shelfUnit(lit, -3.6, -2.8, 2.4, 1.8, 0.45, listOf(0.45, 1.0, 1.5), listOf(CREAM)) // 晾坯架素坯
                val vat = listOf(p(3.8, 0.0, -2.7) to 0x7E926E, p(4.2, 0.0, -2.5) to 0x8E9AA6, p(4.0, 0.0, -2.1) to 0xB07E68) // 釉桶三
                for ((vp, vc) in vat) litBox(lit, vp[0], 0.0, vp[2], 0.4, 0.4, 0.4, rgb(vc))
                counterUnit(lit, 2.6, 1.6, 2.0, 0.9, WOOD, WOOD_L) // 工作台
                for (tx in arrayOf(2.2, 2.5, 2.8)) litBox(lit, tx, 1.06, 1.6, 0.1, 0.06, 0.3, BRASS) // 工具三
                stool(lit, -1.6, 1.2); stool(lit, 0.3, 0.9)
                litBox(lit, 4.3, 0.0, -0.8, 1.4, 1.2, 1.2, rgb(0xA67B5C)) // 小窑
                kilnMouth(emis, 4.3, 0.35, -0.19, 0.3, 0.3)
                litQuad(lit, p(-1.9, 0.012, 1.5), p(0.7, 0.012, 1.5), p(0.7, 0.012, -0.7), p(-1.9, 0.012, -0.7), rgb(0x9A8570)) // 中央毯
                pendant(lit, emis, -0.6, 0.4, 1.15); pendant(lit, emis, 2.6, 1.6, 0.95)
            }
        },
        // ───────────── 火塘茶肆 ─────────────
        "taoqiu_tea" to InteriorRoomDef(
            cityId = "city_taoqiu", placeName = "火塘茶肆",
            plankA = InteriorKit.WOOD, plankB = rgb(0x7A5E45), skirt = rgb(0x6B5A50),
            nativeAnchor = null,
            guestSlots = listOf(a(-2.2, 1.4, 1.5), a(2.0, 1.4, 1.7)),
            petSpots = emptyList(),
            flavorSpots = listOf(InteriorFlavorSpot(a(0.0, 0.8, 0.0), "tea_hearth")),
            steamSpots = listOf(a(0.0, 1.7, 0.0)),
        ) { lit, emis ->
            with(InteriorKit) {
                hearth(lit, emis, 0.0, 0.0)
                for (t in arrayOf(p(-2.2, 0.0, 0.8), p(2.0, 0.0, 1.0), p(0.2, 0.0, -2.0))) { // 矮桌三
                    val tx = t[0]; val tz = t[2]
                    litBox(lit, tx, 0.34, tz, 1.1, 0.07, 1.1, WOOD)   // 面
                    litBox(lit, tx, 0.0, tz, 1.1, 0.34, 1.1, WOOD_D)  // 腿隐实心座
                    litBox(lit, tx - 0.95, 0.0, tz, 0.7, 0.15, 0.7, CLAY)  // 坐垫左
                    litBox(lit, tx + 0.95, 0.0, tz, 0.7, 0.15, 0.7, MOSS)  // 坐垫右
                }
                shelfUnit(lit, -2.8, -2.9, 2.2, 2.0, 0.5, listOf(0.5, 1.1, 1.7), listOf(BRASS, rgb(0x7E926E), rgb(0x9A8570))) // 茶柜
                counterUnit(lit, 2.8, -2.9, 1.8, 0.8, CLAY, DARKCLAY) // 柜台
                for (cx in arrayOf(2.4, 2.7, 3.0, 3.3)) cup(lit, cx, -2.9, 1.03) // 茶具四
                emisBox(emis, -2.0, 2.3, 0.6, 0.26, 0.26, 0.26, LAMP); emisBox(emis, 2.2, 2.3, -0.4, 0.26, 0.26, 0.26, LAMP) // 灯笼二
            }
        },
    )
}
