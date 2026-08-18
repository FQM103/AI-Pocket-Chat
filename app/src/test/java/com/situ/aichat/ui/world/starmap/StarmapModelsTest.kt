package com.situ.aichat.ui.world.starmap

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 星图纯分类映射 T1-4（W10 图纸 §7·E5）：亲密分档边界 / 色彩句表内外·空 / typesJson 降级。
 * 断言从 §4.7 语义映射表独立反推（分档界 15/35/70·13 色·空/坏 json → 相识）。
 */
class StarmapModelsTest {

    // MARK: - 亲密四档分档边界 14/15/34/35/69/70

    @Test
    fun `closenessTier 分档边界`() {
        assertEquals(1, closenessTier(0))
        assertEquals(1, closenessTier(14))
        assertEquals(2, closenessTier(15))
        assertEquals(2, closenessTier(34))
        assertEquals(3, closenessTier(35))
        assertEquals(3, closenessTier(69))
        assertEquals(4, closenessTier(70))
        assertEquals(4, closenessTier(100))
    }

    // MARK: - 色彩句：表内查键 / 表外原样 / 空省略

    @Test
    fun `colorPhraseOf 表内查键`() {
        assertEquals(ColorPhrase.Keyed("curious"), colorPhraseOf("好奇"))
        assertEquals(ColorPhrase.Keyed("heartbeat"), colorPhraseOf("心动"))
        assertEquals(ColorPhrase.Keyed("crush"), colorPhraseOf("暗恋"))
        assertEquals(ColorPhrase.Keyed("distant"), colorPhraseOf("淡漠"))
    }

    @Test
    fun `colorPhraseOf 表外原样_空省略`() {
        assertEquals(ColorPhrase.Raw("狂喜"), colorPhraseOf("狂喜")) // 表外未来扩充值 → 原样
        assertEquals(ColorPhrase.Omit, colorPhraseOf(""))
        assertEquals(ColorPhrase.Omit, colorPhraseOf("   "))
    }

    // MARK: - typesJson 降级（空 / 坏 json → 相识；关系类型全谱无白名单）

    @Test
    fun `typesOrAcquainted 空与坏json降级为相识_合法原样`() {
        assertEquals(listOf("相识"), typesOrAcquainted(""))
        assertEquals(listOf("相识"), typesOrAcquainted("这不是json"))
        assertEquals(listOf("朋友", "恋人"), typesOrAcquainted("""["朋友","恋人"]""")) // 恋人等全谱原样保留
        assertEquals(listOf("邻里", "朋友"), typesOrAcquainted("""["邻里","朋友"]"""))
    }
}
