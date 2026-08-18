package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterDailyScheduleEntity
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.data.local.entity.MeetingAppointmentEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.ScheduleEventEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ChatMessageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * 布局审计刀1+刀2（2026-07-11 过审）行为测试：**非线下**时后置区装订为三张卡——
 * 物理最后一条 = 现在卡（<time_context> + 【此刻】合并一条）、其前一条 = 守卫卡（反元+工具合并一条）、
 * 规则类五模块拼成规则卡一条；半事实卡（待见约定/日历失败）在守卫卡之前、独立成条。
 * **线下**为零回归位：逐模块独立成条、时间/此刻按模块序在守卫前、沉浸 prompt 占最末位。
 * 断言从座次规格独立反推（标记串：<time_context> / 【此刻】 / 绝对禁令 / 【待见约定】），非照搬实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN") // 规则卡断言用中文模块标题（生产主语言）
class PromptBuilderTailOrderTest {

    private val fixedNow = Instant.ofEpochMilli(1_750_000_000_000)
    private fun strings() = PromptStrings(RuntimeEnvironment.getApplication())

    private fun character() = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)

    private fun history(): List<MessageEntity> = listOf(
        MessageEntity(messageUUID = "u1", conversationUuid = "conv1", roleRaw = "user", content = "在干嘛", timestamp = fixedNow.toEpochMilli() - 60_000),
        MessageEntity(messageUUID = "a1", conversationUuid = "conv1", roleRaw = "assistant", content = "刚忙完~", timestamp = fixedNow.toEpochMilli() - 30_000),
    )

    private fun idxOf(msgs: List<ChatMessageDto>, marker: String): Int =
        msgs.indexOfFirst { it.content?.contains(marker) == true }

    // ── 非线下:末位事实席 ──

    @Test
    fun 非线下_最后一条是现在卡_时间锚与此刻同卡() {
        val msgs = PromptBuilder.buildMessages(
            character = character(), sortedMessages = history(), userProfile = null,
            appSettings = AppSettings(), strings = strings(), now = fixedNow,
        )
        val last = msgs.last().content.orEmpty()
        assertTrue("最后一条含时间锚", last.contains("<time_context>"))
        assertTrue("最后一条含【此刻】", last.contains("【此刻】"))
        assertTrue("时间锚在【此刻】之前(卡内序)", last.indexOf("<time_context>") < last.indexOf("【此刻】"))
        assertTrue("全卡唯一尾注在现在卡", last.contains("这段是给你看的"))
        assertEquals("现在卡是 system", "system", msgs.last().role)
    }

    @Test
    fun 非线下_守卫卡合并一条且在现在卡之前() {
        val msgs = PromptBuilder.buildMessages(
            character = character(), sortedMessages = history(), userProfile = null,
            appSettings = AppSettings(characterCanInitiateOfflineMeeting = true), strings = strings(), now = fixedNow,
        )
        val timeIdx = idxOf(msgs, "<time_context>")
        val guardIdx = idxOf(msgs, "绝对禁令")
        assertTrue("守卫卡存在", guardIdx >= 0)
        assertTrue("守卫卡在现在卡之前", guardIdx < timeIdx)
        // 刀2 装订:反元与工具守卫（约定未来见面暗号路恒注入）同卡
        val guardCard = msgs[guardIdx].content.orEmpty()
        assertTrue("工具守卫并入守卫卡", guardCard.contains("future_meeting"))
        assertEquals("守卫卡=现在卡的前一条", timeIdx - 1, guardIdx)
    }

    @Test
    fun 非线下_规则五模块拼成一张规则卡() {
        val msgs = PromptBuilder.buildMessages(
            character = character(), sortedMessages = history(), userProfile = null,
            appSettings = AppSettings(), strings = strings(), now = fixedNow,
        )
        val styleIdx = idxOf(msgs, "【回复风格】")
        assertTrue("规则卡存在", styleIdx >= 0)
        val card = msgs[styleIdx].content.orEmpty()
        assertTrue("聊天格式同卡", card.contains("【聊天格式】"))
        assertTrue("防重复同卡", card.contains("【防重复与质量】"))
        assertTrue("情绪表达同卡", card.contains("【情绪表达】"))
        assertTrue("通用指令同卡", card.contains("App 语言"))
    }

    @Test
    fun 非线下_半事实卡在守卫类之前() {
        val appt = MeetingAppointmentEntity(
            uuid = "m1", characterUuid = "c1", conversationUuid = "conv1",
            status = "confirmed", scheduledAt = fixedNow.toEpochMilli() + 2 * 3600_000,
        )
        val msgs = PromptBuilder.buildMessages(
            character = character(), sortedMessages = history(), userProfile = null,
            appSettings = AppSettings(), strings = strings(), now = fixedNow,
            nextMeetingAppointment = appt,
        )
        val waitingIdx = idxOf(msgs, "【待见约定】")
        val guardIdx = idxOf(msgs, "绝对禁令")
        assertTrue("待见约定存在", waitingIdx >= 0)
        assertTrue("待见约定在守卫类之前", waitingIdx < guardIdx)
        assertTrue("待见约定在时间锚之前", waitingIdx < idxOf(msgs, "<time_context>"))
    }

    @Test
    fun 非线下_带日程时此刻仍收尾且内容含正在活动() {
        val day = fixedNow.toEpochMilli() - 3600_000
        val sched = CharacterDailyScheduleEntity(uuid = "s1", characterUuid = "c1", date = day, generatedAt = day)
        val events = listOf(
            ScheduleEventEntity("e1", "s1", fixedNow.toEpochMilli() - 1800_000, fixedNow.toEpochMilli() + 1800_000, "下午", "店里", "午休小憩"),
        )
        val msgs = PromptBuilder.buildMessages(
            character = character(), sortedMessages = history(), userProfile = null,
            appSettings = AppSettings(), strings = strings(), now = fixedNow,
            todaySchedule = sched, todayScheduleEvents = events,
        )
        val last = msgs.last().content.orEmpty()
        assertTrue("最后一条是【此刻】", last.contains("【此刻】"))
        assertTrue("【此刻】含正在的活动", last.contains("午休小憩"))
        // 四小件图纸 §7 T2-8（2026-07-16）：在线主路注入指令带多源事实裁决句，且排在【此刻】块之后。
        assertTrue("现在卡含裁决句", last.contains("那些是过去，这是现在"))
        assertTrue("裁决句在【此刻】之后", last.indexOf("【此刻】") < last.indexOf("那些是过去，这是现在"))
    }

    @Test
    fun 非线下_无日程兜底路不注入裁决句() {
        // 四小件图纸 E16/J4：兜底路无日程数据 →「日程 vs 此刻」冲突不存在，裁决句不进兜底块（单源，不双写）。
        val msgs = PromptBuilder.buildMessages(
            character = character(), sortedMessages = history(), userProfile = null,
            appSettings = AppSettings(), strings = strings(), now = fixedNow,
        )
        val all = msgs.joinToString("\n\n") { it.content.orEmpty() }
        assertTrue("兜底路确实走了【此刻】兜底块", all.contains("结合你自己的职业、性格和作息"))
        assertFalse("兜底块不含裁决句", all.contains("那些是过去，这是现在"))
    }

    @Test
    fun 非线下_上下文日志后置分段顺序与物理序一致() {
        val result = PromptBuilder.buildMessagesWithSegments(
            character = character(), sortedMessages = history(), userProfile = null,
            appSettings = AppSettings(), strings = strings(), now = fixedNow,
        )
        val suffixTypes = result.segments
            .filter { it.position == ContextSegment.POSITION_SUFFIX }
            .mapNotNull { it.systemModuleType }
        assertEquals("后置分段最后两个 = 时间感知、此刻状态", listOf("timeAwareness", "currentMoment"), suffixTypes.takeLast(2))
    }

    // ── 现在卡语义(2026-07-11 拍板):排序自由,钉末位只对"仍在末尾连续段"生效 ──

    private fun entry(type: SystemModuleType?): SuffixModuleEntry = SuffixModuleEntry(
        content = "c-${type?.name ?: "hint"}",
        module = type?.let {
            PromptModule(id = "id-$it", name = "$it", sortOrder = 0, systemModuleType = it, position = PromptModulePosition.SUFFIX)
        },
    )

    @Test
    fun 默认序_末尾连续段整体入现在卡() {
        val entries = listOf(
            entry(SystemModuleType.RESPONSE_STYLE),
            entry(SystemModuleType.TIME_AWARENESS),
            entry(SystemModuleType.CURRENT_MOMENT),
        )
        val tail = PromptBuilder.splitTrailingNowCard(entries)
        assertEquals(listOf(SystemModuleType.TIME_AWARENESS, SystemModuleType.CURRENT_MOMENT), tail.map { it.systemModuleType })
    }

    @Test
    fun 用户把时间感知挪进中间_不再钉尾_按用户顺序() {
        val entries = listOf(
            entry(SystemModuleType.TIME_AWARENESS), // 用户挪到最前
            entry(SystemModuleType.RESPONSE_STYLE),
            entry(SystemModuleType.CURRENT_MOMENT), // 仍在末尾 → 只有它入现在卡
        )
        val tail = PromptBuilder.splitTrailingNowCard(entries)
        assertEquals(listOf(SystemModuleType.CURRENT_MOMENT), tail.map { it.systemModuleType })
    }

    @Test
    fun 两者都被挪离末尾_现在卡为空_完全尊重用户排序() {
        val entries = listOf(
            entry(SystemModuleType.TIME_AWARENESS),
            entry(SystemModuleType.CURRENT_MOMENT),
            entry(SystemModuleType.RESPONSE_STYLE), // 用户把规则模块挪到最后
        )
        assertTrue(PromptBuilder.splitTrailingNowCard(entries).isEmpty())
    }

    // ── 线下:零回归位 ──

    @Test
    fun 线下_沉浸提示仍占最末位_时间锚保持守卫之前旧序() {
        val conv = ConversationEntity(
            uuid = "conv1", title = "测试会话", characterUuid = "c1", creationDate = 0L,
            isInOfflineMode = true, currentOfflineSessionId = "sess1",
        )
        val offlineHistory = history().map { it.copy(isOfflineMode = true, offlineSessionId = "sess1") }
        val msgs = PromptBuilder.buildMessages(
            character = character(), conversation = conv, sortedMessages = offlineHistory, userProfile = null,
            appSettings = AppSettings(), strings = strings(), now = fixedNow,
            scene = PromptScene.OFFLINE_MEETING,
        )
        val timeIdx = idxOf(msgs, "<time_context>")
        val guardIdx = idxOf(msgs, "绝对禁令")
        assertTrue("时间锚存在", timeIdx >= 0)
        assertTrue("线下:时间锚仍在守卫之前(旧序)", timeIdx < guardIdx)
        assertTrue("线下:最后一条不是时间锚/此刻", timeIdx != msgs.size - 1)
        assertTrue("线下:守卫不是最后一条(沉浸 prompt 收尾)", guardIdx < msgs.size - 1)
    }
}
