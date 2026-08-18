package com.situ.aichat.ui.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.random.Random

/**
 * 纸感微噪 grain（设计语言 v2 §3①·sensed not seen·「我」页 v2 首落地）。
 *
 * 128px 单色噪点位图（**固定种子**·启动间像素恒定，无闪变）懒生成一次，ShaderBrush 平铺；
 * alpha 浅 1.5% / 深 2.5%——近不可见，消解数字平面的「塑料感」。
 * **军规**：只垫 surface 底层（背景色之上、内容之下），绝不叠在文字/图标上层；纯静态与 reduceMotion 无关。
 */
object AppGrain {

    private const val TILE = 128
    private const val SEED = 42

    /** 浅 / 深档 alpha（v2 §3 锁值）。 */
    const val LIGHT_ALPHA = 0.015f
    const val DARK_ALPHA = 0.025f

    /** 灰阶噪点 tile（懒生成一次·全 App 共用）。 */
    val tile: ImageBitmap by lazy {
        val rnd = Random(SEED)
        val pixels = IntArray(TILE * TILE) {
            val v = rnd.nextInt(256)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        android.graphics.Bitmap.createBitmap(pixels, TILE, TILE, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }
}

/** 在既有背景色之上铺 grain（放在 background 之后、内容之前的修饰链位置）。 */
@Composable
fun Modifier.grainSurface(): Modifier {
    val alpha = if (AppTheme.colors.isDark) AppGrain.DARK_ALPHA else AppGrain.LIGHT_ALPHA
    return drawWithCache {
        // brush 在 cache 域一次构建（R1 🔵-4：原 drawBehind 每次重绘新建 shader 包装）。
        val brush = ShaderBrush(ImageShader(AppGrain.tile, TileMode.Repeated, TileMode.Repeated))
        onDrawBehind { drawRect(brush = brush, alpha = alpha) }
    }
}
