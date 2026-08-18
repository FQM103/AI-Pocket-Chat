package com.situ.aichat.ui.moments

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.util.ContentImageStore

/**
 * 朋友圈帖子图片网格（M06 7.2.7，对齐 iOS `MomentImageGrid`）。布局规则逐条复刻：
 * - 1 张 → 整宽大图（高 ≤200dp，裁剪填充）
 * - 2–3 张 → 等宽方图横排（间距 3dp）
 * - 4+ 张 → 3 列九宫格，最多展示 9 张（间距 3dp）
 *
 * 异步解码沿用 [com.situ.aichat.ui.diary.DiaryThumbnail] 的模式（`ContentImageStore.load` 后台解码，
 * `remember`/`LaunchedEffect` 按路径驱动），**不引 Coil**。供帖子卡片与详情页（7.2.8）共用。
 */
@Composable
fun MomentImageGrid(imagePaths: List<String>, modifier: Modifier = Modifier) {
    val count = imagePaths.size
    when {
        count == 0 -> Unit
        count == 1 -> MomentImage(
            path = imagePaths[0],
            contentDescription = stringResource(R.string.moment_image_single_desc),
            modifier = modifier.fillMaxWidth().height(200.dp),
        )
        count <= 3 -> Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            imagePaths.forEachIndexed { index, path ->
                gridCell(path = path, index = index, total = count)
            }
        }
        else -> {
            // 4+ 张：3 列九宫格，最多 9 张。手写行布局（避免在 LazyColumn 内嵌套 LazyVGrid 的滚动冲突）。
            val visible = imagePaths.take(9)
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                visible.chunked(3).forEachIndexed { rowIndex, rowPaths ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        rowPaths.forEachIndexed { colIndex, path ->
                            gridCell(path = path, index = rowIndex * 3 + colIndex, total = count)
                        }
                        // 末行补空占位，保持每格等宽（3 列对齐）。
                        repeat(3 - rowPaths.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** 等宽方格单元（横排 / 九宫格共用）。`total` = 配图总数（>9 时仍报真实总数，对齐 iOS）。 */
@Composable
private fun RowScope.gridCell(path: String, index: Int, total: Int) {
    MomentImage(
        path = path,
        contentDescription = stringResource(R.string.moment_image_content_desc, index + 1, total),
        modifier = Modifier.weight(1f).aspectRatio(1f),
    )
}

/** 单张图片：从内部存储路径异步解码（`ContentImageStore.load`），解码前显示占位底色。发布页预览复用（internal）。 */
@Composable
internal fun MomentImage(
    path: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // 按实际单元格像素降采样解码（grid 缩略图远小于存储 1024px），命中 LruCache 后滚动零重解。
        val targetPx = constraints.maxWidth.coerceIn(1, 2048)
        var bitmap by remember(path, targetPx) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(path, targetPx) { bitmap = ContentImageStore.load(path, targetPx) }
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
