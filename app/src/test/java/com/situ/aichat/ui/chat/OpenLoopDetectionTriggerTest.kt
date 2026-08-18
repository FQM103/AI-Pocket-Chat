package com.situ.aichat.ui.chat

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OpenLoopEntity
import com.situ.aichat.data.local.entity.OpenLoopStatus
import com.situ.aichat.data.local.entity.OpenLoopType
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.data.repository.OpenLoopRepository
import com.situ.aichat.data.repository.PromiseRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.work.BackgroundScheduler
import com.situ.aichat.work.OpenLoopDueWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

/**
 * 活人感一期 P2 · T2-2（E3）：[OpenLoopDetectionTrigger] 接线 + 守卫（照 [MeetingDetectionTriggerTest] 式样）。
 * 节奏判定/解析/选择/清理纯逻辑由 [com.situ.aichat.openloop.OpenLoopScanServiceTest] 覆盖；本测专验接线：
 * 节奏触发 → 落库 + 排到期 worker → 记成功；失败/解析失败 → 记失败冷却；见面/轮数不足 → 跳过。
 * Unconfined 让 fire-and-forget launch 内联跑完。
 */
class OpenLoopDetectionTriggerTest {

    private val character = CharacterEntity(uuid = "c1", name = "团子", creationDate = 0L)

    private fun convo(offline: Boolean = false, lastScan: Long? = null) =
        ConversationEntity(uuid = "conv1", title = "", characterUuid = "c1", creationDate = 0L, isInOfflineMode = offline, lastOpenLoopScanSuccessDate = lastScan)

    /** 4 轮（countRounds=user 消息数）的最近对话。 */
    private fun fourRounds(): List<MessageEntity> = (1..8).map { i ->
        MessageEntity(
            messageUUID = "m$i", conversationUuid = "conv1",
            roleRaw = if (i % 2 == 1) "user" else "assistant",
            content = if (i % 2 == 1) "我下周三面试$i" else "加油$i",
            timestamp = i.toLong(), messageKindRaw = "plain_text",
        )
    }

    private fun trigger(
        conv: ConversationRepository,
        msg: MessageRepository,
        repo: OpenLoopRepository,
        log: ContextLogService,
        scheduler: BackgroundScheduler = mockk(relaxed = true),
        // 记忆改造四期·§3.6-③ 接线（图纸 E15 侦察漏点）：relaxed 桩 openByCharacter 默认返 emptyList → ledgerPromises 空·行为零改。
        promiseRepo: PromiseRepository = mockk(relaxed = true),
    ) = OpenLoopDetectionTrigger(CoroutineScope(Dispatchers.Unconfined), "conv1", conv, msg, repo, promiseRepo, log, scheduler)

    private fun baseMocks(): Quad {
        val conv = mockk<ConversationRepository>(relaxed = true)
        val msg = mockk<MessageRepository>(relaxed = true)
        val repo = mockk<OpenLoopRepository>(relaxed = true)
        val log = mockk<ContextLogService>()
        coEvery { conv.get("conv1") } returns convo()
        coEvery { msg.recentVisibleChronological("conv1", any()) } returns fourRounds()
        coEvery { repo.openLoopsForCharacter("c1") } returns emptyList()
        return Quad(conv, msg, repo, log)
    }

    private data class Quad(
        val conv: ConversationRepository,
        val msg: MessageRepository,
        val repo: OpenLoopRepository,
        val log: ContextLogService,
    )

    private fun stub(log: ContextLogService, reply: String) =
        coEvery { log.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns reply

    // ── 节奏触发 → 落库 + 排到期 worker + 记成功 ──

    @Test fun scan_triggers_persistsAndSchedulesDueWorker() {
        val (conv, msg, repo, log) = baseMocks()
        val scheduler = mockk<BackgroundScheduler>(relaxed = true)
        stub(log, """{"loops":[{"content":"面试结果","type":"user_event","due":"2099-06-01T09:00"}],"resolved":[]}""")

        trigger(conv, msg, repo, log, scheduler).checkAndTrigger(character, mockk(relaxed = true), "用户")

        coVerify { repo.upsertAll(match { rows -> rows.any { it.content == "面试结果" && it.statusRaw == OpenLoopStatus.OPEN } }) }
        verify(exactly = 1) { scheduler.scheduleOneShot(any(), OpenLoopDueWorker::class.java, any(), any(), any(), any()) }
        coVerify { conv.recordOpenLoopScanResult("conv1", true, any()) }
    }

    @Test fun scan_noDue_persistsButNoWorker() {
        val (conv, msg, repo, log) = baseMocks()
        val scheduler = mockk<BackgroundScheduler>(relaxed = true)
        stub(log, """{"loops":[{"content":"在纠结换工作","type":"open_topic","due":null}],"resolved":[]}""")

        trigger(conv, msg, repo, log, scheduler).checkAndTrigger(character, mockk(relaxed = true), "用户")

        coVerify { repo.upsertAll(match { rows -> rows.any { it.content == "在纠结换工作" && it.dueAt == null } }) }
        verify(exactly = 0) { scheduler.scheduleOneShot(any(), OpenLoopDueWorker::class.java, any(), any(), any(), any()) }
        coVerify { conv.recordOpenLoopScanResult("conv1", true, any()) }
    }

    // ── 过期清理 + resolved 流转 ──

    @Test fun scan_expiresStaleLoops_beforeScan() {
        val (conv, msg, _, log) = baseMocks()
        val repo = mockk<OpenLoopRepository>(relaxed = true)
        val stale = OpenLoopEntity(
            uuid = "old", conversationUuid = "conv1", characterUuid = "c1", content = "很久以前的事",
            typeRaw = OpenLoopType.OPEN_TOPIC, dueAt = null,
            createdAt = System.currentTimeMillis() - 15L * 24 * 60 * 60 * 1000, // 15 天前 → >14d 过期
        )
        coEvery { repo.openLoopsForCharacter("c1") } returns listOf(stale)
        stub(log, """{"loops":[],"resolved":[]}""")

        trigger(conv, msg, repo, log).checkAndTrigger(character, mockk(relaxed = true), "用户")

        coVerify { repo.upsertAll(match { rows -> rows.any { it.uuid == "old" && it.statusRaw == OpenLoopStatus.EXPIRED } }) }
    }

    @Test fun scan_resolvesExistingLoop_whenLlmSaysSo() {
        val (conv, msg, _, log) = baseMocks()
        val repo = mockk<OpenLoopRepository>(relaxed = true)
        val existing = OpenLoopEntity(
            uuid = "e1", conversationUuid = "conv1", characterUuid = "c1", content = "答应改简历",
            typeRaw = OpenLoopType.PROMISE_CHAR, createdAt = System.currentTimeMillis(), // 新鲜·不被过期清理
        )
        coEvery { repo.openLoopsForCharacter("c1") } returns listOf(existing)
        stub(log, """{"loops":[],"resolved":["e1"]}""")

        trigger(conv, msg, repo, log).checkAndTrigger(character, mockk(relaxed = true), "用户")

        coVerify { repo.upsertAll(match { rows -> rows.any { it.uuid == "e1" && it.statusRaw == OpenLoopStatus.RESOLVED } }) }
    }

    // ── 守卫：见面 / 轮数不足 ──

    @Test fun scan_offlineMode_skips() {
        val (conv, msg, repo, log) = baseMocks()
        coEvery { conv.get("conv1") } returns convo(offline = true)
        trigger(conv, msg, repo, log).checkAndTrigger(character, mockk(relaxed = true), "用户")
        coVerify(exactly = 0) { log.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { conv.recordOpenLoopScanResult(any(), any(), any()) }
    }

    @Test fun scan_belowMinRounds_skips() {
        val (conv, msg, repo, log) = baseMocks()
        coEvery { msg.recentVisibleChronological("conv1", any()) } returns fourRounds().take(3) // 2 user 轮 < 4
        trigger(conv, msg, repo, log).checkAndTrigger(character, mockk(relaxed = true), "用户")
        coVerify(exactly = 0) { log.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── 失败冷却：LLM 抛 / 解析失败 ──

    @Test fun scan_completionThrows_recordsFailure() {
        val (conv, msg, repo, log) = baseMocks()
        coEvery { log.completion(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws RuntimeException("network")
        trigger(conv, msg, repo, log).checkAndTrigger(character, mockk(relaxed = true), "用户")
        coVerify(exactly = 0) { repo.upsertAll(any()) }
        coVerify { conv.recordOpenLoopScanResult("conv1", false, any()) }
    }

    @Test fun scan_parseGarbage_recordsFailure() {
        val (conv, msg, repo, log) = baseMocks()
        stub(log, "这不是 JSON，只是一句闲聊")
        trigger(conv, msg, repo, log).checkAndTrigger(character, mockk(relaxed = true), "用户")
        coVerify { conv.recordOpenLoopScanResult("conv1", false, any()) }
    }
}
