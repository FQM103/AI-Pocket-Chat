package com.situ.aichat.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 甲 0·T1-A1：[ImageOrientation] 六路（0/90/180/270/无标签/异常）。
 *
 * 断言从**用户遭遇**独立反推，不照搬实现：竖拍照片（EXIF 说「转 90°」而像素是横的）解出来必须是竖的
 * ——即宽高互换（E3）；读不到方向的图**绝不能丢**，原样出图（E2）。
 *
 * Robolectric 4.16 默认 NATIVE 图形模式 = 真 Skia，故 JPEG 编解码、Matrix 旋转、宽高互换都是真实现，
 * 不是影子桩（同 [com.situ.aichat.ui.story.StoryShareCardRendererTest] 的既有先例）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageOrientationTest {

    private val context get() = RuntimeEnvironment.getApplication()

    /** 造一张**横**图（宽 > 高），像素本身不转——正是相机竖拍时的真实产物。 */
    private fun landscapeJpegBytes(width: Int = 40, height: Int = 20): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }

    /** 把 EXIF 方向标签写进 JPEG 字节（经临时文件，ExifInterface 的落盘 API 只认 File/FD）。 */
    private fun withExifOrientation(bytes: ByteArray, orientation: Int): ByteArray {
        val f = File.createTempFile("exif", ".jpg")
        f.writeBytes(bytes)
        ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return f.readBytes().also { f.delete() }
    }

    // ---- rotationDegreesOf：标签 → 度数 ----

    @Test
    fun 无EXIF标签_按不旋转处理() {
        assertEquals(0, ImageOrientation.rotationDegreesOf(landscapeJpegBytes()))
    }

    @Test
    fun EXIF方向NORMAL_零度() {
        val bytes = withExifOrientation(landscapeJpegBytes(), ExifInterface.ORIENTATION_NORMAL)
        assertEquals(0, ImageOrientation.rotationDegreesOf(bytes))
    }

    @Test
    fun EXIF方向九十度_读出九十() {
        val bytes = withExifOrientation(landscapeJpegBytes(), ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(90, ImageOrientation.rotationDegreesOf(bytes))
    }

    @Test
    fun EXIF方向一百八十度_读出一百八() {
        val bytes = withExifOrientation(landscapeJpegBytes(), ExifInterface.ORIENTATION_ROTATE_180)
        assertEquals(180, ImageOrientation.rotationDegreesOf(bytes))
    }

    @Test
    fun EXIF方向二百七十度_读出二百七() {
        val bytes = withExifOrientation(landscapeJpegBytes(), ExifInterface.ORIENTATION_ROTATE_270)
        assertEquals(270, ImageOrientation.rotationDegreesOf(bytes))
    }

    @Test
    fun E2_不是图片的垃圾字节_不崩且按不旋转处理() {
        assertEquals(0, ImageOrientation.rotationDegreesOf(ByteArray(64) { 0x7F }))
        assertEquals(0, ImageOrientation.rotationDegreesOf(ByteArray(0)))
    }

    // ---- applyRotation：度数 → 像素 ----

    @Test
    fun E3_九十度旋转_宽高互换() {
        val src = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        val out = ImageOrientation.applyRotation(src, 90)
        assertEquals("竖拍图扶正后必须变竖的", 20, out.width)
        assertEquals(40, out.height)
    }

    @Test
    fun E3_二百七十度旋转_宽高互换() {
        val out = ImageOrientation.applyRotation(Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888), 270)
        assertEquals(20, out.width)
        assertEquals(40, out.height)
    }

    @Test
    fun 一百八十度旋转_宽高不变() {
        val out = ImageOrientation.applyRotation(Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888), 180)
        assertEquals(40, out.width)
        assertEquals(20, out.height)
    }

    @Test
    fun 零度_原图原样返回_不白复制一张() {
        val src = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        assertSame("0 度不该新建位图", src, ImageOrientation.applyRotation(src, 0))
    }

    // ---- decodeOriented：端到端 ----

    @Test
    fun E3_端到端_竖拍图从uri解出即已扶正() {
        val bytes = withExifOrientation(landscapeJpegBytes(40, 20), ExifInterface.ORIENTATION_ROTATE_90)
        val uri = android.net.Uri.parse("content://test/portrait.jpg")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())

        val out = ImageOrientation.decodeOriented(context, uri, maxEdge = 2048)

        assertNotNull("能解出图", out)
        assertEquals("EXIF 说转 90°，出图必须是竖的（20×40）", 20, out!!.width)
        assertEquals(40, out.height)
    }

    @Test
    fun E2_端到端_无标签图原样出_不丢图() {
        val uri = android.net.Uri.parse("content://test/plain.jpg")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, landscapeJpegBytes(40, 20).inputStream())

        val out = ImageOrientation.decodeOriented(context, uri, maxEdge = 2048)

        assertNotNull("读不到方向也绝不能丢图", out)
        assertEquals(40, out!!.width)
        assertEquals(20, out.height)
    }

    @Test
    fun E1_端到端_坏图解不出_返回null走取消路() {
        val uri = android.net.Uri.parse("content://test/broken.jpg")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArray(32) { 0x11 }.inputStream())

        assertEquals(null, ImageOrientation.decodeOriented(context, uri, maxEdge = 2048))
    }
}
