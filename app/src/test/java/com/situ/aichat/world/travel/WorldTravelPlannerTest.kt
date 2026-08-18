package com.situ.aichat.world.travel

import com.situ.aichat.world.WorldIds.TravelModes.BIKE
import com.situ.aichat.world.WorldIds.TravelModes.CAR
import com.situ.aichat.world.WorldIds.TravelModes.PLANE
import com.situ.aichat.world.WorldIds.TravelModes.TRAIN
import com.situ.aichat.world.WorldIds.TravelModes.WALK
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [WorldTravelPlanner] T1-1（纯函数金标·W7 图纸 §7）：§4.2 金标示例表**逐项照抄**（票价 + 耗时 + 选项集与序）+
 * 四个距离带边界（`≤` 语义）+ T_MIN 钳位 + round 半入。断言值直接取自图纸表（表已脚本互证），不照搬实现。
 *
 * 耗时以**精确毫秒**断言（durationMs）；图纸表的「耗时（分）」列 = `floor(durationMs / 60000)`——两者一并核对
 * （唯一非整分行 d=150 train = 1_350_000ms = 22.5 分 → 表列 22·精确 ms 锁死不许被四舍五入吞掉）。
 */
class WorldTravelPlannerTest {

    /** 图纸 §4.2 金标示例表：距离 → 选项（模式·票价金·耗时毫秒）·序即呈现序。 */
    private val goldTable: List<Pair<Int, List<TravelOption>>> = listOf(
        3 to listOf(TravelOption(WALK, 1_080_000L, 0), TravelOption(BIKE, 360_000L, 0)),
        8 to listOf(TravelOption(WALK, 2_880_000L, 0), TravelOption(BIKE, 960_000L, 0)),
        40 to listOf(TravelOption(BIKE, 4_800_000L, 0), TravelOption(CAR, 1_200_000L, 7)),
        150 to listOf(TravelOption(CAR, 4_500_000L, 13), TravelOption(TRAIN, 1_350_000L, 30)),
        600 to listOf(TravelOption(CAR, 18_000_000L, 35), TravelOption(TRAIN, 5_400_000L, 75), TravelOption(PLANE, 1_440_000L, 190)),
        1000 to listOf(TravelOption(TRAIN, 9_000_000L, 115), TravelOption(PLANE, 2_400_000L, 290)),
        4000 to listOf(TravelOption(TRAIN, 36_000_000L, 415), TravelOption(PLANE, 9_600_000L, 1040)),
    )

    /** 图纸 §4.2 表的「耗时（分）」列（= floor(durationMs/60000)·逐项互证表列真值）。 */
    private val goldMinutes: Map<Int, List<Int>> = mapOf(
        3 to listOf(18, 6), 8 to listOf(48, 16), 40 to listOf(80, 20),
        150 to listOf(75, 22), 600 to listOf(300, 90, 24), 1000 to listOf(150, 40), 4000 to listOf(600, 160),
    )

    @Test
    fun `E1 金标示例表逐项_票价+耗时+选项集与序`() {
        for ((d, expected) in goldTable) {
            assertEquals("d=$d 选项集与序", expected, WorldTravelPlanner.optionsFor(d))
        }
    }

    @Test
    fun `E1 图纸表耗时分列互证_floor(ms÷60000)`() {
        for ((d, minutes) in goldMinutes) {
            val actual = WorldTravelPlanner.optionsFor(d).map { (it.durationMs / 60_000L).toInt() }
            assertEquals("d=$d 表列耗时（分）", minutes, actual)
        }
    }

    @Test
    fun `E2 距离带边界_落上带（≤ 语义）`() {
        assertEquals(listOf(WALK, BIKE), modes(12))
        assertEquals(listOf(BIKE, CAR), modes(13))
        assertEquals(listOf(BIKE, CAR), modes(60))
        assertEquals(listOf(CAR, TRAIN), modes(61))
        assertEquals(listOf(CAR, TRAIN), modes(250))
        assertEquals(listOf(CAR, TRAIN, PLANE), modes(251))
        assertEquals(listOf(CAR, TRAIN, PLANE), modes(900))
        assertEquals(listOf(TRAIN, PLANE), modes(901))
    }

    @Test
    fun `T_MIN 钳位_极近距离耗时至少 5 分钟`() {
        // d=2 bike：4 分 → 钳到 5 分（图纸 §4.2 表末行）。
        assertEquals(300_000L, WorldTravelPlanner.durationOf(BIKE, 2))
        // d=1 走路：6 分 → 不钳（>5 分）。
        assertEquals(360_000L, WorldTravelPlanner.durationOf(WALK, 1))
        // d=0 任何模式：0 → 钳到 5 分。
        assertEquals(300_000L, WorldTravelPlanner.durationOf(PLANE, 0))
    }

    @Test
    fun `round 半入向上_d150 车 7_5 进为 8`() {
        // cost = 5 + round(150×0.05) = 5 + round(7.5) = 5 + 8 = 13（半入向上·非截断 12）。
        assertEquals(13, WorldTravelPlanner.costOf(CAR, 150))
        // train d=150：15 + round(15.0) = 30（整数无半入争议）。
        assertEquals(30, WorldTravelPlanner.costOf(TRAIN, 150))
    }

    private fun modes(d: Int) = WorldTravelPlanner.optionsFor(d).map { it.mode }
}
