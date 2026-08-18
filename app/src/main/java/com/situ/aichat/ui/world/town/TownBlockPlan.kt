package com.situ.aichat.ui.world.town

import com.situ.aichat.ui.world.continent.RegionStyle
import com.situ.aichat.ui.world.continent.rgb
import kotlin.math.abs

/**
 * 街区网络规划（图纸 §3.2·M5 面板②右图「目标」档）：种子 → 主街 / 支巷×2 / 广场 / 地标 / 河桥 / 建筑三排 /
 * 边缘民居 / 树 / 灯柱 的确定性布点。纯逻辑·零 Android·零 GL——只产 [Result]（[TownLayoutSpec] 既有容器 +
 * [TownGrammar] 原语件）。
 *
 * 坐标为**生成器落值**（引擎水带 x∈[-16.5,-11.5] 西河 / [13,26] 东海之外·§3.2 只锁宽度 / 色 / 数 / 地标规格），
 * 与 M5 面板②同密度观感（§9 冲突时 M5 胜）——具体世界坐标见 §11 施工日志。E1 确定性 / E2 让位 / E3 预算 /
 * E4 无河回退 / E5 树采样守卫见各处标注。
 */
internal object TownBlockPlan {

    // ── 世界坐标常量（生成器落值·§11 留档）──
    private const val STREET_X0 = -11.0
    private const val STREET_X1 = 12.0
    private const val STREET_Z = 0.0
    private const val SHIFT = 3.6           // 让位「1 格」= 引擎网格步长（§3.2 E2）
    private const val TREE_GUARD = 5000     // E5 拒绝采样守卫

    /** 地标三型（§3.2·钟楼 / 水车 / 风车）。 */
    internal enum class LandmarkKind { CLOCK, WATERWHEEL, WINDMILL }

    /** 街区规划产物（喂 [TownLayoutSpec]：litBoxes = 街 / 广场 / 桥 / 水车 / 风车·grammar = 建筑 / 地标·trees / lanterns 直用）。 */
    internal class Result(
        val litBoxes: List<TownBox>,
        val grammar: List<GrammarPart>,
        val trees: List<TownTree>,
        val lanterns: List<TownLantern>,
    )

    /** 地标选择（§3.2·`cityId.hashCode() mod 3`·E4：水车城无西河 → 回退钟楼）。 */
    fun landmarkKindFor(cityId: String, water: TownWater): LandmarkKind =
        when (Math.floorMod(cityId.hashCode(), 3)) {
            0 -> LandmarkKind.CLOCK
            1 -> if (water == TownWater.WEST_RIVER) LandmarkKind.WATERWHEEL else LandmarkKind.CLOCK
            else -> LandmarkKind.WINDMILL
        }

    /** 单镇件数预算硬顶（§3.2 E3）= 现云野镇核心件数（4 楼+5 填充+3 灯+4 树+5 环境=21）×3。 */
    const val DEFAULT_BUDGET = 63

    /**
     * 规划一座程序城街区（图纸 §3.2）。[reserved] = 可点建筑（图集坐标）世界中心·支巷 / 广场撞位让位（E2·程序城恒空）。
     * [budgetCap] 超顶按「树→边缘民居→支巷排」削减（E3）·[warn] 收削减日志（保持零 Android 可测）。
     */
    fun plan(
        worldSeed: Long,
        cityId: String,
        style: RegionStyle,
        water: TownWater,
        reserved: List<Pair<Double, Double>> = emptyList(),
        budgetCap: Int = DEFAULT_BUDGET,
        warn: (String) -> Unit = {},
    ): Result {
        val rnd = TownGrammar.Lcg(TownGrammar.seedOf(cityId, worldSeed))
        val litBoxes = ArrayList<TownBox>()

        // ① 主街（沿 x·宽 1.2·石板 #D9C3A3·§3.2）。
        val streetLen = STREET_X1 - STREET_X0
        litBoxes += TownBox((STREET_X0 + STREET_X1) / 2, 0.01, STREET_Z, streetLen, 0.06, 1.2, rgb(0xD9C3A3))

        // ② 支巷 2 条（垂直主街·宽 1.0·#D0BA9A·位置 = 街长 1/3 与 2/3 处 ±1 格抖动·z 向贯穿北排·E2 让位）。
        val alley = rgb(0xD0BA9A)
        val a1 = shiftIfReserved(STREET_X0 + streetLen / 3 + (rnd.next() * 2 - 1), reserved)
        val a2 = shiftIfReserved(STREET_X0 + streetLen * 2 / 3 + (rnd.next() * 2 - 1), reserved)
        litBoxes += TownBox(a1, 0.01, -4.0, 1.0, 0.06, 12.0, alley)
        litBoxes += TownBox(a2, 0.01, -4.0, 1.0, 0.06, 12.0, alley)

        // ③ 广场（4.6×4.6·石板 #D9C3A3·贴主街南侧支巷交口·E2 让位）。
        val plazaX = shiftIfReserved(a2, reserved)
        litBoxes += TownBox(plazaX, 0.02, 5.0, 4.6, 0.1, 4.6, rgb(0xD9C3A3))

        // ④ 建筑三排（语法生成·北排 5 / 南排 4 / 支巷排 3·§3.2）。收集中心供树避让。
        val centers = ArrayList<Pair<Double, Double>>()
        fun row(n: Int, x0: Double, dx: Double, z: Double): List<List<GrammarPart>> = (0 until n).map { i ->
            val parts = TownGrammar.building(rnd, x0 + i * dx, z)
            val w = parts.filterIsInstance<GrammarPart.LitBox>().first { it.role == GrammarPart.BoxRole.WALL }
            centers += (w.x + w.sx / 2) to (w.z + w.sz / 2)
            parts
        }
        val northRow = row(5, -10.0, 4.4, -4.6)
        val southRow = row(4, -8.0, 4.6, 1.8)
        val alleyRow = row(3, 0.0, 3.9, -8.6)

        // ⑤ 边缘民居 3 栋（矮 1.4 高 gable·墙 #A48E7A·顶 #6B5A50·board:L193）。
        var edgeHouses = (0 until 3).map { i ->
            val ex = 10.0; val ez = -4.0 + i * 3.1
            centers += (ex + 1.0) to (ez + 0.8)
            listOf<GrammarPart>(
                GrammarPart.LitBox(ex, 0.0, ez, 2.0, 1.4, 1.6, rgb(0xA48E7A), GrammarPart.BoxRole.WALL),
                GrammarPart.Roof(RoofStyle.GABLE, ex - 0.15, 1.4, ez - 0.15, 2.3, 0.7, 1.8, rgb(0x6B5A50)),
            )
        }

        // ⑥ 地标 1 座（§3.2·E4 水车无河回退钟楼）。
        val landmark = landmark(landmarkKindFor(cityId, water), litBoxes).also { c -> centers += c.second }
        val landmarkParts = landmark.first

        // ⑦ 河上桥（仅西河·1.6×0.22×2.8·#8A6B4E·§3.2）。
        if (water == TownWater.WEST_RIVER) litBoxes += TownBox(-14.0, 0.1, 0.0, 1.6, 0.22, 2.8, rgb(0x8A6B4E))

        // ⑧ 树 12–18（锥/团/矮三型循环·避街走廊 1.2·避建筑 1.5·拒绝采样 5000 守卫 E5·叶色 style.leafs 轮换）。
        var trees = plantTrees(rnd, style, centers)

        // ⑨ 灯柱 5–6（沿主街等距·现原语零改）。
        val lanterns = ArrayList<TownLantern>()
        val nLantern = 5 + (rnd.next() * 2).toInt()
        for (i in 0 until nLantern) lanterns += TownLantern(STREET_X0 + i * streetLen / (nLantern - 1), -0.9)

        // ⑩ 预算硬顶（E3·件 = 街 / 广场 / 桥 / 水车 / 风车盒 + 三排 + 边缘 + 地标语法墙 + 树 + 灯·超顶按 树→边缘民居→支巷排 削减）。
        val lmWalls = landmarkParts.count { it is GrammarPart.LitBox && it.role == GrammarPart.BoxRole.WALL }
        var alleyRowKept = alleyRow
        fun total() = litBoxes.size + northRow.size + southRow.size + alleyRowKept.size +
            edgeHouses.size + lmWalls + trees.size + lanterns.size
        if (total() > budgetCap) {
            warn("town $cityId 件数 ${total()} 超预算 $budgetCap：削树")
            trees = emptyList()
        }
        if (total() > budgetCap) {
            warn("town $cityId 件数 ${total()} 仍超：削边缘民居")
            edgeHouses = emptyList()
        }
        if (total() > budgetCap) {
            warn("town $cityId 件数 ${total()} 仍超：削支巷排")
            alleyRowKept = emptyList()
        }
        if (total() > budgetCap) warn("town $cityId 件数 ${total()} 仍超预算（核心排 / 街 / 地标不削）")

        val grammar = ArrayList<GrammarPart>()
        (northRow + southRow + alleyRowKept + edgeHouses).forEach { grammar += it }
        grammar += landmarkParts
        return Result(litBoxes, grammar, trees, lanterns)
    }

    /** 撞位让位：[x] 与任一 [reserved] 中心 x 距 < 1.6 → 右移 1 格（E2）。 */
    private fun shiftIfReserved(x: Double, reserved: List<Pair<Double, Double>>): Double =
        if (reserved.any { abs(it.first - x) < 1.6 }) x + SHIFT else x

    /**
     * 地标几何（§3.2）。返回（原语件·中心）。钟楼 = 墙盒 1.7×4.6×1.7 #8E9AA6 + pyr 顶 2.1×1.4×2.1 #5C6B7C + 顶窗；
     * 水车 = 8 盒环列 r1.6 #8A6B4E（写入 [litBoxes]）；风车 = 塔 1.4×3.6×1.4 #A48E7A + 4 叶板（写入 [litBoxes]）。
     */
    private fun landmark(kind: LandmarkKind, litBoxes: ArrayList<TownBox>): Pair<List<GrammarPart>, Pair<Double, Double>> =
        when (kind) {
            LandmarkKind.CLOCK -> {
                val x = -0.85; val z = 7.6
                listOf<GrammarPart>(
                    GrammarPart.LitBox(x, 0.0, z, 1.7, 4.6, 1.7, rgb(0x8E9AA6), GrammarPart.BoxRole.WALL),
                    GrammarPart.Roof(RoofStyle.PYRAMID, x - 0.2, 4.6, z - 0.2, 2.1, 1.4, 2.1, rgb(0x5C6B7C)),
                    GrammarPart.EmisBox(x + 0.65, 4.0, z + 1.72, 0.28, 0.34, 0.06, rgb(0xFFD9A0)),
                ) to (x + 0.85 to z + 0.85)
            }
            LandmarkKind.WATERWHEEL -> {
                // 8×(0.6×0.6×0.3) 盒竖直环列（x-y 面·贴西河 x≈-11·图纸 §3.2 修订 2026-07-07 锁定）。
                val cx = -11.0; val cy = 1.9; val cz = 0.0; val r = 1.6
                for (i in 0 until 8) {
                    val a = i * (2 * Math.PI / 8)
                    litBoxes += TownBox(cx + r * Math.cos(a), cy + r * Math.sin(a) - 0.15, cz, 0.6, 0.6, 0.3, rgb(0x8A6B4E))
                }
                emptyList<GrammarPart>() to (cx to cz)
            }
            LandmarkKind.WINDMILL -> {
                // 塔 1.4×3.6×1.4 + 4 叶 2.2×0.28×0.16 顶端十字（图纸 §3.2 修订 2026-07-07 锁定）。
                val x = -6.0; val z = -8.0; val top = 3.6
                litBoxes += TownBox(x, 0.0, z, 1.4, 3.6, 1.4, rgb(0xA48E7A))
                for (i in 0 until 4) {
                    val horiz = i % 2 == 0
                    litBoxes += TownBox(x, top + 0.2, z, if (horiz) 2.2 else 0.28, if (horiz) 0.28 else 2.2, 0.16, rgb(0xA48E7A))
                }
                emptyList<GrammarPart>() to (x to z)
            }
        }

    /** 树 12–18（§3.2·三型循环·避街 1.2 / 避建筑 1.5·5000 守卫 E5·可少不可崩）。 */
    private fun plantTrees(rnd: TownGrammar.Lcg, style: RegionStyle, centers: List<Pair<Double, Double>>): List<TownTree> {
        val n = 12 + (rnd.next() * 7).toInt()   // 12..18
        val out = ArrayList<TownTree>(n)
        var guard = 0
        var i = 0
        while (i < n && guard < TREE_GUARD) {
            val tx = -10.0 + rnd.next() * 21.0     // [-10, 11]
            val tz = -10.0 + rnd.next() * 17.0     // [-10, 7]
            guard++
            val nearStreet = abs(tz - STREET_Z) < 1.2 && tx in STREET_X0..STREET_X1
            val nearBldg = centers.any { abs(it.first - tx) < 1.5 && abs(it.second - tz) < 1.5 }
            if (nearStreet || nearBldg) continue
            val leaf = style.leafs[i % style.leafs.size]
            out += when (i % 3) {
                0 -> TownTree(tx, tz, 0.8, leaf, 0.7, 1.5)   // 锥
                1 -> TownTree(tx, tz, 0.9, leaf, 0.6, 1.0)   // 团（矮胖冠·现锥原语近似·见 §11）
                else -> TownTree(tx, tz, 0.5, leaf, 0.5, 1.0) // 矮
            }
            i++
        }
        return out
    }
}
