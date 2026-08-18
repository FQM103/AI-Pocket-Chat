package com.situ.aichat.ui.contextlog

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.diagnostics.LogToolInfo
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 日志详情「工具调用」节（上下文日志工具可见性·2026-07-12）：让用户在日志页就能看清
 * 「本轮带了哪些工具、AI 调没调用、解析成了什么、有没有降级」——此前这段链路在日志里完全不可见。
 * 数据 = [LogToolInfo]（仅聊天管线落库；旧行/后台生成路为空 → 调用方整节隐藏，不误示「未调用」）。
 * 视觉沿用本页既有 [SectionCard] 与行样式，零新 token。
 */
@Composable
fun ToolCallCard(info: LogToolInfo) {
    SectionCard("工具调用") {
        if (info.mode == LogToolInfo.MODE_MARKER) {
            NoteText(
                "本轮走文本暗号轨：未随请求下发工具，线下见面 / 约定等指令以文字标记形式注入" +
                    "（当前模型不支持工具调用，或本轮媒体降级重装配）。",
            )
            return@SectionCard
        }
        ToolKvRow("下发工具", "${info.sentTools.size} 个")
        if (info.sentTools.isNotEmpty()) {
            Text(
                info.sentTools.joinToString("、"),
                style = AppTheme.typography.caption,
                color = AppTheme.colors.text.secondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (info.calls.isEmpty()) {
            NoteText("模型本轮未发起任何工具调用。")
        } else {
            ToolKvRow("模型发起调用", "${info.calls.size} 次")
            for (call in info.calls) {
                Text("· ${call.name}", style = AppTheme.typography.secondary, color = AppTheme.colors.text.primary)
                call.argsPreview?.let {
                    Text(
                        it,
                        style = AppTheme.typography.caption,
                        color = AppTheme.colors.text.secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val parsed = buildList {
                if (info.parsedCalendarActions > 0) add("日历 ${info.parsedCalendarActions}")
                if (info.parsedOfflineActions > 0) add("线下见面 ${info.parsedOfflineActions}")
                if (info.parsedMeetingCandidates > 0) add("约见面 ${info.parsedMeetingCandidates}")
            }
            if (parsed.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                NoteText("解析产出：${parsed.joinToString(" · ")}")
            }
        }
        if (info.fellBackToPlainText) {
            Spacer(Modifier.height(6.dp))
            NoteText("注：工具流失败或调用全部解析失败，本轮已自动降级为纯文本重发。")
        }
        if (info.usedTextFollowUp) {
            Spacer(Modifier.height(6.dp))
            NoteText("模型只回了调用没回正文——已回喂工具结果、取回文字回复。")
        }
    }
}

@Composable
private fun ToolKvRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = AppTheme.typography.secondary, color = AppTheme.colors.text.secondary)
        Spacer(Modifier.weight(1f))
        Text(v, style = AppTheme.typography.secondary, color = AppTheme.colors.text.primary)
    }
}

@Composable
private fun NoteText(text: String) {
    Text(text, style = AppTheme.typography.caption, color = AppTheme.colors.text.secondary)
}
