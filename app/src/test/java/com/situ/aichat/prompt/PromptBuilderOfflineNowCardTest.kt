package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 现在卡线下专版（前后置区审计 🟡-1b·微图纸 2026-07-13-前后置区审计与修缮 §二）装配级行为测试。
 * 断言从图纸物料独立反推：线下见面时【此刻】=「见面是唯一事实 + 日程背景板」（无进行中时点/短信示范/
 * ⚠️分心提示），时间锚 = 仅时刻事实（无「对方隔了…才回你」间隔行与五档短信措辞）；在线对照组三样俱全
 * （证明专版分流不伤在线行为）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class PromptBuilderOfflineNowCardTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val day = LocalDate.of(2026, 7, 11)
    private val now = LocalDateTime.of(2026, 7, 11, 13, 39).atZone(zone).toInstant()
    private fun at(h: Int, m: Int) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun defaultEvents() = listOf(
        ScheduleEventEntity("e1", "s1", at(9, 0), at(11, 30), "上午", "咖啡店", "开店"),
        ScheduleEventEntity("e3", "s1", at(12, 30), at(14, 0), "午后", "店里二楼", "午休小憩"),
        ScheduleEventEntity("e4", "s1", at(14, 0), at(17, 30), "下午", "咖啡店", "拉花赶单"),
    )

    private fun systemText(
        offline: Boolean,
        events: List<ScheduleEventEntity> = defaultEvents(),
        withSchedule: Boolean = true,
    ): String {
        val sched = if (withSchedule) {
            CharacterDailyScheduleEntity(
                uuid = "s1", characterUuid = "c1",
                date = day.atStartOfDay(zone).toInstant().toEpochMilli(), generatedAt = at(6, 0),
            )
        } else {
            null
        }
        val conversation = if (offline) {
            ConversationEntity(
                uuid = "cv1", title = "t", characterUuid = "c1", creationDate = 0L,
                isInOfflineMode = true, currentOfflineSessionId = "os1",
            )
        } else {
            null
        }
        val msgs = PromptBuilder.buildMessages(
            character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L),
            conversation = conversation,
            sortedMessages = listOf(
                // 3 小时前的角色消息：在线时应产出「对方隔了约 3 小时才回你」间隔行；线下专版必须退场。
                MessageEntity(
                    messageUUID = "a1", conversationUuid = "cv1", roleRaw = "assistant",
                    content = "上午忙完啦", timestamp = now.toEpochMilli() - 3 * 3_600_000,
                ),
                MessageEntity(
                    messageUUID = "u1", conversationUuid = "cv1", roleRaw = "user",
                    content = "我到啦", timestamp = now.toEpochMilli() - 60_000,
                ),
            ),
            userProfile = null, appSettings = AppSettings(), strings = PromptStrings(RuntimeEnvironment.getApplication()),
            todaySchedule = sched, todayScheduleEvents = events, now = now,
        )
        return msgs.filter { it.role == "system" }.joinToString("\n\n") { it.content.orEmpty() }
    }

    @Test
    fun 线下见面_此刻走专版_时间锚只剩事实() {
        val text = systemText(offline = true)
        // 专版三行（图纸 §二物料锁定）。
        assertTrue("恒首行：见面是唯一事实", text.contains("你此刻正在和用户线下见面——见面就是你现在唯一在做的事。"))
        assertTrue("正被替代的安排降级背景板", text.contains("今天这个时段你原本安排的是「午休小憩」"))
        assertTrue("晚些安排不用赶时间", text.contains("今天晚些时候（14:00）原本还有「拉花赶单」"))
        // 在线残留必须退场（审计 🟡-1 的三个冲突源）。
        assertFalse("进行中时点不得再报", text.contains("预计还持续约"))
        assertFalse("短信示范不得注入", text.contains("刚到公司准备开会"))
        assertFalse("时间锚间隔行退场", text.contains("对方隔了"))
        assertFalse("五档短信措辞退场", text.contains("重新拿起手机"))
        // 四小件图纸 §7 T2-7 / E15（2026-07-16）：裁决句属在线注入指令，线下专版全装配不得出现。
        // 独有句取「那些是过去，这是现在」（全库唯一·§9 独有句纪律）。
        assertFalse("多源事实裁决句不进线下", text.contains("那些是过去，这是现在"))
        // 时刻事实仍在（factsOnly），且此刻块先于压轴的见面说明书。
        assertTrue("时刻事实保留", text.contains("现在：2026-07-11"))
        assertTrue(
            "见面说明书压轴",
            text.indexOf("你此刻正在和用户线下见面") < text.indexOf("【当前处于线下见面模式】"),
        )
    }

    @Test
    fun 在线对照_原样三件俱全() {
        val text = systemText(offline = false)
        assertTrue("在线【此刻】带进行中时点", text.contains("预计还持续约"))
        assertTrue("在线注入指令示范仍在", text.contains("刚到公司准备开会"))
        assertTrue("在线间隔行仍在", text.contains("对方隔了约 3 小时才回你"))
    }

    @Test
    fun 线下见面_无进行中事件_背景板取刚结束() {
        // R1 🔵-1 补钉：只有已结束事件 → 专版走「见面之前，你刚结束」分支；无未来事件则无「晚些」行。
        val text = systemText(
            offline = true,
            events = listOf(ScheduleEventEntity("e1", "s1", at(9, 0), at(11, 30), "上午", "咖啡店", "开店")),
        )
        assertTrue("刚结束分支", text.contains("见面之前，你刚结束「开店」。"))
        assertFalse("无未来安排行", text.contains("今天晚些时候"))
    }

    @Test
    fun 线下见面_无日程数据_不注入孤块() {
        // R1 🔵-1 补钉：图纸锁定项「无日程 → \"\"」——整块【此刻】不出现（见面说明书已足够，不留孤块）。
        val text = systemText(offline = true, events = emptyList(), withSchedule = false)
        assertFalse("专版首行不出现", text.contains("你此刻正在和用户线下见面"))
        assertFalse("【此刻】整块不注入", text.contains("【此刻】"))
    }
}
