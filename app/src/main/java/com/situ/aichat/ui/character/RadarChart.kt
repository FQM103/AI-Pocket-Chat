package com.situ.aichat.ui.character

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 通用蛛网/雷达图（§0 拍板：Compose Canvas 原生绘，取代 iOS RadarChartView 的 SwiftUI Canvas；几何 1:1）。
 * 支持 N 维（建议 5–10）：同心多边形网格 + 射线 + 数据多边形 fill/stroke + 数据点 + 维度名标签。
 * 首维在正上方（-π/2），顺时针排列；数值 0–100 映射半径比例。
 */
@Composable
fun RadarChart(
    dimensionNames: List<String>,
    values: List<Int>,
    fillColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    gridLevels: Int = 3,
) {
    val count = dimensionNames.size
    val gridTint = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val labelRadiusPx = with(density) { (size / 2 - 6.dp).toPx() }

    // 无障碍（14.7e）：纯 Canvas 绘的雷达图分值对 TalkBack 不可见，维度名又散成多个 leaf 节点。clearAndSetSemantics
    // 把整图压成一个停、按「维度 分值」拼读（如「理性 80，感性 60，…」），1:1 iOS .accessibilityElement(.combine)。
    val chartDescription = dimensionNames
        .mapIndexed { i, name -> "$name ${values.getOrElse(i) { 0 }.coerceIn(0, 100)}" }
        .joinToString("，")

    Box(
        modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = chartDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            if (count < 3) return@Canvas
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val radius = min(this.size.width, this.size.height) / 2f - 28.dp.toPx()

            // 射线（中心 → 各顶点）
            for (i in 0 until count) {
                val a = angleFor(i, count)
                drawLine(gridTint.copy(alpha = 0.08f), center, pointAt(center, radius, a), strokeWidth = 0.5.dp.toPx())
            }
            // 同心网格多边形
            for (level in 1..gridLevels) {
                val r = radius * level / gridLevels
                drawPath(polygonPath(center, r, count), color = gridTint.copy(alpha = 0.1f), style = Stroke(0.5.dp.toPx()))
            }
            // 数据多边形（填充 + 描边）
            val dataPath = dataPolygonPath(center, radius, values, count)
            drawPath(dataPath, color = fillColor.copy(alpha = 0.15f))
            drawPath(dataPath, color = fillColor.copy(alpha = 0.7f), style = Stroke(1.5.dp.toPx()))
            // 数据点
            for (i in 0 until count) {
                val a = angleFor(i, count)
                val v = (values.getOrElse(i) { 0 }.coerceIn(0, 100)) / 100f
                drawCircle(fillColor.copy(alpha = 0.8f), radius = 2.5.dp.toPx(), center = pointAt(center, radius * v, a))
            }
        }

        // 维度名标签（从中心按角度偏移到外圈，1:1 iOS ForEach offset）
        dimensionNames.forEachIndexed { i, name ->
            val a = angleFor(i, count)
            val x = labelRadiusPx * cos(a)
            val y = labelRadiusPx * sin(a)
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
            )
        }
    }
}

/** 第 i 维的角度：首维在顶部（-π/2），顺时针。 */
private fun angleFor(index: Int, total: Int): Float {
    val step = (2.0 * PI) / total
    return (step * index - PI / 2.0).toFloat()
}

private fun pointAt(center: Offset, radius: Float, angle: Float): Offset =
    Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))

private fun polygonPath(center: Offset, radius: Float, sides: Int): Path = Path().apply {
    for (i in 0 until sides) {
        val p = pointAt(center, radius, angleFor(i, sides))
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

private fun dataPolygonPath(center: Offset, radius: Float, values: List<Int>, count: Int): Path = Path().apply {
    for (i in 0 until count) {
        val v = (values.getOrElse(i) { 0 }.coerceIn(0, 100)) / 100f
        val p = pointAt(center, radius * v, angleFor(i, count))
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}
