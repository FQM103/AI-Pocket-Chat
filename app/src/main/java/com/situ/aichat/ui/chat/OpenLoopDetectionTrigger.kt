package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OpenLoopStatus
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.ResponseFormatDto
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.OpenLoopRepository
import com.situ.aichat.data.repository.PromiseRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.meeting.MeetingDetectionService
import com.situ.aichat.meeting.MeetingDisplayFormatter
import com.situ.aichat.openloop.OpenLoopScanService
import com.situ.aichat.prompt.memory.MemoryService
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.OpenLoopDueWorker
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * 「回合后台分析触发」之惦记的事扫描簇（活人感一期 P2·图纸 §3.2）。AI 回复完成后按节奏（复用
 * [MeetingDetectionService.scanTriggerDecision] 纯函数·参数同默认值 = 4/600s/12/300s）后台扫最近对话，
 * 提取「心里惦记的事」→ 落库（cap 2）+ 判定已解决置 resolved + 对未来 dueAt 排到期 worker。
 *
 * 与 [MeetingDetectionTrigger] 完全同位（VM 持有·回合完成钩子·防并发标志·扫描节奏跨进程持久化在 conversations 的
 * lastOpenLoopScan* 列）。见面中（isInOfflineMode）跳过。fire-and-forget·错误全静默（仅写失败短冷却）。
 */
internal class OpenLoopDetectionTrigger(
    private val scope: CoroutineScope,
    private val conversationUuid: String,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository,
    private val openLoopRepository: OpenLoopRepository,
    private val promiseRepository: PromiseRepository,
    private val contextLog: ContextLogService,
    private val backgroundScheduler: BackgroundScheduler,
) {
    /** 防止并发扫描（仅 Main 调度协程读写，无需同步·照 MeetingDetectionTrigger）。 */
    private var isScanning = false

    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** 扫描取最近消息条数（=图纸「30 条·排除卡片类」）。 */
    private val recentWindow = 30

    /** AI 回复完成后：判定是否到点扫描 → 过期清理 → 扫 → 落库 + 排到期 worker → 记成功/失败冷却。 */
    fun checkAndTrigger(character: CharacterEntity, config: ApiConfigValues, userName: String) {
        if (isScanning) return

        isScanning = true
        scope.launch {
            try {
                val conversation = conversationRepo.get(conversationUuid) ?: return@launch
                if (conversation.isInOfflineMode) return@launch // 见面中跳过
                val now = System.currentTimeMillis()

                val recent = messageRepo.recentVisibleChronological(conversationUuid, recentWindow)
                val sinceScan = recent.filter { it.timestamp > (conversation.lastOpenLoopScanSuccessDate ?: 0L) }
                val rounds = MemoryService.countRounds(sinceScan)

                val decision = MeetingDetectionService.scanTriggerDecision(
                    roundsSinceLastScan = rounds,
                    lastScanMillis = conversation.lastOpenLoopScanSuccessDate,
                    lastFailureMillis = conversation.lastOpenLoopScanFailureDate,
                    nowMillis = now,
                )
                if (decision !is MeetingDetectionService.ScanTriggerDecision.Trigger) return@launch

                // 过期清理（扫描前·纯函数）：无 dueAt >14 天、有 dueAt >due+48h → expired。
                val openLoops = openLoopRepository.openLoopsForCharacter(character.uuid)
                val toExpire = OpenLoopScanService.expiredLoops(openLoops, Instant.ofEpochMilli(now))
                if (toExpire.isNotEmpty()) {
                    openLoopRepository.upsertAll(toExpire.map { it.copy(statusRaw = OpenLoopStatus.EXPIRED, resolvedAt = now) })
                }
                val stillOpen = openLoops.filter { it !in toExpire }

                val convText = recent
                    .filter { !MessageKind.fromRaw(it.messageKindRaw).isStructuredCard && it.content.isNotBlank() }
                    .joinToString("\n") { m ->
                        val who = if (m.roleRaw == "user") userName.ifEmpty { "用户" } else character.name.ifEmpty { "AI" }
                        "$who：${m.content}"
                    }
                if (convText.isBlank()) return@launch

                // 源头治理（记忆改造四期·§3.6-③）：喂已进约定清单的事，让扫描别重复提取（约定清单单独管理）。
                val ledgerPromises = promiseRepository.openByCharacter(character.uuid).map { it.content }
                val prompt = OpenLoopScanService.buildScanPrompt(
                    charName = character.name,
                    userName = userName,
                    nowText = MeetingDisplayFormatter.nowText(now, zone),
                    existing = stillOpen,
                    conversationText = convText,
                    ledgerPromises = ledgerPromises,
                )
                val raw = contextLog.completion(
                    source = LogSource.OPEN_LOOP_SCAN,
                    characterName = character.name,
                    config = config,
                    messages = listOf(ChatMessageDto(role = "user", content = prompt)),
                    temperature = OpenLoopScanService.SCAN_TEMPERATURE,
                    responseFormat = ResponseFormatDto(type = "json_object"),
                )
                val result = OpenLoopScanService.parseScanResult(raw, zone) // 整体解析失败抛 → 落 catch 记失败冷却

                // 落库：新 loops（parse 已 cap 2）+ resolved 置 resolved。
                val newRows = result.newLoops.map { p ->
                    OpenLoopEntity(
                        uuid = UUID.randomUUID().toString(),
                        conversationUuid = conversationUuid,
                        characterUuid = character.uuid,
                        content = p.content,
                        typeRaw = p.typeRaw,
                        dueAt = p.dueAt,
                        statusRaw = OpenLoopStatus.OPEN,
                        createdAt = now,
                    )
                }
                val resolvedSet = result.resolvedUuids.toSet()
                val resolvedRows = stillOpen.filter { it.uuid in resolvedSet }
                    .map { it.copy(statusRaw = OpenLoopStatus.RESOLVED, resolvedAt = now) }
                if (newRows.isNotEmpty() || resolvedRows.isNotEmpty()) {
                    openLoopRepository.upsertAll(newRows + resolvedRows)
                }

                // 对 dueAt 在未来的新 loop 排到期 worker（已到期的靠对话内注入兜底，不排）。
                for (row in newRows) {
                    val due = row.dueAt ?: continue
                    if (due <= now) continue
                    backgroundScheduler.scheduleOneShot(
                        uniqueName = OpenLoopDueWorker.uniqueName(row.uuid),
                        workerClass = OpenLoopDueWorker::class.java,
                        initialDelay = Duration.ofMillis(due - now),
                        requireNetwork = true,
                        existingPolicy = ExistingWorkPolicy.KEEP,
                        inputData = OpenLoopDueWorker.inputData(row.uuid),
                    )
                }

                conversationRepo.recordOpenLoopScanResult(conversationUuid, success = true, now = now)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                conversationRepo.recordOpenLoopScanResult(conversationUuid, success = false, now = System.currentTimeMillis())
            } finally {
                isScanning = false
            }
        }
    }
}
