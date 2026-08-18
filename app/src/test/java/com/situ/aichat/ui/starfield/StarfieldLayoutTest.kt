package com.situ.aichat.ui.starfield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.hypot

/**
 * 记忆星空布局纯函数 T1（图纸 2026-07-16-记忆星空 §7 T1-1…T1-5 / 边界 E2/E4/E10/E11/E16）。
 *
 * 断言口径**从图纸 §3.2 规格独立反推**（黄金角 137.5°/半径 22+13k±6/最小距 26dp/外扩 9dp×20 次/
 * 簇心 0.62h-0.30h·i/x 0.30↔0.70±0.06w/画布高 J8），不照抄实现输出。时区固定 Asia/Shanghai 使
 * 分簇与「今年」判定与运行环境解耦。
 */
class StarfieldLayoutTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val w = 360f
    private val h = 800f
    private val uuid = "char-a"

    /** 2026-07-16T12:00 中国时区 = 布局的「今天」（跨年标判定基准）。 */
    private val now = millis(2026, 7, 16, 12)

    private fun millis(year: Int, month: Int, day: Int, hour: Int): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun star(id: String, ts: Long, weight: Float = 3f, type: StarType = StarType.MEETING) =
        StarNode(id = id, type = type, timestampMillis = ts, title = id, weight = weight)

    private fun layout(nodes: List<StarNode>, characterUuid: String = uuid) =
        StarfieldLayout.layout(nodes, characterUuid, w, h, now, zone)

    // ── T1-1 确定性（同种子同布局 / 不同角色不同布局）────────────────────────────────

    @Test
    fun sameCharacter_sameLayout_everyCoordinateEqual() {
        val nodes = listOf(
            star("a", millis(2026, 7, 1, 9)),
            star("b", millis(2026, 7, 8, 20)),
            star("c", millis(2026, 6, 3, 14)),
        )
        val first = layout(nodes)
        val second = layout(nodes)

        assertEquals(first.canvasHeightDp, second.canvasHeightDp, 0f)
        assertEquals(first.clusters.size, second.clusters.size)
        first.clusters.forEachIndexed { i, c1 ->
            val c2 = second.clusters[i]
            assertEquals(c1.centerXDp, c2.centerXDp, 0f)
            assertEquals(c1.centerYDp, c2.centerYDp, 0f)
            c1.stars.forEachIndexed { k, s1 ->
                val s2 = c2.stars[k]
                assertEquals(s1.xDp, s2.xDp, 0f)
                assertEquals(s1.yDp, s2.yDp, 0f)
                assertEquals(s1.radiusDp, s2.radiusDp, 0f)
            }
        }
    }

    @Test
    fun differentCharacter_differentLayout() {
        val nodes = listOf(star("a", millis(2026, 7, 1, 9)), star("b", millis(2026, 7, 8, 20)))
        val a = layout(nodes, "char-a").clusters.single()
        val b = layout(nodes, "char-b").clusters.single()
        // 种子含 characterUuid.hashCode() → 抖动分量不同（簇心 x 与星半径抖动至少一处不同）。
        val sameCenter = a.centerXDp == b.centerXDp
        val sameStars = a.stars.map { it.xDp to it.yDp } == b.stars.map { it.xDp to it.yDp }
        assertTrue("不同角色应有不同布局", !(sameCenter && sameStars))
    }

    // ── T1-2 单星成簇带 hero / 分簇降序 / 簇心交替左右（E2）─────────────────────────

    @Test
    fun singleStar_formsOneClusterWithHero_andNoLinks() {
        val result = layout(listOf(star("only", millis(2026, 7, 10, 15))))
        val cluster = result.clusters.single()

        assertEquals(1, cluster.stars.size)
        assertTrue("单星必是 hero（簇内 weight 最大者）", cluster.stars.single().hero)
        assertEquals("单星簇连线 0 条", 0, cluster.links.size)
        assertEquals("7月", cluster.label)
        // 一簇：画布高 = max(h, 1×0.30h + 0.24h) = max(800, 432) = 800。
        assertEquals(h, result.canvasHeightDp, 0.01f)
    }

    @Test
    fun clusters_sortedByMonthDescending_recentFirst() {
        val nodes = listOf(
            star("old", millis(2026, 5, 2, 10)),
            star("mid", millis(2026, 6, 2, 10)),
            star("new", millis(2026, 7, 2, 10)),
        )
        val clusters = layout(nodes).clusters
        assertEquals(listOf("7月", "6月", "5月"), clusters.map { it.label })
    }

    @Test
    fun clusterCenters_alternateLeftRight_andStepUpwards() {
        val nodes = listOf(
            star("new", millis(2026, 7, 2, 10)),
            star("mid", millis(2026, 6, 2, 10)),
            star("old", millis(2026, 5, 2, 10)),
        )
        val result = layout(nodes)
        val clusters = result.clusters
        val topOffset = result.canvasHeightDp - h

        // 三簇：画布高 = max(800, 3×240 + 192) = 912 → 顶偏移 112。
        assertEquals(912f, result.canvasHeightDp, 0.01f)
        // cy = 顶偏移 + 0.62h - i×0.30h。
        clusters.forEachIndexed { i, c ->
            assertEquals(topOffset + h * 0.62f - i * h * 0.30f, c.centerYDp, 0.01f)
        }
        // cx = 0.30w（偶）/ 0.70w（奇），抖动 ±0.06w → 偶簇恒在左半、奇簇恒在右半。
        assertTrue(clusters[0].centerXDp in (w * 0.24f)..(w * 0.36f))
        assertTrue(clusters[1].centerXDp in (w * 0.64f)..(w * 0.76f))
        assertTrue(clusters[2].centerXDp in (w * 0.24f)..(w * 0.36f))
    }

    @Test
    fun heroIsMaxWeight_tiesTakeEarlier() {
        val nodes = listOf(
            star("early", millis(2026, 7, 1, 9), weight = 4.4f),
            star("late", millis(2026, 7, 9, 9), weight = 4.4f),
            star("small", millis(2026, 7, 5, 9), weight = 2.4f),
        )
        val cluster = layout(nodes).clusters.single()
        val hero = cluster.stars.single { it.hero }
        assertEquals("并列取更早的", "early", hero.node.id)
    }

    // ── T1-3 往昔簇（E4）─────────────────────────────────────────────────────────

    @Test
    fun legacyStars_goToPastCluster_atFarEnd() {
        val nodes = listOf(
            star("legacy", 0L),
            star("recent", millis(2026, 7, 2, 10)),
            star("older", millis(2026, 6, 2, 10)),
        )
        val clusters = layout(nodes).clusters
        assertEquals(listOf("7月", "6月", "往昔"), clusters.map { it.label })
        assertEquals(listOf("legacy"), clusters.last().stars.map { it.node.id })
        // 最末簇 = 最远端 = 最高（画布 y 最小）+ 最淡（alpha 衰减）。
        assertTrue(clusters.last().centerYDp < clusters.first().centerYDp)
        assertTrue(clusters.last().stars.single().alpha < clusters.first().stars.single().alpha)
    }

    @Test
    fun negativeTimestamp_alsoTreatedAsLegacy() {
        val clusters = layout(listOf(star("neg", -1L))).clusters
        assertEquals("往昔", clusters.single().label)
    }

    // ── T1-4 跨年标签 + 同 ZoneId 分簇（E10/E16）────────────────────────────────

    @Test
    fun crossYearCluster_labelCarriesYear() {
        val nodes = listOf(
            star("thisYear", millis(2026, 7, 2, 10)),
            star("lastYear", millis(2025, 12, 20, 10)),
        )
        val clusters = layout(nodes).clusters
        assertEquals(listOf("7月", "2025年12月"), clusters.map { it.label })
    }

    @Test
    fun monthBoundary_usesGivenZone_notUtc() {
        // 2026-07-01T00:30 Asia/Shanghai = 2026-06-30T16:30Z：按给定时区应落 7 月簇，按 UTC 会落 6 月。
        val nodes = listOf(star("edge", millis(2026, 7, 1, 0) + 30 * 60_000L))
        assertEquals("7月", layout(nodes).clusters.single().label)

        // 换时区分簇结果随之改变（同一时刻在 UTC 属 6 月）——分簇与显示同源（E16）。
        val utc = StarfieldLayout.layout(nodes, uuid, w, h, now, ZoneId.of("UTC"))
        assertEquals("6月", utc.clusters.single().label)
    }

    // ── T1-5 挤簇最小距（E11）────────────────────────────────────────────────────

    @Test
    fun twentyStarsInOneMonth_respectMinGapOrExhaustRetries() {
        val nodes = (0 until 20).map { star("s$it", millis(2026, 7, 1 + it, 10)) }
        val cluster = layout(nodes).clusters.single()
        assertEquals(20, cluster.stars.size)

        val cx = cluster.centerXDp
        val cy = cluster.centerYDp
        cluster.stars.forEachIndexed { k, s ->
            assertTrue("坐标必须有限", s.xDp.isFinite() && s.yDp.isFinite())
            val minGap = cluster.stars.take(k).minOfOrNull { hypot(s.xDp - it.xDp, s.yDp - it.yDp) }
            if (minGap != null && minGap < StarfieldLayout.MIN_STAR_GAP_DP) {
                // 规格允许的唯一例外：外扩满 20 次后原样接受 → 该星半径至少 = 基半径(22+13k) - 抖动 6 + 20×9。
                val exhaustedRadius = StarfieldLayout.STAR_BASE_RADIUS_DP + k * StarfieldLayout.STAR_RADIUS_STEP_DP -
                    StarfieldLayout.STAR_RADIUS_JITTER_DP +
                    StarfieldLayout.MAX_PLACE_RETRIES * StarfieldLayout.RETRY_RADIUS_STEP_DP
                assertTrue(
                    "星 $k 距最近星 $minGap dp < 26dp，且未见 20 次外扩痕迹",
                    hypot(s.xDp - cx, s.yDp - cy) >= exhaustedRadius,
                )
            }
        }
    }

    @Test
    fun linksCapPerCluster_isFour() {
        val nodes = (0 until 10).map { star("s$it", millis(2026, 7, 1 + it, 10)) }
        val cluster = layout(nodes).clusters.single()
        assertEquals(StarfieldLayout.MAX_LINKS_PER_CLUSTER, cluster.links.size)
        // 连线按时间序连相邻星：第一条 = 最早两颗。
        assertEquals(cluster.stars[0].xDp, cluster.links[0].fromXDp, 0f)
        assertEquals(cluster.stars[1].xDp, cluster.links[0].toXDp, 0f)
    }

    // ── 纵深衰减与画布高（§3.2-4 / J8）───────────────────────────────────────────

    @Test
    fun depthDecay_cappedAtThird() {
        // 六簇：i=3/4/5 的衰减系数应与 i=3 相同（封底）。
        val nodes = (0 until 6).map { star("s$it", millis(2026, 7 - it, 2, 10), weight = 4f) }
        val clusters = layout(nodes).clusters
        val alphaAt3 = clusters[3].stars.single().alpha
        val radiusAt3 = clusters[3].stars.single().radiusDp
        assertEquals(alphaAt3, clusters[4].stars.single().alpha, 0f)
        assertEquals(alphaAt3, clusters[5].stars.single().alpha, 0f)
        assertEquals(radiusAt3, clusters[5].stars.single().radiusDp, 0f)
        // i=0 未衰减：radius = weight；alpha = 1。
        assertEquals(4f, clusters[0].stars.single().radiusDp, 0.0001f)
        assertEquals(1f, clusters[0].stars.single().alpha, 0.0001f)
        // i=1：0.92 / 0.94。
        assertEquals(4f * 0.92f, clusters[1].stars.single().radiusDp, 0.0001f)
        assertEquals(0.94f, clusters[1].stars.single().alpha, 0.0001f)
        // i=3（封底）：0.92³ / 0.94³。
        assertEquals(4f * 0.92f * 0.92f * 0.92f, radiusAt3, 0.0001f)
        assertEquals(0.94f * 0.94f * 0.94f, alphaAt3, 0.0001f)
    }

    @Test
    fun canvasHeight_followsBlueprintFormula() {
        // J8：max(视口高, 簇数×0.30×视口高 + 0.24×视口高)。
        assertEquals(800f, StarfieldLayout.canvasHeightDp(0, h), 0.01f)
        assertEquals(800f, StarfieldLayout.canvasHeightDp(2, h), 0.01f)  // 0.84h < h
        assertEquals(912f, StarfieldLayout.canvasHeightDp(3, h), 0.01f)  // 1.14h
        assertEquals(1152f, StarfieldLayout.canvasHeightDp(4, h), 0.01f) // 1.44h
    }

    @Test
    fun emptyInput_yieldsNoClusters() {
        val result = layout(emptyList())
        assertTrue(result.clusters.isEmpty())
        assertEquals(h, result.canvasHeightDp, 0.01f)
    }

    @Test
    fun topCluster_staysInsideCanvas() {
        // 画布高公式的 0.24h 余量 = 最老簇心距画布顶 0.16h（≥3 簇时恒定）。
        val nodes = (0 until 5).map { star("s$it", millis(2026, 7 - it, 2, 10)) }
        val result = layout(nodes)
        val top = result.clusters.last()
        assertEquals(h * 0.16f, top.centerYDp, 0.01f)
        assertNotEquals(0f, result.canvasHeightDp)
        assertTrue(result.clusters.all { it.centerYDp > 0f && it.centerYDp < result.canvasHeightDp })
    }
}
