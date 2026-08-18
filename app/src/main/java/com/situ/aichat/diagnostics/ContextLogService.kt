package com.situ.aichat.diagnostics

import android.util.Log
import com.situ.aichat.data.local.dao.LogDao
import com.situ.aichat.data.local.entity.LogEntryEntity
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.data.remote.llm.StreamToken
import com.situ.aichat.data.remote.llm.UsageDto
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.prompt.ContextSegment
import com.situ.aichat.prompt.TokenEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * 上下文日志记录器（批 D·移植 iOS `LogService` 的写入/容量/隐私逻辑，集中一处=DRY）。
 *
 * 两种接入：
 * - [completion]：包住 `llmClient.completion` 的 1 行替换（后台生成类用）——自动计时 / 捕获真 usage / 落库 / 轮转，
 *   日志失败绝不影响调用，错误原样重抛保持调用方原有处理。
 * - [recordSuccess] / [recordError]：流式路径（chat/voice/story-stream 自行收流）完成后调；usage 经
 *   `llmClient.streamChat(onUsage=)` 捕获回传。
 *
 * 隐私铁规：detail 关（默认）→ 不存 [LogEntryEntity.fullContext]/[LogEntryEntity.responseContent]，只留元数据 +
 * 分段统计；绝不接触 API key（仅渲染消息）。容量轮转：每 [TRIM_CHECK_INTERVAL] 次写入扫一次（1:1 iOS）。
 * **自有 scope**（勿借 viewModelScope）：落库 fire-and-forget，不阻塞也不随调用方取消而丢日志。
 */
@Singleton
class ContextLogService @Inject constructor(
    private val llmClient: LlmClient,
    private val logDao: LogDao,
    private val settingsRepository: SettingsRepository,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private var pendingTrimCheck = 0

    /**
     * 带日志的 completion 包装。全量记录（2026-07-16）：不再按重任务软上限截断，落库统一走
     * [LogContextFormat.storedContext]/[LogContextFormat.storedResponse] 的极端安全帽。
     */
    suspend fun completion(
        source: String,
        characterName: String,
        config: ApiConfigValues,
        messages: List<ChatMessageDto>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        responseFormat: ResponseFormatDto? = null,
        segments: List<ContextSegment> = emptyList(),
        /** finish_reason 透传（记忆护栏 G2·可选尾参默认 null 零波及）。 */
        onFinishReason: ((String?) -> Unit)? = null,
    ): String {
        val start = System.currentTimeMillis()
        var usage: UsageDto? = null
        try {
            val text = llmClient.completion(
                messages = messages,
                config = config,
                temperature = temperature,
                maxTokens = maxTokens,
                responseFormat = responseFormat,
                onUsage = { usage = it },
                onFinishReason = onFinishReason,
            )
            recordSuccess(
                source, characterName, config.modelName, messages, text,
                System.currentTimeMillis() - start, usage, segments,
            )
            return text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            recordError(source, characterName, config.modelName, messages, errorText(e), segments)
            throw e
        }
    }

    /** 流式/外部路径成功落库（fire-and-forget）。[toolInfo]=工具遥测（仅聊天管线传·可选尾参默认 null 零波及）。 */
    fun recordSuccess(
        source: String,
        characterName: String,
        modelName: String,
        messages: List<ChatMessageDto>,
        responseText: String,
        durationMillis: Long?,
        usage: UsageDto?,
        segments: List<ContextSegment> = emptyList(),
        toolInfo: LogToolInfo? = null,
    ) {
        scope.launch {
            val detail = detailEnabled()
            val estimated = usage == null
            val promptTokens = usage?.promptTokens ?: TokenEstimator.estimate(LogContextFormat.plainText(messages))
            val completionTokens = usage?.completionTokens ?: TokenEstimator.estimate(responseText)
            insertGuarded(
                LogEntryEntity(
                    timestampMillis = System.currentTimeMillis(),
                    characterName = characterName,
                    modelName = modelName,
                    isSuccess = true,
                    source = source,
                    messageCount = messages.size,
                    durationMillis = durationMillis,
                    errorMessage = null,
                    fullContext = if (detail) LogContextFormat.storedContext(messages) else "",
                    responseContent = if (detail) LogContextFormat.storedResponse(responseText) else null,
                    contextSegmentsJson = encodeSegments(segments),
                    toolInfoJson = encodeToolInfo(toolInfo, detail),
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    reasoningTokens = usage?.completionTokensDetails?.reasoningTokens ?: 0,
                    cacheHitTokens = usage?.promptCacheHitTokens ?: 0,
                    cacheMissTokens = usage?.promptCacheMissTokens ?: 0,
                    isTokenEstimated = estimated,
                ),
            )
        }
    }

    /**
     * 带日志的「流式收全量」completion（后台生成用·思考模型友好）：走 [LlmClient.streamChat]（空闲计时、
     * 无总时长上限，思考增量持续重置计时——非流式 [completion] 的 120s 总死限对「思考模型 × 大输出」是
     * 系统性超时源），只缓冲 [StreamToken.Content]、丢弃 Reasoning/ToolCallDelta，收完整段返回。
     * 计时 / usage / 落库 / 错误契约与 [completion] 完全一致：成功 [recordSuccess]、失败 [recordError]
     * 后错误原样重抛，日志失败绝不影响调用。
     */
    suspend fun streamedCompletion(
        source: String,
        characterName: String,
        config: ApiConfigValues,
        messages: List<ChatMessageDto>,
        temperature: Double? = null,
        maxTokens: Int? = null,
        responseFormat: ResponseFormatDto? = null,
        idleTimeoutSec: Long = BACKGROUND_SSE_IDLE_TIMEOUT_SEC,
        segments: List<ContextSegment> = emptyList(),
    ): String {
        val start = System.currentTimeMillis()
        var usage: UsageDto? = null
        val buffer = StringBuilder()
        try {
            llmClient.streamChat(
                messages = messages,
                config = config,
                temperature = temperature,
                maxTokens = maxTokens,
                responseFormat = responseFormat,
                tools = null,
                idleTimeoutSec = idleTimeoutSec,
                onUsage = { usage = it },
            ).collect { token ->
                if (token is StreamToken.Content) buffer.append(token.text)
            }
            val text = buffer.toString()
            recordSuccess(
                source, characterName, config.modelName, messages, text,
                System.currentTimeMillis() - start, usage, segments,
            )
            return text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            recordError(source, characterName, config.modelName, messages, errorText(e), segments)
            throw e
        }
    }

    /** 流式/外部路径失败落库的 [Throwable] 便捷重载（统一 [errorText] 格式化，DRY chat/voice/story 收流失败）。 */
    fun recordError(
        source: String,
        characterName: String,
        modelName: String,
        messages: List<ChatMessageDto>,
        error: Throwable,
        segments: List<ContextSegment> = emptyList(),
    ) = recordError(source, characterName, modelName, messages, errorText(error), segments)

    /** 流式/外部路径失败落库（fire-and-forget）。失败条不填 token（= iOS logError），详情页据此隐藏 token 段。 */
    fun recordError(
        source: String,
        characterName: String,
        modelName: String,
        messages: List<ChatMessageDto>,
        errorMessage: String,
        segments: List<ContextSegment> = emptyList(),
    ) {
        scope.launch {
            val detail = detailEnabled()
            insertGuarded(
                LogEntryEntity(
                    timestampMillis = System.currentTimeMillis(),
                    characterName = characterName,
                    modelName = modelName,
                    isSuccess = false,
                    source = source,
                    messageCount = messages.size,
                    durationMillis = null,
                    errorMessage = errorMessage,
                    fullContext = if (detail) LogContextFormat.storedContext(messages) else "",
                    responseContent = null,
                    contextSegmentsJson = encodeSegments(segments),
                    isTokenEstimated = true,
                ),
            )
        }
    }

    /** 设置变更（保留数下调）后立即裁一次。 */
    fun enforceRetentionLimit() {
        scope.launch { writeMutex.withLock { trim() } }
    }

    /**
     * 一键去隐私完整入口（复核 R1-🟡 修正：原先只跑 SQL 步清三正文列，工具遥测里的参数预览漏清）：
     * ① [LogDao.purgeFullText] 清 fullContext/responseContent/contextSegmentsJson；
     * ② 对仍带 toolInfoJson 的行按**写侧同一口径**重消毒（[LogToolInfo.sanitized] detail=false：
     *    剥 argsPreview·名与计数=元数据恒存），编码无变化的行跳过零写。
     * 两步非事务：中断最多残留部分预览，操作幂等、重按即补净。损坏 JSON 解不出 → 原样跳过（与读侧
     * decode 容错同口径，不因去隐私把行清成不可判形态）。
     * @return SQL 步受影响行数（与旧 [LogDao.purgeFullText] 返回口径一致）。
     */
    suspend fun purgeSensitiveText(): Int {
        val purged = logDao.purgeFullText()
        for (row in logDao.toolInfoRows()) {
            val info = LogToolInfo.decode(json, row.toolInfoJson) ?: continue
            val cleaned = info.sanitized(detailEnabled = false).encode(json)
            if (cleaned != row.toolInfoJson && cleaned.isNotEmpty()) logDao.updateToolInfo(row.id, cleaned)
        }
        return purged
    }

    /**
     * 启动期失败率审计（移植 iOS `LogService.auditRecentFailureRates`）：扫近 24h 各 source 失败率，
     * 失败率 ≥50% 且失败 ≥3 次的 source 打 warning（排查时 grep「失败率告警」定位抖动子系统）。
     * 算法见纯函数 [FailureRateAudit.computeFailureRateAlerts]（已单测）。日志表空 / 出错时静默早退。
     */
    fun auditRecentFailureRates() {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val records = logDao.recordsSince(now - FailureRateAudit.WINDOW_MILLIS)
                if (records.isEmpty()) return@launch
                for (alert in FailureRateAudit.computeFailureRateAlerts(records, now)) {
                    Log.w(TAG, "失败率告警 [${alert.source}]：24h 内 ${alert.failures}/${alert.total} 失败（${alert.percent}%）")
                }
            } catch (e: Exception) {
                Log.e(TAG, "失败率审计失败: ${e.message}")
            }
        }
    }

    // MARK: - 私有

    private suspend fun insertGuarded(entry: LogEntryEntity) {
        writeMutex.withLock {
            try {
                logDao.insert(entry)
                // 观测点（verify-process #3·spec §5）：真机/模拟器验时 grep「ContextLog 落库」即可确认各路 LLM 调用真落库。
                Log.d(TAG, "落库 [${entry.source}] ${if (entry.isSuccess) "成功" else "失败"}·${entry.messageCount} 条消息")
                pendingTrimCheck += 1
                if (pendingTrimCheck >= TRIM_CHECK_INTERVAL) {
                    pendingTrimCheck = 0
                    trim()
                }
            } catch (e: Exception) {
                Log.e(TAG, "日志写入失败: ${e.message}")
            }
        }
    }

    /** 容量轮转（已持锁调用）：保留最新 N 条，批量删分界之前（1:1 iOS trimOldLogs）。 */
    private suspend fun trim() {
        try {
            val retention = settingsRepository.appSettings.first().sanitizedLogRetentionCount
            val overflow = LogRetention.overflow(logDao.count(), retention)
            if (overflow <= 0) return
            val cutoff = logDao.oldestTimestampAtOffset(LogRetention.cutoffOffset(overflow)) ?: return
            logDao.deleteOlderThanInclusive(cutoff)
            // 观测点：确认容量轮转真触发（每 TRIM_CHECK_INTERVAL 次写入扫一次）。
            Log.d(TAG, "容量轮转：删旧 $overflow 条（保留上限 $retention）")
        } catch (e: Exception) {
            Log.e(TAG, "日志轮转失败: ${e.message}")
        }
    }

    private suspend fun detailEnabled(): Boolean =
        runCatching { settingsRepository.appSettings.first().logDetailEnabled }.getOrDefault(false)

    private fun encodeSegments(segments: List<ContextSegment>): String {
        if (segments.isEmpty()) return ""
        return runCatching {
            json.encodeToString(ListSerializer(ContextSegment.serializer()), segments)
        }.getOrDefault("")
    }

    /** 工具遥测编码：detail 关剥参数预览（[LogToolInfo.sanitized]·名与计数=元数据恒存）；null → 空串。 */
    private fun encodeToolInfo(toolInfo: LogToolInfo?, detailEnabled: Boolean): String =
        toolInfo?.sanitized(detailEnabled)?.encode(json) ?: ""

    private fun errorText(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    companion object {
        private const val TAG = "ContextLog"
        /** 每累计这么多次写入才扫一次轮转，避免每写都扫全表（1:1 iOS trimCheckInterval）。 */
        const val TRIM_CHECK_INTERVAL = 10

        /**
         * 后台流式收全量的空闲计时上限（秒）：后台无人等待，统一放宽到聊天思考档
         * （[LlmClient.THINKING_SSE_IDLE_TIMEOUT_SEC] = 120s）的两倍，覆盖个别 provider 思考期
         * 完全静默不吐增量的最坏情况；网络真死时仍能数分钟内失败、进调用方既有重试阶梯。
         */
        const val BACKGROUND_SSE_IDLE_TIMEOUT_SEC = 240L
    }
}
