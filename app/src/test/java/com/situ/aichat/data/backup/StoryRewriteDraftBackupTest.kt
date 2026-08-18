package com.situ.aichat.data.backup

import com.situ.aichat.data.local.entity.StoryEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 故事备份段 T1（阅读器掌控力 C3·图纸三 §7 T1-3 / E14）：`stories.pendingRewriteDraftJson`（重写期旧稿接力棒）
 * 的双向保真 + 老备份缺字段兜底。
 *
 * **章侧的单槽列** `story_chapters.previousDraftJson` 在 [StoryChapterBackupTest]（同卷 C3，两列成对）。
 *
 * 断言从「备份 = 绝对快照原样往返」独立反推：接力棒在备份那一刻可能正处于「快照已存、新章未生成」的重写中途
 * 状态（E3），此时它是**唯一**的旧稿副本——漏拷就是永久丢稿。
 */
class StoryRewriteDraftBackupTest {

    // 与 BackupService 同款 Json 配置（导出/导入双侧共用）。
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
    }

    private fun story(pendingRewriteDraftJson: String? = null) = StoryEntity(
        id = "story-1",
        title = "书名哨兵",
        genre = "悬疑",
        coverColorScheme = "amber",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_001_000L,
        storySummary = "summary-sentinel",
        rewriteInstruction = "instruction-sentinel",
        pendingRewriteDraftJson = pendingRewriteDraftJson,
    )

    /** 重写中途备份：接力棒必须活过 Entity → Export → Entity 往返，且同批相邻列不错位。 */
    @Test
    fun pendingRewriteDraft_survivesEntityRoundTrip() {
        val draft = """{"title":"旧标题","content":"旧正文"}"""

        val back = story(pendingRewriteDraftJson = draft)
            .toExport(chapters = null, characterRoles = null)
            .toEntity()

        assertEquals("重写期接力棒应原样往返", draft, back.pendingRewriteDraftJson)
        assertEquals("相邻列 rewriteInstruction 不受影响", "instruction-sentinel", back.rewriteInstruction)
        assertEquals("既有列原样", "书名哨兵", back.title)
        assertEquals("既有列原样", "summary-sentinel", back.storySummary)
    }

    /** 接力棒必须活过 DTO 的 JSON 序列化往返（encodeDefaults=false 下非默认值必被写出）。 */
    @Test
    fun pendingRewriteDraft_survivesJsonRoundTrip() {
        val draft = """{"content":"旧正文"}"""

        val export = story(pendingRewriteDraftJson = draft).toExport(chapters = null, characterRoles = null)
        val text = json.encodeToString(StoryExport.serializer(), export)
        val back = json.decodeFromString(StoryExport.serializer(), text)

        assertEquals(draft, back.pendingRewriteDraftJson)
    }

    /**
     * E14 老备份兜底：v40 及更早导出的 JSON 里**没有** pendingRewriteDraftJson 字段 → 解码与落实体一律 null
     * （= 没有进行中的重写，与 MIGRATION_40_41 的回填口径一致），既有字段一个不丢。
     */
    @Test
    fun legacyBackupWithoutField_decodesToNull() {
        val legacyJson = """
            {
              "id": "story-old",
              "title": "旧书",
              "genre": "言情",
              "coverColorScheme": "rose",
              "createdAt": 100,
              "updatedAt": 200,
              "writingStyle": "古风",
              "status": "serializing",
              "rewriteInstruction": "老包里的重写指令"
            }
        """.trimIndent()

        val export = json.decodeFromString(StoryExport.serializer(), legacyJson)
        assertNull("老包无此字段 → 缺省 null", export.pendingRewriteDraftJson)

        val entity = export.toEntity()
        assertNull("落实体仍是 null（DTO 默认 ↔ 实体默认两端一致）", entity.pendingRewriteDraftJson)
        assertEquals("既有字段照常还原", "旧书", entity.title)
        assertEquals("既有字段照常还原", "老包里的重写指令", entity.rewriteInstruction)
    }

    /** 没有进行中的重写时往返仍是 null（不许被空串兜底填出一个「假接力棒」——materialize 会误挂空槽）。 */
    @Test
    fun storyWithoutPendingDraft_roundTripsAsNull() {
        val back = story().toExport(chapters = null, characterRoles = null).toEntity()

        assertNull("无接力棒的书往返后仍是 null", back.pendingRewriteDraftJson)
    }
}
