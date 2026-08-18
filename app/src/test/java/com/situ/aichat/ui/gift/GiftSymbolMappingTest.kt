package com.situ.aichat.ui.gift

import com.situ.aichat.data.model.GiftCategory
import com.situ.aichat.gift.GiftCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图标映射表完整性（9.2d d-1，验证流程 #2：断言反推 iOS 数据源）。
 *
 * 不靠肉眼核对 46 个映射——直接从 iOS 真相数据源（[GiftCatalog] 46 件 `fallbackSymbol` + [GiftCategory] 7 个
 * `iconSymbol` + DIY 三符号）反推：每一个**实际会被 UI 请求的 SF Symbol** 都必须在 [GiftSymbolMapping.mappedSymbols]
 * 内，否则会静默落到 CardGiftcard 兜底（视觉退化但不崩）。漏一个映射即测试失败。
 */
class GiftSymbolMappingTest {

    /** 每个目录项的 fallbackSymbol 都已映射。 */
    @Test
    fun allCatalogFallbackSymbolsAreMapped() {
        val unmapped = GiftCatalog.allItems
            .map { it.fallbackSymbol }
            .filterNot { it in GiftSymbolMapping.mappedSymbols }
            .distinct()
        assertTrue("未映射的目录 fallbackSymbol: $unmapped", unmapped.isEmpty())
    }

    /** 每个品类的 iconSymbol（d-3 分类 Tab 用）都已映射。 */
    @Test
    fun allCategoryIconSymbolsAreMapped() {
        val unmapped = GiftCategory.entries
            .map { it.iconSymbol }
            .filterNot { it in GiftSymbolMapping.mappedSymbols }
        assertTrue("未映射的品类 iconSymbol: $unmapped", unmapped.isEmpty())
    }

    /** DIY 相关三符号（record DIY 兜底 / 入口卡大图标 / makeUserDIY stub）都已映射。 */
    @Test
    fun diySymbolsAreMapped() {
        listOf("paintbrush.fill", "paintbrush.pointed.fill", "heart.text.square").forEach {
            assertTrue("DIY 符号未映射: $it", it in GiftSymbolMapping.mappedSymbols)
        }
    }

    /** 未知符号回退到终极兜底 CardGiftcard（同一对象）。 */
    @Test
    fun unknownSymbolFallsBackToCardGiftcard() {
        assertEquals(GiftSymbolMapping.ultimateFallback, GiftSymbolMapping.materialIcon("totally.unknown.symbol"))
    }
}
