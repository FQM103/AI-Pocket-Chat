package com.situ.aichat.ui.world.town

import com.situ.aichat.ui.world.continent.rgb

/** 水体档（§4.1·西河 = demo:L146 河同值 / 东海 = 汐屿海同值 / 无水）。几何由 [TownGeometry] 按档铺定值 quad。 */
internal enum class TownWater { NONE, WEST_RIVER, EAST_SEA }

/** 可点建筑地点：box + roof(sx×1.12, 1.1, sz×1.16) + 发光窗（demo:L169-171）。锚高 = [h]+1.1。 */
internal class TownBuilding(
    val cx: Double, val cz: Double, val sx: Double, val h: Double, val sz: Double,
    val wall: DoubleArray, val roof: DoubleArray, val windows: Int,
)

/** 填充民居（背景·不可点·demo:L184-186）：box 2.4×1.8×2.1 + roof 2.7×0.9×2.45 `#6B5A50` + 1 窗。 */
internal class TownFiller(val cx: Double, val cz: Double, val wall: DoubleArray)

/** 灯柱（demo:L138-139）：lit 柱 0.14×1.6×0.14 `#4A4038` + emis 灯头 0.3³ `#FFD9A0`。[baseY] = 底座 y（台上灯用）。 */
internal class TownLantern(val cx: Double, val cz: Double, val baseY: Double = 0.0)

/** 树（demo:L125-132）：trunk box 0.28s×[trunkH]s×0.28s `#6B5138` + 四面锥 r0.85s×[coneH]s。普通 0.7/1.5·椰树 1.3/1.1。 */
internal class TownTree(
    val cx: Double, val cz: Double, val s: Double, val leaf: DoubleArray, val trunkH: Double, val coneH: Double,
)

/** 通用盒（码头 / 窑 / 高台 / 石板 / 灯塔 / 长椅 / 礁石 / 栈道…·lit 或 emis 流按所属列表分）。 */
internal class TownBox(
    val cx: Double, val y0: Double, val cz: Double, val sx: Double, val h: Double, val sz: Double, val col: DoubleArray,
)

/** 通用锥（滩伞面等·lit 流·demo cone 原语）。 */
internal class TownCone(val cx: Double, val y: Double, val cz: Double, val r: Double, val h: Double, val col: DoubleArray)

/**
 * 一座小镇的几何布局（[TownGeometry.buildTown] 消费·精修=查表·程序=种子生成）。[grammar] = 语法建筑原语件
 * （§3.1·[TownGrammar] 产出·程序城 / 精修补充段填充·默认空 = 精修主体逐字节不变）。
 */
internal class TownLayoutSpec(
    val ground: DoubleArray,
    val water: TownWater,
    val buildings: List<TownBuilding>,
    val fillers: List<TownFiller>,
    val lanterns: List<TownLantern>,
    val trees: List<TownTree>,
    val litBoxes: List<TownBox>,
    val emisBoxes: List<TownBox>,
    val cones: List<TownCone>,
    val grammar: List<GrammarPart> = emptyList(),
)

/** 精修城布局表：网格中心（[centerGx]/[centerGy]·G 映射用）+ 几何 [spec] + 每地点锚高 [placeTops]（placeId→top）。 */
internal class TownLayoutTable(
    val centerGx: Double, val centerGy: Double,
    val spec: TownLayoutSpec,
    val placeTops: Map<String, Double>,
)

/**
 * 三精修城手写布局表（W9c 图纸 §4.1A/B/C 逐值·§9 锁死）——云野镇 = 对版 demo `design/world/town-3d-demo.html`
 * 逐值移植；陶丘 / 汐屿 = 图纸作者按 demo 框架落值（装机后随六新区调色一并用户复审·图纸 §0）。
 *
 * 坐标经城内里网格 `G(gx,gy) = ((gx−cx0)×3.6, (gy−cy0)×3.6)`（demo:L142）在建表时解析为盒景世界坐标；三城
 * 中心（图纸 §3.3 锁死）：云野镇 (5.5, 6.5)、陶丘 (6.0, 5.8)、汐屿 (5.6, 7.0)。地点坐标单源 = 图集
 * `WorldCuratedCities.PLACES`（[TownSceneData] 侧经同一 G 映射复算·本表只提供每地点的锚高 [TownLayoutTable.placeTops]
 * 与几何）。颜色以 **DoubleArray**（0..1·= demo `C(hex)` float64）经复用的 [rgb] 落值。程序城布局不在此表（种子
 * 生成·见 [TownSceneData]）。
 */
internal object TownLayout {

    /** 精修城布局表（`null` = 非精修城·走程序生成）。 */
    fun tableOf(cityId: String): TownLayoutTable? = when (cityId) {
        "city_yunye" -> YUNYE
        "city_taoqiu" -> TAOQIU
        "city_xiyu" -> XIYU
        else -> null
    }

    /**
     * 氛围补充段的一栋边缘民居（§3.4·手写常量·= 街区边缘民居同式：矮 1.4 高 + gable·墙 #A48E7A·顶 #6B5A50）。
     * 补充件只追加不改手表现值（E6）；远景层由 [TownRenderer] 对所有城统一供给（§3.3·非精修 spec 携带）。坐标为
     * 作者落值（记忆点外空地·全件界内 ±20·可复审可微调·装机对照见 §11）。
     */
    private fun edgeHouse(x: Double, z: Double): List<GrammarPart> = listOf(
        GrammarPart.LitBox(x, 0.0, z, 2.0, 1.4, 1.6, rgb(0xA48E7A), GrammarPart.BoxRole.WALL),
        GrammarPart.Roof(RoofStyle.GABLE, x - 0.15, 1.4, z - 0.15, 2.3, 0.7, 1.8, rgb(0x6B5A50)),
    )

    // ─────────────────────────── 云野镇（demo 逐值·中心 5.5,6.5）───────────────────────────

    private val YUNYE: TownLayoutTable by lazy {
        val cx0 = 5.5; val cy0 = 6.5
        fun g(gx: Number, gy: Number) = (gx.toDouble() - cx0) * 3.6 to (gy.toDouble() - cy0) * 3.6
        val buildings = mutableListOf<TownBuilding>()
        val tops = mutableMapOf<String, Double>()
        fun bldg(id: String, gx: Int, gy: Int, sx: Double, h: Double, sz: Double, wall: Int, roof: Int, win: Int) {
            val (x, z) = g(gx, gy)
            buildings += TownBuilding(x, z, sx, h, sz, rgb(wall), rgb(roof), win)
            tops[id] = h + 1.1
        }
        bldg("yunye_home", 6, 7, 3.2, 2.4, 2.8, 0xC99A86, 0x9A5B3E, 3)
        bldg("yunye_cafe", 5, 6, 3.4, 2.2, 2.6, 0xB98A6E, 0x8A4E33, 3)
        bldg("yunye_book", 7, 6, 2.8, 2.6, 2.4, 0x8E9AA6, 0x5C6B7C, 2)
        bldg("yunye_eat", 6, 5, 2.6, 2.0, 2.2, 0xC4A484, 0x9A5B3E, 2)
        tops["yunye_square"] = 3.6; tops["yunye_park"] = 2.6; tops["yunye_dock"] = 1.2

        val (dx, dz) = g(3, 5)   // 渡口码头 = 环境件（demo:L147-150）
        val (sx, sz) = g(5, 7)   // 老槐树广场（demo:L174-175）
        val (px, pz) = g(4, 8)   // 河畔公园（demo:L178-179）
        val lit = listOf(
            TownBox(dx - 1.4, 0.06, dz, 4.2, 0.18, 1.1, rgb(0x8A6B4E)),       // 码头板一
            TownBox(dx - 1.4, 0.06, dz + 1.8, 4.2, 0.18, 0.7, rgb(0x81644A)), // 码头板二
            TownBox(-13.6, 0.12, dz + 0.6, 1.8, 0.5, 0.9, rgb(0x6E7F92)),     // 小船
            TownBox(sx, 0.02, sz, 4.6, 0.12, 4.6, rgb(0xD9C3A3)),             // 广场石板
            TownBox(px + 1.6, 0.0, pz - 0.6, 1.4, 0.42, 0.5, rgb(0x8A6B4E)),  // 公园长椅
        )
        val trees = listOf(
            TownTree(sx, sz, 1.6, rgb(0x7E926E), 0.7, 1.5),        // 广场大槐树
            TownTree(px - 1.2, pz, 1.0, rgb(0x8FA37E), 0.7, 1.5),
            TownTree(px + 0.9, pz + 0.8, 1.2, rgb(0x7E926E), 0.7, 1.5),
            TownTree(px + 0.2, pz - 1.2, 0.8, rgb(0x8FA37E), 0.7, 1.5),
        )
        val fillers = listOf(
            TownFiller(8.6, 3.2, rgb(0xA8917E)), TownFiller(-6.8, -3.8, rgb(0x9C8B8A)),
            TownFiller(6.2, 7.4, rgb(0xAD9781)), TownFiller(-2.2, 7.8, rgb(0xA8917E)),
            TownFiller(9.2, -4.2, rgb(0x9C8B8A)),
        )
        val lanterns = listOf(TownLantern(sx + 2.0, sz + 2.0), TownLantern(-0.2, -4.2), TownLantern(3.6, 0.2))
        // 氛围补充段（§3.4·只追加不改上文·记忆点外空地）：支巷1 + 边缘民居3 + 树6。
        val supLit = listOf(TownBox(7.0, 0.01, 4.0, 1.0, 0.06, 8.0, rgb(0xD0BA9A)))
        val supGrammar = edgeHouse(10.0, 5.5) + edgeHouse(10.0, 8.5) + edgeHouse(-9.0, 8.0)
        val supTrees = listOf(
            TownTree(7.5, -6.0, 0.8, rgb(0x7E926E), 0.7, 1.5), TownTree(3.0, 6.6, 0.7, rgb(0x8FA37E), 0.6, 1.0),
            TownTree(-8.0, 2.4, 0.9, rgb(0x7E926E), 0.7, 1.5), TownTree(8.6, 9.6, 0.5, rgb(0x8FA37E), 0.5, 1.0),
            TownTree(-3.4, -8.4, 0.8, rgb(0x7E926E), 0.7, 1.5), TownTree(11.0, 1.2, 0.7, rgb(0x8FA37E), 0.6, 1.0),
        )
        TownLayoutTable(
            cx0, cy0,
            TownLayoutSpec(rgb(0xC7A987), TownWater.WEST_RIVER, buildings, fillers, lanterns, trees + supTrees, lit + supLit, emptyList(), emptyList(), supGrammar),
            tops,
        )
    }

    // ─────────────────────────── 陶丘（作者落值·中心 6.0,5.8）───────────────────────────

    private val TAOQIU: TownLayoutTable by lazy {
        val cx0 = 6.0; val cy0 = 5.8
        fun g(gx: Number, gy: Number) = (gx.toDouble() - cx0) * 3.6 to (gy.toDouble() - cy0) * 3.6
        val buildings = mutableListOf<TownBuilding>()
        val tops = mutableMapOf<String, Double>()
        fun bldg(id: String, gx: Int, gy: Int, sx: Double, h: Double, sz: Double, wall: Int, roof: Int, win: Int) {
            val (x, z) = g(gx, gy); buildings += TownBuilding(x, z, sx, h, sz, rgb(wall), rgb(roof), win); tops[id] = h + 1.1
        }
        bldg("taoqiu_kiln", 5, 4, 3.4, 2.0, 2.8, 0xB98A6E, 0x8A4E33, 2)
        bldg("taoqiu_market", 6, 6, 3.6, 2.2, 2.8, 0xC9A46B, 0x9A5B3E, 3)
        bldg("taoqiu_shop", 4, 6, 2.8, 2.2, 2.4, 0xC4A484, 0x8A4E33, 2)
        bldg("taoqiu_tea", 7, 5, 2.6, 2.0, 2.2, 0xA8917E, 0x6B5A50, 2)
        tops["taoqiu_view"] = 2.9   // 望原台 = 环境件

        val (kx, kz) = g(5, 4)    // 千窑坡旁二窑
        val (mx, mz) = g(6, 6)    // 釉色市集门前石板
        val (vx, vz) = g(8, 8)    // 望原台高台
        val lit = listOf(
            TownBox(kx - 2.2, 0.0, kz + 0.8, 1.6, 1.2, 1.4, rgb(0xA67B5C)),  // 窑一
            TownBox(kx - 1.0, 0.0, kz + 1.9, 1.6, 1.2, 1.4, rgb(0xA67B5C)),  // 窑二
            TownBox(mx, 0.02, mz + 3.62, 4.0, 0.12, 3.0, rgb(0xD9B98A)),      // 门前石板（作者落值·位置见 §11）
            TownBox(vx, 0.0, vz, 3.0, 1.5, 3.0, rgb(0x9A7B5C)),              // 望原台高台
        )
        val emis = listOf(
            TownBox(kx - 2.2, 1.2, kz + 0.8, 0.3, 0.3, 0.3, rgb(0xFFD9A0)),  // 窑口一
            TownBox(kx - 1.0, 1.2, kz + 1.9, 0.3, 0.3, 0.3, rgb(0xFFD9A0)),  // 窑口二
        )
        val trees = listOf(
            TownTree(-7.5, -1.0, 1.0, rgb(0x6B7A52), 0.7, 1.5),
            TownTree(6.5, 4.2, 0.8, rgb(0x6B7A52), 0.7, 1.5),
        )
        val fillers = listOf(
            TownFiller(2.8, -5.0, rgb(0xA8917E)), TownFiller(-5.8, 1.2, rgb(0x9C8B8A)),
            TownFiller(1.2, 6.8, rgb(0xAD9781)), TownFiller(-4.2, -4.6, rgb(0xA8917E)),
        )
        val lanterns = listOf(
            TownLantern(0.0, 0.0), TownLantern(-3.2, 3.0), TownLantern(4.0, -2.2),
            TownLantern(vx, vz, baseY = 1.5),   // 望原台台上灯（作者落值·见 §11）
        )
        // 氛围补充段（§3.4·只追加不改上文）：边缘民居3 + 树5（无支巷·陶丘档）。
        val supGrammar = edgeHouse(9.5, -3.0) + edgeHouse(9.5, 2.5) + edgeHouse(-9.0, -7.0)
        val supTrees = listOf(
            TownTree(5.0, -8.2, 0.8, rgb(0x6B7A52), 0.7, 1.5), TownTree(-9.2, 4.0, 0.9, rgb(0x6B7A52), 0.6, 1.0),
            TownTree(9.2, 6.4, 0.5, rgb(0x6B7A52), 0.5, 1.0), TownTree(-2.4, 9.2, 0.8, rgb(0x6B7A52), 0.7, 1.5),
            TownTree(2.6, -9.6, 0.7, rgb(0x6B7A52), 0.6, 1.0),
        )
        TownLayoutTable(
            cx0, cy0,
            TownLayoutSpec(rgb(0xCBA379), TownWater.NONE, buildings, fillers, lanterns, trees + supTrees, lit, emis, emptyList(), supGrammar),
            tops,
        )
    }

    // ─────────────────────────── 汐屿（作者落值·中心 5.6,7.0）───────────────────────────

    private val XIYU: TownLayoutTable by lazy {
        val cx0 = 5.6; val cy0 = 7.0
        fun g(gx: Number, gy: Number) = (gx.toDouble() - cx0) * 3.6 to (gy.toDouble() - cy0) * 3.6
        val buildings = mutableListOf<TownBuilding>()
        val tops = mutableMapOf<String, Double>()
        fun bldg(id: String, gx: Int, gy: Int, sx: Double, h: Double, sz: Double, wall: Int, roof: Int, win: Int) {
            val (x, z) = g(gx, gy); buildings += TownBuilding(x, z, sx, h, sz, rgb(wall), rgb(roof), win); tops[id] = h + 1.1
        }
        bldg("xiyu_market", 5, 5, 3.4, 2.2, 2.6, 0xC99A86, 0x5C6B7C, 3)
        bldg("xiyu_hall", 5, 6, 3.0, 2.4, 2.6, 0x8E9AA6, 0x5C6B7C, 2)
        tops["xiyu_beach"] = 2.0; tops["xiyu_walk"] = 1.2; tops["xiyu_cove"] = 1.6

        val (bx, bz) = g(4, 8)   // 落汐滩滩伞
        val (mx, mz) = g(5, 5)   // 灯塔渔市旁灯塔
        val (wx, wz) = g(6, 7)   // 椰风栈道三段板
        val (cx, cz) = g(8, 9)   // 星沙湾礁石
        val lit = listOf(
            TownBox(bx, 0.0, bz, 0.12, 1.4, 0.12, rgb(0x8A6B4E)),            // 滩伞杆
            TownBox(mx + 2.6, 0.0, mz - 1.0, 1.0, 2.6, 1.0, rgb(0xEFEDE9)),  // 灯塔基
            TownBox(mx + 2.6, 2.6, mz - 1.0, 0.7, 1.6, 0.7, rgb(0xD9CBA8)),  // 灯塔身
            TownBox(wx - 2.0, 0.15, wz, 2.0, 0.16, 0.9, rgb(0x8A6B4E)),      // 栈道板一（作者落值·见 §11）
            TownBox(wx, 0.15, wz, 2.0, 0.16, 0.9, rgb(0x8A6B4E)),            // 栈道板二
            TownBox(wx + 2.0, 0.15, wz, 2.0, 0.16, 0.9, rgb(0x8A6B4E)),      // 栈道板三
            TownBox(cx, 0.0, cz, 1.2, 0.8, 1.0, rgb(0x8A8072)),              // 礁石一
            TownBox(cx + 1.0, 0.0, cz + 0.6, 0.8, 0.5, 0.7, rgb(0x8A8072)),  // 礁石二（作者落值·见 §11）
        )
        val emis = listOf(TownBox(mx + 2.6, 4.2, mz - 1.0, 0.35, 0.35, 0.35, rgb(0xFFD9A0)))  // 灯塔顶灯
        val cones = listOf(TownCone(bx, 1.4, bz, 1.1, 0.5, rgb(0xC99A86)))                     // 滩伞面
        val palm = rgb(0x5F9E6E)
        val trees = listOf(
            TownTree(2.5, 3.5, 1.1, palm, 1.3, 1.1), TownTree(-3.0, 5.0, 0.9, palm, 1.3, 1.1),
            TownTree(7.5, 6.5, 1.0, palm, 1.3, 1.1), TownTree(-5.5, -2.0, 0.8, palm, 1.3, 1.1),
        )
        val fillers = listOf(
            TownFiller(-2.0, -4.5, rgb(0xA8917E)), TownFiller(3.5, -3.0, rgb(0x9C8B8A)),
            TownFiller(-6.0, 2.5, rgb(0xAD9781)),
        )
        val lanterns = listOf(TownLantern(0.5, 1.0), TownLantern(-3.5, -1.5))
        // 氛围补充段（§3.4·只追加不改上文·全件 x<10 避东海 x≥13/沙滩 10.5-13）：边缘民居3 + 树5（无支巷·汐屿档）。
        val supGrammar = edgeHouse(-9.0, -5.0) + edgeHouse(-9.0, 1.0) + edgeHouse(-8.0, 6.5)
        val supPalm = rgb(0x5F9E6E)
        val supTrees = listOf(
            TownTree(-7.0, -8.0, 0.9, supPalm, 1.3, 1.1), TownTree(6.0, -6.0, 0.8, supPalm, 1.3, 1.1),
            TownTree(-5.0, 9.2, 1.0, supPalm, 1.3, 1.1), TownTree(0.0, 9.6, 0.7, supPalm, 1.3, 1.1),
            TownTree(-9.5, 4.0, 0.8, supPalm, 1.3, 1.1),
        )
        TownLayoutTable(
            cx0, cy0,
            TownLayoutSpec(rgb(0xE2CFA0), TownWater.EAST_SEA, buildings, fillers, lanterns, trees + supTrees, lit, emis, cones, supGrammar),
            tops,
        )
    }
}
