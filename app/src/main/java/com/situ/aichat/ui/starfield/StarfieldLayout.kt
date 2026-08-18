package com.situ.aichat.ui.starfield

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 记忆星空布局（图纸 2026-07-16-记忆星空 §3.2·全部纯函数·锁定算法）：
 * 分簇（本地时区年月·降序）→ 簇心（交替左右 + 抖动）→ 簇内黄金角布点（最小距拒绝采样）→ 纵深衰减 →
 * 连线 → 月份标 → hero。
 *
 * **确定性**：每簇一个 [Random]，种子 = `characterUuid.hashCode() * 31 + 年月序号`（图纸 J9）——
 * 同角色每次进入布局逐像素一致。
 *
 * **坐标系 = 画布空间 dp**（原点 = 画布左上角）：图纸 §3.2-2 的簇心公式写在视口系（i 大时 cy 可为负），
 * 本处统一加 `画布高 - 视口高` 偏移落到画布系——Canvas 会裁掉负坐标，且绘制 / 命中 / a11y 锚点三处
 * 必须共用同一套坐标（§9 机制锁「禁独立换算两套坐标」）。见施工日志 D-3。
 */
internal object StarfieldLayout {

    /** 第 0 簇（最近）簇心 y = 视口高 × 此值。 */
    const val FIRST_CLUSTER_Y_FACTOR = 0.62f
    /** 相邻簇纵向间距 = 视口高 × 此值（往上排）。 */
    const val CLUSTER_SPACING_FACTOR = 0.30f
    /** 画布高公式的顶部余量项（图纸 J8）。 */
    const val CANVAS_TOP_MARGIN_FACTOR = 0.24f
    /** 簇心 x = 视口宽 × 0.30（偶数簇）/ 0.70（奇数簇）。 */
    const val CLUSTER_X_NEAR_FACTOR = 0.30f
    const val CLUSTER_X_FAR_FACTOR = 0.70f
    /** 簇心 x 抖动幅度 = 视口宽 × 此值。 */
    const val CLUSTER_X_JITTER_FACTOR = 0.06f

    /** 簇内布点黄金角（度）。 */
    const val GOLDEN_ANGLE_DEG = 137.5f
    /** 第 k 颗星半径 = 22 + k×13 dp。 */
    const val STAR_BASE_RADIUS_DP = 22f
    const val STAR_RADIUS_STEP_DP = 13f
    /** 半径抖动 ±6dp。 */
    const val STAR_RADIUS_JITTER_DP = 6f
    /** 星间最小距。 */
    const val MIN_STAR_GAP_DP = 26f
    /** 违反最小距时的半径外扩步长与最大重试次数（超出后原样接受）。 */
    const val RETRY_RADIUS_STEP_DP = 9f
    const val MAX_PLACE_RETRIES = 20

    /** 纵深衰减：第 i 簇星径 ×0.92^i、整星 alpha ×0.94^i，i≥3 按 3 计（封底）。 */
    const val RADIUS_DECAY = 0.92f
    const val ALPHA_DECAY = 0.94f
    const val DEPTH_CAP = 3

    /** 每簇连线上限。 */
    const val MAX_LINKS_PER_CLUSTER = 4

    /** 月份标相对簇心的偏移。 */
    const val LABEL_OFFSET_X_DP = -52f
    const val LABEL_OFFSET_Y_DP = -38f

    /** 「往昔」簇（legacy 见面·无时间）的种子分量（图纸 J7/J9·真实年月序号恒 ≥0，不会撞）。 */
    private const val LEGACY_SEED_KEY = -1

    /** 画布逻辑高（图纸 J8）。 */
    fun canvasHeightDp(clusterCount: Int, viewportHeightDp: Float): Float =
        maxOf(
            viewportHeightDp,
            clusterCount * CLUSTER_SPACING_FACTOR * viewportHeightDp + CANVAS_TOP_MARGIN_FACTOR * viewportHeightDp,
        )

    /** 星座数（入口卡「N 段星座」用·与 [layout] 的分簇口径同源·图纸 §3.4）。 */
    fun clusterCount(nodes: List<StarNode>, zone: ZoneId = ZoneId.systemDefault()): Int =
        groupByMonth(nodes, zone).size

    /**
     * 三源星 → 星座布局。[nowMillis] 只用于月份标是否带年份（跨年·见 D-6），不影响几何。
     */
    fun layout(
        nodes: List<StarNode>,
        characterUuid: String,
        viewportWidthDp: Float,
        viewportHeightDp: Float,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): StarfieldLayoutResult {
        val grouped = groupByMonth(nodes, zone)
        val canvasHeightDp = canvasHeightDp(grouped.size, viewportHeightDp)
        // 视口系 → 画布系：最近的簇落在画布下部，最老的簇顶在画布上部（i 大时视口系 cy 为负）。
        val canvasTopOffsetDp = canvasHeightDp - viewportHeightDp
        val currentYear = YearMonth.from(Instant.ofEpochMilli(nowMillis).atZone(zone)).year

        val clusters = grouped.mapIndexed { i, group ->
            val seed = characterUuid.hashCode() * 31 + (group.yearMonth?.let(::yearMonthKey) ?: LEGACY_SEED_KEY)
            val rnd = Random(seed)
            val cx = viewportWidthDp * (if (i % 2 == 0) CLUSTER_X_NEAR_FACTOR else CLUSTER_X_FAR_FACTOR) +
                rnd.jitter(CLUSTER_X_JITTER_FACTOR * viewportWidthDp)
            val cy = canvasTopOffsetDp + viewportHeightDp * FIRST_CLUSTER_Y_FACTOR - i * viewportHeightDp * CLUSTER_SPACING_FACTOR
            val depth = min(i, DEPTH_CAP)
            val placed = placeStars(group.nodes, cx, cy, depth, rnd)
            StarCluster(
                label = labelOf(group.yearMonth, currentYear),
                centerXDp = cx,
                centerYDp = cy,
                depthIndex = i,
                stars = placed,
                links = linksOf(placed),
            )
        }
        return StarfieldLayoutResult(clusters = clusters, canvasHeightDp = canvasHeightDp)
    }

    /** 分簇（§3.2-1）：本地时区年月分组、按时间降序（最近在前）；timestamp<=0 归「往昔」簇排最末（J7）。 */
    private fun groupByMonth(nodes: List<StarNode>, zone: ZoneId): List<MonthGroup> {
        val legacy = nodes.filter { it.timestampMillis <= 0L }
        val dated = nodes.filter { it.timestampMillis > 0L }
        val groups = dated
            .groupBy { YearMonth.from(Instant.ofEpochMilli(it.timestampMillis).atZone(zone)) }
            .toList()
            .sortedByDescending { it.first }
            .map { (ym, list) -> MonthGroup(ym, list.sortedBy { it.timestampMillis }) }
        return if (legacy.isEmpty()) groups else groups + MonthGroup(null, legacy)
    }

    /**
     * 簇内布点（§3.2-3）：时间升序、绕簇心黄金角步进、半径 22 + k×13 ± 6dp；
     * 与已落位星距 < 26dp 则半径 +9dp 重试，[MAX_PLACE_RETRIES] 次后原样接受。
     */
    private fun placeStars(nodes: List<StarNode>, cx: Float, cy: Float, depth: Int, rnd: Random): List<PlacedStar> {
        val radiusScale = pow(RADIUS_DECAY, depth)
        val alpha = pow(ALPHA_DECAY, depth)
        val heroWeight = nodes.maxOfOrNull { it.weight }
        var heroTaken = false
        val out = ArrayList<PlacedStar>(nodes.size)
        nodes.forEachIndexed { k, node ->
            val angle = (k * GOLDEN_ANGLE_DEG) * PI.toFloat() / 180f
            val jitter = rnd.jitter(STAR_RADIUS_JITTER_DP)
            var r = STAR_BASE_RADIUS_DP + k * STAR_RADIUS_STEP_DP + jitter
            var x = cx + r * cos(angle)
            var y = cy + r * sin(angle)
            var attempt = 0
            while (attempt < MAX_PLACE_RETRIES && tooClose(x, y, out)) {
                r += RETRY_RADIUS_STEP_DP
                x = cx + r * cos(angle)
                y = cy + r * sin(angle)
                attempt++
            }
            // hero = 簇内 weight 最大者，并列取更早的（列表已时间升序 → 首个命中即最早）。
            val hero = !heroTaken && heroWeight != null && node.weight == heroWeight
            if (hero) heroTaken = true
            out += PlacedStar(
                node = node,
                xDp = x,
                yDp = y,
                radiusDp = node.weight * radiusScale,
                alpha = alpha,
                hero = hero,
            )
        }
        return out
    }

    private fun tooClose(x: Float, y: Float, placed: List<PlacedStar>): Boolean =
        placed.any { hypot(x - it.xDp, y - it.yDp) < MIN_STAR_GAP_DP }

    /** 连线（§3.2-5）：簇内按时间序连相邻星，每簇最多 4 条。 */
    private fun linksOf(stars: List<PlacedStar>): List<StarLink> =
        stars.zipWithNext()
            .take(MAX_LINKS_PER_CLUSTER)
            .map { (a, b) -> StarLink(a.xDp, a.yDp, b.xDp, b.yDp) }

    /** 月份标（§3.2-6）：「N月」；非本年 →「YYYY年N月」；往昔簇 →「往昔」。 */
    private fun labelOf(yearMonth: YearMonth?, currentYear: Int): String = when {
        yearMonth == null -> LEGACY_LABEL
        yearMonth.year != currentYear -> "${yearMonth.year}年${yearMonth.monthValue}月"
        else -> "${yearMonth.monthValue}月"
    }

    /** 年月 → 单调整数序号（种子分量·图纸 J9）。 */
    private fun yearMonthKey(ym: YearMonth): Int = ym.year * 12 + (ym.monthValue - 1)

    private fun Random.jitter(amplitude: Float): Float = (nextFloat() * 2f - 1f) * amplitude

    private fun pow(base: Float, exp: Int): Float {
        var v = 1f
        repeat(exp) { v *= base }
        return v
    }

    /** 「往昔」簇月份标（图纸 J7·锁定文案）。 */
    const val LEGACY_LABEL = "往昔"

    private data class MonthGroup(val yearMonth: YearMonth?, val nodes: List<StarNode>)
}

/** [StarfieldLayout.layout] 的输出。 */
internal data class StarfieldLayoutResult(
    val clusters: List<StarCluster>,
    val canvasHeightDp: Float,
)
