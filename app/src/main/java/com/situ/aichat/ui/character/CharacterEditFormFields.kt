package com.situ.aichat.ui.character

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.situ.aichat.ui.designsystem.AppSlider
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTextField
import kotlin.math.roundToInt

// 角色编辑屏的通用表单积木（从 CharacterEditScreen 抽出·纯搬 composable）。
// SectionHeader/SectionFooter(段标题/脚注) + FormField(单/多行软填充字段) + DimensionSlider(0-100 维度滑块行)
// 均 internal 供主屏与各段(CharacterEditVoiceSection)跨文件复用。

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

/** 成长维度滑块行（0–100，整数）：名称 + 当前值 + Slider + 左右端说明（1:1 iOS dimensionSlider）。 */
@Composable
internal fun DimensionSlider(name: String, hint: String, value: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(value.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 无障碍（14.7e）：M3 Slider 自带数值语义但不含维度名（名字在同级 Text 节点）→ 焦点落滑杆只读数字。
        // 给 contentDescription = 维度名，让其播报「理性度, 80」。
        AppSlider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..100f,
            modifier = Modifier.semantics { contentDescription = name },
        )
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SectionFooter(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    footer: String? = null,
    singleLine: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    // 输入框重构 §6.1：原私有 helper 以 minLines=if(singleLine)1 else 3 同时承载单/多行所有角色字段；
    // 按 singleLine 拆两路软填充件 — 单行 AppTextField（透传 keyboardType，如 fixedAge 数字键盘），
    // 多行 AppTextArea。label=框上方静态标签（范式 A）；placeholder 空串沿用「空=不显」语义。
    if (singleLine) {
        AppTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = label,
            placeholder = placeholder.ifEmpty { null },
            supportingText = footer,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
    } else {
        AppTextArea(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = label,
            placeholder = placeholder.ifEmpty { null },
            supportingText = footer,
        )
    }
}
