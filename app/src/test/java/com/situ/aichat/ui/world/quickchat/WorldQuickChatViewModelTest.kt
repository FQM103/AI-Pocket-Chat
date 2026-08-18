package com.situ.aichat.ui.world.quickchat

import com.situ.aichat.data.local.dao.ScheduleDao
import com.situ.aichat.data.local.dao.WorldDao
import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.MessageKind
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.MessageRepository
import com.situ.aichat.offline.OfflineMarkerEndPayload
import com.situ.aichat.offline.OfflineMarkerStartPayload
import com.situ.aichat.quickreply.ListQuickReplyService
import com.situ.aichat.world.cast.MeetConfirm
import com.situ.aichat.world.cast.WorldFirstMeetService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * [WorldQuickChatViewModel] T2-6（Robolectric·MockK·图纸 §7·E1/E2/E3/E16/E17 + R1 🔴-1/🔴-2/🔵-1）：open→getOrCreate+markRead /
 * send→sendAndAwait 恰一次 / 失败→failed·retry→retryReply 且 sendAndAwait 仍恰一次 / **忙碌→用户消息即时落库 + 延迟门满才起
 * retryReply + 在途去重** / 断网异常→failed 不崩 / confirmMeet 异常→no-op / respond 返 null→meetcard 显示 / 开着新回复→再 markRead。
 * 无 coroutines-test 依赖 → 真 IO + 轮询（同 WorldViewModelTest）；忙碌延迟经可控 [busyGate] 精确验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorldQuickChatViewModelTest {

    private lateinit var quickReply: ListQuickReplyService
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var messageRepo: MessageRepository
    private lateinit var scheduleDao: ScheduleDao
    private lateinit var worldDao: WorldDao
    private lateinit var firstMeetService: WorldFirstMeetService
    private lateinit var vm: WorldQuickChatViewModel
    private val msgFlow = MutableStateFlow<List<MessageEntity>>(emptyList())

    @Before
    fun setUp() {
        quickReply = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        messageRepo = mockk()
        scheduleDao = mockk(relaxed = true)
        worldDao = mockk(relaxed = true)
        firstMeetService = mockk(relaxed = true)
        coEvery { conversationRepo.getOrCreateForCharacter(any(), any()) } returns CONV
        // failedAfter 读末条角色（R1 🔴-1）：默认末条 = user（快聊发送后的常态·令 !ok 时 failed 置位）。
        coEvery { conversationRepo.get(CONV) } returns mockk<ConversationEntity> { every { lastMessageRole } returns "user" }
        every { messageRepo.observeVisibleWindowed(CONV, 30) } returns msgFlow
        coEvery { scheduleDao.scheduleFor(any(), any()) } returns null // 默认不忙
        coEvery { worldDao.getState() } returns null
        vm = WorldQuickChatViewModel(quickReply, conversationRepo, messageRepo, scheduleDao, worldDao, firstMeetService)
    }

    private fun await(msg: String, cond: () -> Boolean) {
        repeat(400) { if (cond()) return; Thread.sleep(5) }
        fail("超时: $msg")
    }

    /** 轮询版 coVerify：满足返 true、未满足吞异常返 false（供 [await] 等 MockK 调用计数达标）。 */
    private fun verified(block: () -> Unit): Boolean = try { block(); true } catch (e: Throwable) { false }

    /** 令角色当刻忙碌（当前事件 isPhoneAvailable=false）。 */
    private fun makeBusy() {
        coEvery { scheduleDao.scheduleFor(any(), any()) } returns mockk<CharacterDailyScheduleEntity> { every { uuid } returns "sch" }
        coEvery { scheduleDao.eventsForSchedule("sch") } returns
            listOf(ScheduleEventEntity(uuid = "e", scheduleUuid = "sch", startTime = 0, endTime = Long.MAX_VALUE, isPhoneAvailable = false))
    }

    private fun openAndWait(status: String = "在店里") {
        vm.openKnown("char-1", "苏晚", status)
        await("target set") { vm.ui.value.target != null }
    }

    @Test
    fun `open_getOrCreate加markRead加会话uuid`() {
        openAndWait()
        coVerify(exactly = 1) { conversationRepo.getOrCreateForCharacter("char-1", "苏晚") }
        coVerify(atLeast = 1) { conversationRepo.markRead(CONV) }
        assertEquals(CONV, (vm.ui.value.target as QuickChatTarget.Known).conversationUuid)
    }

    @Test
    fun `send_sendAndAwait恰一次_成功清failed`() {
        coEvery { quickReply.sendAndAwait(CONV, "你好") } returns true
        openAndWait()
        vm.send("你好")
        await("回合结束") { !vm.ui.value.typing }
        coVerify(exactly = 1) { quickReply.sendAndAwait(CONV, "你好") }
        assertFalse(vm.ui.value.failed)
    }

    @Test
    fun `失败置failed_retry走retryReply_sendAndAwait仍恰一次`() {
        coEvery { quickReply.sendAndAwait(CONV, "你好") } returns false
        coEvery { quickReply.retryReply(CONV) } returns true
        openAndWait()
        vm.send("你好")
        await("失败态") { vm.ui.value.failed }
        coVerify(exactly = 1) { quickReply.sendAndAwait(CONV, "你好") }
        vm.retry()
        await("重试成功") { !vm.ui.value.failed && !vm.ui.value.typing }
        coVerify(exactly = 1) { quickReply.retryReply(CONV) }
        coVerify(exactly = 1) { quickReply.sendAndAwait(any(), any()) } // 重试绝不重发用户消息
    }

    @Test
    fun `busy_用户消息即时落库_不被延迟门挡`() {
        // R1 🔴-1 ①：忙碌时 insertUserMessage 即时调用（不被 5s 门延迟）→ 气泡即时回显·退屏不丢。
        makeBusy()
        openAndWait()
        await("忙碌态") { vm.ui.value.busy }
        val gate = CompletableDeferred<Unit>()
        vm.busyGate = { gate.await() }
        vm.send("你好")
        await("insert 已即时落") { verified { coVerify(exactly = 1) { quickReply.insertUserMessage(CONV, "你好") } } }
        Thread.sleep(60)
        coVerify(exactly = 0) { quickReply.retryReply(any()) } // 延迟门未满 → 回合未起
        coVerify(exactly = 0) { quickReply.sendAndAwait(any(), any()) } // 忙碌路径绝不走 sendAndAwait
        gate.complete(Unit)
    }

    @Test
    fun `busy_延迟门满才起retryReply恰一次`() {
        // R1 🔴-1 ②：门满 → retryReply 恰一次（只补 TA 的回复慢半拍·成功清 failed）。
        makeBusy()
        coEvery { quickReply.retryReply(CONV) } returns true
        openAndWait()
        await("忙碌态") { vm.ui.value.busy }
        val gate = CompletableDeferred<Unit>()
        vm.busyGate = { gate.await() }
        vm.send("你好")
        await("insert 已即时落") { verified { coVerify(exactly = 1) { quickReply.insertUserMessage(CONV, "你好") } } }
        Thread.sleep(60)
        coVerify(exactly = 0) { quickReply.retryReply(any()) } // 门未满 → 回合未起
        gate.complete(Unit)
        // 门开后等回合真跑完（retryReply 已起 + typing 复位）——不能只等 !typing：回合起前 typing 本就是 false，
        // 满负载下会瞬间误判「已结束」（既有 flaky 根因）。
        await("回合结束") { verified { coVerify(exactly = 1) { quickReply.retryReply(CONV) } } && !vm.ui.value.typing && !vm.ui.value.failed }
        coVerify(exactly = 1) { quickReply.retryReply(CONV) }
        coVerify(exactly = 0) { quickReply.sendAndAwait(any(), any()) }
    }

    @Test
    fun `busy双发_insertUserMessage两次_回合仍恰一次`() {
        // R1 🔴-1 ③：在途去重——回合在飞时第二发只落消息、不起第二回合。
        makeBusy()
        coEvery { quickReply.retryReply(CONV) } returns true
        openAndWait()
        await("忙碌态") { vm.ui.value.busy }
        val gate = CompletableDeferred<Unit>()
        vm.busyGate = { gate.await() }
        vm.send("第一句")
        vm.send("第二句")
        await("两条 insert 均落") { verified { coVerify(exactly = 2) { quickReply.insertUserMessage(CONV, any()) } } }
        gate.complete(Unit)
        // 等回合真跑完（retryReply 已起 + typing 复位）——见上：只等 !typing 会在回合起前瞬间误判结束（既有 flaky 根因）。
        await("回合结束") { verified { coVerify(exactly = 1) { quickReply.retryReply(CONV) } } && !vm.ui.value.typing }
        coVerify(exactly = 1) { quickReply.retryReply(CONV) } // 在途去重 → 回合恰一次
        coVerify(exactly = 1) { quickReply.insertUserMessage(CONV, "第一句") }
        coVerify(exactly = 1) { quickReply.insertUserMessage(CONV, "第二句") }
    }

    @Test
    fun `sendAndAwait抛异常_failed置位不崩`() {
        // R1 🔴-2：断网异常穿透会崩溃 → VM runCatching 兜住转失败态（进程存活 + failed 即证）。
        coEvery { quickReply.sendAndAwait(CONV, "你好") } throws IOException("断网")
        openAndWait() // 非忙碌
        vm.send("你好")
        await("失败态·不崩") { vm.ui.value.failed && !vm.ui.value.typing }
    }

    @Test
    fun `confirmMeet抛异常_no-op_met不置位不崩`() {
        // R1 🔴-2：确认异常 → getOrNull=null → met 不置位·仍 Meet 态·不崩（用户可重按）。
        coEvery { firstMeetService.startMeet(any(), any(), any(), any()) } returns "你好呀"
        coEvery { firstMeetService.confirmMeet(any(), any(), any()) } throws IOException("断网")
        vm.openMeet("nat-1", "林墨", "老槐树下")
        await("开场呈现") { vm.ui.value.firstMeet?.opening == false }
        vm.confirmMeet()
        Thread.sleep(80)
        assertFalse("met 不置位", vm.ui.value.firstMeet?.met == true)
        assertTrue("仍是 Meet 态", vm.ui.value.target is QuickChatTarget.Meet)
    }

    @Test
    fun `confirmMeet成功_转Known态_状态行用初遇地点`() {
        // 🔵-R2-2：确认认识 → Known 态 statusLine = 初遇地点（placeName）·非空字符串 → 头部状态行有文字（不再只剩金点）；
        // 与 Meet 态 QcHead 状态行同源（Meet 显示 placeName，Known 亦然·无缝过渡）。
        coEvery { firstMeetService.startMeet(any(), any(), any(), any()) } returns "你好呀"
        coEvery { firstMeetService.confirmMeet(any(), any(), any()) } returns MeetConfirm("char-9", CONV)
        vm.openMeet("nat-1", "林墨", "老槐树下")
        await("开场呈现") { vm.ui.value.firstMeet?.opening == false }
        vm.confirmMeet()
        await("转 Known 态") { vm.ui.value.target is QuickChatTarget.Known }
        val known = vm.ui.value.target as QuickChatTarget.Known
        assertEquals("状态行 = 初遇地点", "老槐树下", known.statusLine)
        assertEquals("char-9", known.characterUuid)
        assertTrue("met 置位", vm.ui.value.firstMeet?.met == true)
    }

    @Test
    fun `E5_respond返null_meetcard显示_招募不被阻塞`() {
        // R1 🔵-1：respond 返 null（LLM 失败/达上限）→ meetcardVisible=true（VM 半边·招募绝不被阻塞）。
        coEvery { firstMeetService.startMeet(any(), any(), any(), any()) } returns "开场白"
        coEvery { firstMeetService.respond(any(), any()) } returns null
        vm.openMeet("nat-1", "林墨", "老槐树下")
        await("开场呈现") { vm.ui.value.firstMeet?.opening == false }
        vm.send("你好")
        await("meetcard 显示") { vm.ui.value.firstMeet?.meetcardVisible == true }
        assertEquals("reply null 不加计数", 0, vm.ui.value.firstMeet?.replyCount)
    }

    @Test
    fun `离场标记_转分隔条人话_绝不露LLM指令原文`() {
        // 泄漏修复：offline_marker_end 的 content 是发给 LLM 的指令文本（「不要再使用 [叙述]…」），穿过可见流 SQL 白名单。
        // 面板映射必须经 MessagePreviewText 转成「线下见面结束 · 时长」+ 带 kind（渲染层据此走 pill 非气泡）。
        openAndWait()
        val content = OfflineMarkerEndPayload(durationText = "约5分钟", timeString = "02:48", reasonText = "用户主动结束了这次见面").makeContent()
        msgFlow.value = listOf(MessageEntity(
            messageUUID = "m-end", conversationUuid = CONV, roleRaw = "assistant", content = content,
            timestamp = System.currentTimeMillis(), isOfflineMode = true, messageKindRaw = MessageKind.OFFLINE_MARKER_END.raw,
        ))
        await("标记呈现") { vm.ui.value.messages.isNotEmpty() }
        val m = vm.ui.value.messages.single()
        assertEquals(MessageKind.OFFLINE_MARKER_END, m.kind)
        assertEquals("线下见面结束 · 约5分钟", m.text)
        assertFalse("绝不露 LLM 指令", m.text.contains("不要再使用"))
    }

    @Test
    fun `红包卡_转人话_绝不露JSON原文`() {
        openAndWait()
        msgFlow.value = listOf(MessageEntity(
            messageUUID = "m-rp", conversationUuid = CONV, roleRaw = "assistant",
            content = """{"amount":520,"blessing":"拿去买奶茶"}""",
            timestamp = System.currentTimeMillis(), messageKindRaw = MessageKind.RED_PACKET.raw,
        ))
        await("红包呈现") { vm.ui.value.messages.isNotEmpty() }
        assertEquals("🧧 红包", vm.ui.value.messages.single().text)
    }

    @Test
    fun `空预览内部消息_整条不上屏_普通消息不受影响`() {
        // 入场标记（OFFLINE_MARKER_START）预览为空串 → mapNotNull 整条丢弃（渲染兜底·即使 SQL 过滤失守也不泄漏）。
        openAndWait()
        val start = OfflineMarkerStartPayload(location = "拾光咖啡馆", activity = "喝咖啡", timeString = "14:00").makeContent()
        msgFlow.value = listOf(
            MessageEntity(messageUUID = "m-start", conversationUuid = CONV, roleRaw = "assistant", content = start,
                timestamp = 1L, isOfflineMode = true, messageKindRaw = MessageKind.OFFLINE_MARKER_START.raw),
            MessageEntity(messageUUID = "m-t", conversationUuid = CONV, roleRaw = "assistant", content = "来啦", timestamp = 2L),
        )
        await("普通消息呈现") { vm.ui.value.messages.any { it.text == "来啦" } }
        assertEquals("入场标记整条不上屏", 1, vm.ui.value.messages.size)
    }

    @Test
    fun `E16_弹窗开着新回复_再markRead`() {
        openAndWait() // markRead: open + 初始空 emission
        msgFlow.value = listOf(MessageEntity(messageUUID = "m1", conversationUuid = CONV, roleRaw = "assistant", content = "来啦", timestamp = System.currentTimeMillis()))
        await("新消息呈现") { vm.ui.value.messages.any { it.text == "来啦" } }
        coVerify(atLeast = 2) { conversationRepo.markRead(CONV) }
    }

    private companion object {
        const val CONV = "conv-1"
    }
}
