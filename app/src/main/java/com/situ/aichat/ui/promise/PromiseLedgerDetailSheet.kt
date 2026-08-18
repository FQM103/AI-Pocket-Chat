package com.situ.aichat.ui.promise

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseStatus
import com.situ.aichat.promise.PromiseInjectionRenderer
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.AppTypography
import java.time.ZoneId

/**
 * 约定详情底部 sheet + 二次确认框（记忆改造三期·D-4/D-5·图纸 §4.3/§4.4）。挂 Screen 层（detail != null 时渲染·
 * 照钱包 sheet 先例），内容自上而下：状态 chip / 内容全文 / 信息行组 / 证据引文块（对账了结才有·楷体）/
 * 动作区（仅 open）。手动兜底走 [onConfirm]（Screen 转 markResolved → resolveManually·第四道闸）。
 *
 * 形状 / 容器色走 M3 默认（§4.3·半径阶 28 顶角 + surfaceContainerLow·桥接已品牌化）。[nowMillis]=进页快照。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromiseLedgerDetailSheet(
    detail: PromiseEntity,
    nowMillis: Long,
    onDismiss: () -> Unit,
    onConfirm: (statusRaw: String) -> Unit,
) {
    var confirmStatus by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val cs = MaterialTheme.colorScheme
        val onSurfaceVariant = cs.onSurfaceVariant
        val onWarning = AppTheme.colors.status.onWarning
        val ymdPattern = stringResource(R.string.promise_date_pattern_ymd)
        val mdPattern = stringResource(R.string.promise_date_pattern_md)
        val isOpen = detail.statusRaw == PromiseStatus.OPEN
        val manualText = stringResource(R.string.promise_detail_manual)
        val passedText = stringResource(R.string.promise_due_passed)
        val sourceShort = stringResource(PromiseUiFormat.sourceLabelRes(detail.sourceRaw, short = true))

        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            // 1. 状态 chip。
            StatusChip(detail.statusRaw)

            // 2. 内容全文（不截断）。
            Text(
                detail.content,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
            )

            // 3. 信息行组。
            DetailInfoRow(
                stringResource(R.string.promise_detail_made_label),
                buildAnnotatedString {
                    append(PromiseUiFormat.format(detail.createdAtMillis, ymdPattern))
                    withStyle(SpanStyle(color = onSurfaceVariant)) { append(" · $sourceShort") }
                },
            )
            detail.dueAtMillis?.let { due ->
                val mdDue = PromiseUiFormat.format(due, mdPattern)
                val upcoming = PromiseInjectionRenderer.isDueUpcoming(due, nowMillis, ZoneId.systemDefault())
                val diff = PromiseUiFormat.dueDayDiff(due, nowMillis, ZoneId.systemDefault())
                when {
                    isOpen && upcoming -> {
                        val tail = if (diff > 0) stringResource(R.string.promise_due_days_left, diff.toInt())
                        else stringResource(R.string.promise_due_today)
                        DetailInfoRow(
                            stringResource(R.string.promise_detail_due_label),
                            buildAnnotatedString {
                                append(mdDue)
                                withStyle(SpanStyle(color = onSurfaceVariant)) { append(" · $tail") }
                            },
                        )
                    }
                    isOpen -> {
                        val over = stringResource(R.string.promise_due_days_over, (-diff).toInt())
                        DetailInfoRow(
                            stringResource(R.string.promise_detail_due_was_label),
                            buildAnnotatedString { withStyle(SpanStyle(color = onWarning)) { append("$mdDue · $over") } },
                        )
                    }
                    else -> {
                        DetailInfoRow(
                            stringResource(R.string.promise_detail_due_was_label),
                            buildAnnotatedString {
                                append(mdDue)
                                if (!upcoming) withStyle(SpanStyle(color = onWarning)) { append(" · $passedText") }
                            },
                        )
                    }
                }
            }
            if (!isOpen) {
                DetailInfoRow(
                    stringResource(R.string.promise_detail_resolved_label),
                    buildAnnotatedString {
                        append(PromiseUiFormat.format(detail.resolvedAtMillis ?: 0L, ymdPattern))
                        if (PromiseUiFormat.isManualResolution(detail)) {
                            withStyle(SpanStyle(color = onSurfaceVariant)) { append(" · $manualText") }
                        }
                    },
                )
            }

            // 4. 证据引文块（已了结且对账证据非空·楷体引文正字法）。
            if (!isOpen && detail.resolutionEvidence.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.promise_detail_evidence_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = onSurfaceVariant.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(7.dp))
                Surface(shape = AppShapes.small, color = cs.surfaceContainerHighest) {
                    Text(
                        "“${detail.resolutionEvidence}”",
                        style = AppTypography.kaiQuote,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }

            // 5. 动作区（仅 open·手动兜底第四道闸入口）。
            if (isOpen) {
                Spacer(Modifier.height(18.dp))
                AppButton(
                    onClick = { confirmStatus = PromiseStatus.FULFILLED },
                    style = AppButtonStyle.Primary,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.promise_action_fulfill)) }
                Spacer(Modifier.height(8.dp))
                AppButton(
                    onClick = { confirmStatus = PromiseStatus.CANCELLED },
                    style = AppButtonStyle.Text,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.promise_action_cancel)) }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.promise_sheet_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    // 二次确认框（§4.4）：点击只置屏级 confirmStatus，确认才落库（照钱包警告先例）。
    val status = confirmStatus
    if (status != null) {
        val isFulfill = status == PromiseStatus.FULFILLED
        AppDialog(
            onDismissRequest = { confirmStatus = null },
            title = stringResource(
                if (isFulfill) R.string.promise_confirm_fulfill_title else R.string.promise_confirm_cancel_title,
            ),
            body = stringResource(
                if (isFulfill) R.string.promise_confirm_body_fulfill else R.string.promise_confirm_body_cancel,
                detail.content,
            ),
            confirmText = stringResource(R.string.promise_confirm_go),
            onConfirm = { onConfirm(status); confirmStatus = null },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { confirmStatus = null },
        )
    }
}

/** 状态 chip（§4.3·open 陶土软填 / fulfilled 绿 / cancelled 灰）。 */
@Composable
private fun StatusChip(statusRaw: String) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg, textRes) = when (statusRaw) {
        PromiseStatus.FULFILLED -> Triple(AppTheme.colors.status.successContainer, AppTheme.colors.status.onSuccess, R.string.promise_status_fulfilled)
        PromiseStatus.CANCELLED -> Triple(cs.surfaceContainerHighest, cs.onSurfaceVariant, R.string.promise_status_cancelled)
        else -> Triple(cs.primary.copy(alpha = 0.10f), AppTheme.colors.accent.text, R.string.promise_status_open)
    }
    Surface(shape = AppShapes.full, color = bg) {
        Text(
            stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 4.dp),
        )
    }
}

/** 信息行：label（宽 48dp·tx2 淡）+ 值（AnnotatedString·副段色由调用方指定）。 */
@Composable
private fun DetailInfoRow(label: String, value: androidx.compose.ui.text.AnnotatedString) {
    Row(Modifier.padding(vertical = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.width(48.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
