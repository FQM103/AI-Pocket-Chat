package com.situ.aichat.offline

import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.prompt.memory.MemoryService

/**
 * 线下「节拍状态」生成服务（1:1 iOS `SceneProgressService`）。基于本次见面的消息，单独调一次 LLM（temp 0.3）
 * 产出固定格式的 Markdown 节拍状态，直接写回 `ConversationEntity.currentSceneProgress`，由 PromptBuilder 注入
 * 【节拍状态】块。无状态 [object]，[generateProgress] 注入 [ContextLogService] 调 LLM 兼落日志。
 *
 * 触发节流（≥15 条用户消息差 + 3min 防抖）与落库编排在协调器（10.2c-3，iOS SceneProgressCoordinator/
 * ChatViewModel+SceneProgress）。**[processTensionRenewal] 张力自愈**（纯函数）从 iOS 协调器合并到此处与生成
 * 同住——它只对生成结果做规整，是节拍状态产出的一部分；协调器只负责调用 + 落库。
 *
 * 批 D 上下文日志：[generateProgress] 经 [ContextLogService] 调 LLM（source=[LogSource.SCENE_PROGRESS]），
 * 自动落一条记录；调用方注入 contextLog（替原 llmClient）。
 */
object SceneProgressService {

    /** 单独后台任务用的 user 触发语（1:1 iOS）。 */
    private const val TRIGGER_USER_MESSAGE = "请输出当前的节拍状态。"

    /** 触发阈值：自上次触发已累积的用户消息差 ≥ 此值才更新（1:1 iOS threshold=15，用差值非 %15 跨重启不漏）。 */
    const val TURN_THRESHOLD = 15

    /** 防抖：距上次更新不足此值（毫秒）则跳过（1:1 iOS 180s）。 */
    const val DEBOUNCE_MS = 180_000L

    /**
     * 节拍状态更新触发判定（纯函数，1:1 iOS `checkAndTriggerSceneProgressUpdate` 双条件）。
     * 条件 1：用户消息差 (offlineUserTurnCount − lastTriggerCount) ≥ [TURN_THRESHOLD]；
     * 条件 2：距上次更新 ≥ [DEBOUNCE_MS]（lastUpdateAt=null 即从未更新时不防抖）。
     * 「是否在线下模式 / 是否正在更新 / 是否有 config」等门控由调用方（10.2c-3c）另判。
     */
    fun shouldTriggerUpdate(
        offlineUserTurnCount: Int,
        lastTriggerCount: Int,
        lastUpdateAt: Long?,
        now: Long,
    ): Boolean {
        if (offlineUserTurnCount - lastTriggerCount < TURN_THRESHOLD) return false
        if (lastUpdateAt != null && now - lastUpdateAt < DEBOUNCE_MS) return false
        return true
    }

    /**
     * 生成节拍状态 Markdown（1:1 iOS `generateProgress`）。最多取最近 60 条喂 LLM，temp 0.3。
     * @throws IllegalArgumentException 无可分析消息（= iOS SceneProgressError.noMessages；协调器已预过滤，此为兜底）。
     */
    suspend fun generateProgress(
        messages: List<MessageEntity>,
        characterName: String,
        userName: String,
        locationHint: String,
        tensionSeed: String?,
        config: ApiConfigValues,
        contextLog: ContextLogService,
    ): String {
        require(messages.isNotEmpty()) { "没有可分析的线下见面消息" }

        val recent = messages.takeLast(60)
        val chatLog = MemoryService.formatMessages(recent)
        val systemPrompt = buildSystemPrompt(chatLog, characterName, userName, locationHint, tensionSeed)

        // 非流式 completion 不剥内联 <think>（只有流式经 ThinkTagParser）——节拍状态会落库并逐轮注入
        // 线下叙事 system prompt，必须在此剥净（含 trim），否则思考文本随每轮反复回喂。
        return MemoryService.strippingThinkingTags(
            contextLog.completion(
                source = LogSource.SCENE_PROGRESS,
                characterName = characterName,
                config = config,
                messages = listOf(
                    ChatMessageDto(role = "system", content = systemPrompt),
                    ChatMessageDto(role = "user", content = TRIGGER_USER_MESSAGE),
                ),
                temperature = 0.3,
            ),
        )
    }

    /**
     * 构建节拍状态生成的 system prompt（1:1 iOS 硬编码中文模板）。字段标签用半角冒号、章节标签用全角冒号；
     * iOS `\` 续行处合并为单行（Markdown 引言 / 规则 1、4、5）。seed 非空时追加心事种子行 + allow_end 收紧规则。
     */
    internal fun buildSystemPrompt(
        chatLog: String,
        characterName: String,
        userName: String,
        locationHint: String,
        tensionSeed: String?,
    ): String {
        val hasSeed = !tensionSeed.isNullOrEmpty()
        val seedRuleTail = if (hasSeed) "隐藏心事如果还没浮出水面，一律 false。" else ""
        val seedLine = if (hasSeed) "隐藏心事种子：$tensionSeed\n" else ""
        val resolvedUserName = userName.ifEmpty { "用户" }

        // flush-left """ 避免 trimIndent 与多行插值（chatLog/seedLine）换行冲突（= OfflineNarrativePreset.buildPrompt）。
        return """你在维护一段线下见面的"节拍状态"。读完下面的对话后，按固定格式输出 Markdown，不要解释，不要加前言，不要用代码块。格式如下（每行一个字段）：

allow_end: true|false
地点: <最新地点，没变就填初始地点>
时间: <见面进行的大致时间，如"傍晚 18:30 前后"，不要写现实日期>
已发生的关键节点:
- <倒序第 1 个>
- <倒序第 2 个>
- <倒序第 3 个>
当前情绪基调: <一句话>
未解决的张力: <一句话，若无则填"无">
可以发生的事: <基于当前场景的 1-2 个自然走向，不超过一句话，若无灵感填"无">
建议新张力: <如果"未解决的张力"是"无"，从对话中自然生长一条新的微妙张力；否则填"无">

规则：
1. allow_end 只能是 true 或 false。只有当见面已进行至少 3 轮且场景已经进入可自然收束的阶段（说到告别、天晚了、准备离开）时才允许 true。$seedRuleTail
2. 已发生的关键节点倒序最多 5 条，每条不超过 30 字。
3. 整段输出不超过 500 字。
4. 如果"未解决的张力"填了"无"，必须在"建议新张力"给出从对话中自然长出来的新张力——角色某句话背后没说出口的意思、无意提到的某个话题值得深入、场景里某个细节可以引出新的情绪线。不要凭空编造不相关的事件。
5. "可以发生的事"应指向打开新的可能性，而非收束当前情节——"路过一个有意义的地方"而非"把心事说清楚"。

${seedLine}初始地点提示：$locationHint

角色名：$characterName
用户名：$resolvedUserName

## 线下对话记录
$chatLog"""
    }

    // MARK: - 张力自愈（1:1 iOS SceneProgressCoordinator.processTensionRenewal，纯函数）

    /**
     * 当「未解决的张力」为「无」时，用 LLM 建议的新张力替换，然后**始终移除「建议新张力」行**（它是给协调器
     * 用的工作字段，不应注入叙事 prompt）。保留原行的「字段:」前缀格式，只替换值。
     */
    fun processTensionRenewal(beatState: String): String {
        val lines = beatState.split("\n").toMutableList()

        var tensionLineIndex: Int? = null
        var tensionValue = ""
        var suggestedLineIndex: Int? = null
        var suggestedValue = ""

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            extractFieldValue(trimmed, "未解决的张力")?.let { tensionLineIndex = index; tensionValue = it }
            extractFieldValue(trimmed, "建议新张力")?.let { suggestedLineIndex = index; suggestedValue = it }
        }

        val tensionIsEmpty = tensionValue.isEmpty() || tensionValue == "无"
        val hasSuggestion = suggestedValue.isNotEmpty() && suggestedValue != "无"

        // 旧张力已解决且有新建议 → 替换（保留「未解决的张力:」前缀，含原行前导空格）
        val tIdx = tensionLineIndex
        if (tensionIsEmpty && hasSuggestion && tIdx != null) {
            val colonIdx = lines[tIdx].indexOf(':').let { if (it >= 0) it else lines[tIdx].indexOf('：') }
            if (colonIdx >= 0) {
                lines[tIdx] = lines[tIdx].substring(0, colonIdx + 1) + " " + suggestedValue
            }
        }

        // 始终移除「建议新张力」行（工作字段，不注入叙事 prompt）
        suggestedLineIndex?.let { lines.removeAt(it) }

        return lines.joinToString("\n")
    }

    /** 从一行提取「字段[:：]值」的值（兼容全/半角冒号 + 前后空格，1:1 iOS extractFieldValue）。 */
    private fun extractFieldValue(line: String, field: String): String? {
        if (!line.startsWith(field)) return null
        val afterField = line.drop(field.length).trim()
        val first = afterField.firstOrNull() ?: return null
        if (first != ':' && first != '：') return null
        return afterField.drop(1).trim()
    }

    /**
     * R5#0：把节拍状态里某字段**强制重写为规范 `字段: 值`**（单源字段写入器，消除写改端与解析端的容错不对称）。
     * 用与 [extractFieldValue] 同一套容错逻辑定位字段行（兼容全/半角冒号 + 任意空格 + 值大小写），找到即整行重写为
     * 规范半角冒号格式（保留原行前导缩进）；找不到该字段行则原样返回（不无中生有）。
     *
     * 治 `continueOfflineMeeting` 旧用字面 `contains/replace("allow_end: true")`——LLM 漏空格/用全角冒号/写
     * `True` 时静默改不动，导致「再待一会儿」不收紧、AI 可立刻再次提议告别。
     */
    fun forceFieldValue(beatState: String, field: String, value: String): String {
        val lines = beatState.split("\n").toMutableList()
        for ((index, line) in lines.withIndex()) {
            if (extractFieldValue(line.trim(), field) == null) continue
            val leading = line.takeWhile { it == ' ' || it == '\t' }
            lines[index] = "$leading$field: $value"
            return lines.joinToString("\n")
        }
        return beatState
    }
}
