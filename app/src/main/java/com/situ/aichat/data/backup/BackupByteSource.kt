package com.situ.aichat.data.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 备份字节源 = 「**能重新打开的一条流**」（性能专项卷 A·图纸 J1）。
 *
 * 为什么不是 `ByteArray`：整包字节驻留内存正是导入 OOM 的病根（小米 14 实测：64MB 假包只读体检峰值堆已达
 * 上限的 97.9%）。两遍流式（遍一读 manifest / 遍二逐条媒体）各自 [open] 一条**新**流，全程不物化整包；
 * SAF 流不保证支持 mark/reset，故**绝不**在同一条流上回绕。
 *
 * 实现方只负责「交出一条新流」——不缓存、不重试；打不开（文件被移走 / 授权失效）→ 返回 null。
 */
fun interface BackupByteSource {
    /** 交出一条**新**的输入流；打不开 → null。流的所有权归调用方（负责 close）。 */
    fun open(): InputStream?

    companion object {
        /**
         * SAF Uri 源（手选文件 / 「最近备份」行）。OpenDocument 授权在本次 activity 生命周期内有效、
         * 「最近备份」走已持久化的 tree 授权 → 预览段与确认段各重开一条流成立。
         */
        fun fromUri(contentResolver: ContentResolver, uri: Uri): BackupByteSource = BackupByteSource {
            runCatching { contentResolver.openInputStream(uri) }.getOrNull()
        }

        /** 内存字节源（测试 / 已在内存里的小包）。 */
        fun fromBytes(bytes: ByteArray): BackupByteSource = BackupByteSource { ByteArrayInputStream(bytes) }
    }
}

/** 读全文时的分片大小。 */
private const val READ_CHUNK_BYTES = 64 * 1024

/**
 * 读这个源的全文文本，**最多读 [maxBytes] 字节**：超帽 / 打不开 / 读断 → null。
 *
 * 只给旧明文 `.json` 备份的回退路用（那种备份不含媒体、实际只有几 MB）。帽子的意义：用户误选了个几百 MB 的
 * 文件时，绝不把它整个吞进内存——本卷要根治的正是这种整读。
 */
internal fun BackupByteSource.readTextCapped(maxBytes: Long): String? {
    val stream = try { open() } catch (e: Exception) { null } ?: return null
    return stream.use { ins ->
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            val n = try { ins.read(chunk) } catch (e: Exception) { return null }
            if (n < 0) break
            if (buffer.size().toLong() + n > maxBytes) return null // 超帽即收手
            buffer.write(chunk, 0, n)
        }
        runCatching { buffer.toByteArray().decodeToString() }.getOrNull()
    }
}
