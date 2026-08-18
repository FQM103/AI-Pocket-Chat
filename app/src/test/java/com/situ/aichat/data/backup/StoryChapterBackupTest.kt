package com.situ.aichat.data.backup

import com.situ.aichat.data.local.entity.StoryChapterEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 故事章节备份段 T2（ST11·图纸 §7 / E5）：`aiSuggestedEnding` 列进备份后的双向保真 + 老备份缺省兜底。
 *
 * 断言从「备份 = 绝对快照原样往返」语义独立反推（导出端 `encodeDefaults=false` 会丢「==默认」的字段，
 * 导入端按 DTO 默认补回——两端默认必须一致才不丢印，本测试即锁住这一不变量）。
 */
class StoryChapterBackupTest {

    // 与 BackupService 同款 Json 配置（导出/导入双侧共用）。
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
    }

    private fun chapter(
        aiSuggestedEnding: Boolean,
        previousDraftJson: String? = null,
    ) = StoryChapterEntity(
        id = "ch-1",
        storyId = "story-1",
        chapterNumber = 7,
        title = "第七章",
        teaser = "teaser-sentinel",
        createdAt = 1_700_000_000_000L,
        content = "content-sentinel",
        mood = "tense",
        hasChoice = true,
        choicePrompt = "prompt-sentinel",
        choiceOptions = """["A","B"]""",
        userChoice = "选项A",
        choiceMadeAt = 1_700_000_001_000L,
        chapterSummary = "summary-sentinel",
        unlockAt = 1_700_000_002_000L,
        aiSuggestedEnding = aiSuggestedEnding,
        previousDraftJson = previousDraftJson,
    )

    /** true 的印必须活过 Entity → Export → Entity 往返（漏拷 = 用户重装后建议卡凭空消失）。 */
    @Test
    fun aiSuggestedEndingTrue_survivesEntityRoundTrip() {
        val back = chapter(aiSuggestedEnding = true).toExport().toEntity(storyId = "story-1")

        assertTrue("AI 自标结局的印应原样往返", back.aiSuggestedEnding)
        // 同批既有列一并钉住，防新字段插入时错位。
        assertEquals("ch-1", back.id)
        assertEquals(7, back.chapterNumber)
        assertEquals("第七章", back.title)
        assertEquals("content-sentinel", back.content)
        assertEquals("tense", back.mood)
        assertTrue(back.hasChoice)
        assertEquals("选项A", back.userChoice)
        assertEquals("summary-sentinel", back.chapterSummary)
        assertEquals(1_700_000_002_000L, back.unlockAt)
    }

    /** false 同样原样往返（不许被「默认即真」之类的兜底翻面）。 */
    @Test
    fun aiSuggestedEndingFalse_survivesEntityRoundTrip() {
        val back = chapter(aiSuggestedEnding = false).toExport().toEntity(storyId = "story-1")

        assertFalse("未自标结局的章往返后仍是 false", back.aiSuggestedEnding)
    }

    /** true 的印必须活过 DTO 的 JSON 序列化往返（encodeDefaults=false 下非默认值必被写出）。 */
    @Test
    fun aiSuggestedEndingTrue_survivesJsonRoundTrip() {
        val export = chapter(aiSuggestedEnding = true).toExport()
        val text = json.encodeToString(StoryChapterExport.serializer(), export)
        val back = json.decodeFromString(StoryChapterExport.serializer(), text)

        assertTrue("印应活过 JSON 往返", back.aiSuggestedEnding)
    }

    /**
     * E5 老备份兜底：v38 及更早导出的 JSON 里**没有** aiSuggestedEnding 字段 → 导入解码必须落回 false
     * （= 存量章一律「AI 未自标结局」，与 MIGRATION_38_39 的回填口径一致），且既有字段一个不丢。
     */
    @Test
    fun legacyBackupWithoutField_decodesToFalse() {
        val legacyJson = """
            {
              "id": "ch-old",
              "chapterNumber": 3,
              "title": "旧章",
              "createdAt": 100,
              "content": "old-content",
              "mood": "peaceful",
              "hasChoice": true,
              "userChoice": "选项B"
            }
        """.trimIndent()

        val back = json.decodeFromString(StoryChapterExport.serializer(), legacyJson)

        assertFalse("老备份无此字段 → 缺省 false", back.aiSuggestedEnding)
        assertEquals("ch-old", back.id)
        assertEquals(3, back.chapterNumber)
        assertEquals("旧章", back.title)
        assertEquals("old-content", back.content)
        assertEquals("选项B", back.userChoice)

        // 落到实体上仍是 false（DTO 默认 ↔ 实体默认两端一致）。
        assertFalse(back.toEntity(storyId = "story-old").aiSuggestedEnding)
        assertNull("老备份无 previousDraftJson 字段 → 缺省 null（与 MIGRATION_40_41 回填口径一致）", back.previousDraftJson)
        assertNull(back.toEntity(storyId = "story-old").previousDraftJson)
    }

    /**
     * C3（图纸三 T1-3 / E14）：「上一版」单槽 JSON 是用户资产，必须活过 Entity → Export → Entity 与 JSON 双往返。
     * 漏拷 = 用户重装后旧稿凭空消失（且不可逆——单槽被下次重写覆盖后再无副本）。
     */
    @Test
    fun previousDraftJson_survivesEntityAndJsonRoundTrip() {
        val draft = """{"title":"旧标题","content":"旧正文"}"""

        val back = chapter(aiSuggestedEnding = false, previousDraftJson = draft)
            .toExport()
            .toEntity(storyId = "story-1")
        assertEquals("单槽 JSON 应原样往返（实体链）", draft, back.previousDraftJson)

        val export = chapter(aiSuggestedEnding = false, previousDraftJson = draft).toExport()
        val text = json.encodeToString(StoryChapterExport.serializer(), export)
        val decoded = json.decodeFromString(StoryChapterExport.serializer(), text)
        assertEquals("单槽 JSON 应原样往返（JSON 链）", draft, decoded.previousDraftJson)
    }

    /** 无旧稿的章往返后仍是 null（不许被空串之类的兜底填出一个「假槽」——菜单会因此错误地冒出「查看上一版」）。 */
    @Test
    fun chapterWithoutDraft_roundTripsAsNull() {
        val back = chapter(aiSuggestedEnding = false).toExport().toEntity(storyId = "story-1")

        assertNull("无旧稿的章往返后仍是 null", back.previousDraftJson)
    }
}
