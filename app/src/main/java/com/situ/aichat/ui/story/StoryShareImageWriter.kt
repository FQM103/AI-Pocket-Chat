package com.situ.aichat.ui.story

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * 分享长图落盘 + FileProvider 授权（ST8·契约 §5）。
 *
 * 写 PNG 到 `cacheDir/share`（[res/xml/file_paths.xml] 声明的可授权路径），返回 `content://` uri 给
 * `Intent.ACTION_SEND`。分享是临时产物：每次先清空 share 目录再写，避免缓存堆积。IO 操作·调用方切 Dispatchers.IO。
 */
object StoryShareImageWriter {
    const val SHARE_DIR = "share"
    const val FILE_NAME = "story_ending.png"
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    /** 写图并返回 FileProvider content uri；任何 IO 异常 → null（调用方降级提示）。 */
    fun write(context: Context, bitmap: Bitmap): Uri? = runCatching {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // 只留本次分享图
        val file = File(dir, FILE_NAME)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    }.getOrNull()
}
