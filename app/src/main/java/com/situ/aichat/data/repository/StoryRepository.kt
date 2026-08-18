package com.situ.aichat.data.repository

import androidx.room.withTransaction
import com.situ.aichat.data.local.AppDatabase
import com.situ.aichat.data.local.dao.StoryChapterCacheRow
import com.situ.aichat.data.local.dao.StoryChapterSummaryRow
import com.situ.aichat.data.local.dao.StoryDao
import com.situ.aichat.data.local.dao.StoryUnlockRow
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.story.StoryChapterCacheCalculator
import com.situ.aichat.story.StoryStateTransitions
import com.situ.aichat.story.StoryStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 故事 (M08) 聚合读写，对应 iOS 用 SwiftData 持久化 `Story` + 级联的 `StoryChapter`/`StoryCharacterRole`。
 *
 * Value-add over the raw DAO（iOS model 隐含做的事）：
 * - [refreshChapterCaches] 复刻 iOS `Story.refreshChapterCaches`：每次增删章/落选择/重写后重算 5 个 cached_*
 *   并定向写回，书架/卡片只读缓存列、不 join 章节表（spec §3.1 / §4#4）。
 *
 * 章节排序（按 chapterNumber）/解锁/角色排序等纯展示逻辑由调用方算，不入库。
 */
@Singleton
class StoryRepository @Inject constructor(
    private val dao: StoryDao,
    // 落选择三步写包一个事务（图纸卷二 §3.4）：中途崩溃全回滚 + 三次表失效合并为一次（照 CharacterRepository 姿势）。
    private val db: AppDatabase,
) {

    // ── Stories ──

    fun observeStories(): Flow<List<StoryEntity>> = dao.observeStoriesByUpdatedAt()

    /** 书架/档案全览的轻列投影订阅（29 列·排除列落实体默认值，见 [StoryDao.observeStoriesLite]）。 */
    fun observeStoriesLite(): Flow<List<StoryEntity>> = dao.observeStoriesLite()

    /** 动态页最近一本的轻列投影订阅（同上 29 列 + LIMIT 1，见 [StoryDao.observeLatestStoryLite]）。 */
    fun observeLatestStoryLite(): Flow<StoryEntity?> = dao.observeLatestStoryLite()
    fun observeStory(id: String): Flow<StoryEntity?> = dao.observeStory(id)
    suspend fun getStory(id: String): StoryEntity? = dao.getStory(id)
    suspend fun getStoriesByStatus(status: String): List<StoryEntity> = dao.getStoriesByStatus(status)
    suspend fun insertStory(story: StoryEntity) = dao.insertStory(story)
    suspend fun updateStory(story: StoryEntity) = dao.updateStory(story)
    suspend fun deleteStory(id: String) = dao.deleteStory(id)

    /** 大纲定向写（storyOutline + currentArcStartChapter），避整行 clobber（见 [StoryDao.updateOutline]）。 */
    suspend fun updateOutline(id: String, storyOutline: String?, currentArcStartChapter: Int?) =
        dao.updateOutline(id, storyOutline, currentArcStartChapter)

    /** 换弧时的大纲 + 弧线简史定向写（一条 UPDATE 原子落，见 [StoryDao.updateOutlineAndArcHistory]）。 */
    suspend fun updateOutlineAndArcHistory(
        id: String,
        storyOutline: String?,
        currentArcStartChapter: Int?,
        arcHistory: String?,
    ) = dao.updateOutlineAndArcHistory(id, storyOutline, currentArcStartChapter, arcHistory)

    /** 定收尾计划定向写（含 storyOutline 置 null 起新终章弧，见 [StoryDao.updateFinalePlanStartingNewArc]）。 */
    suspend fun updateFinalePlanStartingNewArc(
        id: String,
        finaleEndingType: String,
        finaleEndingDetail: String?,
        updatedAt: Long,
    ) = dao.updateFinalePlanStartingNewArc(id, finaleEndingType, finaleEndingDetail, updatedAt)

    /** 取消收尾计划定向写（清 finale 两列 + 大纲 + 弧起点，见 [StoryDao.clearFinalePlanAndOutline]）。 */
    suspend fun clearFinalePlanAndOutline(id: String, updatedAt: Long) =
        dao.clearFinalePlanAndOutline(id, updatedAt)

    /** 终章弧末章转正定向写（finale 两列 → requestedEnding 两列，一条 UPDATE 原子，见 [StoryDao.promoteFinaleToEndingRequest]）。 */
    suspend fun promoteFinaleToEndingRequest(id: String, updatedAt: Long) =
        dao.promoteFinaleToEndingRequest(id, updatedAt)

    /**
     * 章节落库后的叙事状态定向写（含 ST8 finalEndingType + 卷一账本族三列 + pendingBeatsUserEdited 复位），
     * 避整行 clobber（见 [StoryDao.updateNarrativeState]）。
     */
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
    ) = dao.updateNarrativeState(
        id, storySummary, currentArc, characterStates, openThreads, pendingChapterBeats, storyBible,
        status, maxChapters, autoExtendCount, requestedEndingType, requestedEndingDetail, rewriteInstruction,
        finalEndingType, intimacyLedger, sceneState, sceneLedger, updatedAt,
    )

    /** 重写准备的故事字段定向写（含卷一账本族三列回滚），避整行 clobber（见 [StoryDao.updateRewriteState]）。 */
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
    ) = dao.updateRewriteState(
        id, storyBible, storySummary, rewriteInstruction, status, pendingChapterBeats,
        intimacyLedger, sceneState, sceneLedger, updatedAt,
    )

    /** 章级节拍 + 「用户改过」标志定向写（卷三导演台的写入口，见 [StoryDao.updateChapterBeatsUserEdited]）。 */
    suspend fun updateChapterBeatsUserEdited(id: String, beats: String?, edited: Boolean) =
        dao.updateChapterBeatsUserEdited(id, beats, edited)

    /** 重写期旧稿接力棒落库（C3·删章**之前**调用，见 [StoryDao.setPendingRewriteDraft]）。 */
    suspend fun setPendingRewriteDraft(id: String, json: String?) = dao.setPendingRewriteDraft(id, json)

    /** 接力棒消费后清空（C3·新章已挂槽，见 [StoryDao.clearPendingRewriteDraft]）。 */
    suspend fun clearPendingRewriteDraft(id: String) = dao.clearPendingRewriteDraft(id)

    /** 摘要压缩结果定向写（storySummary + lastCompressedAtChapter），避整行 clobber（见 [StoryDao.updateCompressedSummary]）。 */
    suspend fun updateCompressedSummary(id: String, storySummary: String?, lastCompressedAtChapter: Int?) =
        dao.updateCompressedSummary(id, storySummary, lastCompressedAtChapter)

    /** 圣经结构化压缩结果定向写（storyBible + lastBibleCompressedAtChapter·长篇稳定性 L1，见 [StoryDao.updateCompressedBible]）。 */
    suspend fun updateCompressedBible(id: String, storyBible: String?, lastBibleCompressedAtChapter: Int?) =
        dao.updateCompressedBible(id, storyBible, lastBibleCompressedAtChapter)

    /** 设置页更新模式 + 解锁时间定向写（D1 安全，见 [StoryDao.updateStorySettings]）。 */
    suspend fun updateStorySettings(id: String, updateMode: String, unlockHour: Int, unlockMinute: Int) =
        dao.updateStorySettings(id, updateMode, unlockHour, unlockMinute)

    /** 设置页「创作设定」七列定向写（图纸二 D2，见 [StoryDao.updateCreativeSettings]）。 */
    suspend fun updateCreativeSettings(
        id: String,
        genre: String,
        writingStyle: String,
        narrativePerson: String,
        chapterLength: Int,
        chatInfluence: String,
        worldSetting: String?,
        plotDirection: String?,
    ) = dao.updateCreativeSettings(id, genre, writingStyle, narrativePerson, chapterLength, chatInfluence, worldSetting, plotDirection)

    /** 故事圣经列级写（14.6d 独立编辑屏；空→null）。 */
    suspend fun updateStoryBible(id: String, storyBible: String?) = dao.updateStoryBible(id, storyBible)

    // ── 档案八节的用户定向写（卷二 J3·书页统一编辑页；单列 + updatedAt，见 [StoryDao] 同名族 KDoc）──

    suspend fun updateStoryOutlineUserEdit(id: String, text: String?, now: Long) =
        dao.updateStoryOutlineUserEdit(id, text, now)

    suspend fun updateCurrentArcUserEdit(id: String, text: String?, now: Long) =
        dao.updateCurrentArcUserEdit(id, text, now)

    suspend fun updateIntimacyLedger(id: String, text: String?, now: Long) = dao.updateIntimacyLedger(id, text, now)

    suspend fun updateSceneLedger(id: String, text: String?, now: Long) = dao.updateSceneLedger(id, text, now)

    suspend fun updateSceneState(id: String, text: String?, now: Long) = dao.updateSceneState(id, text, now)

    suspend fun updateStorySummaryUserEdit(id: String, text: String?, now: Long) =
        dao.updateStorySummaryUserEdit(id, text, now)

    suspend fun updateCharacterStates(id: String, text: String?, now: Long) = dao.updateCharacterStates(id, text, now)

    suspend fun updateOpenThreads(id: String, text: String?, now: Long) = dao.updateOpenThreads(id, text, now)

    /** 世界观设定参与生成开关列级写（ST5，见 [StoryDao.setWorldInfoEnabled]；开关行 UI 归 ST7c）。 */
    suspend fun setWorldInfoEnabled(id: String, enabled: Boolean) = dao.setWorldInfoEnabled(id, enabled)

    /** 自定义提示词 JSON 列级写（ST7c·设置页事后编辑，见 [StoryDao.updateCustomPrompts]）。 */
    suspend fun updateCustomPrompts(id: String, json: String?) = dao.updateCustomPrompts(id, json)

    /** 清空某故事全部章节解锁时间（追更→自由，见 [StoryDao.clearChapterUnlocks]）。 */
    suspend fun clearChapterUnlocks(storyId: String) = dao.clearChapterUnlocks(storyId)

    /** 手动续写故事的状态定向写（status/maxChapters/autoExtendCount/finalEndingType/updatedAt + 清 cachedHasPendingChoice），见 [StoryDao.updateContinueState]。 */
    suspend fun updateContinueState(
        id: String,
        status: String,
        maxChapters: Int?,
        autoExtendCount: Int,
        finalEndingType: String?,
        updatedAt: Long,
    ) = dao.updateContinueState(id, status, maxChapters, autoExtendCount, finalEndingType, updatedAt)

    /** 任务管理器状态机定向写（status + updatedAt），D1 安全（见 [StoryDao.updateStatus]）。 */
    suspend fun updateStatus(id: String, status: String, updatedAt: Long) = dao.updateStatus(id, status, updatedAt)

    /**
     * 请求结局定向写（11.1i 阅读器，1:1 iOS `triggerEndingGeneration`）：requestedEndingType/Detail + status=serializing + updatedAt。
     * detail 仅 custom 结局有值（调用方按类型传），D1 安全（见 [StoryDao.updateEndingRequest]）。
     */
    suspend fun updateEndingRequest(id: String, type: String, detail: String?, status: String, updatedAt: Long) =
        dao.updateEndingRequest(id, type, detail, status, updatedAt)

    /**
     * 生成失败定向写（**只写 status + updatedAt**），D1 安全（见 [StoryDao.markGenerationFailed]）。
     * ST11 拍板①：失败不清任何一次性字段——结局意图留到重试（原 `markGenerationFailedClearingRequests` 的清除已删）。
     */
    suspend fun markGenerationFailed(id: String, status: String, updatedAt: Long) =
        dao.markGenerationFailed(id, status, updatedAt)

    /**
     * 结局意图定向清除（ST11 §3.3「用户覆盖」），D1 安全（见 [StoryDao.clearEndingRequest]）。
     * 只在用户做了**其它**推进动作、且该动作**主写库已成功**之后调用。
     */
    suspend fun clearEndingRequest(id: String, updatedAt: Long) = dao.clearEndingRequest(id, updatedAt)

    // ── Chapters ──

    suspend fun getChapters(storyId: String): List<StoryChapterEntity> = dao.getChapters(storyId)
    suspend fun getChapter(id: String): StoryChapterEntity? = dao.getChapter(id)

    /** 章节元数据升序（17 列投影·**禁读 content/previousDraftJson**，见 [StoryDao.getChapterMetas]）。 */
    suspend fun getChapterMetas(storyId: String): List<StoryChapterEntity> = dao.getChapterMetas(storyId)

    /** 最新一章元数据单行（同上 17 列·禁读排除列，见 [StoryDao.getLatestChapterMeta]）。 */
    suspend fun getLatestChapterMeta(storyId: String): StoryChapterEntity? = dao.getLatestChapterMeta(storyId)

    /** 升序列表里的前一项元数据单行（章号不连续也对·禁读排除列，见 [StoryDao.getChapterMetaBefore]）。 */
    suspend fun getChapterMetaBefore(storyId: String, beforeNumber: Int): StoryChapterEntity? =
        dao.getChapterMetaBefore(storyId, beforeNumber)

    /** 最新一章全列（含正文·续章 prompt 用，见 [StoryDao.getLatestChapter]）。 */
    suspend fun getLatestChapter(storyId: String): StoryChapterEntity? = dao.getLatestChapter(storyId)

    /** 章摘要投影（升序，不含正文）：供续章「前情回顾」与摘要压缩取材（见 [StoryDao.getChapterSummaries]）。 */
    suspend fun getChapterSummaries(storyId: String): List<StoryChapterSummaryRow> = dao.getChapterSummaries(storyId)

    /** 待解锁章节（unlockAt 在未来）连 story 标题：供解锁通知重排（见 [StoryDao.getFutureUnlockChapters]）。 */
    suspend fun getFutureUnlockChapters(now: Long): List<StoryUnlockRow> = dao.getFutureUnlockChapters(now)

    /** 某故事全部章号（删除通路撤解锁闹钟·删库前捕获，见 [StoryDao.getChapterNumbers]）。 */
    suspend fun getChapterNumbers(storyId: String): List<Int> = dao.getChapterNumbers(storyId)
    suspend fun insertChapter(chapter: StoryChapterEntity) = dao.insertChapter(chapter)
    suspend fun updateChapter(chapter: StoryChapterEntity) = dao.updateChapter(chapter)
    suspend fun deleteChapter(id: String) = dao.deleteChapter(id)

    /** 本章小结列级写（C4·空白由调用方归 null，见 [StoryDao.updateChapterSummary]）。 */
    suspend fun updateChapterSummary(id: String, summary: String?) = dao.updateChapterSummary(id, summary)

    /** 读者三档快评列级写（卷一建列·写入口归卷三章末快评行，见 [StoryDao.updateChapterRating]）。 */
    suspend fun updateChapterRating(id: String, rating: Int?) = dao.updateChapterRating(id, rating)

    /**
     * 「换回上一版」互换（C3）：13 列一条 UPDATE 原子写，取值来自
     * [com.situ.aichat.story.StoryChapterDraft.swapApplied] 的产物（见 [StoryDao.swapChapterDraft]）。
     * 只换正文与选项态，**绝不触发生成、绝不动故事级叙事字段**（图纸三 §0.2-5 已裁决并写进确认弹窗文案）。
     */
    suspend fun swapChapterDraft(swapped: StoryChapterEntity) = dao.swapChapterDraft(
        id = swapped.id,
        title = swapped.title,
        teaser = swapped.teaser,
        content = swapped.content,
        mood = swapped.mood,
        scenes = swapped.scenes,
        hasChoice = swapped.hasChoice,
        choicePrompt = swapped.choicePrompt,
        choiceOptions = swapped.choiceOptions,
        userChoice = swapped.userChoice,
        choiceMadeAt = swapped.choiceMadeAt,
        aiSuggestedEnding = swapped.aiSuggestedEnding,
        chapterSummary = swapped.chapterSummary,
        previousDraftJson = swapped.previousDraftJson,
    )

    /**
     * 落用户选择（11.1i 阅读器，1:1 iOS `commitPendingChoice` :203-229）：定向写本章 userChoice + choiceMadeAt，
     * 可选把故事置 serializing（普通选择 / 「让故事自然发展」置 true；结局前「跳过选择」置 false——状态由结局请求接着设），
     * 再重算缓存（cachedHasPendingChoice 随之清零）。三步定向写避整行 clobber（D1），顺序与 iOS 单次 save 等效。
     *
     * **三步一事务**（图纸卷二 §3.4）：中途崩溃全回滚（不再留「选择已写、状态没变」的半截态），
     * 三次表失效也合并为一次广播（书架/阅读器的宽行观察者少点两次重查）。步骤内容/顺序/参数逐字节未变；
     * 观测闸 [StoryStateTransitions.check] 留在事务外（纯观测不写库，抛出时一行都不该已写）。
     *
     * @param fromStatus 调用点手头的故事旧状态（[StoryStateTransitions] 观测闸用；null = 手头没有 → 放行）
     */
    suspend fun commitUserChoice(
        storyId: String,
        chapterId: String,
        choice: String,
        nowMillis: Long,
        setSerializing: Boolean = true,
        fromStatus: String? = null,
    ) {
        if (setSerializing) {
            StoryStateTransitions.check(fromStatus, StoryStatus.SERIALIZING, "StoryRepository.commitUserChoice")
        }
        db.withTransaction {
            dao.updateUserChoice(chapterId, choice, nowMillis)
            if (setSerializing) {
                dao.updateStatus(storyId, StoryStatus.SERIALIZING, nowMillis)
            }
            refreshChapterCaches(storyId)
        }
    }

    /**
     * 撤回已答选择（图纸 2026-08-06「已存走向」态 B）：userChoice/choiceMadeAt 清 null + 刷缓存；
     * [revertToWaitingChoice]=true（调用方已判「本章有选项 && fresh.status==SERIALIZING」）时同事务把书转回
     * waitingChoice——不回转则追更自动路（`getStoriesByStatus(SERIALIZING)`）会对重新待答的选择裸跑生成。
     * SERIALIZING→WAITING_CHOICE 是转换表既有合法边（自动连载路先例）；PAUSED/GENERATION_FAILED 一律不回转。
     *
     * 与 [commitUserChoice] 同款事务姿势：写与刷缓存一个事务（中途崩溃全回滚），观测闸
     * [StoryStateTransitions.check] 留在事务外、仅回转档调。**唯一调用方** =
     * [com.situ.aichat.story.StoryDirectionEditor].withdraw（阅读器导演台「撤回走向」）。
     */
    suspend fun withdrawUserChoice(
        storyId: String,
        chapterId: String,
        revertToWaitingChoice: Boolean,
        fromStatus: String?,
        nowMillis: Long,
    ) {
        if (revertToWaitingChoice) {
            StoryStateTransitions.check(fromStatus, StoryStatus.WAITING_CHOICE, "StoryRepository.withdrawUserChoice")
        }
        db.withTransaction {
            dao.updateUserChoice(chapterId, null, null)
            if (revertToWaitingChoice) dao.updateStatus(storyId, StoryStatus.WAITING_CHOICE, nowMillis)
            refreshChapterCaches(storyId)
        }
    }

    // ── Character roles ──

    suspend fun getRoles(storyId: String): List<StoryCharacterRoleEntity> = dao.getRoles(storyId)
    suspend fun insertRoles(roles: List<StoryCharacterRoleEntity>) = dao.insertRoles(roles)

    /** 删单个角色行（图纸二 D1「从本书移出」，见 [StoryDao.deleteRole]）。 */
    suspend fun deleteRole(roleId: String) = dao.deleteRole(roleId)

    /**
     * 重算并定向写回故事缓存字段（1:1 iOS `refreshChapterCaches` :225-245）。
     *
     * @param explicitLatest 重写删章后传「删除前的上一章」算 latest（spec §4#4）；否则从当前章节投影推。
     */
    suspend fun refreshChapterCaches(storyId: String, explicitLatest: StoryChapterCacheRow? = null) {
        val rows = dao.getChapterCacheRows(storyId)
        val caches = StoryChapterCacheCalculator.compute(rows, explicitLatest)
        dao.updateChapterCaches(
            id = storyId,
            count = caches.count,
            latestNumber = caches.latestNumber,
            latestTitle = caches.latestTitle,
            latestCreatedAt = caches.latestCreatedAt,
            hasPendingChoice = caches.hasPendingChoice,
        )
    }
}
