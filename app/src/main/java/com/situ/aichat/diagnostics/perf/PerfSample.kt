package com.situ.aichat.diagnostics.perf

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 性能采集样本族（性能专项卷 0·图纸 §3.2 逐字锁定字段名 —— **字段名即导出 JSON 的键名**，
 * 改任何一个都会让已导出的历史报告与分析侧对不上）。
 *
 * 设计要点：
 * - 样本只装**数字与短枚举串**（场景名/屏幕名/设置 key 名/阶段名），绝不装聊天正文、日记正文、API key
 *   （图纸 §0.3 ②③ + REDLINES §3 日志内容约定）。
 * - 落盘一行一条 JSON（JSONL），逐行独立 —— 进程被杀导致最后一行截断只损失该行，见 [PerfSampleCodec.decode]。
 */
const val PERF_SCHEMA_VERSION = 1

/** `header.kind` 的取值（图纸 §9② 锁定恰 5 个·**不许增删**）。 */
object PerfSampleKind {
    const val FOREGROUND = "foreground"
    const val FRAMES = "frames"
    const val HEALTH = "health"
    const val SETTINGS_WRITE = "settings_write"
    const val BACKUP_PROBE = "backup_probe"
}

/**
 * 被观测场景名（图纸 §9② 锁定恰 6 个·**不许增删**）。只有这几屏会挂帧监听——
 * 它们是「GL 自绘 / 长驻动画 / 长列表」这三类最可能掉帧的屏，其余屏挂了也是白烧电。
 */
object PerfScenes {
    const val WORLD_PLANET = "world_planet"
    const val WORLD_CONTINENT = "world_continent"
    const val WORLD_TOWN = "world_town"
    const val VOICE_CALL = "voice_call"
    const val MEMORY_STARFIELD = "memory_starfield"
    const val STORY_READER = "story_reader"
}

/** 尺 4 埋点位置（`settings_write` 样本的 `screen` / `key` 取值）。 */
object PerfSettingsSites {
    const val SCREEN_CONTEXT_LOG = "context_log_settings"
    const val SCREEN_PERF_COLLECT = "perf_collect"
    const val KEY_LOG_RETENTION = "log_retention_count"
    const val KEY_PERF_COLLECT_ENABLED = "perf_collect_enabled"
}

/** 每条样本的公共头（图纸 §3.2）。[tMillis] = 采样时刻 epoch millis；[kind] 取 [PerfSampleKind]。 */
@Serializable
data class PerfHeader(
    val schemaVersion: Int,
    val tMillis: Long,
    val kind: String,
)

/** 回前台单个 pass 的耗时（毫秒）。[name] 取 [PerfPassNames] 里的固定串。 */
@Serializable
data class PassTiming(val name: String, val ms: Long)

/**
 * 样本本体。**故意不做 kotlinx 多态序列化**：多态会在行首再插一个类判别键，与 [PerfHeader.kind] 语义重复；
 * 这里用 [PerfSampleCodec] 按 `header.kind` 手动分派，导出 JSON 的键集合 = 图纸 §3.2 锁定的那一份，一个不多。
 */
sealed interface PerfSample {
    val header: PerfHeader

    /** 尺 2：一趟「回前台」的各 pass 耗时 + 当时的规模数。 */
    @Serializable
    data class Foreground(
        override val header: PerfHeader,
        val totalMs: Long,
        val passes: List<PassTiming>,
        val scale: ScaleNumbers,
    ) : PerfSample

    /** 尺 3：一段「场景会话」内的帧统计（内存聚合，绝不逐帧落盘·图纸 J2）。 */
    @Serializable
    data class Frames(
        override val header: PerfHeader,
        val scene: String,
        val durationMs: Long,
        val frameCount: Int,
        val jankCount: Int,
        val severeJankCount: Int,
        val p50Ms: Double,
        val p95Ms: Double,
        val p99Ms: Double,
        val maxMs: Double,
        val buckets: List<Int>,
        val refreshHz: Int,
    ) : PerfSample

    /** 尺 3：热节流档位与电池温度。[batteryTempC] 取不到时 = `Double.NaN`（§5 E23）。 */
    @Serializable
    data class Health(
        override val header: PerfHeader,
        val thermalStatus: Int,
        val thermalName: String,
        val batteryTempC: Double,
        val scene: String? = null,
    ) : PerfSample

    /** 尺 4：一次手势内的设置写盘次数与体量。 */
    @Serializable
    data class SettingsWrite(
        override val header: PerfHeader,
        val screen: String,
        val key: String,
        val writesInGesture: Int,
        val gestureMs: Long,
        val payloadBytes: Int,
    ) : PerfSample

    /** 尺 5：备份体检（只读）/ 假包生成的内存与规模数字。 */
    @Serializable
    data class BackupProbe(
        override val header: PerfHeader,
        val mode: String,
        val fileBytes: Long,
        val maxHeapBytes: Long,
        val peakHeapBytes: Long,
        val bitmapCacheBytes: Long,
        val oomCaught: Boolean,
        val stage: String,
        val mediaEntryCount: Int,
        val manifestChars: Int,
        val elapsedMs: Long,
    ) : PerfSample
}

/**
 * 采集侧 JSON 口径（**单源**）：落盘与导出报告的「原始样本」节必须逐字节一致，两边共用这一份。
 *
 * 与 DI 那份的唯一差别是放开非有限浮点 —— 电池温度取不到时按 §5 E23 记 `Double.NaN`，默认配置会在编码时
 * 抛 `JsonEncodingException` 把整条样本吞掉。
 */
fun perfJson(): Json = PERF_JSON_INSTANCE

private val PERF_JSON_INSTANCE = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = true
    allowSpecialFloatingPointValues = true
}

/** JSONL 单行编解码。编码走穷举 `when`（新增变体时编译器逼出这里），解码按 `header.kind` 分派、失败返回 null。 */
object PerfSampleCodec {

    fun encode(json: Json, sample: PerfSample): String = when (sample) {
        is PerfSample.Foreground -> json.encodeToString(PerfSample.Foreground.serializer(), sample)
        is PerfSample.Frames -> json.encodeToString(PerfSample.Frames.serializer(), sample)
        is PerfSample.Health -> json.encodeToString(PerfSample.Health.serializer(), sample)
        is PerfSample.SettingsWrite -> json.encodeToString(PerfSample.SettingsWrite.serializer(), sample)
        is PerfSample.BackupProbe -> json.encodeToString(PerfSample.BackupProbe.serializer(), sample)
    }

    /**
     * 单行解码。**任何异常一律吞成 null**：写盘中途被 force-stop 会留下半行 JSON（§5 E7），
     * 读取侧跳过该行、其余样本照常呈现，绝不让一行坏数据毁掉整份报告。
     */
    fun decode(json: Json, line: String): PerfSample? = runCatching {
        val kind = json.parseToJsonElement(line).jsonObject["header"]
            ?.jsonObject?.get("kind")?.jsonPrimitive?.content
        when (kind) {
            PerfSampleKind.FOREGROUND -> json.decodeFromString(PerfSample.Foreground.serializer(), line)
            PerfSampleKind.FRAMES -> json.decodeFromString(PerfSample.Frames.serializer(), line)
            PerfSampleKind.HEALTH -> json.decodeFromString(PerfSample.Health.serializer(), line)
            PerfSampleKind.SETTINGS_WRITE -> json.decodeFromString(PerfSample.SettingsWrite.serializer(), line)
            PerfSampleKind.BACKUP_PROBE -> json.decodeFromString(PerfSample.BackupProbe.serializer(), line)
            else -> null
        }
    }.getOrNull()
}
