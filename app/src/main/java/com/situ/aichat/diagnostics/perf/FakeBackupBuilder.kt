package com.situ.aichat.diagnostics.perf

import android.content.Context
import android.util.Log
import com.situ.aichat.data.backup.BackupArchive
import com.situ.aichat.data.backup.BackupManifest
import com.situ.aichat.data.backup.BackupPackage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 造可控大小的**假**备份包（图纸 §2.1 尺 5 · J3 ②）。
 *
 * 为什么光有「体检真备份」不够：用户当下的真备份**可能还没到会崩的量级**，只体检它会得出
 * 「没问题」的假结论。要找失败门槛，就得能造出任意大的包来撞。
 *
 * 假包只含**合成随机字节**当媒体 —— 不碰任何真数据、不读用户的任何文件（拍板 4「造假备份的路子，
 * 不碰真数据」）。随机字节还有个好处：不可压缩，故 zip 出来的体积≈投入的体积，目标大小可控。
 * 种子固定（[SEED]），同样的目标大小每次产出一样的包，便于前后对比。
 *
 * 产物落 `cacheDir/perf_fake/`，不进备份、不进任何导出路径。
 */
@Singleton
class FakeBackupBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    /**
     * 造一个目标 [targetBytes] 大小的假包。
     * 失败（磁盘不足等）→ **清理已写的半成品**后返回 null（§5 E14），绝不留垃圾文件。
     *
     * [importable]=true 时造**能真导入**的一档（卷 A·J10）：manifest 里是货真价实的角色/会话/消息，
     * 体积载荷改走 `media/audio/pad_*.wav`（重存是纯字节落盘、不解码，故不会假失败），头像是 1×1 的真 JPEG。
     * 这样才验得了「导入一个比内存上限还大的备份」——默认档那个占位 manifest 只够体检、进不了真导入。
     * **💰 钱段一律留空**（用户钱包/流水/礼物/红包全 null）：E2E 导入零碰钱路。
     * 默认档（[importable]=false）产出字节与改造前完全一致（既有「同种子两次一致」测试护着）。
     */
    suspend fun build(targetBytes: Long, importable: Boolean = false): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR_NAME)
        val out = File(dir, FILE_NAME)
        val mediaDir = File(dir, "src")
        runCatching {
            dir.mkdirs()
            mediaDir.mkdirs()
            val entryCount = entryCountFor(targetBytes)
            val perEntry = (targetBytes / entryCount).coerceAtLeast(1L)
            val random = Random(SEED)

            // ① 先把合成媒体写成真文件——BackupArchive.writeTo 是流式从磁盘拷入的，复用它就等于
            //    走了真实的打包路径（而不是另写一套 zip 逻辑）。
            val mediaPaths = LinkedHashMap<String, String>(entryCount)
            repeat(entryCount) { i ->
                val name = if (importable) "pad_$i.wav" else "blob_$i.bin"
                val f = File(mediaDir, name)
                f.writeBytes(random.nextBytes(perEntry.toInt()))
                mediaPaths[BackupArchive.MEDIA_PREFIX + if (importable) "audio/$name" else "fake/$name"] = f.absolutePath
            }

            // ② manifest 用真 DTO 编码 —— 假包也要能被解析器正常解析，否则体检会停在「解析失败」，
            //    量不到真正想量的「读+解压的内存峰值」。
            val pkg = if (importable) {
                FakeImportableManifest.build(mediaDir, mediaPaths)
            } else {
                BackupPackage(
                    manifest = BackupManifest(
                        version = ARCHIVE_VERSION,
                        appVersion = "perf-fake",
                        includesMedia = true,
                        mediaCount = entryCount,
                    ),
                )
            }
            val manifest = json.encodeToString(BackupPackage.serializer(), pkg)
            out.outputStream().use { BackupArchive.writeTo(it, manifest, mediaPaths) }
            mediaDir.deleteRecursively() // 中间文件即弃，只留成品包
            out
        }.getOrElse {
            Log.e(TAG, "生成假备份失败：${it.message}")
            mediaDir.deleteRecursively()
            out.delete()
            null
        }
    }

    /** 清掉已生成的假包（采集页「清空采集数据」顺手带走）。 */
    fun clear() {
        runCatching { File(context.cacheDir, DIR_NAME).deleteRecursively() }
    }

    companion object {
        private const val TAG = "FakeBackupBuilder"
        private const val DIR_NAME = "perf_fake"
        private const val FILE_NAME = "perf-fake-backup.zip"

        /** zip 全量格式版本（= `BackupManifest.version` 的 2）。 */
        private const val ARCHIVE_VERSION = 2

        /** 图纸 §9⑤：禁无种随机——固定种子，同样的目标大小每次产出同样的包。 */
        private const val SEED = 20260730

        /** 单个合成媒体的目标大小（字节）——切成多份更像真备份（真包是很多张图），也避免一次分配过大数组。 */
        const val BYTES_PER_ENTRY = 512L * 1024

        /** 目标大小 → 合成媒体份数（至少 1 份）。 */
        fun entryCountFor(targetBytes: Long): Int =
            ((targetBytes + BYTES_PER_ENTRY - 1) / BYTES_PER_ENTRY).coerceIn(1L, 4096L).toInt()
    }
}
