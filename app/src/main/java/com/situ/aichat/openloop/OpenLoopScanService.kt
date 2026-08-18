package com.situ.aichat.openloop

import com.situ.aichat.R
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OpenLoopStatus
import com.situ.aichat.data.local.entity.OpenLoopType
import com.situ.aichat.promise.PromiseLedgerService
import com.situ.aichat.prompt.PromptStrings
import com.situ.aichat.util.JSONExtractor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「心里惦记的事」纯逻辑骨干（活人感一期 P2·图纸 §3.2/§4.2/§4.3）：扫描提示词构建 + JSON 宽容解析 +
 * 过期清理 + 注入选择 + 注入块格式化。**全纯函数**——不碰 DB / 网络 / 协程（落库归 Trigger、LLM 调用归
 * Trigger 的 contextLog.completion、注入装配归 PromptBuilder 侧调用方）。
 *
 * ⚠️ **自有强耦合（图纸 §6）**：[buildScanPrompt] 的 JSON schema ↔ [parseScanResult] 的 [ScanDto] 必须同文件共存，
 * 改任一侧（字段名 / 结构）必须同步另一侧，否则静默破坏惦记提取。
 */
object OpenLoopScanService {

    /** 扫描温度（低温保稳·图纸 §3.2）。 */
    const val SCAN_TEMPERATURE = 0.2

    /** 一次扫描最多落库的新 loop 数（图纸 §3.2/§4.2「一次最多提取 2 条」）。 */
    const val NEW_LOOP_CAP = 2

    /** 一次注入最多带出的到期 loop 数（图纸 §3.2 注入上限）。 */
    const val INJECT_CAP = 2

    /** 无 dueAt 的 loop 存活上限（14 天·图纸 §3.2 过期清理）。 */
    const val NO_DUE_EXPIRY_MS = 14L * 24 * 60 * 60 * 1000

    /** 有 dueAt 的 loop 过期宽限（48h·图纸 §3.2 过期清理）。 */
    const val DUE_EXPIRY_GRACE_MS = 48L * 60 * 60 * 1000

    /** 长线回访窗口下限（活人感二期 M2·resolvedAt 距今 ≥7 天才回访·图纸 §3.2）。 */
    const val REVISIT_MIN_MS = 7L * 24 * 60 * 60 * 1000

    /** 长线回访窗口上限（活人感二期 M2·resolvedAt 距今 ≤30 天才回访·图纸 §3.2）。 */
    const val REVISIT_MAX_MS = 30L * 24 * 60 * 60 * 1000

    private val scanJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // ── 扫描提示词（§4.2 逐字·与 [parseScanResult] 强耦合） ──

    /**
     * [charName]/[userName] 空则分别回退 "AI 角色"/"用户"；[existing] 为 open 状态 loops（空则该段整体省略）；
     * [nowText] 由调用方按当前时间格式化传入；[conversationText] 为最近 30 条对话的预格式化文本；
     * [ledgerPromises]（记忆改造四期·§3.6-①·源头治理）= 该角色 open 约定的 content 列表（空则该段整体省略）——
     * 喂给模型「已进约定清单的事」清单，让它别把由约定清单单独管理的事当惦记重复提取（JSON schema / 解析 / resolved 语义零碰）。
     */
    fun buildScanPrompt(
        charName: String,
        userName: String,
        nowText: String,
        existing: List<OpenLoopEntity>,
        conversationText: String,
        ledgerPromises: List<String>,
    ): String {
        val cName = charName.ifBlank { "AI 角色" }
        val uName = userName.ifBlank { "用户" }
        val existingBlock = if (existing.isEmpty()) {
            ""
        } else {
            "已在清单上的事（不要重复提取）：\n" +
                existing.joinToString("\n") { "- [${it.uuid}] ${it.content}" } +
                "\n\n"
        }
        // 账本条目**不带 uuid**——防模型把约定当 loop 报 resolved；即便报了，resolvedUuids 按 loop uuid 匹配不中 = 天然 no-op（§3.6-①）。
        val ledgerBlock = if (ledgerPromises.isEmpty()) {
            ""
        } else {
            "已进约定清单的事（由约定清单单独管理，不要重复提取）：\n" +
                ledgerPromises.joinToString("\n") { "- $it" } + "\n\n"
        }
        return "你在帮 AI 角色「$cName」维护一份\"心里惦记的事\"清单。读下面的对话，找出两类值得一个朋友之后主动问起的事：\n" +
            "1. $cName 答应过对方的事（说好要做、要发、要讲的）；\n" +
            "2. $uName 提到的、即将发生或还没有结果的事（面试、考试、看病、出差、搬家、在纠结的决定……）。\n" +
            "\n" +
            "当前时间：$nowText\n" +
            "\n" +
            existingBlock +
            ledgerBlock +
            "只输出 JSON（不要代码块、不要解释）：\n" +
            "{\"loops\":[{\"content\":\"一句话概括，不超过30字，第三人称\",\"type\":\"promise_char|user_event|open_topic\",\"due\":\"能从对话确定具体日期就输出 yyyy-MM-dd'T'HH:mm（只有日期没有时间就用 09:00），确定不了就 null\"}],\"resolved\":[\"已在清单上、但对话显示已经解决或已经过去的事的 uuid\"]}\n" +
            "\n" +
            "规则：一次最多提取 2 条新的；纯闲聊话题不算；拿不准的宁可不提取。\n" +
            "\n" +
            "对话记录：\n" +
            conversationText
    }

    // ── 宽容解析（§4.2·剥围栏 → 首 { 到末 } → 宽松解析 → 容错映射） ──

    /** 单条解析出的新 loop（未落库·由 Trigger 补 uuid/conv/char/createdAt）。 */
    data class ParsedLoop(val content: String, val typeRaw: String, val dueAt: Long?)

    /** 扫描解析结果：新提取的 loops（≤2）+ 判定已解决的既有 loop uuid 列表。 */
    data class ScanResult(val newLoops: List<ParsedLoop>, val resolvedUuids: List<String>)

    /**
     * 宽容解析：剥 ``` 围栏 + 取首 `{` 到末 `}`（[JSONExtractor]）→ 宽松反序列化 → 容错映射
     * （未知 type→open_topic·坏 due→null·content 空白丢弃·loops 超 2 截断）。整体解析失败抛 [OpenLoopScanParseException]。
     */
    fun parseScanResult(text: String, zone: ZoneId = ZoneId.systemDefault()): ScanResult {
        val jsonStr = JSONExtractor.extract(text)
        val dto = runCatching { scanJson.decodeFromString(ScanDto.serializer(), jsonStr) }.getOrNull()
            ?: throw OpenLoopScanParseException("open-loop 扫描 JSON 解析失败")
        val newLoops = dto.loops
            .mapNotNull { l ->
                val content = l.content.trim()
                if (content.isEmpty()) return@mapNotNull null // content 空白 → 丢弃
                ParsedLoop(
                    content = content,
                    typeRaw = if (l.type in OpenLoopType.ALL) l.type else OpenLoopType.OPEN_TOPIC, // 未知 type → open_topic
                    dueAt = parseDue(l.due, zone), // 坏 due → null
                )
            }
            .take(NEW_LOOP_CAP) // loops 超 2 条截前 2
        val resolvedUuids = dto.resolved.map { it.trim() }.filter { it.isNotEmpty() }
        return ScanResult(newLoops, resolvedUuids)
    }

    /**
     * due 解析：null/空/"null" → null；否则先按 ISO `yyyy-MM-dd'T'HH:mm`(可带秒) 解析，再退回纯日期
     * `yyyy-MM-dd`（补 09:00·图纸 §4.2）；都失败 → null。按 [zone] 转 epoch millis。
     */
    private fun parseDue(raw: String?, zone: ZoneId): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.equals("null", ignoreCase = true)) return null
        runCatching { return LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDate.parse(s).atTime(9, 0).atZone(zone).toInstant().toEpochMilli() }
        return null
    }

    // ── 过期清理（扫描前跑·纯函数·§3.2） ──

    /** 应置 expired 的 loop：无 dueAt 存活 >14 天，或有 dueAt 已过期 >48h（仅 open 行）。 */
    fun expiredLoops(loops: List<OpenLoopEntity>, now: Instant): List<OpenLoopEntity> {
        val nowMillis = now.toEpochMilli()
        return loops.filter { loop ->
            loop.statusRaw == OpenLoopStatus.OPEN && when (val due = loop.dueAt) {
                null -> nowMillis - loop.createdAt > NO_DUE_EXPIRY_MS
                else -> nowMillis > due + DUE_EXPIRY_GRACE_MS
            }
        }
    }

    // ── 注入选择（§3.2·纯函数） ──

    /**
     * 长线回访选择（活人感二期 M2·图纸 §3.2·纯函数）：从 DB 已窗口过滤 + resolvedAt 升序的候选里取最旧一条
     * （SQL 已完成过滤 / 排序，此处只取首个）。空 → null。
     */
    fun selectRevisitLoop(candidates: List<OpenLoopEntity>): OpenLoopEntity? = candidates.firstOrNull()

    /**
     * 惦记×账本注入去重（记忆改造四期·§3.6-②·纯函数）：剔除与账本注入候选内容去空白等值的 loop（账本优先·防一事双呈现）。
     * 归一化单源 = [PromiseLedgerService.normalize]（去全部空白）。双空（无 loop 或无账本）直通返原列表（与现状逐字节一致·E10）。
     */
    fun excludeLedgerEchoes(loops: List<OpenLoopEntity>, ledgerContents: List<String>): List<OpenLoopEntity> {
        if (loops.isEmpty() || ledgerContents.isEmpty()) return loops
        val normalized = ledgerContents.mapTo(HashSet()) { PromiseLedgerService.normalize(it) }
        return loops.filterNot { PromiseLedgerService.normalize(it.content) in normalized }
    }

    /**
     * 从该角色 loops 选注入项（图纸 §3.2·语义向后兼容·输入可含 1 个 resolved 回访项）：
     * ①有到期 **open** 项（statusRaw=open && dueAt!=null && dueAt<=now）→ 全部到期项（≤2·按 dueAt 升序·回访项忽略·E4 自防）；
     * ②否则若回访项（resolved）存在 → [回访项] + （今天首轮 ? [最新 open 项] : []），共 ≤2（回访在前·活人感二期 M2）；
     * ③否则现状首轮规则逐字节不变：今天首轮（[lastAssistantTime] 为 null 或与 [now] 不同天）→ 最新 open 1 条；否则空。
     * 跨天按 [zone] 的本地日历日比较。**输入无 resolved 项时输出与现状逐字节一致**（②被跳过）。
     */
    fun selectLoopsForInjection(
        loops: List<OpenLoopEntity>,
        lastAssistantTime: Instant?,
        now: Instant,
        zone: ZoneId,
    ): List<OpenLoopEntity> {
        val nowMillis = now.toEpochMilli()
        val due = loops.filter { it.statusRaw == OpenLoopStatus.OPEN && it.dueAt != null && it.dueAt <= nowMillis }
            .sortedBy { it.dueAt }.take(INJECT_CAP)
        if (due.isNotEmpty()) return due
        val firstTurnToday = lastAssistantTime == null ||
            lastAssistantTime.atZone(zone).toLocalDate() != now.atZone(zone).toLocalDate()
        val revisit = loops.firstOrNull { it.statusRaw == OpenLoopStatus.RESOLVED }
        if (revisit != null) {
            val latestOpen = loops.filter { it.statusRaw == OpenLoopStatus.OPEN }.maxByOrNull { it.createdAt }
            return if (firstTurnToday && latestOpen != null) listOf(revisit, latestOpen) else listOf(revisit)
        }
        if (!firstTurnToday) return emptyList()
        val latest = loops.maxByOrNull { it.createdAt } ?: return emptyList()
        return listOf(latest)
    }

    // ── 注入块格式化（§4.3·段标题/指引走字符串资源） ──

    /**
     * 把选中的 loops 渲染成注入块；空则返回 ""。回访项（statusRaw=resolved·活人感二期 M2）→「回头问问进展」行；
     * 到期项（dueAt<=now）→「就是今天」行；其余→普通行。**输入无 resolved 项时输出与现状逐字节一致**。
     */
    fun formatInjectionBlock(loops: List<OpenLoopEntity>, now: Instant, strings: PromptStrings): String {
        if (loops.isEmpty()) return ""
        val nowMillis = now.toEpochMilli()
        val lines = loops.joinToString("\n") { loop ->
            when {
                loop.statusRaw == OpenLoopStatus.RESOLVED -> strings.s(R.string.pb_loop_revisit_line, loop.content)
                loop.dueAt != null && loop.dueAt <= nowMillis -> strings.s(R.string.pb_loop_due_line, loop.content)
                else -> strings.s(R.string.pb_loop_line, loop.content)
            }
        }
        return strings.s(R.string.pb_loop_head) + "\n" + lines + "\n" + strings.s(R.string.pb_loop_guide)
    }

    // ── 内部序列化 DTO（⚠️ 与 §4.2 buildScanPrompt 的 JSON schema 强耦合·改一侧同步另一侧） ──

    @Serializable
    private data class ScanDto(
        val loops: List<LoopDto> = emptyList(),
        val resolved: List<String> = emptyList(),
    )

    @Serializable
    private data class LoopDto(
        val content: String = "",
        val type: String = "",
        val due: String? = null,
    )
}

/** 扫描 JSON 整体解析失败（确定性错误·Trigger 据此记失败短冷却）。 */
class OpenLoopScanParseException(message: String) : Exception(message)
