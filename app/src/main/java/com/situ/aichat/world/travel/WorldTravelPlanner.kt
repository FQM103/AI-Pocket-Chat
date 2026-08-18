package com.situ.aichat.world.travel

import com.situ.aichat.world.WorldIds
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 一个旅行选项（呈现给用户的一档：模式 + 耗时 + 票价）。
 *
 * [durationMs] = 到达所需毫秒（已含 [WorldTravelPlanner.T_MIN_MS] 钳位）；[costGold] = 票价（金·免费模式恒 0）。
 * 出发结算把 [costGold] 同值写进 `world_travel.costGold`（账面记录）并作为真扣款额。
 */
data class TravelOption(
    val mode: String,
    val durationMs: Long,
    val costGold: Int,
)

/**
 * 旅行公式与选项呈现（契约 §12 决策 32「温和案」/ W7 图纸 §4·**数值全锁死·图纸 §9 禁改·已脚本互证**）。
 *
 * 纯函数、零依赖、零状态（`internal` 便于 T1 直测·同模块 [WorldTravelService] 亦调）：把「距离带 → 2–3 个有意义
 * 选项」和「模式 + 距离 → 票价 / 耗时」两件事逐字锁在这里。所有人（含用户）旅行都花时间，花钱只买更快（决策 10/15）——
 * 走 / 骑免费（费率 0），车 / 动车 / 飞机按 `起步价 + 里程费` 计价。
 *
 * 公式（锁死）：
 * - `cost = base + round(d × rate)`（round = 四舍五入·`roundToInt` 半入向上）；
 * - `duration = max(5 分钟, round(d / v 小时))`（`v` 单位「里/时」·`roundToLong` 半入向上·[T_MIN_MS] 钳位）。
 */
internal object WorldTravelPlanner {

    /** 耗时下限（5 分钟·任何近距离旅行至少这么久·图纸 §4.1）。 */
    const val T_MIN_MS: Long = 300_000L

    /** 单模式常量（`v` 里/时·`rate` 金/里·`base` 金起步价·图纸 §4.1 五行锁死）。 */
    private data class ModeSpec(val speedLiPerHour: Int, val ratePerLi: Double, val baseGold: Int)

    private val SPECS: Map<String, ModeSpec> = mapOf(
        WorldIds.TravelModes.WALK to ModeSpec(speedLiPerHour = 10, ratePerLi = 0.0, baseGold = 0),
        WorldIds.TravelModes.BIKE to ModeSpec(speedLiPerHour = 30, ratePerLi = 0.0, baseGold = 0),
        WorldIds.TravelModes.CAR to ModeSpec(speedLiPerHour = 120, ratePerLi = 0.05, baseGold = 5),
        WorldIds.TravelModes.TRAIN to ModeSpec(speedLiPerHour = 400, ratePerLi = 0.10, baseGold = 15),
        WorldIds.TravelModes.PLANE to ModeSpec(speedLiPerHour = 1500, ratePerLi = 0.25, baseGold = 40),
    )

    /** 票价（金）：`base + round(d × rate)`（图纸 §4.1·免费模式恒 0）。 */
    fun costOf(mode: String, distanceLi: Int): Int {
        val spec = SPECS.getValue(mode)
        return spec.baseGold + (distanceLi * spec.ratePerLi).roundToInt()
    }

    /** 耗时（毫秒）：`max(T_MIN_MS, round(d / v 小时 → ms))`（图纸 §4.1·近距离 5 分钟钳位）。 */
    fun durationOf(mode: String, distanceLi: Int): Long {
        val spec = SPECS.getValue(mode)
        val ms = (distanceLi.toDouble() / spec.speedLiPerHour * 3_600_000.0).roundToLong()
        return maxOf(T_MIN_MS, ms)
    }

    /**
     * 距离带 → 呈现选项（决策 25「每次 2–3 个有意义选项」·图纸 §4.2·序即呈现序）：
     * `d≤12` 走·骑 / `12<d≤60` 骑·车 / `60<d≤250` 车·动车 / `250<d≤900` 车·动车·飞机 / `d>900` 动车·飞机。
     * 边界值落上带（`≤` 语义·图纸 E2）。
     */
    fun optionsFor(distanceLi: Int): List<TravelOption> =
        modesFor(distanceLi).map { TravelOption(it, durationOf(it, distanceLi), costOf(it, distanceLi)) }

    private fun modesFor(distanceLi: Int): List<String> = with(WorldIds.TravelModes) {
        when {
            distanceLi <= 12 -> listOf(WALK, BIKE)
            distanceLi <= 60 -> listOf(BIKE, CAR)
            distanceLi <= 250 -> listOf(CAR, TRAIN)
            distanceLi <= 900 -> listOf(CAR, TRAIN, PLANE)
            else -> listOf(TRAIN, PLANE)
        }
    }
}
