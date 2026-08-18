package com.situ.aichat.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset

/**
 * 金币余额数字滚动（P1-12）：iOS `contentTransition(.numericText(value:))` + `.smooth(0.35)` 的安卓等价——
 * 值增 → 旧数字上滑出、新数字自下滑入（里程表上滚），值减反向；千分位分组（=iOS `.number`）；
 * tabular figures 防比例字体逐位抖宽（=iOS `monospacedDigit()`）。reduceMotion 退纯 Text。
 *
 * iOS 三处真实现状（2026-06-10 勘察）：礼物 pill 有显式 smooth(0.35) 驱动（InChatGiftSheetView.swift:410-411）、
 * 角色卡只声明 numericText 无 animation 驱动（CharacterWalletCard.swift:199）、用户钱包 hero 故意全静态
 * （WalletView.swift:150-169）——安卓三处统一全动为已登记的有意超越（已向用户知会）。
 *
 * [animateChanges]=false 走纯 Text（批2 复核修 LOW#2）：数据未加载时占位初值→真值的「假变更」不该滚
 * （进屏会先显占位 100 再 350ms 滚到真值，且方向可能误导为「刚扣款」）；调用方以加载旗标门控，
 * 只对已加载值之间的真实变更滚动（iOS 首帧即真值无此问题）。
 */
@Composable
fun AnimatedCoinText(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = 1,
    animateChanges: Boolean = true,
) {
    val tabular = style.copy(fontFeatureSettings = "tnum")
    if (!animateChanges || rememberReduceMotion()) {
        Text("%,d".format(value), style = tabular, maxLines = maxLines, modifier = modifier)
    } else {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                val up = targetState > initialState
                val slide = tween<IntOffset>(AppMotion.SMOOTH_LONG_MS)
                val fade = tween<Float>(AppMotion.SMOOTH_LONG_MS)
                (slideInVertically(slide) { if (up) it else -it } + fadeIn(fade))
                    .togetherWith(slideOutVertically(slide) { if (up) -it else it } + fadeOut(fade))
                    .using(SizeTransform(clip = false)) // 位数变化（999→1,099）不裁剪滑出中的数字
            },
            label = "coinRoll",
            modifier = modifier,
        ) { v ->
            Text("%,d".format(v), style = tabular, maxLines = maxLines)
        }
    }
}
