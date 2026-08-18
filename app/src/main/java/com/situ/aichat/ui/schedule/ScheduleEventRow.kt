package com.situ.aichat.ui.schedule

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import com.situ.aichat.R
import com.situ.aichat.ui.components.rememberReduceMotion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.local.entity.ScheduleEventEntity

/**
 * 日程时间线的单事件行（P14.2，资料页今日卡 + 全天视图共用）。1:1 iOS `ScheduleEventRow`：
 * 左侧时间线指示器（圆点 marker + 连线 + 末端三角）+ 右侧时段标签/活动/地点心情/内心独白。
 *
 * 时态 [timeState] 由父决定（父用 [ScheduleTimelineLogic] 算），行内只负责渲染，不自判。
 * **行本身无点击**——全天视图的 userInteraction 行由父包 clickable（gotcha：点击范围归父决定）。
 * 时间不显示 HH:mm，只以 periodLabel + 时间线相对位置表达（1:1 iOS）。
 */
@Composable
fun ScheduleEventRow(
    event: ScheduleEventEntity,
    timeState: TimeState,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    showInnerThought: Boolean = true,
) {
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    // 未来事件整行半透明（PAST 不置灰，只 marker 变灰圆）；1:1 iOS contentOpacity。
    val contentAlpha = if (timeState == TimeState.FUTURE) 0.5f else 1f

    // P1-18：整行单节点语义（iOS 日程行零语义 a11y=安卓超越）。cd 序=时段·活动·地点·心情·独白·互动
    //（与视觉序一致）；心情三级兜底见 ScheduleRowA11y；独白用裸文不带视觉弯引号、尊重 showInnerThought；
    // stateDescription=进行中/已结束/未开始（TimeState 父算好传入零新时间数学）。clearAndSetSemantics
    // 在 modifier 参数链之后——全天视图的 clickable 在参数里=祖先节点不被清，点击+onClickLabel 存活。
    val activityText = ScheduleTimelineLogic.activityText(event.activity, event.relatedCharacterNames)
    val mappedMood = ScheduleRowA11y.moodResId(event.moodEmoji)?.let { stringResource(it) }
    val mood = ScheduleRowA11y.moodSegment(event.moodText, mappedMood, event.moodEmoji)
    val thoughtForA11y = event.innerThought?.takeIf { showInnerThought && it.isNotBlank() }
    val interaction = if (event.eventTypeRaw == USER_INTERACTION_TYPE) {
        stringResource(R.string.a11y_schedule_interaction)
    } else {
        null
    }
    val rowCd = ScheduleRowA11y.contentDescription(
        periodLabel = event.periodLabel,
        activityText = activityText,
        location = event.location,
        mood = mood,
        innerThought = thoughtForA11y,
        interactionLabel = interaction,
    )
    val stateLabel = stringResource(
        when (timeState) {
            TimeState.CURRENT -> R.string.a11y_schedule_state_ongoing
            TimeState.PAST -> R.string.a11y_schedule_state_ended
            TimeState.FUTURE -> R.string.a11y_schedule_state_upcoming
        },
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .alpha(contentAlpha)
            .clearAndSetSemantics {
                contentDescription = rowCd
                stateDescription = stateLabel
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TimelineIndicator(
            event = event,
            timeState = timeState,
            isLast = isLast,
            accent = accent,
            secondary = secondary,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = event.periodLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (timeState == TimeState.CURRENT) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = activityText,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Place,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = secondary,
                )
                Text(event.location, style = MaterialTheme.typography.bodySmall, color = secondary)
                Text(event.moodEmoji, style = MaterialTheme.typography.bodySmall)
                val moodText = event.moodText
                if (!moodText.isNullOrEmpty()) {
                    Text(moodText, style = MaterialTheme.typography.bodySmall, color = secondary)
                }
            }
            val thought = event.innerThought
            if (showInnerThought && !thought.isNullOrEmpty()) {
                Text(
                    text = "“$thought”",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = secondary,
                )
            }
        }
    }
}

/** 左侧时间线：顶部圆点/星标 + 向下连线（最后一项收末端三角）。宽 10dp，竖向填满行高。 */
@Composable
private fun TimelineIndicator(
    event: ScheduleEventEntity,
    timeState: TimeState,
    isLast: Boolean,
    accent: Color,
    secondary: Color,
) {
    Column(
        modifier = Modifier
            .width(10.dp)
            .fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        Marker(event = event, timeState = timeState, accent = accent, secondary = secondary)
        TimelineLine(
            isLast = isLast,
            dashed = timeState == TimeState.FUTURE,
            color = secondary.copy(alpha = 0.2f),
            modifier = Modifier
                .width(10.dp)
                .weight(1f),
        )
    }
}

@Composable
private fun Marker(event: ScheduleEventEntity, timeState: TimeState, accent: Color, secondary: Color) {
    // userInteraction（聊天/线下见面写回的事件）恒为星标，无视时态（1:1 iOS）。
    if (event.eventTypeRaw == USER_INTERACTION_TYPE) {
        Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(10.dp), tint = accent)
        return
    }
    when (timeState) {
        TimeState.PAST ->
            Box(Modifier.size(10.dp).clip(CircleShape).background(secondary.copy(alpha = 0.5f)))
        TimeState.CURRENT -> {
            // 进行中：accent 实心圆 + 缓慢脉冲缩放（0.8↔1.2），1:1 iOS easeInOut 1.5s repeatForever。
            // P1-23：RM 静止=scale 恰 1.0（=iOS ScheduleEventRow.swift:92 `reduceMotion ? 1 : pulseScale`，
            // 绝非 0.8/1.2 端点）——1:1 门控（iOS 真读了 RM）。一处改动覆盖全天视图+资料页今日卡两消费方。
            val scale = if (rememberReduceMotion()) {
                1f
            } else {
                val transition = rememberInfiniteTransition(label = "scheduleMarkerPulse")
                transition.animateFloat(
                    initialValue = 0.8f,
                    targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
                    label = "scheduleMarkerScale",
                ).value
            }
            Box(Modifier.size(10.dp).scale(scale).clip(CircleShape).background(accent))
        }
        TimeState.FUTURE ->
            Box(Modifier.size(10.dp).border(1.5.dp, secondary.copy(alpha = 0.3f), CircleShape))
    }
}

@Composable
private fun TimelineLine(isLast: Boolean, dashed: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.padding(top = 4.dp)) {
        val x = size.width / 2f
        val strokeW = 1.5.dp.toPx()
        if (isLast) {
            // 末端：虚线 + 向下小三角收尾（1:1 iOS TimelineEndIndicator）。
            val triH = 5.dp.toPx()
            val triW = 8.dp.toPx()
            val lineEnd = (size.height - triH - 2.dp.toPx()).coerceAtLeast(0f)
            drawLine(
                color = color,
                start = Offset(x, 0f),
                end = Offset(x, lineEnd),
                strokeWidth = strokeW,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
            val tri = Path().apply {
                moveTo(x - triW / 2f, size.height - triH)
                lineTo(x + triW / 2f, size.height - triH)
                lineTo(x, size.height)
                close()
            }
            drawPath(tri, color.copy(alpha = 0.6f))
        } else {
            drawLine(
                color = color,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = strokeW,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null,
            )
        }
    }
}

/** iOS `ScheduleEvent.EventType.userInteraction` 的 rawValue（聊天/线下见面写回）。 */
private const val USER_INTERACTION_TYPE = "userInteraction"
