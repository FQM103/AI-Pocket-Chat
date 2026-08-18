package com.situ.aichat.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppListDivider
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 联系人长按动作面板（图纸一 #4·§4.1）：头部角色锚定（头像 + 名）+ 三动作行（查看资料 / 编辑 / 删除）。
 *
 * 只呈现动作不执行业务：三回调透传给 Screen，由现有链承接（导航 / `pendingDelete` 确认弹窗）。容器逐值照
 * `QuickReplySheet` 惯例（ModalBottomSheet + navigationBarsPadding + 20/16dp padding + 头部 40dp 头像 · 12dp 间距）。
 * 触觉档位（锁定）：dismiss=light、动作行=light（长按弹出的 medium 在触发侧 ContactRow）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactActionSheet(
    character: CharacterEntity,
    onDismiss: () -> Unit,
    onViewProfile: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptics = LocalAppHaptics.current
    AppSheet(
        onDismissRequest = { haptics.light(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CharacterAvatar(name = character.name, avatarPath = character.avatarPath, size = 40.dp)
                Text(
                    character.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            ActionRow(
                text = stringResource(R.string.a11y_contact_open_profile),
                color = MaterialTheme.colorScheme.onSurface,
                onClick = onViewProfile,
            )
            AppListDivider(startInset = 0.dp)
            ActionRow(
                text = stringResource(R.string.action_edit),
                color = MaterialTheme.colorScheme.onSurface,
                onClick = onEdit,
            )
            AppListDivider(startInset = 0.dp)
            ActionRow(
                text = stringResource(R.string.action_delete),
                color = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
        }
    }
}

/** 动作行：52dp 高（≥48dp 触达）· 文字居中（微信 ActionSheet 式）· 点按 light 触觉。不带图标（避免为 3 项扩图标族）。 */
@Composable
private fun ActionRow(text: String, color: Color, onClick: () -> Unit) {
    val haptics = LocalAppHaptics.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable { haptics.light(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AppTheme.typography.bodyEmphasis, color = color)
    }
}
