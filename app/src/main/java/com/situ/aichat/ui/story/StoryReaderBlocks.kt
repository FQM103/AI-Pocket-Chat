package com.situ.aichat.ui.story

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.story.StoryReaderTypography
import com.situ.aichat.story.StoryTextMotion
import com.situ.aichat.story.StoryTextSanitizer
import com.situ.aichat.story.StoryTextStyle

/**
 * 阅读器三类可见块的渲染（1:1 iOS `StoryAnimatedTextBlock` / `StorySceneDivider` / `StoryChapterEndDivider`，
 * `StoryReaderAnimatedBlocks.swift`）。全程衬线字体；颜色随纸面深浅自适应（[StoryReaderLayout]）。
 *
 * 逐帧动效（trembling/angry 横抖、excited 纵跳）由 [motionTime]（秒）lambda 驱动并在 graphicsLayer 内读取，
 * 时间变化只触发图层重绘、不重组（性能）；i-1 默认 0（静止外观），i-2 由 [StoryRenderBlock] 接入逐帧时钟。
 * shout/emphasis 的常量放大已烘进字号（[StoryReaderTypography.shoutDisplaySp]·左缘对齐正文、长句真实换行），
 * whisper 半透明。
 */

private val IOSBlue = Color(0xFF007AFF)
private val IOSRed = Color(0xFFFF3B30)

/** 文字块：8 样式 + 首段首字下沉。[typography] 为当前字号档（P1-6；默认档=iOS 原值 18/15/22/38）。 */
@Composable
fun StoryReaderTextBlock(
    text: String,
    style: StoryTextStyle,
    isFirstParagraph: Boolean,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    typography: StoryReaderTypography = StoryReaderTypography.forIndex(StoryReaderTypography.DEFAULT_INDEX),
    motionTime: () -> Double = { 0.0 },
) {
    val sanitized = remember(text) { StoryTextSanitizer.sanitize(text) }
    val baseColor = textColorFor(style, isDark)
    val lineHeight = StoryReaderLayout.lineHeight(fontSizeFor(style, typography))

    // 首段首字下沉：dropCap 衬线粗体首字 + 正常正文（仅 normal 样式且文本多于 1 字）。
    if (isFirstParagraph && style == StoryTextStyle.NORMAL && sanitized.length > 1) {
        val annotated = buildAnnotatedString {
            withStyle(
                SpanStyle(
                    fontSize = typography.dropCapSp.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                ),
            ) { append(sanitized.take(1)) }
            append(sanitized.drop(1))
        }
        Text(
            text = annotated,
            color = baseColor,
            fontSize = typography.bodySp.sp,
            fontFamily = FontFamily.Serif,
            lineHeight = lineHeight,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }

    Text(
        text = sanitized,
        color = baseColor,
        fontSize = fontSizeFor(style, typography),
        fontFamily = FontFamily.Serif,
        fontWeight = fontWeightFor(style),
        fontStyle = if (style == StoryTextStyle.THOUGHT) FontStyle.Italic else FontStyle.Normal,
        lineHeight = lineHeight,
        textAlign = TextAlign.Start,
        modifier = modifier
            .then(if (style == StoryTextStyle.THOUGHT) Modifier.padding(start = 16.dp) else Modifier)
            .fillMaxWidth()
            .then(if (style == StoryTextStyle.WHISPER) Modifier.alpha(0.6f) else Modifier)
            .graphicsLayer {
                // excited 微缩放以行首为轴：中心轴会把左缘随整行宽文本框推进页边距（「操！」贴边同族病根）。
                transformOrigin = TransformOrigin(0f, 0.5f)
                val t = motionTime() // 在图层阶段读取，时间变化只重绘不重组
                val s = StoryTextMotion.scale(style, t).toFloat()
                scaleX = s
                scaleY = s
                translationX = StoryTextMotion.horizontalOffset(style, t).dp.toPx()
                translationY = StoryTextMotion.verticalOffset(style, t).dp.toPx()
            },
    )
}

// internal 供 T1 锁「样式→显示字号」接线（2% 级差在渲染层被 int px 量化吞没，等值比对够不着）。
internal fun fontSizeFor(style: StoryTextStyle, typography: StoryReaderTypography) = when (style) {
    StoryTextStyle.WHISPER -> typography.whisperSp.sp
    StoryTextStyle.SHOUT -> typography.shoutDisplaySp.sp
    StoryTextStyle.EMPHASIS -> typography.emphasisDisplaySp.sp
    else -> typography.bodySp.sp
}

private fun fontWeightFor(style: StoryTextStyle) = when (style) {
    StoryTextStyle.SHOUT, StoryTextStyle.ANGRY, StoryTextStyle.EMPHASIS -> FontWeight.Bold
    else -> FontWeight.Normal
}

private fun textColorFor(style: StoryTextStyle, isDark: Boolean): Color = when (style) {
    // 心情视觉层退役后纸面恒中性 → 原「深色心情专用色」分支恒不成立，两样式一律走 iOS 原色
    // （= 用户此前一直看到的：沉浸氛围开关默认关、纸面恒中性 ⇒ 那两个深心情色从未出现过）。
    StoryTextStyle.THOUGHT -> IOSBlue.copy(alpha = 0.75f)
    StoryTextStyle.ANGRY -> IOSRed.copy(alpha = 0.88f)
    StoryTextStyle.WHISPER -> StoryReaderLayout.secondaryTextColor(isDark)
    else -> StoryReaderLayout.textColor(isDark)
}

/** 场景分隔：✦ 短线夹场景名 ✦（上下各一行装饰）。 */
@Composable
fun StorySceneDivider(text: String, isDark: Boolean, modifier: Modifier = Modifier) {
    val ornament = StoryReaderLayout.ornamentColor(isDark)
    val secondary = StoryReaderLayout.secondaryTextColor(isDark)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = StoryReaderLayout.sceneDividerSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OrnamentLine(ornament)
        if (text.isNotEmpty()) {
            Text(
                text = text,
                color = secondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Serif,
                letterSpacing = StoryReaderLayout.ornamentKerning,
                textAlign = TextAlign.Center,
            )
        }
        OrnamentLine(ornament)
    }
}

@Composable
private fun OrnamentLine(color: Color) {
    // P1-20：纯装饰行对 TalkBack 整体压停（场景名文本保持可读——真实内容，iOS 也不隐藏）。
    Row(
        modifier = Modifier.clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(40.dp).height(0.5.dp).background(color))
        Text("✦", color = color, fontSize = 13.sp)
        Box(Modifier.width(40.dp).height(0.5.dp).background(color))
    }
}

/** 章节结尾装饰：✦ ✦ ✦（P1-20 整行装饰压停——iOS 未隐藏此处=安卓超越）。 */
@Composable
fun StoryChapterEndDivider(isDark: Boolean, modifier: Modifier = Modifier) {
    val ornament = StoryReaderLayout.ornamentColor(isDark)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp)
            .clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    ) {
        repeat(3) { Text("✦", color = ornament, fontSize = 12.sp) }
    }
}
