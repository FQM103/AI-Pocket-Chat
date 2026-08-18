package com.situ.aichat.world.cast

import com.situ.aichat.data.local.entity.WorldNativeStateEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorldAffinityService] 纯函数 T1-2（W6 图纸 §7·E5 权重合计金标 + E6 朦胧四档边界·纯 JVM）。
 *
 * 断言从图纸 §3.2 合计公式与 §4.1 四档边界**独立反推**（手算金标 / 边界枚举），不照搬实现。
 */
class WorldAffinityMathTest {

    private fun state(narrative: Int, gift: Int) =
        WorldNativeStateEntity(nativeId = "native:x", narrativeFuel = narrative, giftFuel = gift)

    // MARK: - E5 权重合计金标（手算·§3.2）

    @Test
    fun `E5 苏晚 1_0乘1_2 燃料30与25 合计60`() {
        val suWan = WorldNativeRoster.bySlug("su_wan")!!
        // round(30×1.0 + 25×1.2) = round(30 + 30) = 60
        assertEquals(60, WorldAffinityService.affinityOf(state(narrative = 30, gift = 25), suWan))
    }

    @Test
    fun `E5 林陌屿 1_3乘0_2 同燃料 合计44`() {
        val linMoyu = WorldNativeRoster.bySlug("lin_moyu")!!
        // round(30×1.3 + 25×0.2) = round(39 + 5) = 44
        assertEquals(44, WorldAffinityService.affinityOf(state(narrative = 30, gift = 25), linMoyu))
    }

    @Test
    fun `E5 合计下限0_零燃料=0_四舍五入半入`() {
        val suWan = WorldNativeRoster.bySlug("su_wan")!!
        assertEquals(0, WorldAffinityService.affinityOf(state(0, 0), suWan))
        // round() 半入方向：害羞诗人 gw=0.2·gift=3 → 0.6 → round 1；nf=1 → 1.0+0.6=1.6 → 2。
        val linMoyu = WorldNativeRoster.bySlug("lin_moyu")!!
        assertEquals(1, WorldAffinityService.affinityOf(state(narrative = 0, gift = 3), linMoyu))
        assertEquals(2, WorldAffinityService.affinityOf(state(narrative = 1, gift = 3), linMoyu))
    }

    // MARK: - E6 朦胧四档边界（§4.1·断言不吐数字）

    @Test
    fun `E6 四档边界 0_24到1_0 逐点`() {
        assertEquals(WorldAffinityStage.STRANGER, WorldAffinityStage.of(0.24))
        assertEquals(WorldAffinityStage.WARMING, WorldAffinityStage.of(0.25))
        assertEquals(WorldAffinityStage.WARMING, WorldAffinityStage.of(0.59))
        assertEquals(WorldAffinityStage.EXPECTING, WorldAffinityStage.of(0.60))
        assertEquals(WorldAffinityStage.EXPECTING, WorldAffinityStage.of(0.99))
        assertEquals(WorldAffinityStage.WILLING, WorldAffinityStage.of(1.0))
        // 兜底两端
        assertEquals(WorldAffinityStage.STRANGER, WorldAffinityStage.of(0.0))
        assertEquals(WorldAffinityStage.WILLING, WorldAffinityStage.of(2.0))
    }

    @Test
    fun `E6 stageOf 用真 threshold 算 fraction`() {
        // 门槛 100 的定制 def：affinity = narrativeFuel（nw=1.0·gw 不参与）。
        val def = WorldNativeRoster.bySlug("wen_qing")!!.copy(recruitThreshold = 100, narrativeWeight = 1.0, giftWeight = 1.0)
        assertEquals(WorldAffinityStage.STRANGER, WorldAffinityService.stageOf(state(24, 0), def))  // 0.24
        assertEquals(WorldAffinityStage.WARMING, WorldAffinityService.stageOf(state(25, 0), def))    // 0.25
        assertEquals(WorldAffinityStage.EXPECTING, WorldAffinityService.stageOf(state(60, 0), def))  // 0.60
        assertEquals(WorldAffinityStage.WILLING, WorldAffinityService.stageOf(state(100, 0), def))   // 1.0
    }

    @Test
    fun `E6 四档短语逐字对 §4_1_且绝不含数字或百分号`() {
        assertEquals("你们还只是打过照面", WorldAffinityStage.STRANGER.phrase)
        assertEquals("TA 见到你会笑了", WorldAffinityStage.WARMING.phrase)
        assertEquals("TA 好像在等你来", WorldAffinityStage.EXPECTING.phrase)
        assertEquals("TA 愿意认识你了", WorldAffinityStage.WILLING.phrase)
        // 朦胧铁则：任何出口不吐数字/百分比。
        for (stage in WorldAffinityStage.entries) {
            assertFalse("${stage.name} 短语含数字", stage.phrase.any { it.isDigit() })
            assertFalse("${stage.name} 短语含百分号", stage.phrase.contains('%'))
            assertTrue(stage.phrase.isNotBlank())
        }
    }
}
