package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ChatMessageDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * 历史时间分割线接入 [PromptBuilder.buildMessages] 的端到端行为测试（chunk2）。
 * 验证：跨天/久隔处插独立 system 横线分割线、连续当天聊天不插、跨天打断 role 合并、
 * 复刻 dump 穿帮场景。时区钉死 Asia/Shanghai（分割线内部用 `ZoneId.systemDefault()`）保证断言确定性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptBuilderTimeDividerTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var originalTz: TimeZone

    @Before
    fun pinTimeZone() {
        originalTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTz)
    }

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun inst(y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant()

    private fun msg(role: String, text: String, t: Long) = MessageEntity(
        messageUUID = "m$t",
        conversationUuid = "c1",
        roleRaw = role,
        content = text,
        timestamp = t,
    )

    private fun build(
        messages: List<MessageEntity>,
        now: Instant,
        settings: AppSettings = AppSettings(),
    ): List<ChatMessageDto> {
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        return PromptBuilder.buildMessages(
            character = character,
            sortedMessages = messages,
            userProfile = null,
            appSettings = settings,
            strings = strings,
            now = now,
        )
    }

    /** 历史里所有「时间分割线」消息（整条 = `【时间 · X】`）。 */
    private fun dividers(msgs: List<ChatMessageDto>): List<String> =
        msgs.mapNotNull { it.content }.filter { HistoryTimeDivider.isDivider(it) }.map { it.trim() }

    @Test
    fun dumpCase_insertsOpeningAnchorAndJumpDivider() {
        // 复刻穿帮：昨天下午聊 → 今天凌晨又来问「几点了」。期望两条分割线把时间轴讲清楚：
        // 起始锚（昨天 14:56）+ 跨夜跳变（今天 00:15），LLM 不再把昨天下午当此刻深夜。
        val out = build(
            listOf(
                msg("user", "在吗", ms(2026, 6, 25, 14, 56)),
                msg("assistant", "在的", ms(2026, 6, 25, 14, 57)),
                msg("user", "你看看几点了", ms(2026, 6, 26, 0, 15)),
            ),
            now = inst(2026, 6, 26, 0, 17),
        )
        assertEquals(
            listOf("【时间 · 昨天 14:56】", "【时间 · 今天 00:15】"),
            dividers(out),
        )
    }

    @Test
    fun continuousChat_today_noDivider() {
        // 全在今天、间隔都 <30 分钟 → 零分割线（不改动连续聊天的 prompt）。
        val out = build(
            listOf(
                msg("user", "早", ms(2026, 6, 26, 9, 0)),
                msg("assistant", "早呀", ms(2026, 6, 26, 9, 1)),
                msg("user", "今天忙吗", ms(2026, 6, 26, 9, 2)),
            ),
            now = inst(2026, 6, 26, 9, 5),
        )
        assertTrue("连续当天聊天不应有任何分割线：${dividers(out)}", dividers(out).isEmpty())
    }

    @Test
    fun crossDay_breaksUserMerge() {
        // 两条 user 跨天 → 分割线在中间打断合并，不应糊成同一个气泡。
        val out = build(
            listOf(
                msg("user", "昨天那条", ms(2026, 6, 25, 14, 0)),
                msg("user", "今天这条", ms(2026, 6, 26, 0, 10)),
            ),
            now = inst(2026, 6, 26, 0, 17),
        )
        val merged = out.any { c ->
            val t = c.content ?: ""
            t.contains("昨天那条") && t.contains("今天这条")
        }
        assertFalse("跨天的两条 user 不应合并成一条：$out", merged)
    }

    @Test
    fun crossDay_lastMessageStrippedEmpty_noDanglingDivider() {
        // 跨天后末条是「长括号旁白」（assistant 非线下被 stripAssistantParentheticalNarration 剥空 → continue）：
        // A1 修复后，本应指向这条的「今天 00:10」分割线不留在末尾悬空，只剩合法的起始锚。
        val out = build(
            listOf(
                msg("user", "在吗", ms(2026, 6, 25, 14, 0)),
                msg("assistant", "在的", ms(2026, 6, 25, 14, 1)),
                msg("assistant", "（她揉了揉眼睛打了个哈欠然后慢慢往沙发上靠过去整个人都困得不行了）", ms(2026, 6, 26, 0, 10)),
            ),
            now = inst(2026, 6, 26, 0, 17),
        )
        assertEquals(listOf("【时间 · 昨天 14:00】"), dividers(out))
    }

    @Test
    fun busyReplyScene_gatesDividerOff() {
        // A5：忙碌回复 / 语音通话等非「普通在线聊天」场景不插分割线（与「仅在线聊天」承诺一致）。
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val out = PromptBuilder.buildMessages(
            character = character,
            sortedMessages = listOf(
                msg("user", "在吗", ms(2026, 6, 25, 14, 56)),
                msg("user", "你看看几点了", ms(2026, 6, 26, 0, 15)),
            ),
            userProfile = null,
            appSettings = AppSettings(),
            strings = strings,
            now = inst(2026, 6, 26, 0, 17),
            scene = PromptScene.BUSY_REPLY,
        )
        assertTrue("非在线聊天场景应门控关闭分割线：${dividers(out)}", dividers(out).isEmpty())
    }
}
