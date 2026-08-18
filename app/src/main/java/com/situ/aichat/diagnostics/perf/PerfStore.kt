package com.situ.aichat.diagnostics.perf

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 性能样本落盘（图纸 §3.3）：`filesDir/perf/` 下按天分 `perf-yyyyMMdd.jsonl`，一行一条样本。
 *
 * 为什么用文件不用 Room（图纸 J1）：样本是 append-only、从不按谓词查询、整体导出 —— 用文件 ⇒ 零 Room 迁移、
 * 零 schema 变更、不与后续卷抢版本号。
 *
 * 铁规：
 * - **全部操作 `runCatching` 静默降级**（§5 E3）：目录建不出来 / 无写权限 / 磁盘满，一律不抛给调用方，
 *   采集失败绝不影响 App（照 [com.situ.aichat.diagnostics.ContextLogService] 「日志失败绝不影响调用」）。
 * - 目录字节超 [DIR_BYTE_CAP] → 按 [PerfRetention] 删最旧的整个日文件；**当天文件永不删**，
 *   当天文件自己撑爆帽时改为停止追加并累计 [droppedSamples]（§5 E4/E5）。
 * - 单一 [mutex] 串行化全部文件读写（含 UI 触发的清空/读取），保证与采集侧的攒批写不打架。
 */
@Singleton
class PerfStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** 采集侧 JSON 口径单源（[perfJson]）——落盘与导出报告的「原始样本」节共用同一份，保证逐字节一致。 */
    private val json: Json = perfJson()

    private val mutex = Mutex()

    /** 因当天文件已撑爆容量帽而被丢弃的样本数（§5 E5·进程内累计，只用于排查「为什么样本变少了」）。 */
    @Volatile
    var droppedSamples: Int = 0
        private set

    private val dir: File get() = File(context.filesDir, DIR_NAME)

    /** 攒批追加。失败静默（[Log] 一行便于排查），绝不抛给采集点。 */
    suspend fun append(samples: List<PerfSample>, nowMillis: Long = System.currentTimeMillis()) {
        if (samples.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val d = dir
                    if (!d.isDirectory && !d.mkdirs()) return@runCatching
                    val todayName = fileNameFor(nowMillis)
                    val today = File(d, todayName)
                    if (today.length() >= DIR_BYTE_CAP) {
                        droppedSamples += samples.size
                        Log.w(TAG, "当天样本文件已达容量帽，停止追加（累计丢弃 $droppedSamples 条）")
                        return@runCatching
                    }
                    today.appendText(
                        buildString { samples.forEach { appendLine(PerfSampleCodec.encode(json, it)) } },
                    )
                    enforceCap(d, todayName)
                }.onFailure { Log.e(TAG, "样本落盘失败: ${it.message}") }
            }
        }
    }

    /** 全量读回（导出/采集页统计用）。解析失败的行跳过（§5 E7 最后一行被截断）。 */
    suspend fun readAll(): List<PerfSample> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                sampleFiles().flatMap { file ->
                    file.readLines().mapNotNull { line ->
                        if (line.isBlank()) null else PerfSampleCodec.decode(json, line)
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    /** 目录总字节（采集页「占用空间」）。 */
    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { sampleFiles().sumOf { it.length() } }.getOrDefault(0L) }
    }

    /** 清空全部采集数据（采集页「清空采集数据」）。 */
    suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    sampleFiles().forEach { it.delete() }
                    droppedSamples = 0
                }.onFailure { Log.e(TAG, "清空采集数据失败: ${it.message}") }
            }
        }
    }

    /**
     * 设置 DataStore 文件的当前字节数（尺 4 的「全量重写付多少代价」）。同步读一次文件长度、不读内容；
     * 读不到（文件还没落盘 / 路径变了）→ 0。
     */
    fun settingsDataStoreBytes(): Int = runCatching {
        File(context.filesDir, SETTINGS_DATASTORE_RELATIVE_PATH).length().toInt()
    }.getOrDefault(0)

    // MARK: - 私有

    /** 目录里的样本文件，按名（= 日期）升序。目录不存在 → 空。 */
    private fun sampleFiles(): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** 容量轮转（已持锁调用）：算法在纯函数 [PerfRetention]，这里只负责删。 */
    private fun enforceCap(d: File, todayName: String) {
        val infos = sampleFiles().map { PerfFileInfo(it.name, it.length()) }
        val victims = PerfRetention.filesToDelete(infos, DIR_BYTE_CAP, todayName)
        if (victims.isEmpty()) return
        victims.forEach { File(d, it).delete() }
        Log.d(TAG, "容量轮转：删旧样本文件 ${victims.size} 个（帽 $DIR_BYTE_CAP 字节）")
    }

    companion object {
        private const val TAG = "PerfStore"

        /** 图纸 §9② 锁定：目录 `filesDir/perf/`、文件名 `perf-yyyyMMdd.jsonl`、目录帽 4MB。 */
        const val DIR_NAME = "perf"
        const val FILE_PREFIX = "perf-"
        const val FILE_SUFFIX = ".jsonl"
        const val DIR_BYTE_CAP = 4L * 1024 * 1024

        /** 设置 DataStore 落点（`preferencesDataStore(name = "settings")` 的约定路径·见 di/DataStoreModule.kt）。 */
        const val SETTINGS_DATASTORE_RELATIVE_PATH = "datastore/settings.preferences_pb"

        /** `Locale.ROOT` 是硬规矩（工程既有教训：阿拉伯/印地区域会把数字本地化成非 ASCII，文件名对不上）。 */
        private val FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)

        /** 当天文件名（按**本地日期**分文件；用户改系统时钟/切时区不做纠错·§5 E9）。 */
        fun fileNameFor(millis: Long): String {
            val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
            return FILE_PREFIX + FILE_DATE_FORMAT.format(date) + FILE_SUFFIX
        }
    }
}
