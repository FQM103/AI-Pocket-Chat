package com.situ.aichat.ui.contextlog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.situ.aichat.diagnostics.LogListRow
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.ContextSegment
import com.situ.aichat.ui.designsystem.AppTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * D-3 上下文日志 UI 共享件 + 取值口径（卡片表面 / 状态圈 / 分段位置着色 / 时间·耗时格式）。
 * 颜色全经 [AppTheme] semantic/feature token（陶土 accent·status 双档·economy.gold），不直引 Palette。
 */

private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val fullTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

/** 列表用短时间「14:32」。 */
fun formatLogTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFmt)

/** 详情用全时间「2026-06-18 14:32」。 */
fun formatLogTimeFull(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(fullTimeFmt)

/** 耗时「2.4s」；null → null（不显）。 */
fun formatDuration(millis: Long?): String? =
    millis?.let { String.format(Locale.ROOT, "%.1fs", it / 1000.0) }

/** 卡片主名：有角色名显角色名，取不到（用户级/故事等）回退显来源（spec §3.8 坑）。 */
fun primaryName(entry: LogListRow): String =
    entry.characterName.ifBlank { entry.source }

/** 来源徽标只在非「对话」时显（1:1 iOS：对话是默认场景不挂徽标）。 */
fun showSourceBadge(entry: LogListRow): Boolean = entry.source != LogSource.CHAT

/** 角色名为空时主名已回退显来源 → 不再重复显徽标（避免「故事生成 · 故事生成」）。 */
fun showSourceBadgeResolved(entry: LogListRow): Boolean =
    showSourceBadge(entry) && entry.characterName.isNotBlank()

/** 分段位置中文标签。 */
fun positionLabel(position: String): String = when (position) {
    ContextSegment.POSITION_PREFIX -> "前置"
    ContextSegment.POSITION_HISTORY -> "历史"
    ContextSegment.POSITION_SUFFIX -> "后置"
    else -> position
}

/** 分段占比条 / 位置徽标着色（前置=info 蓝档 / 历史=陶土 / 后置=success 绿档，D3-6 拍板）。 */
@Composable
fun positionFill(position: String): Color = when (position) {
    ContextSegment.POSITION_PREFIX -> AppTheme.colors.status.onInfo
    ContextSegment.POSITION_HISTORY -> AppTheme.colors.accent.primary
    ContextSegment.POSITION_SUFFIX -> AppTheme.colors.status.onSuccess
    else -> AppTheme.colors.text.secondary
}

@Composable
fun positionContainer(position: String): Color = when (position) {
    ContextSegment.POSITION_PREFIX -> AppTheme.colors.status.infoContainer
    ContextSegment.POSITION_HISTORY -> AppTheme.colors.surface.sunken
    ContextSegment.POSITION_SUFFIX -> AppTheme.colors.status.successContainer
    else -> AppTheme.colors.surface.sunken
}

@Composable
fun positionOn(position: String): Color = when (position) {
    ContextSegment.POSITION_PREFIX -> AppTheme.colors.status.onInfo
    ContextSegment.POSITION_HISTORY -> AppTheme.colors.accent.text
    ContextSegment.POSITION_SUFFIX -> AppTheme.colors.status.onSuccess
    else -> AppTheme.colors.text.secondary
}

/** 成功/失败状态圈（成功=success 双档 ✓、失败=error 双档 ✕；26dp 软圆角圆，Fable-5 无锐角）。 */
@Composable
fun StatusGlyph(isSuccess: Boolean, modifier: Modifier = Modifier) {
    val container = if (isSuccess) AppTheme.colors.status.successContainer else AppTheme.colors.status.errorContainer
    val on = if (isSuccess) AppTheme.colors.status.onSuccess else AppTheme.colors.status.onError
    Box(
        modifier = modifier
            .size(26.dp)
            .background(container, RoundedCornerShape(percent = 50)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isSuccess) Icons.Filled.Check else Icons.Filled.Close,
            contentDescription = null,
            tint = on,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** Fable-5 raised 卡面 modifier：暖白纸 + 1px 明度描边代投影（深色靠亮度阶梯，1:1 设计语言 §3）+ medium 16dp 角。 */
@Composable
fun Modifier.logCard(): Modifier = this
    .background(AppTheme.colors.surface.raised, AppTheme.shapes.medium)
    .border(1.dp, AppTheme.colors.surface.stroke, AppTheme.shapes.medium)
    .padding(horizontal = 13.dp, vertical = 12.dp)
