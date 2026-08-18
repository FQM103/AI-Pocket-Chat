package com.situ.aichat.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 列表行**内缩发丝分隔线**（仿微信通讯录 / 会话列表·过审 2026-06-20）：起于文字左缘、**头像列不穿线**
 * （故视觉上是「短」横线）、右到屏幕边缘。聊天列表 + 联系人共用，单一事实源。
 *
 * - [startInset] 默认 **78dp** ＝ 行横向 padding 12 + 头像 52 + 头像-文字间距 14（= 文字左缘），与两页行布局一致。
 * - 0.5dp 发丝；色取 M3 `outlineVariant`（= LinenDeep·与原全宽分隔同色·浅深档自适应）。注意：
 *   `surface.stroke`/Linen(#F1ECE4) 对暖底 surface 几乎不可见，故不用它。
 * - 仅在相邻数据行之间使用（分组头 / 归档入口等不加）。
 */
@Composable
fun AppListDivider(
    modifier: Modifier = Modifier,
    startInset: Dp = 78.dp,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}
