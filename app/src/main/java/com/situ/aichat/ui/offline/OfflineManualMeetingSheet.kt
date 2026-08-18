package com.situ.aichat.ui.offline

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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTextField

/**
 * 手动发起线下见面表单（1:1 iOS `OfflineManualMeetingSheet`，medium detent → ModalBottomSheet）：
 * 地点 + 活动，两者均非空才可提交。[originalMessageContent] 非空 = 长按消息「改成邀约」路径，顶部显示
 * 原文做参考（提交后该消息变邀约卡片）；nil = 「+ 入口发起」路径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineManualMeetingSheet(
    onStart: (location: String, activity: String) -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit = {},
    originalMessageContent: String? = null,
    navTitle: String = "发起线下见面",
    confirmButtonTitle: String = "见面！",
) {
    val sheetState = rememberModalBottomSheetState()
    // 审计 B2（拍板 2026-07-02）：表单字段跨重建存活——填一半转屏/切深色不丢（弹窗开合旗标在 ChatSheetsState 同升）。
    var location by rememberSaveable { mutableStateOf("") }
    var activity by rememberSaveable { mutableStateOf("") }
    val canStart = location.trim().isNotEmpty() && activity.trim().isNotEmpty()
    // offline-2：区分「提交」与「取消/下滑关闭未提交」——只有后者才回调 onCancel（1:1 iOS .sheet onCancel）。
    var committed by remember { mutableStateOf(false) }

    AppSheet(
        onDismissRequest = { if (!committed) onCancel(); onDismiss() },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(navTitle, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))

            if (!originalMessageContent.isNullOrEmpty()) {
                Text("原消息（将作为邀约台词）", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        originalMessageContent,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // 原文仅作语境参考：限 4 行防长消息把下方输入框/确认按钮顶出弹层（2026-07-31 修缮拍板）。
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "根据这条消息的语境，填入地点和活动。提交后这条消息会变成一张邀约卡片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AppTextField(
                value = location,
                onValueChange = { location = it },
                modifier = Modifier.fillMaxWidth(),
                label = "地点",
                placeholder = "星巴克、公园、家里…",
            )
            AppTextField(
                value = activity,
                onValueChange = { activity = it },
                modifier = Modifier.fillMaxWidth(),
                label = "活动",
                placeholder = "喝咖啡、散步、看电影…",
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton(onClick = { onCancel(); onDismiss() }, style = AppButtonStyle.Text, modifier = Modifier.weight(1f)) { Text("取消") }
                AppButton(
                    onClick = {
                        committed = true
                        onStart(location.trim(), activity.trim())
                        onDismiss()
                    },
                    style = AppButtonStyle.Primary,
                    enabled = canStart,
                    modifier = Modifier.weight(1f),
                ) { Text(confirmButtonTitle) }
            }
        }
    }
}
