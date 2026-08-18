package com.situ.aichat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography

/**
 * 聊天输入栏「+」功能面板的一格（图标 + 标签 + 点击动作 + 可用性）。
 */
data class ChatPanelItem(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * 聊天输入栏「+」功能面板（风格 B 浮起白卡·契约 FABLE5_CHAT_PLUS_PANEL_PROPOSAL.md §4）：
 * **一排** N 格浮起白卡 tile（非微信两行八宫格——本品功能少，一排更精致），接替键盘位置铺在输入托盘下方。
 *
 * 图标=自绘 [com.situ.aichat.ui.designsystem.AppPanelIcons]；tile 按压回弹（clickableScale·内置 reduceMotion
 * 门控）+ 选中触觉 + Role.Button。待 C2 续：壁纸态毛玻璃面板（有壁纸时同 §4 五要素）。
 */
@Composable
fun ChatFunctionPanel(
    items: List<ChatPanelItem>,
    modifier: Modifier = Modifier,
    labelColor: Color? = null, // 非空=玻璃上亮度自适应字色（§4 要素⑤）；空=无壁纸默认 text.secondary。
) {
    // 两排网格（方案 A·左对齐）：每排最多 [PANEL_COLUMNS] 格、按列等宽；不足一排的格留在左侧，尾列用等宽占位补齐
    // （绝不拉伸/居中），右下空位天然留给以后扩展。通话已移到聊天顶栏右上角，面板承载 送礼/红包/表情 + 见面/约见面。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, start = 8.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items.chunked(PANEL_COLUMNS).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                rowItems.forEach { item ->
                    ChatPanelTile(item = item, modifier = Modifier.weight(1f), labelColor = labelColor)
                }
                repeat(PANEL_COLUMNS - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val PANEL_COLUMNS = 3 // 面板每排格数（方案 A 网格·左对齐）

@Composable
private fun ChatPanelTile(
    item: ChatPanelItem,
    modifier: Modifier = Modifier,
    labelColor: Color? = null,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val iconTint = if (item.enabled) colors.accent.text else colors.text.tertiary
    Column(
        modifier = modifier
            // 启用：按压回弹(clickableScale 内置 reduceMotion 门控)+ 选中触觉 + Role.Button；禁用：纯展示不可点。
            .then(
                if (item.enabled) {
                    Modifier.clickableScale(pressedScale = 0.94f, role = Role.Button) {
                        haptics.selection()
                        item.onClick()
                    }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                // 浅档：发丝边 + 极浅柔投影（明度分层浮起）；深档：去投影、改 1px 暖灰描边替（设计语言 §1.2）。
                .then(if (colors.isDark) Modifier else Modifier.shadow(2.dp, AppShapes.medium, clip = false))
                .clip(AppShapes.medium)
                .background(colors.surface.raised)
                .border(0.5.dp, colors.surface.stroke, AppShapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null, // 标签即无障碍名，整 tile 的语义在 C2 收口
                tint = iconTint,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.label,
            style = AppTypography.caption,
            color = labelColor ?: colors.text.secondary,
            textAlign = TextAlign.Center,
        )
    }
}
