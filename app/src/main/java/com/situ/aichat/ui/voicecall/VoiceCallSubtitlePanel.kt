package com.situ.aichat.ui.voicecall

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R

/**
 * 实时字幕深玻璃条（FABLE5_VOICE_CALL_REDESIGN_PROPOSAL.md D-3）：暖咖半透底 + 暖白发丝描边坐在暖夜
 * 背景上（原浅白磨砂卡退场——深色沉浸里不再有一块刺眼的白）。角色区分靠字色与对齐：TA=暖白左对齐、
 * 你=陶土浅档右对齐；顶部渐隐让旧行「沉入」玻璃。自动滚到最新行；空态占位。
 */
@Composable
fun VoiceCallSubtitlePanel(
    lines: List<CallSubtitleLine>,
    modifier: Modifier = Modifier,
    fallbackMode: Boolean = false,
) {
    val shape = RoundedCornerShape(16.dp)
    val listState = rememberLazyListState()

    // 过渡丝滑化·C：面板首次出现/重新开启时瞬时落到最新行（scrollToItem），仅后续新增行才动画滚，
    // 避免开面板那一下可见地滚动。
    var didInitialScroll by remember { mutableStateOf(false) }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            if (didInitialScroll) listState.animateScrollToItem(lines.size - 1)
            else { listState.scrollToItem(lines.size - 1); didInitialScroll = true }
        }
    }

    val glassTint = VoiceCallPalette.glass.copy(alpha = 0.55f)
    Column(
        // P1-17：面板级合并+Polite——每行「说话人+正文」连读，新行落地播一次，开空面板播占位。
        // SubtitleRow 绝不加 mergeDescendants（自带 merge 的子节点会被排除出祖先合并，
        // 面板合并文本变空→liveRegion 永不播·批5 同族教训反向形态）。
        modifier = modifier
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .height(184.dp)
            .clip(shape)
            .background(glassTint)
            .border(1.dp, VoiceCallPalette.warmWhite.copy(alpha = 0.08f), shape),
    ) {
        // VU2 字幕通话模式钉行（2026-07-12）：≥2 轮失声进模式即在玻璃条顶部钉一行说明，真出声即撤。
        if (fallbackMode) SubtitleFallbackFlag()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // 顶部渐隐（D-3）：旧行向上「沉入」玻璃，视觉只强调最近几句。渐隐移到内层内容区，
                // 无钉行时内层高=外层内容高（逐像素同旧）；有钉行时渐隐带按内层高度重算（VU2 有意微差）。
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to glassTint,
                            0.16f to glassTint.copy(alpha = 0f),
                        ),
                    )
                },
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.voice_call_transcript_placeholder),
                    color = VoiceCallPalette.textMid,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(lines) { line -> SubtitleRow(line) }
                }
            }
        }
    }
}

/** VU2 字幕通话模式顶部钉行：琥珀圆点 + 「语音暂不可用」+「· 字幕通话中」两段拼排，底缘 1px 暖白发丝分线。 */
@Composable
private fun SubtitleFallbackFlag() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .background(VoiceCallPalette.amber, CircleShape),
        )
        Text(
            text = stringResource(R.string.voice_call_subtitle_mode_flag),
            color = VoiceCallPalette.amber,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
        )
        Text(
            text = stringResource(R.string.voice_call_subtitle_mode_label),
            color = VoiceCallPalette.textMid,
            fontSize = 11.sp,
        )
    }
    HorizontalDivider(
        thickness = 1.dp,
        color = VoiceCallPalette.warmWhite.copy(alpha = 0.08f),
    )
}

@Composable
private fun SubtitleRow(line: CallSubtitleLine) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (line.isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = line.speakerName,
            color = VoiceCallPalette.textLo,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
        )
        Text(
            text = line.text,
            color = if (line.isUser) VoiceCallPalette.subtitleUser else VoiceCallPalette.warmWhite,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}
