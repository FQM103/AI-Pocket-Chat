package com.situ.aichat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTextArea
import kotlinx.coroutines.launch

/**
 * 列表内联快捷回复面板（B5·安卓超越 iOS）：列表行长按弹出，不进会话直接回一句。
 * 顶部角色头像+名 + 最近几条可见消息预览（上下文）+ 输入框 + 发送。发送=后台跑一轮 LLM 回复（[ListQuickReplyService]），
 * 面板随即关闭、回到列表——AI 回复落库后列表预览/未读角标自动刷新（Room 流驱动）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickReplySheet(
    row: ChatListViewModel.Row,
    loadRecent: suspend () -> List<MessageEntity>,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // C3-haptics（契约 §2）：快捷面板发送成功=success、关闭=light（仅用户主动 dismiss；发送路径只打 success 不叠加）。
    val haptics = LocalAppHaptics.current
    var input by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(row.conversation.uuid) { recent = loadRecent() }

    fun closeAnimated() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    val youPrefix = stringResource(R.string.chat_list_you_prefix)

    AppSheet(onDismissRequest = { haptics.light(); onDismiss() }, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CharacterAvatar(name = row.displayName, avatarPath = row.character?.avatarPath, size = 40.dp)
                Text(row.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            if (recent.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    recent.forEach { msg ->
                        val prefix = if (msg.roleRaw == "user") youPrefix else ""
                        Text(
                            prefix + MessagePreviewText.forMessage(msg),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            AppTextArea(
                value = input,
                onValueChange = { input = it },
                placeholder = stringResource(R.string.chat_quick_reply_hint),
                minHeight = 56.dp, // 单行起步，随内容长到 maxLines=4
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            AppButton(
                onClick = {
                    haptics.success() // 发送成功（提交即成功——后台 LLM 轮次 fire-and-forget）
                    onSend(input.trim())
                    input = "" // 立即清空 → 按钮禁用，防 hide 动画期间二次点击多落一条重复消息（对抗复核 UI#2）。
                    closeAnimated()
                },
                style = AppButtonStyle.Primary,
                enabled = input.isNotBlank(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.chat_quick_reply_send))
            }
        }
    }
}
