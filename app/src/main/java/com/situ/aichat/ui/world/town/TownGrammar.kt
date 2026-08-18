package com.situ.aichat.ui.world.town

import com.situ.aichat.ui.world.continent.rgb

/**
 * 屋顶样式（图纸 §3.1·M5 面板①三型）：
 * - [GABLE] 双坡脊（脊沿 x·两坡面 + 两山墙·= board:L77 `gable`·几何同复用的 continent `roof` 原语）；
 * - [PYRAMID] 四坡锥（矩形底 + 单顶点·= board:L85 `pyramid`·发射器为 [TownGeometry] 新增·非现 `roof`）；
 * - [FLAT] 平顶女儿墙（底板 + 两侧墙 = 三盒·= board:L124 flat 分支）。
 *
 * ⚠ 命名对齐 M5 board 语义（视觉裁判·§9 冲突时 M5 胜），非图纸 §2 措辞——详见 §11 施工日志「屋顶命名歧义」。
 */
internal enum class RoofStyle { GABLE, PYRAMID, FLAT }

/**
 * 一栋语法建筑拆出的一件原语（[TownGrammar] 产出·[TownGeometry] 发射·零 GL / 零 Android）。坐标 **corner-anchored**
 * （`(x,z)` = 底座近角·`y` = 底）——与 M5 board `box()/gable()/pyramid()/win()` 同锚（board:L69 等）；发射时由
 * [TownGeometry] 换算成 continent 原语的 center-anchored 入参。
 */
internal sealed interface GrammarPart {
    /** lit 盒的语义角色（发射无关·仅便于阅读与 T1 分布点验）。 */
    enum class BoxRole { WALL, WALL_UPPER, CHIMNEY, PORCH_POST, PORCH_ROOF, SIGN }

    /** lit 盒（墙体 / 二层 / 烟囱 / 门廊柱 / 门廊顶板 / 招牌·[role] 标语义）。 */
    class LitBox(
        val x: Double, val y: Double, val z: Double, val sx: Double, val h: Double, val sz: Double, val col: DoubleArray,
        val role: BoxRole,
    ) : GrammarPart

    /** 屋顶（[GABLE]/[PYRAMID] = 单件 emitter·[FLAT] = parapet 三盒由 [TownGeometry] 展开·[h] = 屋顶高）。 */
    class Roof(
        val style: RoofStyle,
        val x: Double, val y: Double, val z: Double, val sx: Double, val h: Double, val sz: Double, val col: DoubleArray,
    ) : GrammarPart

    /** 发光窗（emis 盒·board `win()`:L104 = 0.28×0.34×0.06）。 */
    class EmisBox(
        val x: Double, val y: Double, val z: Double, val sx: Double, val h: Double, val sz: Double, val col: DoubleArray,
    ) : GrammarPart
}

/**
 * 建筑语法（图纸 §3.1·M5 面板①逐参数）：种子 → 一栋建筑的原语件列表（墙体盒 + 屋顶 + 烟囱 + 门廊 + 招牌 + 窗）。
 * 纯函数·零 Android·抽卡顺序与域 / 概率逐一对齐 board `drawGrammarBuilding`（board:L112-131·§9 冲突时 M5 胜）。
 * LCG 与工程同参（1103515245 / 12345 / 2^31·= board:L107）·种子 = `cityId.hashCode() xor worldSeed`（[seedOf]）。
 */
internal object TownGrammar {

    /** 墙板六色族（图纸 §3.1·= board:L110·前四 = 云野镇手表现值）。 */
    private val WALL = intArrayOf(0xC99A86, 0xB98A6E, 0xC4A484, 0x8E9AA6, 0xA48E7A, 0xB59A8A)

    /** 顶板六色族（图纸 §3.1·= board:L111·前三 = 手表现值）。 */
    private val ROOF = intArrayOf(0x9A5B3E, 0x8A4E33, 0x5C6B7C, 0x7A5A44, 0x6B5A50, 0x8A6B4E)

    private val CHIMNEY = rgb(0x7A6455)
    private val SIGN = rgb(0xD4B96A)
    private val WINDOW = rgb(0xFFD9A0)

    /**
     * 线性同余（board:L107·`s=(1103515245*s+12345)%2^31; return s/2^31`）。Long 精确实现同参 LCG；board JS
     * 大 s 时有 float64 精度损失，两侧随机流不必逐位等价——契约是工程自身确定性（E1）+ §3.1 分布，均有 T1 覆盖。
     * 种子经 [seedOf] 派生。
     */
    class Lcg(seed: Long) {
        private var s = seed and 0xFFFFFFFFL   // board `s>>>0`：截 32 位无符号
        fun next(): Double {
            s = (1103515245L * s + 12345L) and 0x7FFFFFFFL   // `% 2^31`：s≥0 故与位与等价
            return s.toDouble() / 2147483648.0
        }
    }

    /** 语法种子（图纸 §3.1）：`cityId.hashCode() xor worldSeed`。 */
    fun seedOf(cityId: String, worldSeed: Long): Long = cityId.hashCode().toLong() xor worldSeed

    /**
     * 一栋语法建筑（底座近角 = ([x],[z])·底 y=0）→ 原语件列表。抽卡序 = board:L112-131 逐一
     * （wall 色 → roof 色 → sx → sz → floors → h → roofT → 墙 → 顶 → 烟囱 → 门廊 → 招牌 → 窗），§3.1 域 / 概率一字不改。
     */
    fun building(rnd: Lcg, x: Double, z: Double): List<GrammarPart> {
        val parts = ArrayList<GrammarPart>(8)
        val wall = rgb(WALL[(rnd.next() * 6).toInt()])
        val roofC = rgb(ROOF[(rnd.next() * 6).toInt()])
        val sx = 2.2 + rnd.next() * 1.6        // [2.2, 3.8]
        val sz = 1.9 + rnd.next() * 1.2        // [1.9, 3.1]
        val floors = if (rnd.next() < 0.3) 2 else 1
        val h = (1.6 + rnd.next() * 0.8) * floors * (if (floors == 2) 0.82 else 1.0)
        val rt = rnd.next().let { if (it < 0.45) RoofStyle.GABLE else if (it < 0.8) RoofStyle.PYRAMID else RoofStyle.FLAT }

        parts += GrammarPart.LitBox(x, 0.0, z, sx, h, sz, wall, GrammarPart.BoxRole.WALL)                 // 一层墙
        if (floors == 2) {
            parts += GrammarPart.LitBox(x + 0.1, h, z + 0.1, sx - 0.2, h * 0.7, sz - 0.2, wall, GrammarPart.BoxRole.WALL_UPPER)  // 二层缩进 0.1·高 ×0.7
        }
        val topY = if (floors == 2) h + h * 0.7 else h

        // 屋顶（gable / pyr 各消耗 1 rnd 定高·flat 无·出檐 +0.3 / 平顶 +0.2·board:L122-124）。
        parts += when (rt) {
            RoofStyle.GABLE -> GrammarPart.Roof(rt, x - 0.15, topY, z - 0.15, sx + 0.3, 0.9 + rnd.next() * 0.4, sz + 0.3, roofC)
            RoofStyle.PYRAMID -> GrammarPart.Roof(rt, x - 0.15, topY, z - 0.15, sx + 0.3, 1.0 + rnd.next() * 0.3, sz + 0.3, roofC)
            RoofStyle.FLAT -> GrammarPart.Roof(rt, x - 0.1, topY, z - 0.1, sx + 0.2, 0.18, sz + 0.2, roofC)
        }

        // 烟囱 P=0.55（0.32×0.7×0.32·board:L125·平顶顶偏移 0.4 / 坡顶 1.0）。
        if (rnd.next() < 0.55) {
            parts += GrammarPart.LitBox(x + sx * 0.65, topY + (if (rt == RoofStyle.FLAT) 0.4 else 1.0), z + sz * 0.3, 0.32, 0.7, 0.32, CHIMNEY, GrammarPart.BoxRole.CHIMNEY)
        }
        // 门廊 P=0.40（柱 0.5 深×0.9 高 + 顶板·board:L126）。
        if (rnd.next() < 0.4) {
            parts += GrammarPart.LitBox(x - 0.5, 0.0, z + sz * 0.25, 0.5, 0.9, sz * 0.5, wall, GrammarPart.BoxRole.PORCH_POST)
            parts += GrammarPart.LitBox(x - 0.6, 0.9, z + sz * 0.2, 0.7, 0.12, sz * 0.6, roofC, GrammarPart.BoxRole.PORCH_ROOF)
        }
        // 招牌 P=0.35（0.08×0.5×0.5·贴墙·board:L127）。
        if (rnd.next() < 0.35) {
            parts += GrammarPart.LitBox(x + sx + 0.02, 1.0, z + sz * 0.4, 0.08, 0.5, 0.5, SIGN, GrammarPart.BoxRole.SIGN)
        }
        // 窗 1–3（board:L128-129·前面 z+sz+0.01·y=0.6·横向等分）。
        val wn = 1 + (rnd.next() * 3).toInt()
        for (i in 0 until wn) {
            val wx = x + 0.4 + i * (sx - 0.8) / maxOf(1, wn - 1)
            parts += GrammarPart.EmisBox(wx, 0.6, z + sz + 0.01, 0.28, 0.34, 0.06, WINDOW)
        }
        return parts
    }
}
