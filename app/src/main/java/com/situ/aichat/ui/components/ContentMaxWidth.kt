package com.situ.aichat.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 大屏内容行长上限（P15.2-P1 批0 基建 / P1-38）：把长表单/设置列约束到可读行长并水平居中。
 *
 * 手机竖屏（可用宽 < [max]）下等价于 `fillMaxWidth()`，零行为变化；折叠展开态（约 673dp）/
 * 平板/横屏上避免单列卡片拉到屏幕边缘、行长过长。iOS 参照为 per-surface 上限
 * （OnboardingView.swift:236 maxWidth 520 / CalendarActionCard.swift:118 maxWidth 500 /
 * SystemEventCard.swift:306 maxWidth 260）；安卓统一 600dp 更系统化（有意统一，P1 复核
 * 2026-06-10 登记）。各屏应用扫在批7（P1-38），本文件只提供基建。
 */
fun Modifier.contentMaxWidth(max: Dp = 600.dp): Modifier =
    fillMaxWidth()
        .wrapContentWidth(align = Alignment.CenterHorizontally)
        .widthIn(max = max)
        .fillMaxWidth()
