package com.situ.aichat.ui.character

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

// 资料页吸顶三 Tab 栏（图纸 2026-07-15-资料页Tab栏样式与经历重排 §4）：弃 AppSegmentedControl 填充药丸，改自绘
// A+B 标签页——未选中灰/常规（text.secondary·body），选中陶土（accent.text·titleSmall·加粗略大）+ 短下划线
// （宽=文字宽），整条压一道通栏发丝线（text.primary@10%）。48dp 触达 / Role.Tab / selectableGroup / haptics /
// 深浅双模全走 AppTheme token 自绘承担；选中态即时落位（无动画·§0 判断②）。

/** 资料页三 Tab（顺序锁定·默认落 NEAR）。 */
enum class ProfileTab(@StringRes val labelRes: Int) {
    NEAR(R.string.profile_tab_now),
    STORY(R.string.profile_tab_story),
    PROFILE(R.string.profile_tab_about),
}

/**
 * 吸顶 Tab 栏（自绘 A+B）：底色 = [AppTheme.colors] surface.base（页面底色·吸顶时不透明遮挡下方滚动内容·
 * stickyHeader 必须不透明）。三 Tab 起始排列（gap 28dp）+ 底边一道通栏发丝线（[AppTheme.colors] text.primary@10%·
 * 同 StatsBar 列分隔笔法）。选中态即时落位（无动画）：陶土字 + 加粗 + 略大 + 短下划线（宽=文字宽·靠列内禀宽自然得）；
 * 未选中灰常规、下划线透明占位（保三 Tab 底边齐、始终落在通栏线之上）。横向 20dp 对齐 cardPadding。
 */
@Composable
internal fun ProfileTabBar(selected: ProfileTab, onSelect: (ProfileTab) -> Unit) {
    val haptics = LocalAppHaptics.current
    val hairline = AppTheme.colors.text.primary.copy(alpha = 0.10f)
    Row(
        Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surface.base)
            .drawBehind {
                val y = size.height
                drawLine(
                    color = hairline,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 0.5.dp.toPx(),
                )
            }
            .selectableGroup()
            .padding(start = 20.dp, end = 20.dp, top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        ProfileTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val interaction = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .selectable(
                        selected = isSelected,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Tab,
                        onClick = { haptics.selection(); onSelect(tab) },
                    )
                    .heightIn(min = 48.dp)
                    .width(IntrinsicSize.Max),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    style = if (isSelected) AppTypography.titleSmall else AppTypography.body,
                    color = if (isSelected) AppTheme.colors.accent.text else AppTheme.colors.text.secondary,
                    maxLines = 1,
                )
                Spacer(Modifier.height(9.dp))
                Box(
                    Modifier
                        .height(2.5.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isSelected) AppTheme.colors.accent.text else Color.Transparent),
                )
            }
        }
    }
}
