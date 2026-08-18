package com.situ.aichat.data.backup

import com.situ.aichat.data.local.entity.StoryEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 故事备份段 T2（无限连载卷二·图纸 §7 T2-1 / E2）：三个新列（arcHistory / finaleEndingType / finaleEndingDetail）
 * 的双向保真 + **老备份包满章语义归一化**（maxChapters→null、autoExtendCount→0）+ 老包缺字段兜底。
 *
 * 断言从图纸 §3.1「备份三件」与 J2/J3 规格独立反推：导出端直传、导入端直传**但两列强制归零**——
 * 有限连载模式已整体退役，老包里的「共 60 章 / 已扩展 2 次」带回来只会是引擎再也不读的死数据。
 */
class StoryFinaleBackupTest {

    // 与 BackupService 同款 Json 配置（导出/导入双侧共用）。
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = true
    }

    private fun story(
        maxChapters: Int? = null,
        autoExtendCount: Int = 0,
        arcHistory: String? = null,
        finaleEndingType: String? = null,
        finaleEndingDetail: String? = null,
    ) = StoryEntity(
        id = "story-1",
        title = "书名哨兵",
        genre = "悬疑",
        coverColorScheme = "amber",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_001_000L,
        chapterLengthPreference = 3000,
        maxChapters = maxChapters,
        autoExtendCount = autoExtendCount,
        storyOutline = "outline-sentinel",
        currentArcStartChapter = 13,
        arcHistory = arcHistory,
        finaleEndingType = finaleEndingType,
        finaleEndingDetail = finaleEndingDetail,
        finalEndingType = "open",
    )

    /** 三个新列必须活过 Entity → Export → Entity 往返（漏拷 = 用户重装后收尾计划/弧线简史凭空蒸发）。 */
    @Test
    fun newColumns_surviveEntityRoundTrip() {
        val back = story(
            arcHistory = "第1–12章·雨夜追凶\n第13–24章·旧案重启",
            finaleEndingType = "custom",
            finaleEndingDetail = "两个人在海边和解",
        ).toExport(chapters = null, characterRoles = null).toEntity()

        assertEquals("弧线简史应原样往返", "第1–12章·雨夜追凶\n第13–24章·旧案重启", back.arcHistory)
        assertEquals("收尾计划类型应原样往返", "custom", back.finaleEndingType)
        assertEquals("收尾方向应原样往返", "两个人在海边和解", back.finaleEndingDetail)
        // 同批既有列一并钉住，防新字段插入时错位。
        assertEquals("story-1", back.id)
        assertEquals("书名哨兵", back.title)
        assertEquals("悬疑", back.genre)
        assertEquals(3000, back.chapterLengthPreference)
        assertEquals("outline-sentinel", back.storyOutline)
        assertEquals(13, back.currentArcStartChapter)
        assertEquals("既有结局徽章快照不受新列影响", "open", back.finalEndingType)
    }

    /** 三个新列必须活过 DTO 的 JSON 序列化往返（encodeDefaults=false 下非默认值必被写出）。 */
    @Test
    fun newColumns_surviveJsonRoundTrip() {
        val export = story(
            arcHistory = "第1–9章·开局",
            finaleEndingType = "ai",
        ).toExport(chapters = null, characterRoles = null)
        val text = json.encodeToString(StoryExport.serializer(), export)
        val back = json.decodeFromString(StoryExport.serializer(), text)

        assertEquals("第1–9章·开局", back.arcHistory)
        assertEquals("ai", back.finaleEndingType)
        assertNull("custom 之外的类型不带 detail", back.finaleEndingDetail)
    }

    /**
     * E2 归一化：老备份包带着「共 60 章 / 已自动扩展 2 次」→ 导入后**一律** maxChapters=null、autoExtendCount=0
     * （J2 单模式化：有限连载已退役，满章语义不许还魂），其余字段一个不改。
     */
    @Test
    fun legacyFiniteBackup_normalizesToUnlimitedOnImport() {
        val back = story(maxChapters = 60, autoExtendCount = 2)
            .toExport(chapters = null, characterRoles = null)
            .toEntity()

        assertNull("老包的连载上限必须被归一化成 null（无限连载）", back.maxChapters)
        assertEquals("老包的自动扩展次数必须被归零", 0, back.autoExtendCount)
        assertEquals("归一化只碰这两列，标题原样", "书名哨兵", back.title)
        assertEquals("归一化只碰这两列，大纲原样", "outline-sentinel", back.storyOutline)
        assertEquals("归一化只碰这两列，弧起点原样", 13, back.currentArcStartChapter)
    }

    /** 老备份 JSON 里直接写着 maxChapters/autoExtendCount 也照样归一化（解码保真、映射归零，两步分工清楚）。 */
    @Test
    fun legacyFiniteBackupJson_decodesValuesButMapsToUnlimited() {
        val legacyJson = """
            {
              "id": "story-old",
              "title": "旧的有限书",
              "genre": "言情",
              "coverColorScheme": "rose",
              "createdAt": 100,
              "updatedAt": 200,
              "writingStyle": "古风",
              "chapterLengthPreference": 1500,
              "maxChapters": 100,
              "autoExtendCount": 3,
              "status": "serializing"
            }
        """.trimIndent()

        val export = json.decodeFromString(StoryExport.serializer(), legacyJson)
        assertEquals("DTO 层如实解码老包的值", 100, export.maxChapters)
        assertEquals("DTO 层如实解码老包的值", 3, export.autoExtendCount)
        assertNull("老包无此字段 → 缺省 null", export.arcHistory)
        assertNull("老包无此字段 → 缺省 null", export.finaleEndingType)
        assertNull("老包无此字段 → 缺省 null", export.finaleEndingDetail)

        val entity = export.toEntity()
        assertNull("落实体时归一化成无限连载", entity.maxChapters)
        assertEquals("落实体时自动扩展次数归零", 0, entity.autoExtendCount)
        assertEquals("既有字段照常还原", "旧的有限书", entity.title)
        assertEquals("既有字段照常还原", "言情", entity.genre)
        assertNull("三个新列在老包上落 null", entity.arcHistory)
        assertNull("三个新列在老包上落 null", entity.finaleEndingType)
        assertNull("三个新列在老包上落 null", entity.finaleEndingDetail)
    }
}
