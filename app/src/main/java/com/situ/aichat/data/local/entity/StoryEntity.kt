package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.situ.aichat.story.StoryChatInfluenceWeight
import com.situ.aichat.story.StoryNarrativePerson
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.story.StoryUpdateMode
import java.util.UUID

/**
 * 一部 AI 交互式连载小说（1:1 iOS `Models/Story.swift` `@Model Story` :85-246）。
 *
 * 用户创建故事（类型/文风/角色/人称/聊天影响权重·一律无限连载），系统调 LLM 逐章生成，每章结尾给 2-3 选项，
 * 用户选择影响下一章走向。正文嵌「沉浸标签」（心情/天气/特效/文字样式/停顿）供阅读器渲染。
 *
 * ## 关系
 * - 章节 [StoryChapterEntity] / 角色 [StoryCharacterRoleEntity] 经 FK `storyId` 关联，删故事级联删（iOS `.cascade`）。
 * - 章节排序（按 chapterNumber）/解锁判定/角色排序（用户角色优先）等纯展示逻辑做成 Repository/扩展，不入库（spec §3.1）。
 *
 * ## 缓存字段（cached*）
 * 等价 iOS `refreshChapterCaches`（:225-245）：每次增删章/落选择/重写后由
 * [com.situ.aichat.data.repository.StoryRepository.refreshChapterCaches] 重算并定向写回，
 * 书架/卡片只读这 5 列、**不 join 章节表**（iOS 故意不展开 chapters 关系，安卓为列表性能同样如此，spec §4#4）。
 *
 * 不可变 Room 行 + `copy()`（与 gift/pet/diary/redpacket 一致）；变更经定向 @Query 或 copy()+@Update。
 * 多数枚举型字段存 raw 串（见 [com.situ.aichat.story] 下常量），无 TypeConverter。
 */
@Entity(
    tableName = "stories",
    indices = [Index("updatedAt"), Index("status")],
)
data class StoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val genre: String = "",
    /** 封面配色方案 key（按类型映射，见 spec §3.7 coverColorScheme/genreTint）。 */
    val coverColorScheme: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    /** 世界观设定。 */
    val worldSetting: String? = null,
    /** 剧情方向。 */
    val plotDirection: String? = null,
    /** 文风（轻松幽默/严肃文学/网文爽文/日系轻小说/哥特暗黑/古风…，见 [com.situ.aichat.story.StoryWritingTechniques]）。 */
    val writingStyle: String = "",

    /** 章节字数偏好（短 500 / 中 1500 / 长 3000）。 */
    val chapterLengthPreference: Int = 1500,
    /** 连载上限，null = 无限连载。 */
    val maxChapters: Int? = null,
    /** 自动扩展次数（到 maxChapters 后自动 +10 章，最多 3 次，spec §2.11）。 */
    val autoExtendCount: Int = 0,
    /** 聊天影响权重 raw（[StoryChatInfluenceWeight]，默认 medium）。 */
    val chatInfluenceWeight: String = StoryChatInfluenceWeight.MEDIUM,
    /** 叙事人称 raw（[StoryNarrativePerson]，默认 second「你」）。 */
    val narrativePerson: String = StoryNarrativePerson.SECOND,
    /** 更新模式 raw（[StoryUpdateMode]，默认 free；仅 chase 自动连载）。 */
    val updateMode: String = StoryUpdateMode.FREE,
    /** 追更每日解锁时（0-23）。 */
    val unlockHour: Int = 20,
    /** 追更每日解锁分（0-59）。 */
    val unlockMinute: Int = 0,
    /** 世界观设定（世界书）参与章节生成（ST5·契约 FABLE5_STORY_REDESIGN_PROPOSAL.md §4，默认开；开关行 UI 归 ST7c）。 */
    val worldInfoEnabled: Boolean = true,

    /** 状态机 raw（[StoryStatus]，默认 serializing）。 */
    val status: String = StoryStatus.SERIALIZING,

    /** 全局/前情摘要。 */
    val storySummary: String? = null,
    /** 当前剧情弧线。 */
    val currentArc: String? = null,
    /** LLM 每章输出的角色状态追踪。 */
    val characterStates: String? = null,
    /** LLM 每章输出的待回收伏笔。 */
    val openThreads: String? = null,
    /** 故事圣经：逐章 append（不覆盖）的角色/事件/伏笔记录（spec §4#5）。 */
    val storyBible: String? = null,
    /** 上次摘要压缩时的章号（判是否触发新一轮压缩）。 */
    val lastCompressedAtChapter: Int? = null,
    /**
     * 上次圣经结构化压缩覆盖到的章号（长篇稳定性 L1·契约 FABLE5_STORY_LONGFORM_STABILITY_PROPOSAL §3）。
     * null = 从未压缩（短篇/老故事）。圣经逐章行按此水位线切分：章号 ≤ 水位线的已并入结构化档案。
     */
    val lastBibleCompressedAtChapter: Int? = null,
    /** 大纲（有限=里程碑大纲 / 无限=当前弧线大纲）。 */
    val storyOutline: String? = null,
    /** 下一章方向提示（每章 METADATA 提取，下一章创作时注入）。 */
    val pendingChapterBeats: String? = null,
    /**
     * [pendingChapterBeats] 是否被用户在导演台亲手改过（故事二期卷一·提案 §3.3 层 3）。
     * true → 注入「## 用户指定的本章节拍（最高优先）」段且**不被自由输入跳过**；false → 注入「## 本章计划草稿（上一章末预排）」段。
     * 与 [pendingChapterBeats] **同生命周期**：写新 beats（materialize）/ 清 beats（prepareRewrite）时一并复位 false。
     */
    val pendingBeatsUserEdited: Boolean = false,
    /** 无限模式当前弧线起始章（有限模式保持 null）。 */
    val currentArcStartChapter: Int? = null,
    /**
     * 已写过的弧线简史（卷二 B2·每弧一行「第X–Y章·主题」，上限
     * [com.situ.aichat.story.StoryArcPlanning.ARC_HISTORY_MAX_LINES] 行，超出掐最老）。
     * 注入新弧线大纲 prompt 的「已写过的弧线」段，防连载久了反复写同一类冲突。null/空 = 还没写完过任何一条弧。
     */
    val arcHistory: String? = null,

    // ── 账本族三件（故事二期卷一·提案 §4.2-§4.4·由 METADATA 三个可选字段每章喂养） ──
    /**
     * 关系史账本（提案 §4.2）：两段制「【里程碑】永不机器裁剪 +【相处近况】滚动
     * [com.situ.aichat.story.StoryLedgers.RECENT_MAX_LINES] 行」，每行自带「第N章·」前缀。
     * 由 METADATA `intimacyUpdates` 按前缀分流追加（[com.situ.aichat.story.StoryLedgers.appendIntimacy]），
     * 注入续章背景区「## 两人的关系史」段。null/空 = 还没有已确立的关系事实。
     */
    val intimacyLedger: String? = null,
    /**
     * 章末场景状态快照（提案 §4.3）：一行「地点｜在场人物及状态要点」，**每章整列替换**（不是账本）。
     * 由 METADATA `sceneEndState` 落库：字段缺失 = 沿用旧值，显式「无」= 清 null（已离开该场景）。
     * 注入续章「上一章结尾」段之后，受本书开关 `sceneSnapshotEnabled` 门控。
     */
    val sceneState: String? = null,
    /**
     * 场景台账（提案 §4.4）：写过的重点场景每章一行「第N章·场景·地点·要点」，滚动
     * [com.situ.aichat.story.StoryLedgers.SCENE_MAX_LINES] 行。由 METADATA `sceneTag` 追加。
     * 两处消费：弧线大纲的排布规划段 + 主节拍段尾的「别与上一场重样」提醒。null/空 = 还没写过重点场景。
     */
    val sceneLedger: String? = null,

    /** 自定义提示词 JSON（[com.situ.aichat.data.model.CustomStoryPrompts] 编码），预设类型为 null。 */
    val customPromptsJson: String? = null,

    // ── 用户请求结局 / 重写（一次性字段，生成后立即清空，spec §4#6） ──
    /** 结局类型："open"/"ai"/"custom"，null=未请求。 */
    val requestedEndingType: String? = null,
    /** 仅 requestedEndingType=="custom" 时有值。 */
    val requestedEndingDetail: String? = null,
    /** 重写最新章的补充指令（一次性），null=无额外要求。 */
    val rewriteInstruction: String? = null,
    /**
     * 重写期的旧稿「接力棒」（C3·图纸三 §0.2-2）：`prepareRewrite` **删章之前**先把旧章快照写这里
     * （先写后删的写序保证进程死亡不丢稿），新章 materialize 时**仅当 [rewriteInstruction] != null**
     * 才搬进新章 [StoryChapterEntity.previousDraftJson] 并清空本列。正常续章恒为 null。
     */
    val pendingRewriteDraftJson: String? = null,

    // ── 终章弧「预约的收尾计划」（卷二 J1·**单一真理源**：finaleEndingType != null == 收尾弧进行中） ──
    /**
     * 收尾计划的结局类型（取值域同 [requestedEndingType] = [com.situ.aichat.story.StoryEndingType] 三值），
     * null = 没有收尾计划。与一次性的 [requestedEndingType] 的区别：这是**预约**——终章弧期间每章都是普通章，
     * 倒数到末章时由 [com.situ.aichat.data.local.dao.StoryDao.promoteFinaleToEndingRequest] 原子搬进
     * requestedEnding 两列，其后完全复用既有结局章管线。用户点「取消收尾」是**唯一**清空它的路。
     */
    val finaleEndingType: String? = null,
    /** 仅 finaleEndingType=="custom" 时有值（用户期望的结局方向，注入终章弧大纲 prompt）。 */
    val finaleEndingDetail: String? = null,

    /**
     * 完结时定格的结局类型（ST8·结局档案徽章数据源）："open"/"ai"/"custom"，null=非用户请求的自然/满章结局。
     * 与一次性的 [requestedEndingType]（生成时读、完结即清空 §4#6）区分：此列**持久不清**——完结那一刻把用户
     * 选的结局类型快照下来（[com.situ.aichat.story.StoryChapterMaterializer]），供档案卡/分享长图显示正确徽章。
     */
    val finalEndingType: String? = null,

    // ── 缓存字段（refreshChapterCaches 重算，书架/卡片只读这 5 列） ──
    val cachedChapterCount: Int = 0,
    val cachedLatestChapterNumber: Int? = null,
    val cachedLatestChapterTitle: String? = null,
    val cachedLatestChapterCreatedAt: Long? = null,
    val cachedHasPendingChoice: Boolean = false,
)
