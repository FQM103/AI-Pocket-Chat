package com.situ.aichat.ui.world.interior

import com.situ.aichat.ui.world.continent.rgb

/**
 * 汐屿 2 间室内布局（W9d 图纸 §4.2C·作者落值·装机复审）：灯塔渔市 / 潮声馆。
 * 房壳/窗景/降水由 [InteriorSceneData.of] 统一补，本表 build 只摆家具。坐标/尺寸/色逐值照 §4.2C 表。
 */
internal object InteriorLayoutXiyu {

    private fun a(x: Double, y: Double, z: Double) = InteriorAnchor(x.toFloat(), y.toFloat(), z.toFloat())

    val ROOMS: Map<String, InteriorRoomDef> = mapOf(
        // ───────────── 灯塔渔市 ─────────────
        "xiyu_market" to InteriorRoomDef(
            cityId = "city_xiyu", placeName = "灯塔渔市",
            plankA = rgb(0x8A7B66), plankB = rgb(0x74675A), skirt = rgb(0x5C6B7C),
            nativeAnchor = null,
            guestSlots = listOf(a(-0.8, 1.6, -0.2), a(1.4, 1.6, 0.0)),
            petSpots = emptyList(),
            flavorSpots = listOf(InteriorFlavorSpot(a(-0.8, 1.0, -1.4), "fish_ice")),
            steamSpots = emptyList(),
        ) { lit, emis ->
            with(InteriorKit) {
                for (m in arrayOf(p(-3.0, 0.0, -1.0), p(-0.8, 0.0, -1.4), p(1.4, 0.0, -1.0))) { // 鱼摊三
                    val mx = m[0]; val mz = m[2]
                    stallTable(lit, mx, mz)
                    litBox(lit, mx, 0.91, mz, 1.5, 0.12, 0.8, rgb(0xEDF2F5)) // 冰台
                    for (i in 0..3) litBox(lit, mx + (i - 1.5) * 0.36, 0.97, mz, 0.4, 0.1, 0.16, rgb(if (i % 2 == 0) 0x6E7F92 else 0x8E9AA6)) // 鱼四
                }
                litBox(lit, 0.6, 2.4, -3.3, 4.0, 0.06, 0.06, WOOD_D) // 浮标串后墙杆
                val buoy = listOf(0xC99A86, 0xE8C57E, 0x6E7F92)
                arrayOf(-1.0, -0.2, 0.6, 1.4, 2.2, 3.0).forEachIndexed { i, bx -> litBox(lit, bx, 2.0, -3.3, 0.2, 0.2, 0.2, rgb(buoy[i % 3])) } // 吊球六
                litBox(lit, 4.2, 0.0, -2.5, 0.7, 0.5, 0.7, WOOD_D); litBox(lit, 4.2, 0.5, -2.5, 0.6, 0.45, 0.6, WOOD) // 货箱堆
                litBox(lit, 3.5, 0.0, -2.6, 0.65, 0.45, 0.65, WOOD_D); litBox(lit, 4.25, 0.0, -1.7, 0.6, 0.4, 0.6, WOOD)
                litBox(lit, -4.4, 0.0, -2.7, 0.6, 0.8, 0.6, rgb(0x8A6B4E)); litBox(lit, -4.35, 0.0, -1.9, 0.55, 0.7, 0.55, rgb(0x8A6B4E)) // 木桶二
                counterUnit(lit, 3.0, 1.8, 1.8, 0.7, WOOD_L, WOOD_D) // 收银台
                litBox(lit, 3.3, 1.03, 1.8, 0.3, 0.24, 0.3, BRASS) // 秤
                for (lx in arrayOf(-2.4, -0.2, 2.0)) emisBox(emis, lx, 2.6, 3.1, 0.22, 0.22, 0.22, LAMP) // 灯笼串三
                pendant(lit, emis, 0.0, -0.4, 1.35)
            }
        },
        // ───────────── 潮声馆 ─────────────
        "xiyu_hall" to InteriorRoomDef(
            cityId = "city_xiyu", placeName = "潮声馆",
            plankA = InteriorKit.WOOD, plankB = rgb(0x7A5E45), skirt = rgb(0x5C6B7C),
            nativeAnchor = a(0.0, 1.9, -1.4),
            guestSlots = listOf(a(-4.3, 1.6, 0.4), a(2.6, 1.7, 0.0)),
            petSpots = emptyList(),
            flavorSpots = listOf(InteriorFlavorSpot(a(2.2, 2.0, 3.0), "hall_chime")),
            steamSpots = emptyList(),
        ) { lit, emis ->
            with(InteriorKit) {
                for (s in arrayOf(p(-2.6, 0.0, -0.8), p(0.0, 0.0, -1.4), p(2.6, 0.0, -0.8))) litBox(lit, s[0], 0.0, s[2], 0.9, 1.1, 0.9, CREAM) // 展台三
                // 台上：船模 @展台2·贝壳堆 @展台1·螺号 @展台3
                litBox(lit, 0.0, 1.16, -1.4, 0.6, 0.18, 0.26, rgb(0x6E7F92)) // 船壳
                litTri(lit, p(-0.15, 1.34, -1.4), p(0.15, 1.34, -1.4), p(0.0, 1.76, -1.4), CREAM) // 帆
                // 贝壳堆三（小盒 0.14×0.1×0.14·簇于展台1 顶 y1.1·§11：簇内 x/z 为作者框架内落值）
                for (sh in arrayOf(p(-2.7, -0.8, 0xEDD9AC.toDouble()), p(-2.5, -0.8, 0xC99A86.toDouble()), p(-2.6, -0.7, 0xEDD9AC.toDouble()))) litBox(lit, sh[0], 1.1, sh[1], 0.14, 0.1, 0.14, rgb(sh[2].toInt()))
                litBox(lit, 2.6, 1.1, -0.8, 0.36, 0.16, 0.2, rgb(0xC99A86)) // 螺号
                litBox(lit, -1.4, 2.0, -3.3, 2.6, 0.1, 0.07, WOOD_D); litBox(lit, -1.1, 2.4, -3.3, 2.2, 0.1, 0.07, WOOD) // 后墙挂桨二
                litBox(lit, 2.2, 2.5, 3.1, 1.6, 0.05, 0.05, WOOD_D) // 风铃前檐杆
                for (cx in arrayOf(1.7, 2.2, 2.7)) for (cy in arrayOf(2.3, 2.1, 1.9)) litBox(lit, cx, cy, 3.1, 0.06, 0.18, 0.06, rgb(0xEDD9AC)) // 风铃三串
                litBox(lit, -4.3, 0.0, 0.4, 0.5, 0.42, 2.0, rgb(0x8A6B4E)) // 长椅（沿 z）
                litBox(lit, 4.2, 0.0, -2.6, 0.6, 0.9, 0.6, rgb(0xC2A26B)); litBox(lit, 4.2, 0.9, -2.6, 0.5, 0.4, 0.5, rgb(0x4A4038)) // 播放角
                litQuad(lit, p(-1.5, 0.012, 0.4), p(1.5, 0.012, 0.4), p(1.5, 0.012, -2.0), p(-1.5, 0.012, -2.0), rgb(0x8E9AA6)) // 中央毯
                cornerPlant(lit, -4.6, -2.9)
                pendant(lit, emis, -1.6, -0.6, 1.0); pendant(lit, emis, 2.0, 0.2, 1.0)
            }
        },
    )
}
