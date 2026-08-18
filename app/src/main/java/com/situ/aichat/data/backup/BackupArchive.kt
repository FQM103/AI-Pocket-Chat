package com.situ.aichat.data.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份容器 = 一个 ZIP：`manifest.json`（全部结构化数据）+ `media/` 子目录（头像 / 语音 / 图片 / 贴纸字节）。
 *
 * **为什么不照搬 iOS**（13.6，用户拍板）：iOS 把每段媒体 Base64 内嵌进单个 JSON，再整体 LZFSE 压成
 * `.aichatbackup`——解析时必须把整包载进内存、且 Base64 比原字节膨胀 ~33%。安卓借「无沙盒文件系统」把媒体拆成
 * zip 内独立文件，DTO 内只存 zip 相对键（archiveKey）：更省体积（zip deflate + 无 Base64 膨胀）、用户可在电脑上
 * 解压检视/挑拣、且为「真·换机迁移」打基础。媒体绝对路径是安装相关的（`/data/user/0/...`），换机必断 → 一律带
 * 字节走 zip，导入端重存重铸新绝对路径（=头像那套），故 DB 列零改动。
 *
 * 容器只负责「打包/解包字节」，不认识业务结构（manifest 由 [BackupService] 序列化/反序列化）。
 */
object BackupArchive {
    /** zip 内 manifest 文件名（结构化数据 JSON）。 */
    const val MANIFEST_ENTRY = "manifest.json"

    /** 媒体子目录前缀；archiveKey 形如 `media/audio/<uuid>.mp3`。 */
    const val MEDIA_PREFIX = "media/"

    /**
     * 流式打包到 [out]：先写 manifest，再**逐个媒体文件从磁盘流式拷入** zip——峰值内存只一个文件的拷贝缓冲，
     * 不把全部媒体字节一次性载进内存（13.6c：自动备份/大媒体备份避免 OOM，手动导出同样受益）。
     * @param mediaPaths key = zip 内相对路径（[MEDIA_PREFIX] 开头），value = 媒体绝对路径（空键 / 文件不存在 → 跳过）。
     * @param onEntry 只读进度观测点（P1-7）：每处理完一个媒体条目回调 (done, total)；**跳过项也计入 done**
     *   （total = mediaPaths.size，保证 done 收敛到 total）。在 closeEntry 之后调用、不触流——产出字节与不带
     *   回调时完全一致（BackupRoundTripTest 锁等价）。
     *
     * 用 [ZipOutputStream.finish] 写完 zip 中央目录但**不关闭** [out]——[out] 的所有权归调用方（用 `out.use { ... }`
     * 包裹本调用即可，避免重复 close）。
     */
    fun writeTo(
        out: OutputStream,
        manifestJson: String,
        mediaPaths: Map<String, String>,
        onEntry: ((done: Int, total: Int) -> Unit)? = null,
    ) = writeTo(out, { it.write(manifestJson.encodeToByteArray()) }, mediaPaths, onEntry)

    /**
     * 同上，但 manifest **直接编码进 zip 条目流**（卷 A·J7）：导出侧不再先 `encodeToString` 攒出完整字符串、
     * 再复制一份 UTF-8 字节（1 万条消息含 embedding ≈ String 55MB + ByteArray 27MB 两份白占）。
     *
     * [manifestWriter] 收到的是 **manifest 条目的输出流**：只管往里写，**绝不 close**（关掉它就等于提前封了整个 zip）。
     * 其余语义（媒体逐个从磁盘流式拷入 / [onEntry] 口径 / 不 close [out]）与 String 版逐字相同——String 版即委托本函数。
     */
    fun writeTo(
        out: OutputStream,
        manifestWriter: (OutputStream) -> Unit,
        mediaPaths: Map<String, String>,
        onEntry: ((done: Int, total: Int) -> Unit)? = null,
    ) {
        val zos = ZipOutputStream(out)
        zos.putNextEntry(ZipEntry(MANIFEST_ENTRY))
        manifestWriter(zos)
        zos.closeEntry()
        val total = mediaPaths.size
        var done = 0
        for ((key, path) in mediaPaths) {
            done++
            if (key.isBlank() || !File(path).exists()) {
                onEntry?.invoke(done, total)
                continue
            }
            zos.putNextEntry(ZipEntry(key))
            File(path).inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
            onEntry?.invoke(done, total)
        }
        zos.finish()
        zos.flush()
    }

    /**
     * 遍一（卷 A·J2）：找到 [MANIFEST_ENTRY] 并把**这一条目的有界流**交给 [consume]（导入侧 = `decodeFromStream`，
     * 连 manifest 字符串都不物化）。返回 consume 的产物。
     *
     * - **不假设 manifest 是第一个 entry**：我方导出恒写在首位，但设计上鼓励用户解压检视/挑拣后重打包（见类注释），
     *   故逐条扫；非 manifest 的条目**一个字节都不读**，直接 [ZipInputStream.closeEntry] 掠过。
     * - 返回 null = 打不开流 / 不是 zip / zip 里没有 manifest → 调用方回退旧 `.json` 解析（对齐旧 `read()==null` 语义）。
     * - [consume] 自己抛出的异常（JSON 损坏）**原样上抛**，绝不吞成 null——「文件损坏」与「不是 zip」是两回事，
     *   吞了就会把损坏的包误送进 legacy 回退路、报成风马牛不相及的错。
     * - 只 catch [Exception]：[OutOfMemoryError] 一类 Error 照样上抛，绝不化装成「不是 zip」。
     */
    fun <T> consumeManifest(open: () -> InputStream?, consume: (InputStream) -> T): T? {
        val raw = try { open() } catch (e: Exception) { null } ?: return null
        try {
            val zis = ZipInputStream(raw)
            while (true) {
                val entry = try { zis.nextEntry } catch (e: Exception) { return null } ?: return null
                if (!entry.isDirectory && entry.name == MANIFEST_ENTRY) return consume(zis)
                try { zis.closeEntry() } catch (e: Exception) { return null }
            }
        } finally {
            runCatching { raw.close() }
        }
    }

    /**
     * 遍二（卷 A·J3）：逐个 [MEDIA_PREFIX] 前缀条目回调——**读一条、处理一条、即弃一条**，峰值内存 = 单个媒体文件。
     *
     * [action] 收到 (key, readBytes)：**调用 `readBytes()` 才真正把这一条解压进内存**——调用方决定跳过的条目
     * （如「跳过」策略角色的私有媒体）连字节都不读，直接掠过；每个条目恒回调一次（跳过项也回调，供进度计数）。
     *
     * 异常分工：条目推进（`nextEntry`）失败原样上抛（zip 截断 / 中央目录损坏 → 调用方中止导入）；
     * [action] 自己抛出的（含它调用 `readBytes()` 读断、或重存失败）也原样上抛，由调用方决定处置
     * （导入侧逐条捕获计 `mediaFailed`）。打不开流 → 静默返回（以遍一的结论为准）。
     *
     * `inline` 的用意：让 [action] 里能直接调用挂起函数（导入侧要在回调里重存媒体），同时本对象保持零协程依赖。
     */
    inline fun forEachMediaEntry(open: () -> InputStream?, action: (key: String, readBytes: () -> ByteArray) -> Unit) {
        val raw = try { open() } catch (e: Exception) { null } ?: return
        try {
            val zis = ZipInputStream(raw)
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory && entry.name.startsWith(MEDIA_PREFIX)) {
                    action(entry.name) { zis.readBytes() }
                }
                zis.closeEntry()
            }
        } finally {
            runCatching { raw.close() }
        }
    }

}
