package com.situ.aichat.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * J5「今天的素材」空态芯片行（图纸 §4-J5）：仅正文为空且有素材时显示（放 MoodRow 与 DiaryPaper 之间·条件由调用侧控）。
 * 横滑 Row·芯片 = `surface.sunken` 胶囊（padding 12×6）+ 6dp 莫兰迪圆点（chat=calm/见面=sad/礼物=shy 装饰档）+
 * `text.secondary` caption；点击 = `haptics.selection()` + [onPick]（起笔模板句·调用侧 setContent + 换行）。
 * 正文非空即整行消失（条件重组自然达成·无退场动画）。
 */
@Composable
internal fun MaterialChipsRow(chips: List<MaterialChip>, onPick: (String) -> Unit) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            val dot = when (chip.kind) {
                MaterialKind.CHAT -> colors.emotion.calm
                MaterialKind.MEETING -> colors.emotion.sad
                MaterialKind.GIFT -> colors.emotion.shy
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(AppTheme.shapes.full)
                    .background(colors.surface.sunken)
                    .clickable(onClickLabel = chip.label) { haptics.selection(); onPick(chip.starter) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                Text(chip.label, style = AppTheme.typography.caption, color = colors.text.secondary)
            }
        }
    }
}
