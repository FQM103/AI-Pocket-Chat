package com.situ.aichat.story

/**
 * 阅读器字号档位（15.2-P1·P1-6，安卓超越——iOS 阅读器 Font.system(size:) 固定字号且菜单无任何字号设置，
 * StoryReaderView+Sections.swift:59-115）。四档查表、运行时零浮点：
 *
 * - 建表口径 = `Math.round(bodySp × iOS字号 / 18.0)` 半进位（iOS 真值比例 whisper 15/18、shout 22/18、
 *   dropCap 38/18，源自 AppTheme.swift:176/:190/:192 + StoryReaderAnimatedBlocks.swift:244）。
 * - 档位 [DEFAULT_INDEX]=1 即 iOS 精确原值 (18,15,22,38)——不动设置则与 iOS 像素一致。
 * - 行距不入表：= 「字号 × 1.8」倍率（ST7d·契约 §6.4-③·超越 iOS 旧「字号+12」），由 StoryReaderLayout.lineHeight 单源随动。
 * - 存储侧只存 Int 档位下标（[StoryReadingProgressStore]），sp 真值单源在本表，[forIndex] 钳位抗脏值。
 * - shout/emphasis 的**显示字号** = 表值 × 烘焙系数（原 iOS scaleEffect 常量·2026-07-13 拍板烘进字号，
 *   见 [shoutDisplaySp]/[emphasisDisplaySp]）——建档进位口径不变，表仍是 iOS 真值比例。
 */
data class StoryReaderTypography(
    val bodySp: Int,
    val whisperSp: Int,
    val shoutSp: Int,
    val dropCapSp: Int,
) {
    /**
     * shout 显示字号 = [shoutSp] × 1.15。原 iOS 是 22pt 字再 scaleEffect(1.15)；安卓文本框被拉成整行宽，
     * 绘制期中心缩放会把左缘顶出页边距（2026-07-13 真机「操！」贴屏边）→ 拍板方案 A：常量放大烘进字号，
     * 布局即真实尺寸——左缘与正文对齐、长句按页宽真实换行、字形按真值渲染不发虚。视觉大小与烘焙前等价。
     */
    val shoutDisplaySp: Float = shoutSp * SHOUT_BAKED_SCALE

    /** emphasis 显示字号 = [bodySp] × 1.02（烘焙口径同 [shoutDisplaySp]·消除同病根的整体左偏 ~3dp）。 */
    val emphasisDisplaySp: Float = bodySp * EMPHASIS_BAKED_SCALE

    companion object {
        /** 原 iOS `StoryAnimatedTextBlock` scaleEffect 常量（StoryReaderAnimatedBlocks.swift:303-334），现为字号烘焙系数。 */
        const val SHOUT_BAKED_SCALE = 1.15f

        /** 同上：emphasis 的 scaleEffect 常量 → 字号烘焙系数。 */
        const val EMPHASIS_BAKED_SCALE = 1.02f

        /** 默认档（=iOS 原值档）。 */
        const val DEFAULT_INDEX = 1

        /** 四档查表（body 16/18/20/22；派生列按建表口径预算定，单测以公式互证防漂移）。 */
        val TIERS = listOf(
            StoryReaderTypography(bodySp = 16, whisperSp = 13, shoutSp = 20, dropCapSp = 34),
            StoryReaderTypography(bodySp = 18, whisperSp = 15, shoutSp = 22, dropCapSp = 38), // iOS 原值
            StoryReaderTypography(bodySp = 20, whisperSp = 17, shoutSp = 24, dropCapSp = 42),
            StoryReaderTypography(bodySp = 22, whisperSp = 18, shoutSp = 27, dropCapSp = 46),
        )

        fun forIndex(index: Int): StoryReaderTypography = TIERS[index.coerceIn(0, TIERS.lastIndex)]
    }
}
