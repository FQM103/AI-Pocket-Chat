package com.situ.aichat.ui.starfield

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.clickableScale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 「故事」Tab 记忆星空入口卡（图纸 §4.9）：104dp 深空横幅 + 右半散星 + 标题/副行 + chevron。
 * 计数走自取的 [StarfieldEntryViewModel]（J3·不给 CharacterProfileViewModel 加职责）。
 * 星 0 颗也照常渲染（副行走空态文案·E1）。
 */
@Composable
fun StarfieldEntryCard(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StarfieldEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = stringResource(R.string.starfield_title)
    val subtitle = when {
        state.starCount == 0 -> stringResource(R.string.starfield_entry_sub_empty)
        // nova > 1 也用这一句（克制·§3.4 锁定文案）。
        state.hasNova -> stringResource(R.string.starfield_entry_sub_nova, state.starCount, state.clusterCount)
        else -> stringResource(R.string.starfield_entry_sub, state.starCount, state.clusterCount)
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(CARD_CORNER_DP.dp))
            .clickableScale(role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = "$title · $subtitle" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawEntryBackdrop()
            drawEntryStars()
        }
        Row(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                    color = WarmWhite,
                )
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = WarmWhite.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text("›", fontSize = 17.sp, color = WarmWhite.copy(alpha = 0.45f))
        }
    }
}

/** 三层底（§4.9）：115° 主渐变 + 右上紫雾 + 左下蓝雾。 */
private fun DrawScope.drawEntryBackdrop() {
    val w = size.width
    val h = size.height
    // CSS `linear-gradient(115deg,…)`：角自「向上」顺时针量；渐变线过卡心，长 = |w·sinθ| + |h·cosθ|。
    val theta = Math.toRadians(115.0).toFloat()
    val dir = Offset(sin(theta), -cos(theta))
    val lineLength = abs(w * sin(theta)) + abs(h * cos(theta))
    val center = Offset(w / 2f, h / 2f)
    drawRect(
        Brush.linearGradient(
            0f to EntryStop0, 0.58f to EntryStop58, 1f to EntryStop100,
            start = center - dir * (lineLength / 2f),
            end = center + dir * (lineLength / 2f),
        ),
    )
    entryWash(Offset(0.9f * w, -0.3f * h), 1.5f * h, HorizonGlow, 0.5f)
    entryWash(Offset(0.1f * w, 1.2f * h), h, EntryBlueWash, 0.55f)
}

/** 径向雾（画满整卡·超出半径处按 Clamp 收尾透明）。 */
private fun DrawScope.entryWash(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawRect(
        Brush.radialGradient(
            listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
            center = center, radius = radius,
        ),
        topLeft = Offset.Zero,
        size = Size(size.width, size.height),
    )
}

/**
 * 右半散星（§4.9）：金 / 青瓷 / 月白各一 + 尘星两颗，层结构复用 §4.3 [drawStar]（晕色三元组随 [StarType] 出）。
 * 位置为「右缘内 44–150dp、上 16–70dp」带内的固定落值（mockup HTML 按规不读·见施工日志 D-12/TODO-3）。
 */
private fun DrawScope.drawEntryStars() {
    val w = size.width
    fun placed(type: StarType, fromRightDp: Float, topDp: Float, coreDp: Float) = PlacedStar(
        node = StarNode(id = "entry_$type", type = type, timestampMillis = 0L, title = "", weight = coreDp),
        xDp = (w - fromRightDp.dp.toPx()) / density,
        yDp = topDp,
        radiusDp = coreDp,
        alpha = 1f,
        hero = false,
    )
    drawStar(placed(StarType.MEETING, 60f, 30f, 4.5f), selected = false, novaAlpha = 0f)
    drawStar(placed(StarType.PROMISE, 108f, 56f, 5.0f), selected = false, novaAlpha = 0f)
    drawStar(placed(StarType.MILESTONE, 138f, 24f, 5.5f), selected = false, novaAlpha = 0f)
    // 尘星两颗（§4.4 族·静态无辉光档的观感）。
    drawCircle(WarmWhite.copy(alpha = 0.5f), radius = 1.1.dp.toPx(), center = Offset(w - 88.dp.toPx(), 68.dp.toPx()))
    drawCircle(WarmWhite.copy(alpha = 0.5f), radius = 1.1.dp.toPx(), center = Offset(w - 124.dp.toPx(), 44.dp.toPx()))
}

/** 入口卡三停主渐变（§4.9 锁定）+ 左下蓝雾 rgba(52,58,96)。 */
internal val EntryStop0 = Color(0xFF0B0E1D)
internal val EntryStop58 = Color(0xFF181D36)
internal val EntryStop100 = Color(0xFF2E2A4A)
private val EntryBlueWash = Color(0xFF343A60)

private const val CARD_HEIGHT_DP = 104
private const val CARD_CORNER_DP = 18
