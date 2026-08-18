package com.situ.aichat.ui.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Fable-5 「我」页仪表盘自绘图标（PROFILE 契约 §9.2·与底栏 [AppNavIcons] 同笔法：24 网格·线宽 1.7·
 * 圆头圆角·全曲线无锐角·**单色矢量**，颜色由 `Icon` 的 `tint` 上色）。
 *
 * `stroke`/`fill` 用占位 [PLACEHOLDER] 黑，渲染时被 tint 整体重染（SrcIn）。动态=层叠双帖（前帖圆角矩形 +
 * 后帖翻角提示）/ 钱包=横搭扣包身 + 掌扣点 / 礼物店=雨棚小店 + 门 / 礼物盒=盖沿礼盒 + 缎带双环 /
 * 设置=三杆调节滑轨 [Tune] / chevron=右行细箭（小尺寸用·线宽 2 光学补偿·v2 §5）。
 */
object AppProfileIcons {

    private val PLACEHOLDER = SolidColor(Color.Black)
    private const val W = 1.7f

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

    /** 我的动态：前帖（圆角矩形）+ 右上后帖翻边（层叠「发过的帖子」）。 */
    val Moments: ImageVector by lazy {
        builder("ProfileMoments").apply {
            // 前帖 x4 y7.5 w12 h12 r3
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7f, 7.5f)
                horizontalLineToRelative(6f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 3f)
                verticalLineToRelative(6f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3f, 3f)
                horizontalLineToRelative(-6f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3f, -3f)
                verticalLineToRelative(-6f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, -3f)
                close()
            }
            // 后帖翻边（开笔画：左起横到右上角圆弧再垂下）
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8.5f, 4f)
                horizontalLineTo(17f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 3f)
                verticalLineTo(15.5f)
            }
        }.build()
    }

    /** 我的钱包：圆角包身 + 翻盖横线 + 搭扣点（实心）。 */
    val Wallet: ImageVector by lazy {
        builder("ProfileWallet").apply {
            // 包身 x3.5 y6 w17 h13 r3
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.5f, 6f)
                horizontalLineToRelative(11f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 3f)
                verticalLineToRelative(7f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3f, 3f)
                horizontalLineToRelative(-11f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, -3f, -3f)
                verticalLineToRelative(-7f)
                arcToRelative(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, -3f)
                close()
            }
            // 翻盖横线
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round) {
                moveTo(3.5f, 10.2f)
                horizontalLineTo(20.5f)
            }
            // 搭扣点（实心·呼应底栏「动态」节点画法）
            path(fill = PLACEHOLDER) {
                moveTo(17.75f, 14.6f)
                arcToRelative(1.15f, 1.15f, 0f, isMoreThanHalf = true, isPositiveArc = true, -2.3f, 0f)
                arcToRelative(1.15f, 1.15f, 0f, isMoreThanHalf = true, isPositiveArc = true, 2.3f, 0f)
                close()
            }
        }.build()
    }

    /** 礼物店：雨棚（梯形）+ 店身（圆角底）+ 檐梁 + 门。 */
    val Shop: ImageVector by lazy {
        builder("ProfileShop").apply {
            // 雨棚
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.5f, 9.2f)
                lineTo(5.6f, 5f)
                horizontalLineTo(18.4f)
                lineTo(19.5f, 9.2f)
            }
            // 店身（底两角圆）
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.5f, 9.2f)
                verticalLineToRelative(8f)
                arcToRelative(1.4f, 1.4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 1.4f, 1.4f)
                horizontalLineToRelative(12.2f)
                arcToRelative(1.4f, 1.4f, 0f, isMoreThanHalf = false, isPositiveArc = false, 1.4f, -1.4f)
                verticalLineToRelative(-8f)
            }
            // 檐梁
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round) {
                moveTo(4.5f, 9.2f)
                horizontalLineTo(19.5f)
            }
            // 门
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10f, 18.6f)
                verticalLineToRelative(-4.2f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(4.2f)
            }
        }.build()
    }

    /** 礼物盒：盒身 + 盖沿 + 缎带竖线 + 蝴蝶结双环。 */
    val GiftBox: ImageVector by lazy {
        builder("ProfileGiftBox").apply {
            // 盒身 x5 y10.8 w14 h8 r1.8
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.8f, 10.8f)
                horizontalLineToRelative(10.4f)
                arcToRelative(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1.8f, 1.8f)
                verticalLineToRelative(4.4f)
                arcToRelative(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -1.8f, 1.8f)
                horizontalLineToRelative(-10.4f)
                arcToRelative(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -1.8f, -1.8f)
                verticalLineToRelative(-4.4f)
                arcToRelative(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1.8f, -1.8f)
                close()
            }
            // 盖沿 x4 y7.6 w16 h3.2 r1.2
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5.2f, 7.6f)
                horizontalLineToRelative(13.6f)
                arcToRelative(1.2f, 1.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1.2f, 1.2f)
                verticalLineToRelative(0.8f)
                arcToRelative(1.2f, 1.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -1.2f, 1.2f)
                horizontalLineToRelative(-13.6f)
                arcToRelative(1.2f, 1.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -1.2f, -1.2f)
                verticalLineToRelative(-0.8f)
                arcToRelative(1.2f, 1.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1.2f, -1.2f)
                close()
            }
            // 缎带竖线
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round) {
                moveTo(12f, 7.6f)
                verticalLineTo(18.8f)
            }
            // 蝴蝶结左右双环
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 7.4f)
                curveToRelative(-1.1f, -2.8f, -4.4f, -2.5f, -4.4f, -0.9f)
                curveToRelative(0f, 1.3f, 2.6f, 1.05f, 4.4f, 0.9f)
                close()
            }
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 7.4f)
                curveToRelative(1.1f, -2.8f, 4.4f, -2.5f, 4.4f, -0.9f)
                curveToRelative(0f, 1.3f, -2.6f, 1.05f, -4.4f, 0.9f)
                close()
            }
        }.build()
    }

    /** 设置：三杆调节滑轨（横杆 + 错位实心圆钮·原「齿轮八齿」小尺寸读作太阳故弃·装机走查 2026-07-12）。 */
    val Tune: ImageVector by lazy {
        builder("ProfileTune").apply {
            // 三横杆
            path(stroke = PLACEHOLDER, strokeLineWidth = W, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, 7f); horizontalLineTo(20f)
                moveTo(4f, 12f); horizontalLineTo(20f)
                moveTo(4f, 17f); horizontalLineTo(20f)
            }
            // 三实心圆钮（错位：上右 / 中左 / 下中右·实心画法呼应底栏「动态」节点）
            path(fill = PLACEHOLDER) {
                moveTo(17.9f, 7f)
                arcToRelative(2.1f, 2.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, -4.2f, 0f)
                arcToRelative(2.1f, 2.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4.2f, 0f)
                close()
                moveTo(10.3f, 12f)
                arcToRelative(2.1f, 2.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, -4.2f, 0f)
                arcToRelative(2.1f, 2.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4.2f, 0f)
                close()
                moveTo(16.1f, 17f)
                arcToRelative(2.1f, 2.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, -4.2f, 0f)
                arcToRelative(2.1f, 2.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4.2f, 0f)
                close()
            }
        }.build()
    }

    /** 右行 chevron（12–14dp 小尺寸装饰用·线宽 2 光学补偿小图缩放）。 */
    val ChevronRight: ImageVector by lazy {
        builder("ProfileChevronRight").apply {
            path(stroke = PLACEHOLDER, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9.6f, 6.6f)
                lineTo(14.8f, 12f)
                lineTo(9.6f, 17.4f)
            }
        }.build()
    }
}
