package com.situ.aichat.ui.gift

import androidx.compose.ui.graphics.Color

/**
 * 礼物 UI 共享色板（1:1 iOS 散落在 GiftShopView/GiftCardBubbleView 的硬编码色值）。集中一处，d-1~d-5 复用。
 *
 * iOS 礼物店/反应页用「奶油纸 + 琥珀金」暖色系；可负担金 / 余额不足暗红是钱相关的语义色，必须 1:1。
 */
object GiftColors {
    /** 可负担价格的琥珀金（iOS `Color(hex: 0xC9892F)`）。 */
    val Gold = Color(0xFFC9892F)

    /** 余额不足的暗红（iOS `Color.unafford = Color(hex: 0xD4605D)`）。 */
    val Unafford = Color(0xFFD4605D)

    /** 图片缺失/DIY 兜底图标的琥珀色（iOS `Color(hex: 0xE8B86C)`）。 */
    val FallbackIcon = Color(0xFFE8B86C)

    /** 奶油纸渐变起点（iOS `Color(hex: 0xF5EDE4)`，左上）。 */
    val PaperStart = Color(0xFFF5EDE4)

    /** 奶油纸渐变终点（iOS `Color(hex: 0xFAF2E6)`，右下）。 */
    val PaperEnd = Color(0xFFFAF2E6)
}
