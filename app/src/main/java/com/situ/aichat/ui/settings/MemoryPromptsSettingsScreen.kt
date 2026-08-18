package com.situ.aichat.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.SettingsSection
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 记忆提示词段（14.5b·1:1 iOS `MemoryPromptSettingsView`）：提取提示词 + 注入提示词双多行编辑器 +
 * 恢复默认 + 宏说明。向量阈值滑块在 [MemorySettingsSections]（安卓 ONNX 向量层）故此处不重复（iOS 在该视图顶部带
 * 同一阈值滑块，安卓信息架构里它另有归属）。模板内容为中文常量（与解析格式耦合·同 iOS），UI 文案才本地化。
 * SETTINGS_REORG D3 起为 [MemoryHubScreen] 下半段：壳（Scaffold / 滚动 / 标题）在 hub，此处只出内容。
 */
@Composable
fun MemoryPromptsSections(viewModel: MemoryPromptsSettingsViewModel = hiltViewModel()) {
    val extraction by viewModel.extraction.collectAsStateWithLifecycle()
    val injection by viewModel.injection.collectAsStateWithLifecycle()

    Column {
        // 提取提示词
        SettingsSection(
            title = stringResource(R.string.mem_prompts_extraction_title),
            footer = stringResource(R.string.mem_prompts_extraction_macros),
        ) {
            CaptionText(stringResource(R.string.mem_prompts_extraction_desc))
            PromptEditor(
                value = extraction,
                onValueChange = viewModel::onExtractionChange,
                minHeight = 260.dp,
            )
            ResetButton(onClick = viewModel::resetExtraction)
        }

        // 注入提示词
        SettingsSection(
            title = stringResource(R.string.mem_prompts_injection_title),
            footer = stringResource(R.string.mem_prompts_injection_macros),
        ) {
            CaptionText(stringResource(R.string.mem_prompts_injection_desc))
            PromptEditor(
                value = injection,
                onValueChange = viewModel::onInjectionChange,
                minHeight = 140.dp,
            )
            ResetButton(onClick = viewModel::resetInjection)
        }

        // 宏说明
        SettingsSection(title = stringResource(R.string.mem_prompts_macros_title)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MacroRow("{{聊天记录}}", stringResource(R.string.mem_prompts_macro_chatlog))
                MacroRow("{{已有记忆}}", stringResource(R.string.mem_prompts_macro_existing))
                MacroRow("{{当前时间}}", stringResource(R.string.mem_prompts_macro_now))
                MacroRow("{{最大字数}}", stringResource(R.string.mem_prompts_macro_maxchars))
                MacroRow("{{当前字数}}", stringResource(R.string.mem_prompts_macro_curchars))
                MacroRow("{{压缩策略}}", stringResource(R.string.mem_prompts_macro_compress))
                MacroRow("{{记忆内容}}", stringResource(R.string.mem_prompts_macro_content))
                MacroRow("{{char}}", stringResource(R.string.mem_prompts_macro_char))
                MacroRow("{{user}}", stringResource(R.string.mem_prompts_macro_user))
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CaptionText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun PromptEditor(value: String, onValueChange: (String) -> Unit, minHeight: Dp) {
    AppTextArea(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        minHeight = minHeight,
        highlightMacros = true,
    )
}

@Composable
private fun ResetButton(onClick: () -> Unit) {
    AppButton(onClick = onClick, style = AppButtonStyle.Text, modifier = Modifier.padding(horizontal = 8.dp)) {
        Icon(Icons.Filled.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.mem_prompts_reset_default))
    }
}

@Composable
private fun MacroRow(macro: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = macro,
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.accent.text,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
