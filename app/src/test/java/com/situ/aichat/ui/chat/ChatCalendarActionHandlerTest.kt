package com.situ.aichat.ui.chat

import com.situ.aichat.data.calendar.CalendarAction
import com.situ.aichat.data.calendar.CalendarActionType
import com.situ.aichat.data.calendar.CalendarWriter
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.repository.CharacterRepository
import com.situ.aichat.data.repository.ConversationRepository
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.notification.CalendarNotificationScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ChatCalendarActionHandler 行为测试——验证刀3 日历协作者「真的能用」（不止编译过）。
 *
 * 手法：MockK 假掉 5 个具体依赖（CalendarWriter/SettingsRepository/通知调度/会话/角色仓库），
 * 用 Dispatchers.Unconfined 让 handler 内 `scope.launch` 同步跑完 → 断言确定性、可重复、不吃模拟器随机性。
 * CalendarWriter.createEvent/deleteEvent 是 suspend，故用 coVerify。
 * 覆盖：确认队列(开/关) · 真写入(create/delete) · #E 引用解析(命中/缺失) · 提醒兜底 · 未集成兜底 · Toast。
 */
class ChatCalendarActionHandlerTest {

    private lateinit var calendarWriter: CalendarWriter
    private lateinit var notificationScheduler: CalendarNotificationScheduler
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var characterRepo: CharacterRepository
    private lateinit var errorFlow: MutableStateFlow<String?>
    private lateinit var handler: ChatCalendarActionHandler

    /** 直接执行（确认开关关）。 */
    private val confirmOff = AppSettings(calendarIntegrationEnabled = true, calendarActionConfirmation = false)
    /** 弹确认卡片（确认开关开）。 */
    private val confirmOn = AppSettings(calendarIntegrationEnabled = true, calendarActionConfirmation = true)

    @Before
    fun setUp() {
        calendarWriter = mockk(relaxed = true)
        notificationScheduler = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        characterRepo = mockk(relaxed = true)
        settingsRepo = mockk()
        // executeCalendarAction 读 settingsRepo.getAppSettings().calendarReminderMode（suspend）。
        coEvery { settingsRepo.getAppSettings() } returns AppSettings()
        errorFlow = MutableStateFlow(null)
        handler = ChatCalendarActionHandler(
            scope = CoroutineScope(Dispatchers.Unconfined),
            errorFlow = errorFlow,
            settingsRepo = settingsRepo,
            calendarWriter = calendarWriter,
            calendarNotificationScheduler = notificationScheduler,
            conversationRepo = conversationRepo,
            characterRepo = characterRepo,
            conversationUuid = "conv-123",
        )
    }

    private fun createEvent(title: String = "开会", start: String = "2026-07-01T10:00") =
        CalendarAction(action = CalendarActionType.CREATE_EVENT, title = title, startDate = start)

    /** 断言整轮没有发生任何日历写入（动作均为 CREATE/DELETE，故核对这两个 suspend 写入零次）。 */
    private fun assertNoWrite() {
        coVerify(exactly = 0) { calendarWriter.createEvent(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { calendarWriter.deleteEvent(any()) }
    }

    @Test
    fun 确认开_只入队不写入() {
        val a = createEvent()
        handler.applyParsedCalendarActions(listOf(a), confirmOn)
        assertEquals(listOf(a), handler.pendingCalendarActions.value)
        assertNoWrite()
    }

    @Test
    fun 确认关_立即真写入事件() {
        val a = createEvent(title = "开会", start = "2026-07-01T10:00")
        handler.applyParsedCalendarActions(listOf(a), confirmOff)
        coVerify {
            calendarWriter.createEvent(
                title = "开会",
                startMillis = a.parsedStartMillis()!!,
                endMillis = null,
                notes = null,
                location = null,
                addSystemReminder = any(),
            )
        }
        // 成功写入后应弹 Toast。
        assertNotNull(handler.calendarToast.value)
    }

    @Test
    fun 确认队首_执行并出队() {
        val a = createEvent()
        handler.applyParsedCalendarActions(listOf(a), confirmOn)
        handler.confirmPendingCalendarAction()
        coVerify {
            calendarWriter.createEvent(
                title = "开会",
                startMillis = a.parsedStartMillis()!!,
                endMillis = any(),
                notes = any(),
                location = any(),
                addSystemReminder = any(),
            )
        }
        assertTrue(handler.pendingCalendarActions.value.isEmpty())
    }

    @Test
    fun 取消队首_出队且不写入() {
        val a = createEvent()
        handler.applyParsedCalendarActions(listOf(a), confirmOn)
        handler.cancelPendingCalendarAction()
        assertTrue(handler.pendingCalendarActions.value.isEmpty())
        assertNoWrite()
    }

    @Test
    fun 提醒类_回不支持错误() {
        val reminder = CalendarAction(action = CalendarActionType.CREATE_REMINDER, title = "吃药")
        handler.applyParsedCalendarActions(listOf(reminder), confirmOff)
        assertEquals("当前版本暂不支持提醒事项操作", errorFlow.value)
    }

    @Test
    fun 未开启日历集成_空操作() {
        handler.applyParsedCalendarActions(
            listOf(createEvent()),
            AppSettings(calendarIntegrationEnabled = false),
        )
        assertTrue(handler.pendingCalendarActions.value.isEmpty())
        assertNoWrite()
    }

    @Test
    fun 删除_已知引用_解析并删除() {
        handler.calendarEventRefMap = mapOf("#E1" to 42L)
        val del = CalendarAction(action = CalendarActionType.DELETE_EVENT, title = "取消会议", ref = "#E1")
        handler.applyParsedCalendarActions(listOf(del), confirmOff)
        coVerify { calendarWriter.deleteEvent(42L) }
    }

    @Test
    fun 删除_未知引用_报错且不删除() {
        handler.calendarEventRefMap = emptyMap()
        val del = CalendarAction(action = CalendarActionType.DELETE_EVENT, title = "取消会议", ref = "#E9")
        handler.applyParsedCalendarActions(listOf(del), confirmOff)
        assertEquals("找不到编号 #E9 对应的日历事件，可能已被删除", errorFlow.value)
        coVerify(exactly = 0) { calendarWriter.deleteEvent(any()) }
    }

    @Test
    fun 关闭Toast_清空() {
        handler.applyParsedCalendarActions(listOf(createEvent()), confirmOff)
        assertNotNull(handler.calendarToast.value)
        handler.dismissCalendarToast()
        assertNull(handler.calendarToast.value)
    }

    // — ② 执行失败回流（陪伴改良版）：真失败记一条·人话原因·一次性·TTL（措辞过审后落地） —

    @Test
    fun 创建_时间没认出_记真失败据实人话原因() {
        handler.applyParsedCalendarActions(listOf(createEvent(title = "牙医", start = "压根不是时间")), confirmOff)
        val f = handler.consumePendingFailure(System.currentTimeMillis())
        assertNotNull("时间没认出应记失败", f)
        assertEquals("创建", f!!.verb)
        assertEquals("牙医", f.title)
        assertEquals(ChatCalendarActionHandler.REASON_BAD_TIME, f.reason)
        assertNoWrite()
    }

    @Test
    fun 删除_未知引用_记真失败() {
        handler.calendarEventRefMap = emptyMap()
        handler.applyParsedCalendarActions(
            listOf(CalendarAction(action = CalendarActionType.DELETE_EVENT, title = "取消会议", ref = "#E9")),
            confirmOff,
        )
        val f = handler.consumePendingFailure(System.currentTimeMillis())
        assertNotNull("ref 查不到那条日程应记失败", f)
        assertEquals("删除", f!!.verb)
        assertEquals(ChatCalendarActionHandler.REASON_EVENT_NOT_FOUND, f.reason)
    }

    @Test
    fun 写入手机日历抛异常_记真失败且原因是人话() {
        coEvery {
            calendarWriter.createEvent(any(), any(), any(), any(), any(), any())
        } throws RuntimeException("ContentResolver SQLiteException boom")
        handler.applyParsedCalendarActions(listOf(createEvent(title = "团建")), confirmOff)
        val f = handler.consumePendingFailure(System.currentTimeMillis())
        assertNotNull("写入异常应记失败", f)
        assertEquals(ChatCalendarActionHandler.REASON_WRITE_FAILED, f!!.reason)
        assertFalse("人话原因·绝不漏技术异常文本", f.reason.contains("ContentResolver") || f.reason.contains("SQLite"))
    }

    @Test
    fun 成功写入_不记失败() {
        handler.applyParsedCalendarActions(listOf(createEvent()), confirmOff)
        assertNull("成功不该记失败", handler.consumePendingFailure(System.currentTimeMillis()))
    }

    @Test
    fun 待确认不算失败_不记() {
        // confirmOn → 仅入队、未执行 → 绝不记失败（②只记真失败，不记待确认）。
        handler.applyParsedCalendarActions(listOf(createEvent()), confirmOn)
        assertNull("待确认不算失败", handler.consumePendingFailure(System.currentTimeMillis()))
    }

    @Test
    fun 缺编号AI内部错_不记失败() {
        // UPDATE/DELETE ref 为空 = AI 没给编号（内部错·非用户可感）→ 不记。
        handler.applyParsedCalendarActions(
            listOf(CalendarAction(action = CalendarActionType.DELETE_EVENT, title = "x", ref = null)),
            confirmOff,
        )
        assertNull("缺编号不记（非用户可感失败）", handler.consumePendingFailure(System.currentTimeMillis()))
    }

    @Test
    fun 消费一次性_第二次为null() {
        handler.applyParsedCalendarActions(listOf(createEvent(start = "不是时间")), confirmOff)
        assertNotNull(handler.consumePendingFailure(System.currentTimeMillis()))
        assertNull("一次性·消费后即清", handler.consumePendingFailure(System.currentTimeMillis()))
    }

    @Test
    fun 消费TTL过期_返回null且已清() {
        handler.applyParsedCalendarActions(listOf(createEvent(start = "不是时间")), confirmOff)
        val staleNow = System.currentTimeMillis() + ChatCalendarActionHandler.FAILURE_TTL_MILLIS + 60_000L
        assertNull("过期不再提", handler.consumePendingFailure(staleNow))
        assertNull("过期消费也清掉", handler.consumePendingFailure(System.currentTimeMillis()))
    }

    @Test
    fun 新鲜度边界_纯函数() {
        val ttl = ChatCalendarActionHandler.FAILURE_TTL_MILLIS
        assertTrue(ChatCalendarActionHandler.isFailureFresh(1000L, 1000L, ttl)) // now==recordedAt
        assertTrue(ChatCalendarActionHandler.isFailureFresh(1000L, 1000L + ttl, ttl)) // 恰好 TTL
        assertFalse(ChatCalendarActionHandler.isFailureFresh(1000L, 1000L + ttl + 1, ttl)) // 超 1ms
        assertFalse(ChatCalendarActionHandler.isFailureFresh(1000L, 999L, ttl)) // 钟漂(now<recordedAt)
    }
}
