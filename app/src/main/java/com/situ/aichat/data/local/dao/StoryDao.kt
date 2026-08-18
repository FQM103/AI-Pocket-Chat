package com.situ.aichat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 章节缓存投影（**不含正文**），供 [com.situ.aichat.data.repository.StoryRepository.refreshChapterCaches]
 * 重算 5 个 cached_* 用，避免拉大字段 content（spec §3.1）。
 */
data class StoryChapterCacheRow(
    val chapterNumber: Int,
    val title: String,
    val createdAt: Long,
    val hasChoice: Boolean,
    val userChoice: String?,
)

/**
 * 章节摘要投影（**不含正文**），供续章创作 prompt 的「前情回顾」滑动窗口
 * （[com.situ.aichat.story.StoryGenerationPromptBuilder.appendRecapSection]）。
 */
data class StoryChapterSummaryRow(
    val chapterNumber: Int,
    val chapterSummary: String?,
)

/**
 * 待解锁章节投影（**不含正文**，连 story 标题），供 11.1g-1 章节解锁通知重排
 * （[com.situ.aichat.story.StoryUnlockNotificationScheduler.refreshAllUnlockNotifications]）：
 * 精确闹钟不跨重启，回前台/开机时按 DB 里 `unlockAt > now` 的章节重新登记。
 */
data class StoryUnlockRow(
    val storyId: String,
    val storyTitle: String,
    val chapterNumber: Int,
    val chapterTitle: String,
    val unlockAt: Long,
)

/**
 * 故事 (M08) Room 访问。聚合/缓存逻辑见 [com.situ.aichat.data.repository.StoryRepository]。
 *
 * **行数豁免声明（CLAUDE.md §2 🔴 逻辑层 600 硬上限）**：本文件越线——查询瘦身卷二加 6 条投影/单行查询
 * （章节元数据三条 + 末章全列 + stories 轻列两条，每条的显式列清单本身就占 3-4 行），图纸
 * `docs/handoff/2026-08-03-故事查询瘦身-卷二.md` §9 明令**禁拆 StoryDao**（拆 DAO 会 ripple 到 Room 编译期
 * 生成物与全部注入点，属独立重构卷）。已登记 FILE_SIZE_REFACTOR_BACKLOG 观察名单，等专门的拆分卷处理。
 */
@Dao
interface StoryDao {

    // ── Story ──

    /** 书架：按 updatedAt 倒序（iOS `StoryBookshelfView` 排序）。stories 表无正文列，直接全列查。 */
    @Query("SELECT * FROM stories ORDER BY updatedAt DESC")
    fun observeStoriesByUpdatedAt(): Flow<List<StoryEntity>>

    /** 书架/档案轻列投影（29 列·去 18 个大文本列）：排除列落实体默认值，MISMATCH 有意——消费面禁读排除列（图纸 2026-08-03 卷二 §9）。 */
    @SuppressWarnings(androidx.room.RoomWarnings.QUERY_MISMATCH)
    @Query(
        "SELECT id, title, genre, coverColorScheme, createdAt, updatedAt, writingStyle, " +
            "chapterLengthPreference, maxChapters, autoExtendCount, chatInfluenceWeight, narrativePerson, " +
            "updateMode, unlockHour, unlockMinute, worldInfoEnabled, status, lastCompressedAtChapter, " +
            "lastBibleCompressedAtChapter, pendingBeatsUserEdited, currentArcStartChapter, requestedEndingType, " +
            "finaleEndingType, finalEndingType, cachedChapterCount, cachedLatestChapterNumber, " +
            "cachedLatestChapterTitle, cachedLatestChapterCreatedAt, cachedHasPendingChoice " +
            "FROM stories ORDER BY updatedAt DESC",
    )
    fun observeStoriesLite(): Flow<List<StoryEntity>>

    /** 动态页最近一本（同 [observeStoriesLite] 29 列 + LIMIT 1）：排除列落实体默认值，MISMATCH 有意——消费面禁读排除列（图纸卷二 §9）。 */
    @SuppressWarnings(androidx.room.RoomWarnings.QUERY_MISMATCH)
    @Query(
        "SELECT id, title, genre, coverColorScheme, createdAt, updatedAt, writingStyle, " +
            "chapterLengthPreference, maxChapters, autoExtendCount, chatInfluenceWeight, narrativePerson, " +
            "updateMode, unlockHour, unlockMinute, worldInfoEnabled, status, lastCompressedAtChapter, " +
            "lastBibleCompressedAtChapter, pendingBeatsUserEdited, currentArcStartChapter, requestedEndingType, " +
            "finaleEndingType, finalEndingType, cachedChapterCount, cachedLatestChapterNumber, " +
            "cachedLatestChapterTitle, cachedLatestChapterCreatedAt, cachedHasPendingChoice " +
            "FROM stories ORDER BY updatedAt DESC LIMIT 1",
    )
    fun observeLatestStoryLite(): Flow<StoryEntity?>

    @Query("SELECT * FROM stories WHERE id = :id")
    suspend fun getStory(id: String): StoryEntity?

    @Query("SELECT * FROM stories WHERE id = :id")
    fun observeStory(id: String): Flow<StoryEntity?>

    /** 按状态查（追更自动连载扫 serializing，留待 11.1g）。 */
    @Query("SELECT * FROM stories WHERE status = :status")
    suspend fun getStoriesByStatus(status: String): List<StoryEntity>

    /** 全部故事（备份导出 13.6）。章节/角色经 [getAllChapters]/[getAllRoles] 一次性取，避免 N+1。 */
    @Query("SELECT * FROM stories ORDER BY createdAt ASC")
    suspend fun getAllStories(): List<StoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStory(story: StoryEntity)

    @Update
    suspend fun updateStory(story: StoryEntity)

    @Query("DELETE FROM stories WHERE id = :id")
    suspend fun deleteStory(id: String)

    /**
     * 大纲定向更新（storyOutline + currentArcStartChapter），1:1 iOS `generateOutline`/`ensureOutline` 的两处写合并为一次原子写。
     * 定向 UPDATE 而非整行 @Update：避免与并发的 cached_* / status 写互相覆盖（D1 教训）。
     */
    @Query("UPDATE stories SET storyOutline = :storyOutline, currentArcStartChapter = :currentArcStartChapter WHERE id = :id")
    suspend fun updateOutline(id: String, storyOutline: String?, currentArcStartChapter: Int?)

    /**
     * 换弧时的大纲定向更新（卷二 B2）：与 [updateOutline] 同三列 + `arcHistory`，**一条 UPDATE 原子落**。
     * 合并的理由：弧线简史那一行记的是「刚写完的那条弧」，它与「新弧线大纲已就位」必须同生同死——
     * 分两次写会出现「史已追加、新弧却生成失败」的半截态（下次换弧再追一遍 = 同一条弧记两行）。
     * 定向 UPDATE 而非整行 @Update（D1 教训）。
     */
    @Query(
        "UPDATE stories SET storyOutline = :storyOutline, currentArcStartChapter = :currentArcStartChapter, " +
            "arcHistory = :arcHistory WHERE id = :id",
    )
    suspend fun updateOutlineAndArcHistory(
        id: String,
        storyOutline: String?,
        currentArcStartChapter: Int?,
        arcHistory: String?,
    )

    /**
     * 定收尾计划（卷二 J1·终章弧「计划」步）：写 finaleEndingType/Detail + updatedAt，**同一条 UPDATE 把
     * `storyOutline` 置 null**——这正是方法名里 StartingNewArc 的含义：下一次
     * [com.situ.aichat.story.StoryGenerationService.ensureOutline] 必然看到「大纲空 + 有收尾计划」，
     * 于是生成终章弧大纲（图纸 §3.2 判据免加列）。`currentArcStartChapter` **有意不动**：
     * 换终章弧时要靠它给刚结束的那条弧记一行简史（第X–Y章），清了就丢了起点。
     * 定向 UPDATE 避整行 clobber（D1）。
     */
    @Query(
        "UPDATE stories SET finaleEndingType = :finaleEndingType, finaleEndingDetail = :finaleEndingDetail, " +
            "storyOutline = NULL, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateFinalePlanStartingNewArc(
        id: String,
        finaleEndingType: String,
        finaleEndingDetail: String?,
        updatedAt: Long,
    )

    /**
     * 取消收尾计划（卷二 J7）：**一条 UPDATE** 同时清 finale 两列 + storyOutline + currentArcStartChapter。
     * 下一次生成走 GenerateInitial(arc)（arcStart = 当前章·J6）重新起一条普通弧；不试图恢复被终章弧顶掉的旧弧
     * （旧弧已蒸发，弧线简史里留了名）。原子化以杜绝「计划清了、大纲还在」的半截态。
     */
    @Query(
        "UPDATE stories SET finaleEndingType = NULL, finaleEndingDetail = NULL, " +
            "storyOutline = NULL, currentArcStartChapter = NULL, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun clearFinalePlanAndOutline(id: String, updatedAt: Long)

    /**
     * 终章弧末章「转正」（卷二 J1）：**一条 UPDATE 内列间自拷贝**——把 finale 两列搬进 requestedEnding 两列
     * 并清空 finale 两列。原子性是关键：分两句写会出现「copy 成功、清失败」→ 下一章又转正一次 → 连写两章结局。
     * 转正之后完全复用既有结局章管线（结局协议 prompt / 字数 ×1.5 / decideStatus → COMPLETED /
     * finalEndingType 快照 / ST11 失败不清意图）。无收尾计划的书调用它是无害空写（两列本就 NULL）。
     */
    @Query(
        "UPDATE stories SET requestedEndingType = finaleEndingType, requestedEndingDetail = finaleEndingDetail, " +
            "finaleEndingType = NULL, finaleEndingDetail = NULL, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun promoteFinaleToEndingRequest(id: String, updatedAt: Long)

    /**
     * 章节落库后的故事叙事状态定向更新（iOS `materializeChapter` 改的 13 个非 cached_* 字段 + ST8 新增 finalEndingType）。
     * 定向 UPDATE 而非整行 @Update：只写本方法关心的列，不碰 storyOutline/currentArcStartChapter/cached_*
     * 等由 ensureOutline / refreshChapterCaches 等并发路径写的列（D1 教训）。
     * finalEndingType（结局档案徽章·[com.situ.aichat.data.local.entity.StoryEntity.finalEndingType]）随此写入=完结那一刻快照。
     *
     * 故事二期卷一：账本族三列（intimacyLedger / sceneState / sceneLedger）随同一条 UPDATE 原子落库
     * （值在 [com.situ.aichat.story.StoryChapterMaterializer] 内由纯函数算好），与 beats/圣经同一事务语义、无新竞态窗。
     * `pendingBeatsUserEdited` 写**字面 0**而非参数：本方法必写新 beats，新 beats 恒是 AI 预排（用户还没机会改），
     * 语义上没有「写 beats 却仍算用户改过」的合法组合——写死即杜绝接线漏传。
     */
    @Query(
        "UPDATE stories SET storySummary = :storySummary, currentArc = :currentArc, " +
            "characterStates = :characterStates, openThreads = :openThreads, " +
            "pendingChapterBeats = :pendingChapterBeats, pendingBeatsUserEdited = 0, storyBible = :storyBible, " +
            "status = :status, maxChapters = :maxChapters, autoExtendCount = :autoExtendCount, " +
            "requestedEndingType = :requestedEndingType, requestedEndingDetail = :requestedEndingDetail, " +
            "rewriteInstruction = :rewriteInstruction, finalEndingType = :finalEndingType, " +
            "intimacyLedger = :intimacyLedger, sceneState = :sceneState, sceneLedger = :sceneLedger, " +
            "updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateNarrativeState(
        id: String,
        storySummary: String?,
        currentArc: String?,
        characterStates: String?,
        openThreads: String?,
        pendingChapterBeats: String?,
        storyBible: String?,
        status: String,
        maxChapters: Int?,
        autoExtendCount: Int,
        requestedEndingType: String?,
        requestedEndingDetail: String?,
        rewriteInstruction: String?,
        finalEndingType: String?,
        intimacyLedger: String?,
        sceneState: String?,
        sceneLedger: String?,
        updatedAt: Long,
    )

    /**
     * 重写准备的故事字段定向更新（1:1 iOS `prepareRewrite` 改的 6 个非 cached_* 字段）。
     * 同样定向 UPDATE 避整行 clobber（D1）：只回滚圣经 / 恢复摘要 / 设重写指令 / 复位 serializing，
     * 不碰 currentArc/characterStates/maxChapters 等本流程不该动的列。
     *
     * 故事二期卷一：账本族三列一并回滚（两账本按「第N章·」删本章行、sceneState 清空），随同一条 UPDATE 原子；
     * `pendingBeatsUserEdited` 同样写字面 0——本方法必清 beats，标志随宿主一起归零。
     */
    @Query(
        "UPDATE stories SET storyBible = :storyBible, storySummary = :storySummary, " +
            "rewriteInstruction = :rewriteInstruction, status = :status, " +
            "pendingChapterBeats = :pendingChapterBeats, pendingBeatsUserEdited = 0, " +
            "intimacyLedger = :intimacyLedger, sceneState = :sceneState, sceneLedger = :sceneLedger, " +
            "updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateRewriteState(
        id: String,
        storyBible: String?,
        storySummary: String?,
        rewriteInstruction: String?,
        status: String,
        pendingChapterBeats: String?,
        intimacyLedger: String?,
        sceneState: String?,
        sceneLedger: String?,
        updatedAt: Long,
    )

    /**
     * 重写期旧稿接力棒落库（C3·图纸三 §3.1）：`prepareRewrite` **删章之前**调用，先把旧章快照存住
     * （先写后删的写序 = 进程死亡也不丢稿）。单列定向写，不碰 updatedAt 与任何叙事列。
     */
    @Query("UPDATE stories SET pendingRewriteDraftJson = :json WHERE id = :id")
    suspend fun setPendingRewriteDraft(id: String, json: String?)

    /**
     * 接力棒消费后清空（C3）：新章已把快照挂进自己的槽，故事级的中转位随即清掉。
     * 独立一条而不并进 [updateNarrativeState]：不动那条 14 列写的签名与其既有测试。
     * 幂等——重复清空无副作用；万一没清成，下次重写会覆盖，不会误挂（消费守卫另有 rewriteInstruction 把关）。
     */
    @Query("UPDATE stories SET pendingRewriteDraftJson = NULL WHERE id = :id")
    suspend fun clearPendingRewriteDraft(id: String)

    /**
     * 手动续写故事的状态定向更新（1:1 iOS `continueStory` :160-175 改的字段）：status / maxChapters / autoExtendCount /
     * updatedAt，并恒清 cachedHasPendingChoice（iOS 无条件置 false）。定向 UPDATE 避整行 clobber（D1）：
     * 不碰其余 cached_*（count/latest*）与 storyOutline/summary 等并发列。
     * finalEndingType（结局档案徽章）随此定向写：开启续篇（completed 起步）时清 null——续篇本次将重新走向完结，
     * 上一次结局类型不再是本书结局（R1 🟡-2 修正）；非 completed 起步传等值重写（不改）。
     */
    @Query(
        "UPDATE stories SET status = :status, maxChapters = :maxChapters, autoExtendCount = :autoExtendCount, " +
            "finalEndingType = :finalEndingType, updatedAt = :updatedAt, cachedHasPendingChoice = 0 WHERE id = :id",
    )
    suspend fun updateContinueState(
        id: String,
        status: String,
        maxChapters: Int?,
        autoExtendCount: Int,
        finalEndingType: String?,
        updatedAt: Long,
    )

    /**
     * 任务管理器状态机定向更新（status + updatedAt）：1:1 iOS `StoryGenerationTaskManager` 设
     * generating（startGeneration）/ serializing（retryGeneration）/ generationFailed（recoverStuckStories 卡死恢复）。
     * 定向 UPDATE 而非整行 @Update / updateNarrativeState：只写这两列，绝不 clobber 并发写的
     * cached_* / storyOutline / 叙事状态等列（D1 教训）。
     */
    @Query("UPDATE stories SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)

    /**
     * 请求结局定向更新（11.1i 阅读器，1:1 iOS `triggerEndingGeneration` :314-320）：写 requestedEndingType/Detail + status + updatedAt。
     * 定向 UPDATE 而非整行 @Update：只写这 4 列，绝不 clobber 并发写的 cached_* / 叙事状态等列（D1 教训）。
     */
    @Query(
        "UPDATE stories SET requestedEndingType = :requestedEndingType, requestedEndingDetail = :requestedEndingDetail, " +
            "status = :status, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateEndingRequest(
        id: String,
        requestedEndingType: String?,
        requestedEndingDetail: String?,
        status: String,
        updatedAt: Long,
    )

    /**
     * 生成失败定向更新（原 1:1 iOS `StoryGenerationTaskManager.startGeneration` catch :148-153）：**只写 status + updatedAt**。
     *
     * **ST11 拍板①（意图保留）**：原实现叫 `markGenerationFailedClearingRequests`，失败即清三个一次性字段
     * （requestedEndingType/Detail/rewriteInstruction）——用户请求结局遇一次网络失败，重试就静默变成普通续章。
     * 现在失败**一个一次性字段都不清**：意图留着，点「重新生成」即照旧写结局章。
     * 一次性字段的清除只剩三条路（图纸 §3.3）：①生成成功兑现 ②用户做了别的推进动作（[clearEndingRequest]）③删故事。
     * **任何失败路都不许清**——这正是本卷删掉的东西，别再加回来。
     *
     * status 由调用方传 [com.situ.aichat.story.StoryStatus.GENERATION_FAILED]（避免 SQL 魔法串）。定向 UPDATE 避整行 clobber（D1）。
     */
    @Query("UPDATE stories SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markGenerationFailed(id: String, status: String, updatedAt: Long)

    /**
     * 结局意图定向清除（ST11 §3.3「用户覆盖」·**只清 requestedEndingType/Detail 两列**）。
     *
     * 用在：失败残留期间用户做了**其它**推进动作（选择确认 / 让故事自然发展 / 重写末章）——旧的「写结局」意图
     * 被新动作覆盖，须在动作**主写库成功之后**调用（失败路不清：动作没成立，意图不动）。
     * 不碰 rewriteInstruction（那是重写路自己的一次性字段，由 materialize 成功时清）、不碰 status
     * （状态由各动作自己定）。定向 UPDATE 避整行 clobber（D1）。
     */
    @Query(
        "UPDATE stories SET requestedEndingType = NULL, requestedEndingDetail = NULL, " +
            "updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun clearEndingRequest(id: String, updatedAt: Long)

    /**
     * 摘要压缩结果定向更新（1:1 iOS `compressSummaryChainIfNeeded` :128-129 仅写 storySummary + lastCompressedAtChapter）。
     * 定向 UPDATE 避整行 clobber（D1）；不动 updatedAt 等其它列（iOS 压缩亦不刷新 updatedAt）。
     */
    @Query("UPDATE stories SET storySummary = :storySummary, lastCompressedAtChapter = :lastCompressedAtChapter WHERE id = :id")
    suspend fun updateCompressedSummary(id: String, storySummary: String?, lastCompressedAtChapter: Int?)

    /**
     * 圣经结构化压缩结果定向更新（长篇稳定性 L1·契约 FABLE5_STORY_LONGFORM_STABILITY_PROPOSAL §3）：
     * 仅写 storyBible + lastBibleCompressedAtChapter 两列。定向 UPDATE 避整行 clobber（D1）；
     * 与摘要压缩同理不动 updatedAt（后台整理不改书架排序）。
     */
    @Query("UPDATE stories SET storyBible = :storyBible, lastBibleCompressedAtChapter = :lastBibleCompressedAtChapter WHERE id = :id")
    suspend fun updateCompressedBible(id: String, storyBible: String?, lastBibleCompressedAtChapter: Int?)

    /**
     * 设置页更新模式定向更新（11.1j 设置，1:1 iOS StorySettingsSheet 更新模式 + 解锁时间）：updateMode/unlockHour/unlockMinute。
     * 不动 updatedAt（iOS 设置编辑不刷新书架排序）。定向 UPDATE 避整行 clobber（D1）。
     */
    @Query("UPDATE stories SET updateMode = :updateMode, unlockHour = :unlockHour, unlockMinute = :unlockMinute WHERE id = :id")
    suspend fun updateStorySettings(id: String, updateMode: String, unlockHour: Int, unlockMinute: Int)

    /**
     * 设置页「创作设定」七列定向更新（图纸二 D2）：题材/文风/人称/章长/聊天影响/世界观/剧情方向。
     * 与 [updateStorySettings]、[updateStoryMemory] 并列的第三条设置页写路——同样定向 UPDATE 避整行 clobber（D1 惯例），
     * 不动 updatedAt（设置编辑不刷新书架排序，同 [updateStorySettings]）。genre 绝不许写空由 VM 侧保证。
     */
    @Query(
        "UPDATE stories SET genre = :genre, writingStyle = :writingStyle, narrativePerson = :narrativePerson, " +
            "chapterLengthPreference = :chapterLength, chatInfluenceWeight = :chatInfluence, " +
            "worldSetting = :worldSetting, plotDirection = :plotDirection WHERE id = :id",
    )
    suspend fun updateCreativeSettings(
        id: String,
        genre: String,
        writingStyle: String,
        narrativePerson: String,
        chapterLength: Int,
        chatInfluence: String,
        worldSetting: String?,
        plotDirection: String?,
    )

    /** 故事圣经（worldBible）列级定向写（14.6d 独立编辑屏；空→null）。只碰 storyBible 一列，与设置页草稿/生成路径互不覆盖。 */
    @Query("UPDATE stories SET storyBible = :storyBible WHERE id = :id")
    suspend fun updateStoryBible(id: String, storyBible: String?)

    /**
     * 档案八节的**用户定向写族**（故事二期卷二 J3·书页档案 Tab 的统一编辑页保存口）：一列 + updatedAt 一条
     * UPDATE，与生成侧的多列写互不覆盖（D1 惯例）。空白由调用方归 null。与生成侧并发时 last-write-wins。
     * [updateStoryOutlineUserEdit] 与生成侧 [updateOutline] **有意分家**：用户改大纲绝不动 currentArcStartChapter
     * （弧起点是变速箱的锚，只该由换弧路径写）。圣经复用既有 [updateStoryBible]（不刷 updatedAt，保持原语义）。
     */
    @Query("UPDATE stories SET storyOutline = :text, updatedAt = :now WHERE id = :id")
    suspend fun updateStoryOutlineUserEdit(id: String, text: String?, now: Long)

    @Query("UPDATE stories SET currentArc = :text, updatedAt = :now WHERE id = :id")
    suspend fun updateCurrentArcUserEdit(id: String, text: String?, now: Long)

    @Query("UPDATE stories SET intimacyLedger = :text, updatedAt = :now WHERE id = :id")
    suspend fun updateIntimacyLedger(id: String, text: String?, now: Long)

    @Query("UPDATE stories SET sceneLedger = :text, updatedAt = :now WHERE id = :id")
    suspend fun updateSceneLedger(id: String, text: String?, now: Long)

    @Query("UPDATE stories SET sceneState = :text, updatedAt = :now WHERE id = :id")
    suspend fun updateSceneState(id: String, text: String?, now: Long)

    @Query("UPDATE stories SET storySummary = :text, updatedAt = :now WHERE id = :id")
    suspend fun updateStorySummaryUserEdit(id: String, text: String?, now: Long)

    @Query("UPDATE stories SET characterStates = :text, updatedAt = :now WHERE id = :id")
    suspend fun updateCharacterStates(id: String, text: String?, now: Long)

    @Query("UPDATE stories SET openThreads = :text, updatedAt = :now WHERE id = :id")
    suspend fun updateOpenThreads(id: String, text: String?, now: Long)

    /**
     * 世界观设定参与生成开关列级定向写（ST5·开关行 UI 归 ST7c）。只碰 worldInfoEnabled 一列，
     * 与生成路径 / 缓存重算等并发写互不覆盖（D1 惯例）。
     */
    @Query("UPDATE stories SET worldInfoEnabled = :enabled WHERE id = :id")
    suspend fun setWorldInfoEnabled(id: String, enabled: Boolean)

    /** 自定义提示词 JSON 列级定向写（ST7c·设置页事后编辑；空→null 走预设默认）。只碰 customPromptsJson 一列。 */
    @Query("UPDATE stories SET customPromptsJson = :json WHERE id = :id")
    suspend fun updateCustomPrompts(id: String, json: String?)

    /**
     * 章级节拍 + 「用户改过」标志定向写（故事二期卷一·提案 §3.3；写入口 = 卷三导演台）。
     * 两列一条 UPDATE 原子：标志与它描述的那份 beats 绝不允许出现「新 beats 配旧标志」的半截态。
     * 复位（写新 beats / 清 beats 时归 false）不走这里，由 [updateNarrativeState] / [updateRewriteState] 的字面 0 承担。
     */
    @Query("UPDATE stories SET pendingChapterBeats = :beats, pendingBeatsUserEdited = :edited WHERE id = :id")
    suspend fun updateChapterBeatsUserEdited(id: String, beats: String?, edited: Boolean)

    /**
     * 缓存字段定向更新（1:1 iOS `refreshChapterCaches` 写 5 列）。
     * 定向 UPDATE 而非整行 @Update：避免与并发的 status/summary/bible 写互相覆盖（D1 教训）。
     */
    @Query(
        "UPDATE stories SET cachedChapterCount = :count, cachedLatestChapterNumber = :latestNumber, " +
            "cachedLatestChapterTitle = :latestTitle, cachedLatestChapterCreatedAt = :latestCreatedAt, " +
            "cachedHasPendingChoice = :hasPendingChoice WHERE id = :id",
    )
    suspend fun updateChapterCaches(
        id: String,
        count: Int,
        latestNumber: Int?,
        latestTitle: String?,
        latestCreatedAt: Long?,
        hasPendingChoice: Boolean,
    )

    // ── Chapters ──

    @Query("SELECT * FROM story_chapters WHERE storyId = :storyId ORDER BY chapterNumber ASC")
    suspend fun getChapters(storyId: String): List<StoryChapterEntity>

    /**
     * 章节元数据升序（17 列真读 + 正文位常量空串）：`previousDraftJson` 缺列落实体默认 null，MISMATCH 有意——
     * 消费面禁读这两列（图纸 2026-08-03 卷二 §9）。**`'' AS content` 不物化正文**：Room 不许非空列缺席
     * （施工日志 D-2），故以常量占位兑现图纸「content 落默认空串」的语义；省的是正文字符串的堆分配与拷贝
     * （主收益·MB 级），content 之后各列的定位仍会经过其溢出链（复核 R1 措辞校正——页 IO 靠 page cache 摊薄）。
     */
    @SuppressWarnings(androidx.room.RoomWarnings.QUERY_MISMATCH)
    @Query(
        "SELECT id, storyId, chapterNumber, title, teaser, createdAt, '' AS content, mood, scenes, hasChoice, " +
            "choicePrompt, choiceOptions, userChoice, aiSuggestedEnding, choiceMadeAt, chapterSummary, unlockAt, " +
            "userRating FROM story_chapters WHERE storyId = :storyId ORDER BY chapterNumber ASC",
    )
    suspend fun getChapterMetas(storyId: String): List<StoryChapterEntity>

    /** 最新一章的元数据单行（同 [getChapterMetas] 的 17 列 + 空串正文位）：禁读 content/previousDraftJson（图纸卷二 §9）。 */
    @SuppressWarnings(androidx.room.RoomWarnings.QUERY_MISMATCH)
    @Query(
        "SELECT id, storyId, chapterNumber, title, teaser, createdAt, '' AS content, mood, scenes, hasChoice, " +
            "choicePrompt, choiceOptions, userChoice, aiSuggestedEnding, choiceMadeAt, chapterSummary, unlockAt, " +
            "userRating FROM story_chapters WHERE storyId = :storyId ORDER BY chapterNumber DESC LIMIT 1",
    )
    suspend fun getLatestChapterMeta(storyId: String): StoryChapterEntity?

    /** 「升序列表里的前一项」元数据单行（章号不连续也对·同上列清单）：禁读 content/previousDraftJson（图纸卷二 §9）。 */
    @SuppressWarnings(androidx.room.RoomWarnings.QUERY_MISMATCH)
    @Query(
        "SELECT id, storyId, chapterNumber, title, teaser, createdAt, '' AS content, mood, scenes, hasChoice, " +
            "choicePrompt, choiceOptions, userChoice, aiSuggestedEnding, choiceMadeAt, chapterSummary, unlockAt, " +
            "userRating FROM story_chapters WHERE storyId = :storyId AND chapterNumber < :beforeNumber " +
            "ORDER BY chapterNumber DESC LIMIT 1",
    )
    suspend fun getChapterMetaBefore(storyId: String, beforeNumber: Int): StoryChapterEntity?

    /** 最新一章**全列**（含正文）：续章 prompt 要上一章正文，故此处不投影，砍的是「拉整本书取 lastOrNull」。 */
    @Query("SELECT * FROM story_chapters WHERE storyId = :storyId ORDER BY chapterNumber DESC LIMIT 1")
    suspend fun getLatestChapter(storyId: String): StoryChapterEntity?

    /** 全部章节（备份导出 13.6；含正文）。按故事 + 章号升序，导出端按 storyId 分组。 */
    @Query("SELECT * FROM story_chapters ORDER BY storyId ASC, chapterNumber ASC")
    suspend fun getAllChapters(): List<StoryChapterEntity>

    /** 全表章节数（性能采集规模数 `storyChapters`·图纸 §3.2·只 COUNT 不读正文）。 */
    @Query("SELECT COUNT(*) FROM story_chapters")
    suspend fun countAllChapters(): Int

    /** 全部故事角色（备份导出 13.6）。 */
    @Query("SELECT * FROM story_character_roles")
    suspend fun getAllRoles(): List<StoryCharacterRoleEntity>

    @Query("SELECT * FROM story_chapters WHERE id = :id")
    suspend fun getChapter(id: String): StoryChapterEntity?

    /** 缓存重算专用投影（不含正文）。 */
    @Query(
        "SELECT chapterNumber, title, createdAt, hasChoice, userChoice FROM story_chapters " +
            "WHERE storyId = :storyId",
    )
    suspend fun getChapterCacheRows(storyId: String): List<StoryChapterCacheRow>

    /** 前情回顾专用投影（不含正文）：按章号升序取所有章的摘要，供续章 prompt 滑动窗口裁剪。 */
    @Query(
        "SELECT chapterNumber, chapterSummary FROM story_chapters " +
            "WHERE storyId = :storyId ORDER BY chapterNumber ASC",
    )
    suspend fun getChapterSummaries(storyId: String): List<StoryChapterSummaryRow>

    /**
     * 待解锁章节（unlockAt 在未来）连 story 标题（不含正文），供解锁通知重排（11.1g-1）。
     * unlockAt 仅追更章在 materialize 时计算，故此查询天然只命中追更未解锁章。
     */
    @Query(
        "SELECT c.storyId AS storyId, s.title AS storyTitle, c.chapterNumber AS chapterNumber, " +
            "c.title AS chapterTitle, c.unlockAt AS unlockAt FROM story_chapters c " +
            "JOIN stories s ON c.storyId = s.id WHERE c.unlockAt IS NOT NULL AND c.unlockAt > :now",
    )
    suspend fun getFutureUnlockChapters(now: Long): List<StoryUnlockRow>

    /**
     * 某故事全部章号（删除通路撤解锁闹钟用：闹钟 key 含章号，须在级联删库**前**捕获——删后章行已不在，
     * 见 [com.situ.aichat.story.StoryDeleter]）。
     */
    @Query("SELECT chapterNumber FROM story_chapters WHERE storyId = :storyId")
    suspend fun getChapterNumbers(storyId: String): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: StoryChapterEntity)

    @Update
    suspend fun updateChapter(chapter: StoryChapterEntity)

    /**
     * 落选择定向更新（11.1i 阅读器：用户选择 / 跳过选择）：只写 userChoice + choiceMadeAt 两列。
     * 定向 UPDATE 而非整行 @Update：阅读器只改这两列，绝不 clobber 同章并发可能在写的 content/mood/unlockAt 等（D1 教训）。
     */
    @Query("UPDATE story_chapters SET userChoice = :userChoice, choiceMadeAt = :choiceMadeAt WHERE id = :id")
    suspend fun updateUserChoice(id: String, userChoice: String?, choiceMadeAt: Long?)

    /**
     * 本章小结列级写（C4·图纸三 §3.2）：只写 chapterSummary 一列。
     * 定向 UPDATE 而非整行 @Update：用户改小结时同章可能正被生成链写别的列（D1 教训，同 [updateUserChoice]）。
     * 空白输入由调用方归 null（= 回落「无小结」，前情滑窗的 mapNotNull 自然跳过该章）。
     */
    @Query("UPDATE story_chapters SET chapterSummary = :summary WHERE id = :id")
    suspend fun updateChapterSummary(id: String, summary: String?)

    /**
     * 读者三档快评列级写（故事二期卷一·提案 §5.2；写入口 = 卷三章末快评行）。只写 userRating 一列，
     * 与生成链并发写别的列互不覆盖（D1 惯例，同 [updateChapterSummary]）。传 null = 撤销评分。
     * 取值域 1/2/3 由调用方保证；注入端另有 `in 1..3` 门（畸形值静默不注入，不 clamp）。
     */
    @Query("UPDATE story_chapters SET userRating = :rating WHERE id = :id")
    suspend fun updateChapterRating(id: String, rating: Int?)

    /**
     * 「换回上一版」互换（C3·图纸三 §3.1）：一条 UPDATE 原子写 13 列——12 个内容列取上一版的值，
     * `previousDraftJson` 槽同步写成**互换前**的当前章快照，故两版可反复来回切。
     * 取值一律来自纯函数 [com.situ.aichat.story.StoryChapterDraft.swapApplied]，调用方不得自行拼装。
     * 定向 UPDATE 而非整行 @Update：轨道列（id/storyId/chapterNumber/createdAt/unlockAt）与并发列绝不 clobber（D1）。
     */
    @Query(
        "UPDATE story_chapters SET title = :title, teaser = :teaser, content = :content, mood = :mood, " +
            "scenes = :scenes, hasChoice = :hasChoice, choicePrompt = :choicePrompt, " +
            "choiceOptions = :choiceOptions, userChoice = :userChoice, choiceMadeAt = :choiceMadeAt, " +
            "aiSuggestedEnding = :aiSuggestedEnding, chapterSummary = :chapterSummary, " +
            "previousDraftJson = :previousDraftJson WHERE id = :id",
    )
    @Suppress("LongParameterList")
    suspend fun swapChapterDraft(
        id: String,
        title: String,
        teaser: String?,
        content: String,
        mood: String,
        scenes: String?,
        hasChoice: Boolean,
        choicePrompt: String?,
        choiceOptions: String?,
        userChoice: String?,
        choiceMadeAt: Long?,
        aiSuggestedEnding: Boolean,
        chapterSummary: String?,
        previousDraftJson: String?,
    )

    /** 清空某故事所有章节的解锁时间（11.1j 设置：追更→自由时已锁章节立即解锁，1:1 iOS 切回自由清 unlockAt）。 */
    @Query("UPDATE story_chapters SET unlockAt = NULL WHERE storyId = :storyId")
    suspend fun clearChapterUnlocks(storyId: String)

    @Query("DELETE FROM story_chapters WHERE id = :id")
    suspend fun deleteChapter(id: String)

    // ── Character roles ──

    @Query("SELECT * FROM story_character_roles WHERE storyId = :storyId")
    suspend fun getRoles(storyId: String): List<StoryCharacterRoleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoles(roles: List<StoryCharacterRoleEntity>)

    @Query("DELETE FROM story_character_roles WHERE storyId = :storyId")
    suspend fun deleteRoles(storyId: String)

    /**
     * 删单个角色行（图纸二 D1：设定页「从本书移出」）。按 PK 删，只影响这一行；
     * 更新单个角色走 [insertRoles]（REPLACE 按 PK 覆盖），故 roles 侧只需补这一条 DELETE。
     */
    @Query("DELETE FROM story_character_roles WHERE id = :roleId")
    suspend fun deleteRole(roleId: String)

    /**
     * 把关联某 AI 角色的故事角色行「摘链」为纯故事角色（characterId 置 null，R6#3）：
     * 删 AI 角色时调用——行保留（roleName/roleDescription 仍驱动生成），只断开悬空引用，
     * 否则角色段采集查不到人、人设/声线注入静默丢失。
     */
    @Query("UPDATE story_character_roles SET characterId = NULL WHERE characterId = :characterUuid")
    suspend fun detachCharacterFromRoles(characterUuid: String)
}
