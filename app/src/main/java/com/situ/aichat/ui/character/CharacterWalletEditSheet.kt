package com.situ.aichat.ui.character

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppStepper
import com.situ.aichat.ui.designsystem.AppTextField

/**
 * 角色月薪 + 发薪日编辑面板（14.6b·💰涉钱写·1:1 iOS `CharacterWalletEditSheet`）。
 *
 * 月薪 TextField（数字键盘·空=未推断）+ 发薪日步进器（1-28·避月末边界）。保存经 [onSave] 抛给 VM 落库 +
 * 触发入职储蓄（VM 内 clamp/解析/onboarding）。初值规则 1:1 iOS：仅「已推断」才回填月薪数字，否则留空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterWalletEditSheet(
    initialSalary: Int,
    salaryInferred: Boolean,
    initialSalaryDay: Int,
    onDismiss: () -> Unit,
    onSave: (salaryText: String, salaryDay: Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // E1#0：月薪输入升 rememberSaveable（涉钱表单·转屏/进程死亡不丢编辑中值）；saved 是瞬态成功位保持 remember。
    var salaryText by rememberSaveable {
        mutableStateOf(if (salaryInferred && initialSalary >= 0) initialSalary.toString() else "")
    }
    var salaryDay by rememberSaveable { mutableStateOf(initialSalaryDay.coerceIn(1, 28)) }
    var saved by remember { mutableStateOf(false) }

    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.wallet_edit_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            // 月薪
            AppTextField(
                value = salaryText,
                onValueChange = { input -> salaryText = input.filter { it.isDigit() }.take(5) },
                label = stringResource(R.string.wallet_edit_salary),
                suffix = stringResource(R.string.wallet_coins_unit),
                supportingText = stringResource(R.string.wallet_edit_salary_footer),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            // 发薪日步进器（1-28）
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.wallet_edit_payday), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    AppStepper(
                        value = salaryDay,
                        valueText = stringResource(R.string.wallet_edit_payday_value, salaryDay),
                        range = 1..28,
                        onValueChange = { salaryDay = it },
                    )
                }
                Text(
                    stringResource(R.string.wallet_edit_payday_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                AppButton(onClick = onDismiss, style = AppButtonStyle.Text) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                AppButton(
                    onClick = {
                        if (!saved) {
                            saved = true
                            onSave(salaryText, salaryDay)
                            onDismiss()
                        }
                    },
                    style = AppButtonStyle.Tonal,
                    enabled = !saved,
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}
