package com.situ.aichat.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppDialog

/**
 * 主动后台可靠性引导对话框（13.7a）。用户首次开启依赖后台的功能时弹一次（[com.situ.aichat.work.ReliabilityPromptController]
 * 决定时机/一次性）。大白话讲清「不设置会导致提醒不准时」，一键去后台运行保障页（电池 + 自启动两张卡都在那）。
 */
@Composable
fun ReliabilityPromptDialog(
    onGoToSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 电池 icon 随 M3 清零卷一有意去除（图纸 §2.3 V2·总契约 §2.1「无 icon 槽·文字优先」）。
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.reliability_prompt_title),
        body = stringResource(R.string.reliability_prompt_body),
        confirmText = stringResource(R.string.reliability_prompt_go),
        onConfirm = onGoToSettings,
        dismissText = stringResource(R.string.reliability_prompt_later),
        onDismiss = onDismiss,
    )
}
