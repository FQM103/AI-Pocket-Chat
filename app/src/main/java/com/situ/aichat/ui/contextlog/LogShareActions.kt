package com.situ.aichat.ui.contextlog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.situ.aichat.R
import com.situ.aichat.diagnostics.LogShareFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 日志复制 / 导出动作（D-3 打磨·②）。**线程契约**：文件 IO 在 [Dispatchers.IO]、剪贴板 / Toast /
 * startActivity 回主线程；超限判定为 O(1)（length×2·复核 R1 改 UTF-16 口径后不再编码整串）就地算。
 * 调用方只管在 UI scope `launch` 调 suspend 入口，内部自行切线程，主线程绝不碰磁盘与大串编码。
 *
 * 超大兜底：UTF-16 字节数（= Binder parcel 真实体量）超 [LogShareFormat.CLIP_BYTE_LIMIT] 时，
 * 复制自动改走导出文件并 Toast 说明——绝不静默失败或让系统抛 TransactionTooLarge。
 */
object LogShareActions {

    /** 分享临时目录（复用故事分享 cacheDir/share·file_paths.xml 已授权；写前清空同一惯例）。 */
    private const val SHARE_DIR = "share"
    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val CLIP_LABEL = "context-log"

    /** 复制 [text]；超限自动转导出为 [fileName] 并说明。 */
    suspend fun copyOrExport(context: Context, text: String, fileName: String) {
        // 超限判定 O(1)（length×2·复核 R1 改 UTF-16 口径后不再编码整串），无需下 IO。
        if (LogShareFormat.copyPayloadTooLarge(text)) {
            val ok = exportTxt(context, text, fileName)
            toast(context, if (ok) R.string.contextlog_copy_too_large else R.string.contextlog_export_failed)
            return
        }
        withContext(Dispatchers.Main) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
            // Android 13+ 系统自带剪贴板确认浮层，再 Toast 是双重噪音（官方指引）；旧版本补可见反馈。
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, R.string.contextlog_copied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 详情页「导出 .txt」直点入口：失败 Toast 降级（不崩不静默）。 */
    suspend fun exportWithFeedback(context: Context, text: String, fileName: String) {
        if (!exportTxt(context, text, fileName)) toast(context, R.string.contextlog_export_failed)
    }

    /** 写 `cacheDir/share/[fileName]` 并拉系统分享面板；任何 IO 异常 → false。 */
    private suspend fun exportTxt(context: Context, text: String, fileName: String): Boolean {
        val uri = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() } // 临时产物只留本次（照 StoryShareImageWriter 惯例）
                val file = File(dir, fileName)
                file.writeText(text)
                FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
            }.getOrNull()
        } ?: return false
        withContext(Dispatchers.Main) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, fileName))
        }
        return true
    }

    private suspend fun toast(context: Context, resId: Int) = withContext(Dispatchers.Main) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }
}
