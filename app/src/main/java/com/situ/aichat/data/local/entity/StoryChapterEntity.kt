package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 故事的一章（1:1 iOS `Models/StoryChapter.swift` `@Model StoryChapter` :4-61）。
 *
 * 正文 [content] 在 iOS 是 `.externalStorage`（大文本外置）；Room 直接存 TEXT，列表/缓存查询走投影
 * （[com.situ.aichat.data.local.dao.StoryChapterCacheRow]）避免拉正文（spec §3.1）。
 *
 * 解锁判定 `isUnlocked`（unlockAt==null || now>=unlockAt）是计算属性，做成 Repository/扩展、不入库。
 */
@Entity(
    tableName = "story_chapters",
    foreignKeys = [
        ForeignKey(
            entity = StoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["storyId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("storyId"), Index("createdAt"), Index("chapterNumber")],
)
data class StoryChapterEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    /** 所属故事 [StoryEntity.id]（FK，级联删）。 */
    val storyId: String = "",
    val chapterNumber: Int = 0,
    val title: String = "",
    /** 章节悬念引子。 */
    val teaser: String? = null,
    val createdAt: Long = System.currentTimeMillis(),

    /** 正文（含沉浸标签）。iOS `.externalStorage`；列表查询勿拉此列。 */
    val content: String = "",

    /** 心情 raw（11 合法值之一，解析器归一化，缺省 peaceful，spec §2.6）。 */
    val mood: String = "peaceful",
    /** 场景切换标记（可空）。 */
    val scenes: String? = null,

    /** 本章是否有选择分支。 */
    val hasChoice: Boolean = false,
    /** 选择提示语。 */
    val choicePrompt: String? = null,
    /** 选项 JSON 数组串（choiceA-D 合并）。 */
    val choiceOptions: String? = null,
    /** 用户已选项（null = 尚未选择）。 */
    val userChoice: String? = null,
    /**
     * LLM 在本章 METADATA 里自标了结局（isEnding=true）——**仅是建议，不决定故事状态**（ST11 拍板②：完结权归用户）。
     * 唯一消费者 = 阅读器末章「建议完结卡」（[com.situ.aichat.ui.story.StoryReaderEndgameLogic]）；
     * 书架/档案/统计一律不读此列（防蔓延·图纸 §3.2）。
     */
    val aiSuggestedEnding: Boolean = false,
    /** 做选择的时间。 */
    val choiceMadeAt: Long? = null,
    /** 本章摘要（METADATA summary，缺失时用正文前 150 字代替，spec §2.2）。 */
    val chapterSummary: String? = null,
    /** 追更解锁时间，null = 立即可读（自由模式或已解锁）。 */
    val unlockAt: Long? = null,
    /**
     * 读者三档快评（故事二期卷一·提案 §5.2）：3=爽 / 2=还行 / 1=不行，null = 未评。
     * 唯一消费者 = 续章 prompt 的「## 读者反馈」段（只读**上一章**的评分，注入门 `in 1..3`）；
     * 写入口（章末快评 UI）归卷三，本卷只建列与 DAO 写法。
     */
    val userRating: Int? = null,
    /**
     * 「上一版」单槽（C3·图纸三 §3.1）：重写本章时把**重写前那一版**的 12 个内容字段编码成 JSON 存这里
     * （[com.situ.aichat.story.StoryChapterDraft]），null = 本章没有可回翻的旧稿。
     *
     * **单槽语义**：只保留「最近一次重写前」的那一版，再次重写会被新快照覆盖（用户已过审知悉）；
     * 「换回上一版」= 内容字段与本槽**互换**（[com.situ.aichat.data.local.dao.StoryDao.swapChapterDraft]），
     * 因此可反复来回切。轨道字段（id/storyId/chapterNumber/createdAt/unlockAt）恒不进槽、恒不参与互换。
     */
    val previousDraftJson: String? = null,
)
