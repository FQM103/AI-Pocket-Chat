package com.situ.aichat.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Fable-5 底部导航自绘图标（设计语言 §3·2026-06-19 过审·底栏重设计 B 悬浮胶囊）。
 *
 * 圆角描边·全曲线无锐角·**单色矢量**（颜色由 [androidx.compose.material3.Icon] 的 `tint` 上色——选中
 * 陶土 `accent.text`、未选中 `text.secondary`）。`stroke`/`fill` 用占位 [PLACEHOLDER] 黑，渲染时被 tint
 * 整体重染（`ColorFilter.tint` SrcIn）。圆形用「右端点→两段半圆 arcToRelative」标准画法（避免 large-arc 翻转）。
 *
 * **动态 = 社交圈子**（一个环 + 三节点）——替原「相册/风景照」图标（读着像相册·与「动态/圈子」语义不符）。
 */
object AppNavIcons {

    private val PLACEHOLDER = SolidColor(Color.Black)
    private const val W = 1.7f

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

    /** 聊天：圆胶囊气泡 + 左下短尾（单笔连续路径·描边）。 */
    val Chat: ImageVector by lazy {
        builder("NavChat").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 7f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, -3f)
                horizontalLineToRelative(10f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 3f)
                verticalLineToRelative(5f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3f, 3f)
                horizontalLineTo(9f)
                lineToRelative(-4f, 3f)
                verticalLineToRelative(-3f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -1f, -2.2f)
                close()
            }
        }.build()
    }

    /** 联系人：两个错位人像（前排实头 + 肩、后排小头 + 肩提示·描边）。 */
    val Contacts: ImageVector by lazy {
        builder("NavContacts").apply {
            // 前排头（圆 cx9 cy8.5 r3）
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 8.5f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -6f, 0f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 6f, 0f)
                close()
            }
            // 前排肩
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3.8f, 18.6f)
                arcToRelative(5.2f, 5.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 10.4f, 0f)
            }
            // 后排头（圆 cx16.6 cy7.8 r2.3）
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(18.9f, 7.8f)
                arcToRelative(2.3f, 2.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -4.6f, 0f)
                arcToRelative(2.3f, 2.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4.6f, 0f)
                close()
            }
            // 后排肩（提示）
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15.4f, 13.3f)
                arcToRelative(4.6f, 4.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 4.8f, 4.6f)
            }
        }.build()
    }

    /** 动态：社交圈子（环 + 三节点）。环描边、三节点实心。 */
    val Moments: ImageVector by lazy {
        builder("NavMoments").apply {
            // 环（圆 cx12 cy12 r7）
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(19f, 12f)
                arcToRelative(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = true, -14f, 0f)
                arcToRelative(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = true, 14f, 0f)
                close()
            }
            // 三节点（实心圆 r1.55·环内三角分布 = 圈子里的成员，与环留空隙才不糊）
            path(fill = PLACEHOLDER) {
                // 顶 (12, 7.8)
                moveTo(13.55f, 7.8f)
                arcToRelative(1.55f, 1.55f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3.1f, 0f)
                arcToRelative(1.55f, 1.55f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3.1f, 0f)
                close()
                // 右下 (15.6, 14.1)
                moveTo(17.15f, 14.1f)
                arcToRelative(1.55f, 1.55f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3.1f, 0f)
                arcToRelative(1.55f, 1.55f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3.1f, 0f)
                close()
                // 左下 (8.4, 14.1)
                moveTo(9.95f, 14.1f)
                arcToRelative(1.55f, 1.55f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3.1f, 0f)
                arcToRelative(1.55f, 1.55f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3.1f, 0f)
                close()
            }
        }.build()
    }

    /** 我：单人像（居中·描边）。 */
    val Profile: ImageVector by lazy {
        builder("NavProfile").apply {
            // 头（圆 cx12 cy8.5 r3.2）
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15.2f, 8.5f)
                arcToRelative(3.2f, 3.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -6.4f, 0f)
                arcToRelative(3.2f, 3.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 6.4f, 0f)
                close()
            }
            // 肩
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5.5f, 19.2f)
                arcToRelative(6.5f, 6.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 13f, 0f)
            }
        }.build()
    }
}
