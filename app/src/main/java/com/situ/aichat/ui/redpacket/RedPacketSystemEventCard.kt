package com.situ.aichat.ui.redpacket

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.data.model.SystemEventData
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 红包系统事件居中灰字卡（行为 1:1 iOS `SystemEventCard` 红包极简样式）：红包被收/拒/过期时插入聊天流的居中灰字
 * （emoji + title + 「·」+ 月日时分，如「🧧 你收下了小七的红包 · 4月22日 15:45」），**不含金额**（金额只在气泡/详情显示）。
 *
 * Fable-5 换装（契约 §2）：sunken 暖灰 full 胶囊 + 13sp secondary 文字 + 时间戳 11sp tnum（时间=功能小字走
 * text.secondary·WCAG 决议③）；「·」纯装饰走 text.tertiary。
 *
 * 纯展示：[event] 由调用方解析自 SYSTEM_EVENT_CARD 消息（[com.situ.aichat.data.model.SystemEventJson]）。
 */
@Composable
fun RedPacketSystemEventCard(event: SystemEventData, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    // redpacket-2：解析 event.timestamp → 「月日时分」（= iOS DateFormatters.dateMDHM）；解析失败/空 → ""（隐藏点+时间）。
    val time = remember(event.timestamp) { formatEventTime(event.timestamp) }
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .clip(AppShapes.full)
                .background(colors.surface.sunken)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), // iOS HStack(spacing: 6)
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1:1 iOS：emoji 与 title 是 HStack 内两个独立 Text（间距 6 由 Row 提供）；title 灰、最多 2 行居中。
            Text(text = event.emoji, style = typography.secondary)
            Text(
                text = event.title,
                style = typography.secondary,
                color = colors.text.secondary,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
            // 1:1 iOS redPacketMinimalBody：formattedTime 非空才显示「·」+ 时间。
            if (time.isNotEmpty()) {
                Text(
                    text = "·",
                    style = typography.secondary,
                    color = colors.text.tertiary,
                )
                Text(
                    text = time,
                    style = typography.captionNumeric,
                    color = colors.text.secondary,
                )
            }
        }
    }
}

// 模板只含数字/字面量，用 Locale.ROOT 恒输出 ASCII 数字（与通话记录时间戳一致，避免非拉丁数字区域乱码）。
private val eventTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.ROOT)

/** ISO-8601 → "M月d日 HH:mm"（= iOS `DateFormatters.dateMDHM`）；空串/解析失败 → ""（对齐 iOS `formattedTime.isEmpty` 隐藏）。 */
private fun formatEventTime(iso: String): String =
    if (iso.isBlank()) {
        ""
    } else {
        runCatching {
            Instant.parse(iso).atZone(ZoneId.systemDefault()).format(eventTimeFormatter)
        }.getOrDefault("")
    }
