package com.situ.aichat.data.backup

import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 故事备份段 T1-7（故事二期卷一·图纸 §7 / E7）：v43 六个新列的**双向保真**与**老包兜底**。
 *
 * 断言从图纸 §3.1「备份三处成对」与 §5-E7 规格独立反推：
 * - 三表六列（stories 的账本族三件 + pendingBeatsUserEdited、story_chapters.userRating、
 *   story_character_roles.intimatePersona）必须活过 Entity → Export → Entity 与 JSON 往返；
 *   漏拷 = 用户重装后关系史/台账/评分/角色反差凭空蒸发。
 * - 老备份包（不含这些字段）导入落 null / false，一个异常都不许抛。
 * - 新包被老 app 读：老 app 的 `ignoreUnknownKeys=true` 忽略新键——由「新增键不影响既有键解码」侧证。
 */
class StoryNarrativeLedgerBackupTest {

    // 与 BackupService 同款 Json 配置（导出/导入双侧共用）。
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
    }

    private val ledgerSentinel = "【里程碑】第3章·第一次牵手\n\n【相处近况】第11章·她开始主动整理他的衣领"

    private fun story(
        intimacyLedger: String? = null,
        sceneState: String? = null,
        sceneLedger: String? = null,
        pendingBeatsUserEdited: Boolean = false,
    ) = StoryEntity(
        id = "story-1",
        title = "书名哨兵",
        genre = "都市",
        coverColorScheme = "amber",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_001_000L,
        chapterLengthPreference = 3000,
        storyOutline = "outline-sentinel",
        pendingChapterBeats = "beats-sentinel",
        pendingBeatsUserEdited = pendingBeatsUserEdited,
        arcHistory = "arc-history-sentinel",
        intimacyLedger = intimacyLedger,
        sceneState = sceneState,
        sceneLedger = sceneLedger,
    )

    /** 账本族三件 + 节拍标志必须活过实体往返，且不挤掉相邻既有列。 */
    @Test
    fun storyLedgerColumns_surviveEntityRoundTrip() {
        val back = story(
            intimacyLedger = ledgerSentinel,
            sceneState = "她的公寓客厅｜两人并肩坐着，外套已脱",
            sceneLedger = "第7章·雨夜·车里\n第11章·公寓·厨房",
            pendingBeatsUserEdited = true,
        ).toExport(chapters = null, characterRoles = null).toEntity()

        assertEquals("关系史账本应原样往返（含两段标题与空行）", ledgerSentinel, back.intimacyLedger)
        assertEquals("场景状态应原样往返", "她的公寓客厅｜两人并肩坐着，外套已脱", back.sceneState)
        assertEquals("场景台账应原样往返", "第7章·雨夜·车里\n第11章·公寓·厨房", back.sceneLedger)
        assertTrue("「节拍是用户改过的」这件事必须跟着 beats 一起还原", back.pendingBeatsUserEdited)
        // 相邻既有列一并钉住，防新字段插入时错位。
        assertEquals("story-1", back.id)
        assertEquals("书名哨兵", back.title)
        assertEquals("beats-sentinel", back.pendingChapterBeats)
        assertEquals("arc-history-sentinel", back.arcHistory)
        assertEquals("outline-sentinel", back.storyOutline)
    }

    /** 章级快评必须活过实体往返（评分是用户资产，重装后不该归零）。 */
    @Test
    fun chapterRating_survivesEntityRoundTrip() {
        val chapter = StoryChapterEntity(
            id = "ch-1",
            storyId = "story-1",
            chapterNumber = 11,
            title = "第十一章",
            content = "content-sentinel",
            chapterSummary = "summary-sentinel",
            userRating = 3,
        )

        val back = chapter.toExport().toEntity(storyId = "story-1")

        assertEquals("评分应原样往返", 3, back.userRating)
        assertEquals("相邻既有列不受影响", "content-sentinel", back.content)
        assertEquals("相邻既有列不受影响", "summary-sentinel", back.chapterSummary)
        assertEquals(11, back.chapterNumber)
    }

    /** 角色私下反差必须活过实体往返（用户手写的人设）。 */
    @Test
    fun rolePersona_survivesEntityRoundTrip() {
        val role = StoryCharacterRoleEntity(
            id = "role-1",
            storyId = "story-1",
            roleName = "林晚",
            roleDescription = "desc-sentinel",
            intimatePersona = "人前清冷寡言，私下黏人到不肯松手",
        )

        val back = role.toExport().toEntity(storyId = "story-1")

        assertEquals("私下反差应原样往返", "人前清冷寡言，私下黏人到不肯松手", back.intimatePersona)
        assertEquals("相邻既有列不受影响", "desc-sentinel", back.roleDescription)
        assertEquals("林晚", back.roleName)
    }

    /** 六个新字段必须活过 DTO 的 JSON 序列化往返（encodeDefaults=false 下非默认值必被写出）。 */
    @Test
    fun newFields_surviveJsonRoundTrip() {
        val storyText = json.encodeToString(
            StoryExport.serializer(),
            story(
                intimacyLedger = ledgerSentinel,
                sceneState = "车里｜副驾",
                sceneLedger = "第7章·雨夜·车里",
                pendingBeatsUserEdited = true,
            ).toExport(chapters = null, characterRoles = null),
        )
        val backStory = json.decodeFromString(StoryExport.serializer(), storyText)
        assertEquals(ledgerSentinel, backStory.intimacyLedger)
        assertEquals("车里｜副驾", backStory.sceneState)
        assertEquals("第7章·雨夜·车里", backStory.sceneLedger)
        assertTrue(backStory.pendingBeatsUserEdited)

        val chapterText = json.encodeToString(
            StoryChapterExport.serializer(),
            StoryChapterEntity(id = "ch-1", storyId = "story-1", chapterNumber = 2, userRating = 1).toExport(),
        )
        assertEquals(1, json.decodeFromString(StoryChapterExport.serializer(), chapterText).userRating)

        val roleText = json.encodeToString(
            StoryCharacterRoleExport.serializer(),
            StoryCharacterRoleEntity(id = "role-1", storyId = "story-1", intimatePersona = "反差哨兵").toExport(),
        )
        assertEquals("反差哨兵", json.decodeFromString(StoryCharacterRoleExport.serializer(), roleText).intimatePersona)
    }

    /**
     * E7 老包兜底：v42 及更早的备份包完全没有这六个键 → 解码得缺省值（null / false），
     * 落实体后同样是 null / false，既有字段照常还原。
     */
    @Test
    fun legacyBackup_withoutNewFields_decodesToDefaults() {
        val legacyStoryJson = """
            {
              "id": "story-old",
              "title": "旧的书",
              "genre": "言情",
              "coverColorScheme": "rose",
              "createdAt": 100,
              "updatedAt": 200,
              "writingStyle": "古风",
              "chapterLengthPreference": 1500,
              "status": "serializing",
              "pendingChapterBeats": "老包里的方向提示"
            }
        """.trimIndent()
        val legacyStory = json.decodeFromString(StoryExport.serializer(), legacyStoryJson).toEntity()
        assertNull("老包无关系史 → null", legacyStory.intimacyLedger)
        assertNull("老包无场景状态 → null", legacyStory.sceneState)
        assertNull("老包无场景台账 → null", legacyStory.sceneLedger)
        assertFalse("老包无节拍标志 → false（存量节拍算 AI 预排）", legacyStory.pendingBeatsUserEdited)
        assertEquals("既有字段照常还原", "旧的书", legacyStory.title)
        assertEquals("既有字段照常还原", "老包里的方向提示", legacyStory.pendingChapterBeats)

        val legacyChapterJson = """
            {"id": "ch-old", "chapterNumber": 3, "title": "第三章", "content": "正文", "mood": "peaceful"}
        """.trimIndent()
        val legacyChapter = json.decodeFromString(StoryChapterExport.serializer(), legacyChapterJson).toEntity("story-old")
        assertNull("老包无评分 → null（未评）", legacyChapter.userRating)
        assertEquals("正文", legacyChapter.content)

        val legacyRoleJson = """
            {"id": "role-old", "roleName": "旧角色", "roleType": "supporting"}
        """.trimIndent()
        val legacyRole = json.decodeFromString(StoryCharacterRoleExport.serializer(), legacyRoleJson).toEntity("story-old")
        assertNull("老包无私下反差 → null", legacyRole.intimatePersona)
        assertEquals("旧角色", legacyRole.roleName)
    }

    /**
     * 新包被老 app 读（E7 另一半）：老 app 的解析器不认识新键。这里用同款 `ignoreUnknownKeys=true` 的
     * Json 解一份「带未知键」的包来侧证——未知键被忽略、既有键照常解出，不抛异常。
     */
    @Test
    fun unknownKeys_areIgnoredSoNewPackagesLoadOnOldApp() {
        val futureJson = """
            {
              "id": "story-future",
              "title": "新包的书",
              "genre": "都市",
              "coverColorScheme": "amber",
              "createdAt": 100,
              "updatedAt": 200,
              "status": "serializing",
              "intimacyLedger": "【里程碑】第3章·第一次牵手",
              "someFieldFromTheFuture": "更新的字段"
            }
        """.trimIndent()

        val decoded = json.decodeFromString(StoryExport.serializer(), futureJson)

        assertEquals("未知键不影响既有键解码", "新包的书", decoded.title)
        assertEquals("本卷新键照常解出", "【里程碑】第3章·第一次牵手", decoded.intimacyLedger)
    }
}
