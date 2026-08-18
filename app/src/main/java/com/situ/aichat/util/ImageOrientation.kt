package com.situ.aichat.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * 「解出一张**摆正**的位图」的单一入口（甲 0）。
 *
 * 为什么需要：相机拍竖图时通常**不转像素**，只在 JPEG 里写一个 EXIF 方向标签，看图 app 负责按标签转。
 * [ImageScaler.decodeSampled] 走裸 `BitmapFactory`，不读该标签 → 竖拍照片解出来是横躺的。头像裁剪屏与
 * 壁纸裁剪屏都吃这一口：取景时横躺，裁出来的成品同错。
 *
 * 职责边界：只管「解码 + 扶正」，**不落盘**（落盘归 [AvatarStore]/[WallpaperStore]）。
 * 解码底座复用 [ImageScaler.decodeSampled]（两趟 inSampleSize，防大图 OOM），本文件只加方向那一层。
 */
object ImageOrientation {

    /**
     * 从 [uri] 解出一张已按 EXIF 扶正的位图，长边不超过 [maxEdge]。
     *
     * 失败（读流异常 / 解码不出 / 坏图）一律返回 null 交调用方走取消路（E1），不抛。
     * EXIF 无标签、标签为 0、或读取异常 → 原样返回解码结果（E2：绝不因为读不到方向就丢图）。
     */
    fun decodeOriented(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val decoded = ImageScaler.decodeSampled(bytes, maxEdge) ?: return null
        return applyRotation(decoded, rotationDegreesOf(bytes))
    }

    /**
     * 读 EXIF 方向 → 需要顺时针转多少度（0/90/180/270）。
     *
     * 拿字节而非 InputStream：调用方已把整图读进内存喂给解码器，这里再开一条 [ByteArrayInputStream]
     * 即可——省一次 IO，也避免「同一条流被解码器消费完后 ExifInterface 读到空」的经典坑。
     * 任何异常（非图片 / 截断 / 无 EXIF 段）→ 0，即「不转」（E2）。
     */
    internal fun rotationDegreesOf(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees
    }.getOrElse {
        Log.w(TAG, "EXIF 方向读取失败，按不旋转处理", it)
        0
    }.let { if (it == 90 || it == 180 || it == 270) it else 0 }

    /**
     * 按 [degrees] 转出新图并回收原图；[degrees] 为 0 时原样返回（不白复制一张）。
     * 90/270 会使宽高互换——下游裁剪数学一律吃**旋正后**的尺寸（E3）。
     * 旋转失败（OOM 等）→ 返回原图，宁可方向不对也不让用户丢图。
     */
    internal fun applyRotation(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }.getOrElse {
            Log.w(TAG, "位图旋转失败，退回原图", it)
            bitmap
        }
    }

    private const val TAG = "ImageOrientation"
}
