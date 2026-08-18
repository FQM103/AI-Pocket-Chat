package com.situ.aichat.diagnostics.perf

import android.content.Context
import android.net.Uri
import android.os.Debug
import android.util.Log
import com.situ.aichat.data.backup.BackupArchive
import com.situ.aichat.data.backup.BackupPackage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 备份体检（图纸 §2.1 尺 5 · J3 ①）——**只读**。
 *
 * 量的是「多大的备份包会把 App 撑爆」。做法是**照着真导入的路子走一遍但什么都不留**：遍一解 manifest、
 * 遍二逐条媒体读进来即弃，记内存峰值与是否捕到 [OutOfMemoryError]，然后就停下。
 *
 * **一行都不写库、一个字节都不落盘**（图纸 §2.3 / §9④）：本类连一个 DAO、一个 Store 都没注入 —— 想写也没有
 * 句柄，这比「注入了但保证不调」更硬。也绝不调重存、绝不碰既有导入路径。
 *
 * 为什么要真的把字节读进内存：那正是导入时的真实峰值，也正是要钉的「多大开始失败」的门槛所在。
 * 卷 A 之后导入改成两遍流式，体检**跟着量新路**——字段名、`mode`/`stage` 取值、样本格式一律不变，
 * 修前修后的报告可直接对比（`manifestChars` 的含义微调见 [CountingInputStream]）。
 */
@Singleton
class BackupHealthProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val collector: PerfCollector,
    private val json: Json,
) {

    /** 体检用户选中的真备份（只读）。样本已落进采集队列，同时返回给调用方做即时提示。 */
    suspend fun probe(uri: Uri): PerfSample.BackupProbe {
        val bytes = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull() ?: UNKNOWN_SIZE
        return probeSource(MODE_READONLY, bytes) { context.contentResolver.openInputStream(uri) }
    }

    /** 体检本机生成的假包（[FakeBackupBuilder] 产物）。 */
    suspend fun probe(file: File): PerfSample.BackupProbe =
        probeSource(MODE_FAKE, file.length()) { file.inputStream() }

    /**
     * 体检本体。[open] 每次调用交出一条新输入流（返回 null = 打不开）。
     * 分阶段推进，任何一步失败都**如实记下停在哪一阶段**，绝不编一个好看的结果。
     */
    @OptIn(ExperimentalSerializationApi::class) // decodeFromStream（卷 A·J9：跟着导入走两遍流式）
    internal suspend fun probeSource(
        mode: String,
        fileBytes: Long,
        open: () -> InputStream?,
    ): PerfSample.BackupProbe = withContext(Dispatchers.IO) {
        val runtime = Runtime.getRuntime()
        val startNanos = System.nanoTime()
        var peakHeap = usedHeap(runtime)
        var stage = STAGE_READ
        var oom = false
        var mediaEntryCount = 0
        var manifestChars = 0

        fun mark() {
            val used = usedHeap(runtime)
            if (used > peakHeap) peakHeap = used
        }

        var opened = false
        var manifestCounter: CountingInputStream? = null

        try {
            // 遍一：只把 manifest 那一条条目的流交给解析器（卷 A 之后导入就是这么走的，体检跟着量新路）。
            val parsed = BackupArchive.consumeManifest({ open()?.also { opened = true } }) { entry ->
                stage = STAGE_PARSE
                val counting = CountingInputStream(entry).also { manifestCounter = it }
                json.decodeFromStream(BackupPackage.serializer(), counting)
            }
            mark()
            when {
                !opened -> stage = STAGE_READ_FAILED
                // 非 zip / 无 manifest / zip 损坏（§5 E12）。
                parsed == null -> stage = STAGE_PARSE_FAILED
                else -> {
                    // 遍二：逐条媒体读进来再丢掉——量的正是导入第二遍的真实峰值。**一个字节都不落盘**。
                    stage = STAGE_UNZIP
                    BackupArchive.forEachMediaEntry(open) { _, readBytes ->
                        mediaEntryCount++
                        readBytes()
                        mark()
                    }
                    mark()
                    stage = STAGE_DONE
                }
            }
        } catch (e: OutOfMemoryError) {
            // 这正是要量的东西（§5 E13）：记下「在哪一阶段、多大的包」把内存撑爆了。
            oom = true
            mark()
            Log.w(TAG, "备份体检撞到 OOM：阶段=$stage 包大小=$fileBytes 字节")
        } catch (e: Exception) {
            stage = STAGE_PARSE_FAILED
            mark()
            Log.w(TAG, "备份体检失败：${e.message}")
        }

        // 解析成功与失败都要如实带出（失败时 = 解析器在炸掉之前读了多少）。
        manifestChars = manifestCounter?.bytesRead ?: 0

        val sample = PerfSample.BackupProbe(
            header = collector.newHeader(PerfSampleKind.BACKUP_PROBE),
            mode = mode,
            fileBytes = fileBytes,
            maxHeapBytes = runtime.maxMemory(),
            peakHeapBytes = peakHeap,
            bitmapCacheBytes = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L),
            oomCaught = oom,
            stage = stage,
            mediaEntryCount = mediaEntryCount,
            manifestChars = manifestChars,
            elapsedMs = (System.nanoTime() - startNanos) / ForegroundTrace.NANOS_PER_MILLI,
        )
        collector.record(sample)
        collector.requestFlush()
        sample
    }

    private fun usedHeap(runtime: Runtime): Long = runtime.totalMemory() - runtime.freeMemory()

    /**
     * 边读边数、读完即弃（卷 A）：manifest 不再物化成字符串，所以「有多大」只能这样量。
     * `manifestChars` 的含义随之从「字符数」微调为「**该条目的字节数**」——字段名与样本格式不变，
     * 修前修后的报告仍可直接对比（UTF-8 下中文 3 字节/字，量级与趋势一致）。
     */
    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var bytesRead = 0
            private set

        override fun read(): Int = delegate.read().also { if (it >= 0) bytesRead++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len).also { if (it > 0) bytesRead += it }

        override fun close() = delegate.close()
    }

    companion object {
        private const val TAG = "BackupHealthProbe"

        /** 图纸 §3.2 锁定的 `mode` 取值。 */
        const val MODE_READONLY = "readonly"
        const val MODE_FAKE = "fake"

        /** `stage` 取值：正常推进的三段 + 两种收场。`parse_failed` 由 §5 E12 逐字锁定。 */
        const val STAGE_READ = "read"
        const val STAGE_UNZIP = "unzip"
        const val STAGE_PARSE = "parse"
        const val STAGE_DONE = "done"
        const val STAGE_READ_FAILED = "read_failed"
        const val STAGE_PARSE_FAILED = "parse_failed"

        /** 取不到文件大小时的占位（与真实的 0 字节可区分）。 */
        const val UNKNOWN_SIZE = -1L
    }
}
