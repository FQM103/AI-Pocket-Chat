package com.situ.aichat.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码编解码工具（13.10b · C7，扫码导入/导出 API 配置）。基于 ZXing core（纯 Java、无 GMS）。
 * 编码用于「生成二维码导出」；解码用于扫码导入（从相册图 [decode] / 相机帧 [decodeYuvLuminance]）。
 *
 * 像素级 Bitmap/相机帧操作不便纯单测（依赖真实位图/帧数据），故本类逻辑薄、留真机批验往返；负载层
 * [com.situ.aichat.share.ApiConfigShareCodec] 才是可单测的纯函数部分。
 */
object QrCodec {

    /** 把文本编码为 [sizePx]×[sizePx] 的黑白二维码 Bitmap（ARGB_8888）。 */
    fun encode(text: String, sizePx: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /** 从一张 Bitmap（相册选的图）里解码二维码文本；未找到 / 解析失败 → null。 */
    fun decode(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return decodeSource(RGBLuminanceSource(width, height, pixels))
    }

    /**
     * 从相机 YUV 帧的 Y（亮度）平面解码二维码文本；未找到 → null（继续扫下一帧）。
     * [yPlane] = Y 平面字节，[dataWidth] = Y 平面 rowStride（每行字节数，可能 > 图宽），[dataHeight] = 图高，
     * [cropWidth]/[cropHeight] = 实际有效区（图宽×图高）。
     */
    fun decodeYuvLuminance(
        yPlane: ByteArray,
        dataWidth: Int,
        dataHeight: Int,
        cropWidth: Int,
        cropHeight: Int,
    ): String? = decodeSource(
        PlanarYUVLuminanceSource(yPlane, dataWidth, dataHeight, 0, 0, cropWidth, cropHeight, false),
    )

    /** 用 QR 专用 reader 解一个亮度源；先正常解、未中再试反色（亮底暗码）。两者都未中 → null。 */
    private fun decodeSource(source: LuminanceSource): String? {
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text
            } catch (_: NotFoundException) {
                null
            }
        }
    }
}
