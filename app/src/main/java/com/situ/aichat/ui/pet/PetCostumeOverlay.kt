package com.situ.aichat.ui.pet

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 宠物装扮叠图（1:1 iOS `PetCostumeOverlay`/`PetCostumeRenderer`，AIChatShared）：**纯 Compose Canvas 矢量，
 * 零素材**。装扮按「物种头部锚点 + 相对偏移 + 尺寸比例」叠在精灵图上（坐标 0-1 归一化）。锚点/偏移/比例/
 * 颜色见 SPEC §2.19。装扮目前仅 P9 商店购买后可佩戴（equippedItemId 在 P9 前恒 null，故暂不渲染）。
 */
object PetCostumeRenderer {
    const val BOWTIE = "pet_costume_bowtie"
    const val SCARF = "pet_costume_scarf"
    const val CROWN = "pet_costume_crown"
    const val WINGS = "pet_costume_wings"

    /** 物种头部锚点（0-1 归一化，左上(0,0)→右下(1,1)）。 */
    fun headAnchor(speciesRaw: String): Offset = when (speciesRaw) {
        "cat" -> Offset(0.50f, 0.32f)
        "dog" -> Offset(0.50f, 0.30f)
        "rabbit" -> Offset(0.50f, 0.28f) // 兔耳头比例大
        "hamster" -> Offset(0.50f, 0.34f) // 仓鼠头小靠下
        else -> Offset(0.50f, 0.32f) // 隐藏款奇幻宠物用通用值
    }

    /** 装扮相对锚点偏移（0-1 归一化）。 */
    fun relativeOffset(itemId: String): Offset = when (itemId) {
        BOWTIE -> Offset(0f, 0.16f) // 领结在脖子 · 锚点下方
        SCARF -> Offset(0f, 0.18f)
        CROWN -> Offset(0f, -0.14f) // 皇冠在头顶 · 锚点上方
        WINGS -> Offset(0f, 0.05f)
        else -> Offset.Zero
    }

    /** 装扮总尺寸（占 host size 的比例）。 */
    fun sizeRatio(itemId: String): Float = when (itemId) {
        BOWTIE -> 0.22f
        SCARF -> 0.32f
        CROWN -> 0.28f
        WINGS -> 0.46f // 翅膀最大
        else -> 0f
    }

    fun isSupported(itemId: String): Boolean = itemId == BOWTIE || itemId == SCARF || itemId == CROWN || itemId == WINGS
}

/**
 * 把装扮叠在宠物精灵图上（[PetAnimationView] 用 [Box] 包住精灵图 + 本视图）。itemId 为 null/不支持不渲染。
 * 入场动画 scale 0.6→1.0 spring + opacity（切换装扮时重播）。
 */
@Composable
fun PetCostumeOverlay(
    itemId: String?,
    speciesRaw: String,
    @Suppress("UNUSED_PARAMETER") stageRaw: String, // 预留：目前不影响位置（对齐 iOS）
    hostSize: Dp,
    modifier: Modifier = Modifier,
) {
    if (itemId == null || !PetCostumeRenderer.isSupported(itemId)) return
    val anchor = PetCostumeRenderer.headAnchor(speciesRaw)
    val offset = PetCostumeRenderer.relativeOffset(itemId)
    val costumeSize = hostSize * PetCostumeRenderer.sizeRatio(itemId)
    // 锚点+偏移 → 相对 host 中心的偏移（centerOffset = (anchor+offset)*host - host/2）
    val centerOffsetX = hostSize * (anchor.x + offset.x) - hostSize / 2
    val centerOffsetY = hostSize * (anchor.y + offset.y) - hostSize / 2

    var appeared by remember(itemId) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.6f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 224f),
        label = "costumeScale",
    )
    val alpha by animateFloatAsState(if (appeared) 1f else 0f, label = "costumeAlpha")
    LaunchedEffect(itemId) {
        appeared = false
        delay(16)
        appeared = true
    }

    Box(modifier.size(hostSize), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .offset(centerOffsetX, centerOffsetY)
                .size(costumeSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(costumeSize)) {
                when (itemId) {
                    PetCostumeRenderer.BOWTIE -> drawBowtie()
                    PetCostumeRenderer.SCARF -> drawScarf()
                    PetCostumeRenderer.CROWN -> drawCrown()
                    PetCostumeRenderer.WINGS -> drawWings()
                }
            }
        }
    }
}

// MARK: - 矢量绘制（颜色/比例 1:1 SPEC §2.19；正方形画布内居中）

/** 红色小领结（双翼三角 + 中央打结），#C82E3E→#9C1D2B 渐变。 */
private fun DrawScope.drawBowtie() {
    val s = size.width
    val cx = s / 2f
    val cy = size.height / 2f
    val knotW = s * 0.18f
    val wingW = s * 0.42f
    val wingHalf = s * 0.55f / 2f
    val brush = Brush.linearGradient(listOf(Color(0xFFC82E3E), Color(0xFF9C1D2B)))
    val left = Path().apply {
        moveTo(cx - knotW / 2f, cy)
        lineTo(cx - knotW / 2f - wingW, cy - wingHalf)
        lineTo(cx - knotW / 2f - wingW, cy + wingHalf)
        close()
    }
    val right = Path().apply {
        moveTo(cx + knotW / 2f, cy)
        lineTo(cx + knotW / 2f + wingW, cy - wingHalf)
        lineTo(cx + knotW / 2f + wingW, cy + wingHalf)
        close()
    }
    drawPath(left, brush)
    drawPath(right, brush)
    val knotH = s * 0.65f
    drawRoundRect(
        color = Color(0xFF9C1D2B),
        topLeft = Offset(cx - knotW / 2f, cy - knotH / 2f),
        size = Size(knotW, knotH),
        cornerRadius = CornerRadius(knotW / 2f, knotW / 2f),
    )
}

/** 毛绒围巾（胶囊 + 斜白纹），#F0B46E→#B46E50 渐变。 */
private fun DrawScope.drawScarf() {
    val s = size.width
    val scarfH = s * 0.4f
    val cy = size.height / 2f
    val top = cy - scarfH / 2f
    val capsule = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = 0f, top = top, right = s, bottom = top + scarfH,
                cornerRadius = CornerRadius(scarfH / 2f, scarfH / 2f),
            ),
        )
    }
    drawPath(
        capsule,
        Brush.verticalGradient(
            listOf(Color(0xFFF0B46E), Color(0xFFB46E50)),
            startY = top, endY = top + scarfH,
        ),
    )
    // 5 条斜白纹（裁剪到胶囊内）
    clipPath(capsule) {
        val stripeW = s * 0.04f
        val gap = s * 0.18f
        var x = s * 0.08f
        repeat(5) {
            rotate(degrees = 15f, pivot = Offset(x, cy)) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.32f),
                    topLeft = Offset(x - stripeW / 2f, cy - scarfH * 0.35f),
                    size = Size(stripeW, scarfH * 0.7f),
                    cornerRadius = CornerRadius(stripeW / 2f, stripeW / 2f),
                )
            }
            x += gap
        }
    }
}

/** 金色三尖小皇冠（M 形 + 底座 + 红宝石），#FEE9B8→#E8B87A→#C49650 渐变。 */
private fun DrawScope.drawCrown() {
    val s = size.width
    val crownH = s * 0.62f
    val baseH = s * 0.18f
    val cy = size.height / 2f
    val top = cy - crownH / 2f
    val bottom = cy + crownH / 2f
    val w = s
    val crown = Path().apply {
        moveTo(0f, bottom)
        lineTo(w * 0.18f, top + crownH * 0.10f)
        lineTo(w * 0.32f, top + crownH * 0.55f)
        lineTo(w * 0.5f, top)
        lineTo(w * 0.68f, top + crownH * 0.55f)
        lineTo(w * 0.82f, top + crownH * 0.10f)
        lineTo(w, bottom)
        close()
    }
    val gold = Brush.verticalGradient(
        listOf(Color(0xFFFEE9B8), Color(0xFFE8B87A), Color(0xFFC49650)),
        startY = top, endY = bottom,
    )
    drawPath(crown, gold)
    drawPath(crown, Color(0xFFC49650), style = Stroke(width = 0.6f))
    // 底座
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(Color(0xFFE8B87A), Color(0xFFC49650)), startY = bottom - baseH, endY = bottom),
        topLeft = Offset(0f, bottom - baseH),
        size = Size(w, baseH),
        cornerRadius = CornerRadius(baseH * 0.3f, baseH * 0.3f),
    )
    // 中央红宝石
    drawCircle(
        color = Color(0xFFE03C46),
        radius = baseH * 0.55f / 2f,
        center = Offset(w / 2f, bottom - baseH * 0.7f),
    )
}

/** 透明精灵翅膀（对称椭圆 + 边描），#F0DCFF→#B4A0DC 渐变。 */
private fun DrawScope.drawWings() {
    val s = size.width
    val cx = s / 2f
    val cy = size.height / 2f
    val wingW = s * 0.48f
    val wingH = s * 0.78f
    val fill = Brush.linearGradient(
        listOf(Color(0xFFF0DCFF).copy(alpha = 0.85f), Color(0xFFB4A0DC).copy(alpha = 0.55f)),
    )
    val edge = Color(0xFF8C6EC8).copy(alpha = 0.85f)
    // 左翅（向左下旋转）
    rotate(degrees = -22f, pivot = Offset(cx, cy)) {
        val tl = Offset(cx - wingW, cy - wingH / 2f)
        drawOval(fill, topLeft = tl, size = Size(wingW, wingH))
        drawOval(edge, topLeft = tl, size = Size(wingW, wingH), style = Stroke(width = 0.8f))
    }
    // 右翅（向右下旋转）
    rotate(degrees = 22f, pivot = Offset(cx, cy)) {
        val tl = Offset(cx, cy - wingH / 2f)
        drawOval(fill, topLeft = tl, size = Size(wingW, wingH))
        drawOval(edge, topLeft = tl, size = Size(wingW, wingH), style = Stroke(width = 0.8f))
    }
}
