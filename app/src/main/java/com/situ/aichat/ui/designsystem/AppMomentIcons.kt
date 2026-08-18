package com.situ.aichat.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 朋友圈内页族自绘图标（MOMENTS_FEED 契约 §3·与 [AppFeatureIcons]/底栏 AppNavIcons 同笔法：24dp viewport·
 * 单色描边·圆头圆转角·全曲线无锐角）。颜色由 `Icon` 的 tint 上色，[PLACEHOLDER] 黑仅占位。
 *
 * 笔宽 1.7f 与既有图标族单源同值（契约 §3 写 1.8 系 mockup CSS 近似值，家族一致优先——偏差已登记账本）；
 * FAB 专用 [QuillBold] 按契约加粗到 2f 做视觉配重。心形描边/填充共用一条轮廓路径（[heartOutline] 单源）。
 */
object AppMomentIcons {

    private val PLACEHOLDER = SolidColor(Color.Black)
    private const val W = 1.7f

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

    /** 心形轮廓（底尖起笔·左右双肺叶圆弧）——描边态与填充态共用的单源路径。 */
    private fun PathBuilder.heartOutline() {
        moveTo(12f, 20.1f)
        curveTo(6.9f, 16f, 3.7f, 12.7f, 3.7f, 9.2f)
        arcToRelative(4.5f, 4.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 8.3f, -2.4f)
        arcTo(4.5f, 4.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 20.3f, 9.2f)
        curveToRelative(0f, 3.5f, -3.2f, 6.8f, -8.3f, 10.9f)
        close()
    }

    /** 心（描边·未赞态·tint 走 text.secondary）。 */
    val Heart: ImageVector by lazy {
        builder("MomentHeart").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                heartOutline()
            }
        }.build()
    }

    /** 心（填充·已赞态·tint 走 accent.text 深陶——D3 拍板）。 */
    val HeartFilled: ImageVector by lazy {
        builder("MomentHeartFilled").apply {
            path(fill = PLACEHOLDER) {
                heartOutline()
            }
        }.build()
    }

    /** 评论气泡（圆泡 + 左下短尾·描边）。 */
    val CommentBubble: ImageVector by lazy {
        builder("MomentComment").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 4.2f)
                curveToRelative(4.6f, 0f, 8.3f, 3f, 8.3f, 6.8f)
                reflectiveCurveToRelative(-3.7f, 6.8f, -8.3f, 6.8f)
                curveToRelative(-0.9f, 0f, -1.8f, -0.1f, -2.6f, -0.3f)
                lineToRelative(-3.9f, 2.3f)
                lineToRelative(0.9f, -3.1f)
                curveToRelative(-1.7f, -1.2f, -2.7f, -3f, -2.7f, -5.7f)
                curveToRelative(0f, -3.8f, 3.7f, -6.8f, 8.3f, -6.8f)
                close()
            }
        }.build()
    }

    /** 羽毛笔轮廓（上下两缘汇于笔尖 + 笔尖-笔杆线 + 羽枝线）——常规/加粗两档共用。 */
    private fun ImageVector.Builder.quillPaths(width: Float) {
        // 羽毛上缘 → 笔尖
        path(stroke = PLACEHOLDER, strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(19.8f, 4.2f)
            curveToRelative(-4.6f, 0.3f, -8.2f, 2.2f, -10.4f, 5.7f)
            lineToRelative(-3.1f, 6.6f)
        }
        // 羽毛下缘 → 笔尖（与上缘汇合于 6.3,16.5）
        path(stroke = PLACEHOLDER, strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(19.8f, 4.2f)
            curveToRelative(-0.3f, 4.6f, -2.2f, 8.2f, -5.7f, 10.4f)
            lineToRelative(-7.8f, 1.9f)
        }
        // 笔尖 → 笔杆收尾
        path(stroke = PLACEHOLDER, strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(6.3f, 16.5f)
            lineTo(4.4f, 20f)
        }
        // 羽枝
        path(stroke = PLACEHOLDER, strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(9.4f, 13.9f)
            curveToRelative(1.9f, -0.3f, 3.7f, -1.1f, 5.2f, -2.4f)
        }
    }

    /** 羽毛笔（描边·发布语义）。 */
    val Quill: ImageVector by lazy {
        builder("MomentQuill").apply { quillPaths(W) }.build()
    }

    /** 羽毛笔·加粗档（2f·契约 §3：深陶 FAB 上做视觉配重）。 */
    val QuillBold: ImageVector by lazy {
        builder("MomentQuillBold").apply { quillPaths(2f) }.build()
    }

    /** 铃铛（钟体 + 铃舌弧·描边）。 */
    val Bell: ImageVector by lazy {
        builder("MomentBell").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 4.2f)
                arcToRelative(5.3f, 5.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5.3f, 5.3f)
                verticalLineToRelative(2.9f)
                lineToRelative(1.5f, 2.7f)
                horizontalLineTo(5.2f)
                lineToRelative(1.5f, -2.7f)
                verticalLineTo(9.5f)
                arcTo(5.3f, 5.3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 12f, 4.2f)
                close()
            }
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10.2f, 18.3f)
                arcToRelative(1.9f, 1.9f, 0f, isMoreThanHalf = false, isPositiveArc = false, 3.6f, 0f)
            }
        }.build()
    }

    /** 纸飞机（机身三角 + 折线·描边·评论发送钮）。 */
    val PaperPlane: ImageVector by lazy {
        builder("MomentPaperPlane").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(20.3f, 3.7f)
                lineTo(3.9f, 10f)
                lineToRelative(6f, 2.6f)
                lineToRelative(2.6f, 6f)
                lineTo(20.3f, 3.7f)
                close()
            }
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9.9f, 12.6f)
                lineToRelative(4.6f, -4.6f)
            }
        }.build()
    }
}
