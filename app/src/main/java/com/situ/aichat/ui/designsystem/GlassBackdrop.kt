package com.situ.aichat.ui.designsystem

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** 玻璃栏与内容相邻的「迎光描边」位置（要素④）。 */
enum class GlassDivider { Top, Bottom, None }

/**
 * 聊天壁纸毛玻璃悬浮控件（契约 FABLE5_CHAT_WALLPAPER_PROPOSAL.md §4「五要素配方」）。在自有静态壁纸上做出 iOS
 * Material 级高级质感，全自研（守铁律#1，不引第三方）。五要素：
 *  ① 真实背景模糊——画的是**壁纸像素**：拿预糊好的小图 [blurred] 按全屏 [rootSize] 拉伸、再按本栏在屏内的偏移
 *     [offset] 负向平移、clip 到本栏边界 → 模糊切片与四周清晰壁纸像素级对齐，且无每帧模糊成本。
 *  ② 暖色 vibrancy 染色——非冷白霜，浅向暖瓷 / 深向暖咖（[dark]）。
 *  ③ 轻微饱和提升——模糊背景过 [SATURATION] 的 ColorMatrix，透而有神不发灰。
 *  ④ 迎光描边——内容相邻缘 1px 高光（[divider]）。
 *  ⑤ 亮度自适应——[dark] 由调用方按「本栏背后那块壁纸」的亮度（[WallpaperBlur.averageLuminance]）给出，
 *     决定染色与 onGlass 内容色，保证任何照片下都清晰可读。
 *
 * 本栏的屏内位置 / 全屏尺寸经 onGloballyPositioned 实时取得（[blurred] 为空时只剩纯染色，等同优雅降级）。
 */
@Composable
fun GlassBackdrop(
    blurred: ImageBitmap?,
    dark: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    divider: GlassDivider = GlassDivider.None,
    content: @Composable BoxScope.() -> Unit,
) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }

    val tint = if (dark) Color(0xFF1C1916) else Color(0xFFFAF7F2)
    // 染色 alpha（要素②）：浅向 0.56（旧 0.46 在浅壁纸上太淡、悬浮控件糊进去不成形——用户定稿项，已上调一档对齐契约 §4.3
    //  ~0.55）；深向 0.50 保持（深壁纸上已挺括）。最终强度真机/模拟器走查再微调（本组件常量为唯一调参点）。
    val tintAlpha = if (dark) 0.50f else 0.56f
    val highlight = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.42f)
    // ④ 整圈发丝描边：让悬浮控件在**任何壁纸**上都清楚成形（GlassDivider 只描一条边、对独立浮丸不够）。深向用白高光、
    //  浅向用暖墨极淡线（浅玻璃配白边在浅壁纸上会糊掉、定不出形）。按调用方 [shape] 描边，圆/胶囊/圆角各自贴边。
    val border = if (dark) Color.White.copy(alpha = 0.18f) else Color(0xFF2E2925).copy(alpha = 0.14f)
    val saturation = remember { ColorMatrix().apply { setToSaturation(SATURATION) } }
    val satFilter = remember(saturation) { ColorFilter.colorMatrix(saturation) }

    Box(
        modifier
            .onGloballyPositioned { coords ->
                val root = coords.findRootCoordinates()
                rootSize = root.size
                offset = root.localPositionOf(coords, Offset.Zero)
            }
            .drawBehind {
                clipRect {
                    if (blurred != null && rootSize.width > 0 && rootSize.height > 0) {
                        // 模糊壁纸按全屏拉伸 + 负向平移 → 本栏背后那块切片，与清晰背景对齐。
                        drawImage(
                            image = blurred,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(blurred.width, blurred.height),
                            dstOffset = IntOffset(-offset.x.roundToInt(), -offset.y.roundToInt()),
                            dstSize = rootSize,
                            colorFilter = satFilter,
                        )
                    }
                    drawRect(color = tint, alpha = tintAlpha)
                }
                val hp = 1.dp.toPx()
                when (divider) {
                    GlassDivider.Bottom ->
                        drawLine(highlight, Offset(0f, size.height - hp), Offset(size.width, size.height - hp), strokeWidth = hp)
                    GlassDivider.Top ->
                        drawLine(highlight, Offset(0f, hp), Offset(size.width, hp), strokeWidth = hp)
                    GlassDivider.None -> Unit
                }
            }
            .border(GLASS_HAIRLINE, border, shape),
        content = content,
    )
}

/** 饱和提升（=iOS vibrancy）。1=不变；>1 更鲜。 */
private const val SATURATION = 1.3f

/** 玻璃整圈描边宽（要素④·极细发丝边·定稿调参点）。 */
private val GLASS_HAIRLINE = 0.75.dp

/**
 * 壁纸毛玻璃上的内容色单源（审计 T1·2026-07-02）——值 = 各站点现值**逐位搬**（外观零变化）。
 * 主色三处曾各自写死同值；次级色五值并存的**漂移原样保留**（是否统一属 B5·真机调玻璃观感时一并裁决，
 * 契约 FABLE5_CHAT_WALLPAPER §4.3 明言这些是「起始值靠真屏调」——单源后真机微调只改这里）。
 */
object OnGlass {
    /** 暖白主字（深壁纸底·聊天顶栏/输入框/壁纸裁剪屏共用）。 */
    val PrimaryOnDark = Color(0xFFF3EEE8)
    /** 深墨主字（浅壁纸底·= Palette.Ink 同值）。 */
    val PrimaryOnLight = Color(0xFF2E2925)
    /** 次级·输入框（深/浅底）。与顶栏值漂移（B5 待裁决）。 */
    val SecondaryOnDarkInput = Color(0xFFCFC8BE)
    val SecondaryOnLightInput = Color(0xFF6B6258)
    /** 次级·顶栏（深/浅底）。 */
    val SecondaryOnDarkTopBar = Color(0xFFCBC3B9)
    val SecondaryOnLightTopBar = Color(0xFF5A5249)
    /** 底部「+」钮 / 面板标签（浅底）。 */
    val SecondaryOnLightBottom = Color(0xFF4A423B)
}
