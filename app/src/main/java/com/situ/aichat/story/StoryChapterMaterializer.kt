package com.situ.aichat.story

import android.util.Log
import com.situ.aichat.data.local.dao.StoryChapterCacheRow
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.repository.StoryRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 故事章节落库器（从 [StoryGenerationService] 抽出·1:1 iOS +Materialization.swift:9-144 落库簇）。
 *
 * 承载「一章生成结果 → Room」的全部持久化编排：[materializeChapter]（清思考标签 → 插章 → 12 叙事字段降级写 +
 * 圣经追加 + 状态机 + 缓存刷新）/ [prepareRewrite]（删最新章 + 回滚圣经 + 恢复摘要 + 复位 serializing，供重写）。
 * D1 安全：叙事字段经 [StoryRepository.updateNarrativeState] 定向写、缓存经 refreshChapterCaches 定向写，两组列不互相
 * clobber。状态机/解锁时间/圣经追加纯函数在 [StoryGenerationPolicy]；编排薄层在此。注入 [StoryRepository]（@Singleton
 * 单例·与 [StoryGenerationService] 同实例）。
 */
@Singleton
class StoryChapterMaterializer @Inject constructor(
    private val storyRepository: StoryRepository,
) {
    /**
     * 把一章生成结果落库（1:1 iOS `materializeChapter` :9-144）：清思考标签 → 插入 [StoryChapterEntity]
     * （chase 模式算解锁时间）→ 按降级规则更新 12 个故事叙事字段 + 圣经追加 + 状态机决策（[StoryGenerationPolicy.decideStatus]）
     * → 刷新缓存。
     *
     * D1 安全：叙事字段经 [StoryRepository.updateNarrativeState] 定向写（非 cached_*），缓存经
     * [StoryRepository.refreshChapterCaches] 定向写（cached_*），两组列不互相 clobber，也不碰 storyOutline 等并发列。
     *
     * @param nowMillis 注入的当前时刻；章节 createdAt 与故事 updatedAt 同取此值，保纯可测
     * @return 落库后的新章实体
     */
    internal suspend fun materializeChapter(
        payload: StoryChapterPayload,
        chapterNumber: Int,
        story: StoryEntity,
        nowMillis: Long,
    ): StoryChapterEntity {
        val cleanedContent = StoryTextCleaning.cleanContentThinkingTags(payload.content)
        // 落库口空稿守卫：剥净思考后正文为空（纯思考响应/思考中途截断）绝不落空章——抛给生成失败路（用户可重试）。
        if (cleanedContent.isBlank()) throw StoryGenerationError.EmptyResponse
        val chapterSummary = payload.summary?.takeIf { it.isNotEmpty() } ?: cleanedContent.take(150)
        val unlockAt = if (story.updateMode == StoryUpdateMode.CHASE) {
            StoryGenerationPolicy.computeUnlockAt(nowMillis, story.unlockHour, story.unlockMinute)
        } else {
            null
        }

        // 状态决策前移到章实体构建之前（ST10-4）：清洗需要知道本章是否把书带向完结。
        // 输入全部来自 story/payload/chapterNumber 快照，与插章顺序无关（纯函数，行为口径零变）。
        val decision = StoryGenerationPolicy.decideStatus(
            requestedEndingType = story.requestedEndingType,
            hasChoice = payload.hasChoice,
        )
        // 幽灵选择清洗（ST10-4·微图纸 2026-07-17）：任何完结路（用户请求结局 / 满章封顶）落库的章都不许携带
        // 未答选择——完结书重读末章时幽灵选择可点，一点会经 commitUserChoice(setSerializing=true) 把书从已完结
        // 拉回连载中。提示词已要求结局章 hasChoice=false（StoryWritingTechniques.requestedEndingRequirements），
        // 此处是 LLM 违规输出的落库口硬闸。条件挂在判定结果上（decision.status），判定链改动自动跟随
        // （ST11 删 isEnding 分支后，「AI 自标结局」不再是完结路，其章的选择照常保留）。
        val chapterCompletes = decision.status == StoryStatus.COMPLETED

        // C3「上一版」接力棒消费（图纸三 §3.1）：**仅重写路**——`rewriteInstruction != null` 是「重写进行中」的
        // 既有单源信号（prepareRewrite 设 / 本函数末尾清），双条件同时成立才把 story 级中转快照挂进新章的槽。
        // 正常续章两者恒 null ⇒ 新章槽为 null，行为逐字节如旧（E1）。
        val carriedDraftJson = story.pendingRewriteDraftJson?.takeIf { story.rewriteInstruction != null }

        val chapter = StoryChapterEntity(
            storyId = story.id,
            chapterNumber = chapterNumber,
            title = payload.title,
            teaser = payload.teaser,
            createdAt = nowMillis,
            content = cleanedContent,
            mood = payload.mood,
            hasChoice = if (chapterCompletes) false else payload.hasChoice,
            choicePrompt = if (chapterCompletes) null else payload.choicePrompt,
            choiceOptions = if (chapterCompletes) null else StoryGenerationParsing.encodeChoiceOptions(payload.choiceOptions),
            chapterSummary = chapterSummary,
            unlockAt = unlockAt,
            // ST11 拍板②：AI 自标结局只落章印（供阅读器末章「建议完结卡」），不参与状态决策。
            aiSuggestedEnding = payload.isEnding == true,
            previousDraftJson = carriedDraftJson,
        )
        storyRepository.insertChapter(chapter)
        // 挂槽成功后才清中转位（进程死亡最坏 = pending 残留且已消费：下次重写覆盖，幂等无害）。
        if (carriedDraftJson != null) storyRepository.clearPendingRewriteDraft(story.id)

        // 故事叙事字段更新（缺失则沿用上一章；nextChapterBeats 缺失则清空）。
        val newStorySummary =
            if (story.lastCompressedAtChapter == null && !payload.summary.isNullOrEmpty()) payload.summary else story.storySummary
        val newCurrentArc = payload.currentArc?.takeIf { it.isNotEmpty() } ?: story.currentArc
        val newCharacterStates = payload.characterStates?.takeIf { it.isNotEmpty() } ?: story.characterStates
        val newOpenThreads = payload.openThreads?.takeIf { it.isNotEmpty() } ?: story.openThreads
        val newPendingBeats = payload.nextChapterBeats?.takeIf { it.isNotEmpty() }

        // 账本族三件（故事二期卷一 §3.4）：值在内存算好，随下面那条 updateNarrativeState 原子落库。
        // 三条降级口径各不相同，勿互相「统一」：
        // - 关系史 / 台账：字段缺失与「无」同义（本章没有这件事）→ 账本原样不动；
        // - 场景状态：**字段整个缺失 = 沿用上一章**（同 characterStates 惯例）、显式「无」= 清列（人已离开该场景）。
        val newIntimacyLedger = StoryLedgers.appendIntimacy(story.intimacyLedger, payload.intimacyUpdates, chapterNumber)
        val newSceneLedger = StoryLedgers.appendScene(story.sceneLedger, payload.sceneTag, chapterNumber)
        val newSceneState =
            if (payload.sceneEndState == null) story.sceneState else StoryLedgers.normalizeMeta(payload.sceneEndState)

        // 故事圣经追加（仅 appendix 非空时拼接，否则原样保留——含 null）。
        val appendix = StoryGenerationPolicy.buildBibleAppendix(chapterNumber, payload.characterStates, payload.openThreads)
        val newBible = if (appendix.isEmpty()) story.storyBible else (story.storyBible ?: "") + appendix

        // 结局类型快照（ST8·档案徽章）：完结那一刻若是用户请求的结局，把类型定格进持久列 finalEndingType
        // （requestedEndingType 紧接着被清空，故须在此从内存里的旧值取）；自然/满章结局无用户类型 → 保留原值(通常 null)。
        val newFinalEndingType =
            if (decision.status == StoryStatus.COMPLETED && story.requestedEndingType != null) story.requestedEndingType
            else story.finalEndingType

        // 重写指令一次性消费：本章生成完成即清空（rewriteInstruction = null）。
        StoryStateTransitions.check(story.status, decision.status, "StoryChapterMaterializer.materializeChapter")
        storyRepository.updateNarrativeState(
            id = story.id,
            storySummary = newStorySummary,
            currentArc = newCurrentArc,
            characterStates = newCharacterStates,
            openThreads = newOpenThreads,
            pendingChapterBeats = newPendingBeats,
            storyBible = newBible,
            status = decision.status,
            // 卷二·单模式化：两列已恒 null/0（迁移 39→40 归一化），此处等值重写、不再由状态机改动。
            maxChapters = story.maxChapters,
            autoExtendCount = story.autoExtendCount,
            requestedEndingType = if (decision.clearRequestedEnding) null else story.requestedEndingType,
            requestedEndingDetail = if (decision.clearRequestedEnding) null else story.requestedEndingDetail,
            rewriteInstruction = null,
            finalEndingType = newFinalEndingType,
            intimacyLedger = newIntimacyLedger,
            sceneState = newSceneState,
            sceneLedger = newSceneLedger,
            updatedAt = nowMillis,
        )

        // 显式以新章为 latest 刷新缓存（= iOS `refreshChapterCaches(using: chapter)`）。
        storyRepository.refreshChapterCaches(story.id, explicitLatest = chapter.toCacheRow())

        logStorageNotes(payload)
        Log.d(TAG, "章节落库：第 $chapterNumber 章，状态 → ${decision.status}")
        return chapter
    }

    /** 降级处理观测点（1:1 iOS storageNotes :122-140）：哪些字段缺失而沿用上一章/被清空，走 Logcat 便于真机验。 */
    private fun logStorageNotes(payload: StoryChapterPayload) {
        val notes = buildList {
            if (payload.summary.isNullOrEmpty()) add("summary 沿用上一章")
            if (payload.currentArc.isNullOrEmpty()) add("currentArc 沿用上一章")
            if (payload.characterStates.isNullOrEmpty()) add("characterStates 沿用上一章")
            if (payload.openThreads.isNullOrEmpty()) add("openThreads 沿用上一章")
            if (payload.nextChapterBeats.isNullOrEmpty()) add("nextChapterBeats 已清空")
            // 账本族的降级路径（故事二期卷一）：缺失与「无」都走这里，值不进日志（只记「哪个字段走了降级」）。
            if (StoryLedgers.normalizeMeta(payload.intimacyUpdates) == null) add("intimacyUpdates 无新增")
            if (StoryLedgers.normalizeMeta(payload.sceneTag) == null) add("sceneTag 无场面")
            if (payload.sceneEndState == null) add("sceneEndState 沿用上一章")
        }
        if (notes.isNotEmpty()) {
            Log.d(TAG, "存储结果：${notes.size} 个字段使用了降级处理：${notes.joinToString(", ")}")
        }
    }

    /**
     * 删除最新章并还原故事状态，准备重新生成（1:1 iOS `prepareRewrite` :151-201）：回滚圣经本章追加内容 →
     * 用上一章摘要恢复 storySummary → 删最新章 → 设重写指令（null→空串=有重写无附加文）+ 复位 serializing +
     * 清 pendingChapterBeats → 以删除前的上一章为 latest 刷新缓存。调用方随后触发新生成。
     *
     * D1 安全：故事字段经 [StoryRepository.updateRewriteState] 定向写，缓存经 refreshChapterCaches 定向写。
     *
     * @param nowMillis 注入的当前时刻（故事 updatedAt），保纯可测
     */
    internal suspend fun prepareRewrite(
        story: StoryEntity,
        latestChapter: StoryChapterEntity,
        instruction: String?,
        nowMillis: Long,
    ) {
        val chapterNumber = latestChapter.chapterNumber
        val newBible = StoryGenerationPolicy.rollbackBible(story.storyBible, chapterNumber)

        // 删除前先定位上一章（= 升序列表里的前一项），用于恢复摘要 + 刷新缓存的 explicitLatest。
        // 图纸卷二 §3.2：单行元数据查询取代「拉整本书 + indexOfFirst 取前一项」；按 `chapterNumber < 本章`
        // 倒序取一，**不假设章号连续**（历史洞照样对），首章无前一项 → null 走既有分支。
        val previous = storyRepository.getChapterMetaBefore(story.id, chapterNumber)
        val restoredSummary = previous?.chapterSummary?.takeIf { it.isNotEmpty() } ?: story.storySummary

        // C3「上一版」接力棒（图纸三 §3.1）：**删章之前**先把旧稿快照落库——先写后删的写序保证进程死亡
        // 也不丢稿（快照已在库而章还在；重跑 prepareRewrite 幂等覆盖）。此处抛出 = 整个重写中止，旧章原封不动。
        storyRepository.setPendingRewriteDraft(
            story.id,
            StoryChapterDraft.encode(StoryChapterDraft.fromEntity(latestChapter)),
        )

        storyRepository.deleteChapter(latestChapter.id)

        StoryStateTransitions.check(story.status, StoryStatus.SERIALIZING, "StoryChapterMaterializer.prepareRewrite")
        storyRepository.updateRewriteState(
            id = story.id,
            storyBible = newBible,
            storySummary = restoredSummary,
            rewriteInstruction = instruction ?: "",
            status = StoryStatus.SERIALIZING,
            pendingChapterBeats = null,
            // 账本族回滚（故事二期卷一 §3.4）：两账本删掉本章写进去的行、场景状态清空——
            // 否则重写出来的新版会踩着旧版留下的关系史与场面记录写。纯函数按章号删行 ⇒ 重跑幂等。
            intimacyLedger = StoryLedgers.rollbackChapter(story.intimacyLedger, chapterNumber),
            sceneState = null,
            sceneLedger = StoryLedgers.rollbackChapter(story.sceneLedger, chapterNumber),
            updatedAt = nowMillis,
        )

        storyRepository.refreshChapterCaches(story.id, explicitLatest = previous?.toCacheRow())
        Log.d(TAG, "重写准备：删第 $chapterNumber 章，回滚至上一章并复位 serializing")
    }

    /** 章节实体 → 缓存重算投影（refreshChapterCaches 的 explicitLatest）。 */
    private fun StoryChapterEntity.toCacheRow(): StoryChapterCacheRow =
        StoryChapterCacheRow(chapterNumber, title, createdAt, hasChoice, userChoice)

    private companion object {
        const val TAG = "StoryGeneration"
    }
}
