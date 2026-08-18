package com.situ.aichat.ui.starfield

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppTypography

/**
 * 记忆星空 chrome（图纸 §4.7）+ 空态（§4.10）：顶栏（返回/标题/计数）、图例三项、底部楷体标注 + 提示行。
 * 全部视口系、恒暗页锁定色（暖白 [WarmWhite] 按 α 分层）。自 `StarfieldScreen.kt` 拆出（D-11 同因）。
 */
@Composable
internal fun BoxScope.StarfieldChrome(
    state: StarfieldUiState,
    onBack: () -> Unit,
    footerStart: String,
) {
    TopBar(state.starCount, onBack, Modifier.align(Alignment.TopCenter))

    // 空态句：画布中心楷体（§4.10·星 0 颗且已加载完）。
    if (!state.loading && state.starCount == 0) {
        Text(
            stringResource(R.string.starfield_empty),
            style = AppTypography.kaiQuote.copy(fontSize = 12.5.sp, lineHeight = 22.sp),
            color = WarmWhite.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
        )
    }

    // 加载态只画夜幕+尘星，无星无标注（§4.10）。
    if (state.loading) return

    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Legend()
        Spacer12()
        Footer(state, footerStart)
    }
}

/** 顶栏（§4.7）：30dp 圆底返回钮（触达补到 48dp = a11y 红线）+ 15sp 标题 + 右计数。 */
@Composable
private fun TopBar(starCount: Int, onBack: () -> Unit, modifier: Modifier) {
    val backDesc = stringResource(R.string.action_back)
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 视觉 30dp 圆（§4.7），触达补到 48dp = a11y 最小触达红线。
        Box(
            Modifier
                .size(48.dp)
                .clickableScale(role = Role.Button, onClickLabel = backDesc, onClick = onBack)
                .semantics(mergeDescendants = true) { contentDescription = backDesc },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(30.dp).background(WarmWhite.copy(alpha = 0.07f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", fontSize = 15.sp, color = WarmWhite)
            }
        }
        Text(
            stringResource(R.string.starfield_title),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
            color = WarmWhite,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.starfield_count, starCount),
                fontSize = 11.sp,
                color = WarmWhite.copy(alpha = 0.4f),
            )
        }
    }
}

/** 图例（§4.7）：三项 10.5sp α.7，每项前 5dp 白点 + 背后 10dp 各自晕色径向辉光。 */
@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendItem(R.string.starfield_legend_meeting, HaloMeeting)
        LegendItem(R.string.starfield_legend_promise, HaloPromise)
        LegendItem(R.string.starfield_legend_milestone, HaloMilestone)
    }
}

@Composable
private fun LegendItem(labelRes: Int, halo: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val glowR = 5.dp.toPx()
            drawCircle(
                Brush.radialGradient(
                    listOf(halo.copy(alpha = 0.58f), halo.copy(alpha = 0f)),
                    center = center, radius = glowR,
                ),
                radius = glowR, center = center,
            )
            drawCircle(StarCore, radius = 2.5.dp.toPx(), center = center)
        }
        Text(
            stringResource(labelRes),
            fontSize = 10.5.sp,
            color = WarmWhite.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** 底部标注（§4.7）：楷体「N 颗星 · 始于 X 月」两侧 36dp×1dp 细线 + 下行提示。 */
@Composable
private fun Footer(state: StarfieldUiState, footerStart: String) {
    val text = if (state.starCount == 0) {
        stringResource(R.string.starfield_footer_empty)
    } else {
        stringResource(R.string.starfield_footer, state.starCount, footerStart)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        HairLine()
        Text(
            text,
            style = AppTypography.kaiQuote.copy(fontSize = 12.sp, lineHeight = 16.sp),
            letterSpacing = 3.sp,
            color = WarmWhite.copy(alpha = 0.68f),
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        HairLine()
    }
    Spacer6()
    Text(
        stringResource(R.string.starfield_hint),
        fontSize = 9.5.sp,
        letterSpacing = 1.5.sp,
        color = WarmWhite.copy(alpha = 0.34f),
    )
}

@Composable
private fun HairLine() {
    Box(Modifier.width(36.dp).height(1.dp).background(WarmWhite.copy(alpha = 0.2f)))
}

@Composable
private fun Spacer12() = Box(Modifier.height(12.dp))

@Composable
private fun Spacer6() = Box(Modifier.height(6.dp))
