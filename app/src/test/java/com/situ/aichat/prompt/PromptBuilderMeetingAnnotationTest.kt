package com.situ.aichat.prompt

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.local.entity.OfflineMeetingMemoryEntity
import com.situ.aichat.data.model.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.TimeZone

/**
 * 见面时间线注记端到端装配 T2（记忆改造二期·部件④·图纸 §3.1·T2-2）：走完整 [PromptBuilder.buildMessages]
 * 证明 meetingTimeline 被 thread 到 [appendConversationMessages] 且注记按规格发射——插入位置（bucket flush 后、
 * 分割线前）、每行只发一次、now=null（BUSY 场景）零注记且输出与现状一致（E2）。
 *
 * 默认时区钉 UTC（[appendConversationMessages] 用 ZoneId.systemDefault() 判日）保分割线数量与位置确定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptBuilderMeetingAnnotationTest {

    private val originalTz = TimeZone.getDefault()

    @Before fun setUp() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After fun tearDown() = TimeZone.setDefault(originalTz)

    private val now = Instant.parse("2026-07-10T23:00:00Z")

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private val meetingRow = OfflineMeetingMemoryEntity(
        uuid = "mm1",
        characterUuid = "c1",
        startedAtMillis = at("2026-07-10T12:00:00Z"), // 落在 m1(08:00) 与 m2(13:00) 之间
        location = "江边",
        activity = "散步",
        createdAtMillis = 0,
        updatedAtMillis = 0,
    )

    /** 走完整 buildMessages，返回每条消息 content 的有序列表。 */
    private fun buildWith(
        meetingTimeline: List<OfflineMeetingMemoryEntity>,
        scene: PromptScene,
    ): List<String> {
        val strings = PromptStrings(RuntimeEnvironment.getApplication())
        val character = CharacterEntity(uuid = "c1", name = "小雨", creationDate = 0L)
        val m1 = MessageEntity(
            messageUUID = "u1", conversationUuid = "c1", roleRaw = "user",
            content = "你好", timestamp = at("2026-07-10T08:00:00Z"),
        )
        val m2 = MessageEntity(
            messageUUID = "a1", conversationUuid = "c1", roleRaw = "assistant",
            content = "嗯嗯", timestamp = at("2026-07-10T13:00:00Z"),
        )
        return PromptBuilder.buildMessages(
            character = character,
            sortedMessages = listOf(m1, m2),
            userProfile = null,
            appSettings = AppSettings(),
            strings = strings,
            scene = scene,
            meetingTimeline = meetingTimeline,
            now = now,
        ).map { it.content.orEmpty() }
    }

    @Test fun `annotation emitted after bucket flush and before divider, once (T2-2)`() {
        val contents = buildWith(listOf(meetingRow), PromptScene.ONLINE_CHAT)
        val userIdx = contents.indexOfFirst { it == "你好" }
        val annotationIdx = contents.indexOfFirst { it.contains("这中间你们线下见了一面：江边，散步") }
        val dividerIdx = contents.indexOfFirst { it.startsWith("【时间 · ") && !it.contains("这中间") }
        val assistantIdx = contents.indexOfFirst { it == "嗯嗯" }

        assertTrue("应产出注记行", annotationIdx >= 0)
        assertTrue("bucket flush 后：user「你好」独立成条且在注记之前", userIdx in 0 until annotationIdx)
        assertTrue("注记在分割线之前（注记先、分割线后）", annotationIdx < dividerIdx)
        assertTrue("注记在其标记的后续消息之前", annotationIdx < assistantIdx)
        assertEquals("每行只发一次", 1, contents.count { it.contains("这中间你们线下见了一面：江边，散步") })
    }

    @Test fun `annotation line starts with time bracket prefix (inherits safety net)`() {
        val contents = buildWith(listOf(meetingRow), PromptScene.ONLINE_CHAT)
        val annotation = contents.first { it.contains("这中间你们线下见了一面") }
        assertTrue("以「【时间 · 」开头 → 继承剥回声/悬空清理/检测器避让", annotation.startsWith("【时间 · "))
    }

    @Test fun `now null busy scene yields zero annotation and identical output (E2)`() {
        val withRows = buildWith(listOf(meetingRow), PromptScene.BUSY_REPLY)
        val without = buildWith(emptyList(), PromptScene.BUSY_REPLY)
        assertTrue("BUSY 场景 now=null → 零注记", withRows.none { it.contains("这中间你们线下见了一面") })
        assertEquals("now=null 时 meetingTimeline 对输出零影响", without, withRows)
    }

    @Test fun `empty meetingTimeline yields no annotation`() {
        val contents = buildWith(emptyList(), PromptScene.ONLINE_CHAT)
        assertTrue(contents.none { it.contains("这中间你们线下见了一面") })
    }
}
