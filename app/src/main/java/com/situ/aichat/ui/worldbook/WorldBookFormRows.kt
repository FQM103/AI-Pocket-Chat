package com.situ.aichat.ui.worldbook

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppDropdownField
import com.situ.aichat.ui.designsystem.AppDropdownMenuItem
import com.situ.aichat.ui.designsystem.AppStepper
import com.situ.aichat.ui.designsystem.AppTheme

/** 设定集表单行件（WB7b/WB7c 共用：条目编辑器高级组 + 触发设置页）。 */

/** 左标签（+可选提示）右步进器。 */
@Composable
internal fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    valueText: String,
    onChange: (Int) -> Unit,
    hint: String? = null,
) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (label.isNotEmpty()) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.text.primary,
                    modifier = Modifier.weight(1f),
                )
            }
            AppStepper(
                value = value,
                valueText = valueText,
                range = range,
                onValueChange = onChange,
                modifier = if (label.isEmpty()) Modifier.fillMaxWidth() else Modifier,
            )
        }
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.text.secondary)
        }
    }
}

/** 只读下拉行（一处收口 expanded 状态）。 */
@Composable
internal fun DropdownRow(label: String, value: String, options: List<Pair<String, () -> Unit>>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    AppDropdownField(
        value = value,
        expanded = expanded,
        onExpandedChange = { expanded = it },
        label = label,
    ) {
        options.forEach { (text, onPick) ->
            AppDropdownMenuItem(
                text = text,
                onClick = {
                    onPick()
                    expanded = false
                },
                selected = text == value,
            )
        }
    }
}

/** Boolean? 三态行：跟随全局 / 开启 / 关闭（+可选警示文案）。 */
@Composable
internal fun TriStateRow(label: String, value: Boolean?, onPick: (Boolean?) -> Unit, warning: String? = null) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DropdownRow(
            label = label,
            value = stringResource(
                when (value) {
                    null -> R.string.wb_follow_global
                    true -> R.string.wb_on
                    false -> R.string.wb_off
                },
            ),
            options = listOf(
                stringResource(R.string.wb_follow_global) to { onPick(null) },
                stringResource(R.string.wb_on) to { onPick(true) },
                stringResource(R.string.wb_off) to { onPick(false) },
            ),
        )
        warning?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.status.onWarning)
        }
    }
}
