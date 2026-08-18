package com.situ.aichat.prompt.growth

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 词表解析产物：归一化词条→原型ID 索引 + 跳行/重复/未知原型计数（诊断用·由调用侧决定是否 Log）
 * + 有效词条流 SHA-256 摘要（微图纸「指纹五项加固」⑤：注释/空行/非法行不进指纹，指纹只随真语义变）。
 */
internal data class ParsedLexicon(
    val index: Map<String, String>,
    val skippedLines: Int,
    val duplicateWords: Int,
    val unknownArchetypes: Int,
    val entriesDigestHex: String,
)

/** 小写十六进制（查表·与 Locale 无关——微图纸加固④）。 */
private val HEX_LOWER = "0123456789abcdef".toCharArray()
internal fun bytesToLowerHex(bytes: ByteArray): String {
    val out = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xFF
        out[i * 2] = HEX_LOWER[v ushr 4]
        out[i * 2 + 1] = HEX_LOWER[v and 0x0F]
    }
    return String(out)
}

/**
 * 成长原型校准（图纸 docs/handoff/2026-07-11-成长原型校准.md §3.2）名分→原型词表。
 *
 * 资产 `assets/growth/relationship_lexicon.tsv` 懒加载一次进内存 HashMap；归一化 + 最长子串匹配。
 * 解析 / 匹配 / 归一化全为 companion 纯函数（**不打 Log**，JVM 可直测）；@Singleton 主构造只负责读 asset。
 * 测试经 [fromRawText] 构造真词表实例（零 Android 依赖）；指纹计算（D-14）消费 [entriesDigestHex]
 * （有效词条流摘要——**原文解析后即弃不驻留**，微图纸加固②⑤：词表涨到万级也只常驻索引 + 32 字节摘要）。
 */
@Singleton
class RelationshipLexicon private constructor(
    private val rawSource: () -> String,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(rawSource = { readAsset(context) })

    private val parsed: ParsedLexicon by lazy {
        // 原文只活在本 lazy 块内：解析（同步算词条流摘要）完成即随局部变量丢弃（加固②）。
        parseLexicon(rawSource()).also {
            // 诊断日志在此消费侧打（纯函数不打 Log → JVM 单测零 Android 依赖·PITFALLS §1c）。
            if (it.skippedLines > 0 || it.duplicateWords > 0 || it.unknownArchetypes > 0) {
                Log.w(TAG, "词表解析：非法 ${it.skippedLines} 行 / 重复词条 ${it.duplicateWords} / 未知原型 ${it.unknownArchetypes}")
            }
        }
    }

    /** 归一化词条 → 原型ID。 */
    val index: Map<String, String> get() = parsed.index

    /** 有效词条流 SHA-256 摘要（指纹 D-14 的 lexicon 分量；触发懒解析）。 */
    val entriesDigestHex: String get() = parsed.entriesDigestHex

    /** 名分 → 原型 id（归一化 + 匹配）；空 / 认不出 → null。校准器唯一消费口。 */
    fun resolve(relationshipName: String?): String? {
        val n = normalizeRelationshipName(relationshipName ?: return null)
        return matchArchetypeId(n, index)
    }

    companion object {
        const val ASSET_PATH = "growth/relationship_lexicon.tsv"
        private const val TAG = "RelationshipLexicon"

        /** 测试接缝：直接喂原始文本构造实例（JVM 零 Android 依赖·图纸 §3.2）。 */
        internal fun fromRawText(raw: String): RelationshipLexicon = RelationshipLexicon(rawSource = { raw })

        private fun readAsset(context: Context): String = runCatching {
            context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse {
            Log.w(TAG, "词表资产读取失败（$ASSET_PATH），降级空表", it)
            ""
        }

        /** 归一化：trim → 移除全部空白 → 英文小写。不做繁简/全半角（图纸 0.3）。 */
        internal fun normalizeRelationshipName(raw: String): String =
            raw.trim().filterNot { it.isWhitespace() }.lowercase()

        /**
         * 纯函数解析（测试接缝·**不打 Log**）：逐行解析，非法行（列数≠2）/短词（<2）跳过并计数；
         * 原型 id 对照 [RelationshipArchetype.byId]（前向兼容，未知 id 跳过并计数）；
         * 重复归一化词条后者跳过并计数（词条→原型一对一）。资产整体空 → 空索引（全体走闭嘴分支）。
         *
         * 同步累计**有效词条流摘要**（微图纸加固⑤锁定）：按文件出现序，每个被接受的条目喂一段
         * `归一化词条 + '\t' + 原型ID + '\n'`（UTF-8）进 SHA-256——注释/空行/非法行/短词/未知原型/重复词条
         * 不进流（顺序属语义：重复词先者胜）。
         */
        internal fun parseLexicon(raw: String): ParsedLexicon {
            val index = HashMap<String, String>()
            val digest = MessageDigest.getInstance("SHA-256")
            var skipped = 0
            var dup = 0
            var unknown = 0
            for (line0 in raw.lineSequence()) {
                val line = line0.trimEnd('\r')
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split('\t')
                if (parts.size != 2) { skipped++; continue }
                val key = normalizeRelationshipName(parts[0])
                val archId = parts[1].trim()
                if (key.length < 2) { skipped++; continue }
                if (RelationshipArchetype.byId(archId) == null) { unknown++; continue }
                if (index.containsKey(key)) { dup++; continue }
                index[key] = archId
                digest.update("$key\t$archId\n".toByteArray(Charsets.UTF_8))
            }
            return ParsedLexicon(index, skipped, dup, unknown, bytesToLowerHex(digest.digest()))
        }

        /**
         * 匹配（图纸 §3.2 / D-13）：归一化名分为空或 <2 → null；否则枚举全部长度≥2 子串查表，
         * 取长度严格最大者；若最大长度并列且映射到**不同**原型 → null（宁少说不说错）。O(n²)·n≤名分长度。
         */
        internal fun matchArchetypeId(normalized: String, index: Map<String, String>): String? {
            if (normalized.length < 2) return null
            var bestLen = 0
            var bestId: String? = null
            var ambiguous = false
            val n = normalized.length
            for (start in 0 until n) {
                for (end in start + 2..n) {
                    val id = index[normalized.substring(start, end)] ?: continue
                    val len = end - start
                    when {
                        len > bestLen -> { bestLen = len; bestId = id; ambiguous = false }
                        len == bestLen && id != bestId -> ambiguous = true
                    }
                }
            }
            return if (ambiguous) null else bestId
        }
    }
}
