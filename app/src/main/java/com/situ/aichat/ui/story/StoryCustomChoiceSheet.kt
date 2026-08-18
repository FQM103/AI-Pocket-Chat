package com.situ.aichat.ui.story

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTheme
import kotlinx.coroutines.delay

/**
 * 自由输入选择面板（1:1 iOS `StoryCustomChoiceSheet`）。多行输入 + 字数提示；确认后把 trim 文本交给
 * [onConfirm]（上层据此进入 5 秒反悔窗口，与点选项同路）。空文本禁用确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryCustomChoiceSheet(
    prompt: String,
    hint: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val trimmed = text.trim()

    LaunchedEffect(Unit) {
        delay(250) // 等 Sheet 呈现动画完成再弹键盘，避免布局跳动（= iOS）
        runCatching { focusRequester.requestFocus() }
    }

    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.story_custom_choice_title),
                style = AppTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(prompt, style = AppTheme.typography.listName, fontFamily = FontFamily.Serif)
            Text(hint, style = AppTheme.typography.secondary, color = AppTheme.colors.text.secondary)

            // P1-20·1:1 补缺：iOS TextEditor accessibilityLabel「输入自定义选择」（StoryCustomChoiceSheet.swift:107）。
            val fieldA11y = stringResource(R.string.story_choice_free_input_a11y)
            AppTextArea(
                value = text,
                onValueChange = { text = it },
                placeholder = stringResource(R.string.story_custom_choice_placeholder),
                minHeight = 160.dp,
                // 封顶后字段内部滚动：长走向指令不再把确认键顶出屏幕（自由输入无字数上限）
                maxLines = 8,
                focusRequester = focusRequester,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = fieldA11y },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.story_custom_choice_footer),
                    style = AppTheme.typography.secondary,
                    color = AppTheme.colors.text.secondary.copy(alpha = 0.7f),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.story_custom_choice_count, text.length),
                    style = AppTheme.typography.secondary,
                    color = AppTheme.colors.text.secondary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                AppButton(onClick = onDismiss, style = AppButtonStyle.Text) { Text(stringResource(R.string.action_cancel)) }
                AppButton(
                    onClick = {
                        if (trimmed.isNotEmpty()) {
                            onConfirm(trimmed)
                            onDismiss()
                        }
                    },
                    style = AppButtonStyle.Primary,
                    enabled = trimmed.isNotEmpty(),
                ) { Text(stringResource(R.string.action_confirm)) }
            }
        }
    }
}
