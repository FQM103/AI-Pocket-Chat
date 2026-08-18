package com.situ.aichat.story

import android.util.Log
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.model.CustomStoryPrompts
import com.situ.aichat.data.model.MaxOutputLength
import com.situ.aichat.data.remote.llm.ApiConfigValues
import com.situ.aichat.data.remote.llm.LlmClient
import com.situ.aichat.data.remote.llm.StreamToken
import com.situ.aichat.data.remote.llm.UsageDto
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.ApiFunctionRouter
import com.situ.aichat.data.repository.SettingsRepository
import com.situ.aichat.data.repository.StoryRepository
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 故事章节生成服务（1:1 iOS `StoryGenerationService` 单一类型）。安卓用 **1 个 @Singleton 服务**承载全部 8 步管线，
 * 纯逻辑抽到 [StoryGenerationParsing]/[StoryGenerationPolicy] 可单测，编排留薄层。前台生成与 11.1g 追更后台调度
 * 共用本服务（不像 iOS 因 actor 隔离而在 `StoryScheduleActor` 重复一份）。
 *
 * 本块（11.1e-3）= 服务骨架 + API config 解析 + 大纲编排 + 角色段原料收集：
 * - [creationConfig]/[structuringConfig]：功能 API 分配解析（结构化无自定义分配时回落到创作配置，1:1 iOS）。
 * - [ensureOutline]：首次生成 / 无限模式弧线续接（决策见 [StoryGenerationPolicy.decideOutlineAction]；失败不阻塞）。
 * - [collectCharacterData]：角色 → [StoryCharacterSectionData] 预收集，喂给 d-4/d-5 纯函数 prompt。
 *
 * 后续 e 子块接：流式创作 + 三级 resolvePayload + 截断续写 / materialize 落库 + 状态机 / 摘要压缩 / 主编排。
 * LLM/DB 编排部分按验证流程批到真机集中验（关键事件已加 Logcat 观测点）。
 * （一致性检查已按 FABLE5_STORY_REDESIGN_PROPOSAL §8-J1 拍板退役删码 2026-07-02。）
 */
@Singleton
class StoryGenerationService @Inject constructor(
    private val llmClient: LlmClient,
    private val contextLog: ContextLogService,
    private val storyRepository: StoryRepository,
    private val apiConfigRepository: ApiConfigRepository,
    private val apiFunctionRouter: ApiFunctionRouter,
    private val storyChatInfluenceBuilder: StoryChatInfluenceBuilder,
    private val storyCharacterDataCollector: StoryCharacterDataCollector,
    private val storyChapterMaterializer: StoryChapterMaterializer,
    private val storyPayloadResolver: StoryPayloadResolver,
    private val storyWorldInfoService: StoryWorldInfoService,
    private val settingsRepository: SettingsRepository,
    private val storyOutlineOrchestrator: StoryOutlineOrchestrator,
    private val storyCompressionCoordinator: StoryCompressionCoordinator,
) {

    /**
     * 本轮生成用的故事创作温度（卷一 V1）：每次生成读**一次快照**——生成中改设置不影响当轮，下一次「生成新章」生效。
     * clamp 与非有限回退在 [com.situ.aichat.data.model.AppSettings.sanitizedStoryCreationTemperature]。
     */
    private suspend fun storyCreationTemperature(): Double =
        settingsRepository.appSettings.first().sanitizedStoryCreationTemperature

    /**
     * 本轮生成用的全局文字忌口快照（2026-07-30·照温度先例读一次）：生成中改忌口不影响当轮，下一次生成生效。
     * **原值直传**，三态判定全在 [StoryPromptSections.resolvedBannedExpressions]，这里一个字都不许判。
     */
    private suspend fun globalBannedExpressions(): String? =
        settingsRepository.appSettings.first().storyBannedExpressions

    /**
     * 本轮生成用的全局场面节拍 / 口味画像快照（故事二期卷一·照忌口先例每轮读一次）。**原值直传**，
     * 三态判定全在 [StoryCraftSections] 的 resolved\* 单源，这里一个字都不许判（判了就多出第二个真理源）。
     */
    private suspend fun globalSceneBeats(): String? = settingsRepository.appSettings.first().storySceneBeats

    private suspend fun globalTasteProfile(): String? = settingsRepository.appSettings.first().storyTasteProfile

    // MARK: - API config 解析（1:1 iOS creationAPIConfig/structuringAPIConfig +Parsing:494-539）

    /** 创作配置：功能 API「故事创作」分配，否则活跃配置（经 [ApiConfigRepository.resolveConfigValues]）。 */
    suspend fun creationConfig(): ApiConfigValues? =
        apiConfigRepository.resolveConfigValues(ApiFunction.STORY_CREATION)

    /**
     * 结构化配置：仅当「故事结构化」有自定义分配时用之，否则回落到 [creationConfig]（1:1 iOS `structuringAPIConfig`）。
     *
     * 边角差异：自定义分配的配置被删除时，安卓经 resolveConfigValues 回落到活跃配置（iOS 回落到创作配置）；
     * 主路径（有/无自定义分配）行为一致，此边角可忽略。
     */
    suspend fun structuringConfig(): ApiConfigValues? {
        if (apiFunctionRouter.assignedId(ApiFunction.STORY_STRUCTURING) != null) {
            apiConfigRepository.resolveConfigValues(ApiFunction.STORY_STRUCTURING)?.let { return it }
        }
        return creationConfig()
    }

    // MARK: - 大纲生成（编排外搬 [StoryOutlineOrchestrator]·薄门面保签名逐字不变）

    /**
     * 确保故事有当前剧情弧线的大纲（委托 [StoryOutlineOrchestrator.ensureOutline]）。
     * 创作 API 配置以 suspend 供给函数传入，保持「不需要生成大纲就不解析配置」的惰性不变。
     */
    suspend fun ensureOutline(story: StoryEntity, chapterNumber: Int, nowMillis: Long): StoryEntity =
        storyOutlineOrchestrator.ensureOutline(story, chapterNumber, nowMillis) { creationConfig() }

    // MARK: - 流式创作（1:1 iOS 主文件 / +Parsing.swift LLM 部分·三级解析 + 截断续写外搬 [StoryPayloadResolver]）

    /**
     * 流式创作（1:1 iOS `requestCreation` +Parsing:202-249）：累积可见正文 [StreamToken.Content]（思考内容由 LlmClient
     * 内联剥离为 Reasoning，不入缓冲），按 ~150ms 节流经 [onPreview] 推流式预览——回调解耦 11.1f 任务管理器：
     * 无回调时不做预览计算（等价 iOS 仅在有 storyID 时刷新）。末尾再清思考标签兜底；空 → 抛 [StoryGenerationError.EmptyResponse]。
     *
     * @param onPreview (预览末 200 字, 累计已收原始字符数)——第二参供撰写段真实进度换算（灵动岛卷一 §3.3）。
     */
    internal suspend fun requestCreation(
        request: StoryGenerationRequest,
        config: ApiConfigValues,
        onPreview: ((String, Int) -> Unit)? = null,
        /** 末帧 finish_reason（卷一 V8·可选尾参默认 null 零波及）：交调用方判「输出撞上限被掐断」，见 [generateChapter]。 */
        onFinishReason: ((String?) -> Unit)? = null,
    ): String {
        val buffer = StringBuilder()
        var lastPreviewFlush = System.nanoTime()
        var previewStopped = false

        // 批 D 上下文日志：主生成（流式创作），捕获末帧 usage + 计时；收流后落一条（source=STORY_GENERATION·
        // 用户级 characterName=""·重任务截断），失败原样重抛前先记。fire-and-forget，不影响创作流。
        val turnStart = System.currentTimeMillis()
        var usage: UsageDto? = null
        try {
            llmClient.streamChat(
                messages = request.messages,
                config = config,
                temperature = request.temperature,
                maxTokens = request.maxTokens,
                onUsage = { usage = it },
                onFinishReason = { onFinishReason?.invoke(it) },
            ).collect { token ->
                if (token is StreamToken.Content) buffer.append(token.text)

                if (onPreview != null && !previewStopped) {
                    val now = System.nanoTime()
                    if (now - lastPreviewFlush >= PREVIEW_INTERVAL_NANOS) {
                        lastPreviewFlush = now
                        val (preview, stopped) = StoryGenerationParsing.cleanBufferForPreview(buffer.toString())
                        previewStopped = stopped
                        if (preview.isNotEmpty()) onPreview(preview, buffer.length)
                    }
                }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            contextLog.recordError(LogSource.STORY_GENERATION, "", config.modelName, request.messages, e)
            throw e
        }

        if (onPreview != null && !previewStopped) {
            val (preview, _) = StoryGenerationParsing.cleanBufferForPreview(buffer.toString())
            if (preview.isNotEmpty()) onPreview(preview, buffer.length)
        }

        // 流成功（即便清洗后为空也算一次真实 LLM 调用，记原始输出）；空检查在落库之后。
        contextLog.recordSuccess(
            LogSource.STORY_GENERATION, "", config.modelName, request.messages, buffer.toString(),
            System.currentTimeMillis() - turnStart, usage,
        )
        val cleaned = StoryTextCleaning.cleanContentThinkingTags(buffer.toString())
        Log.d(TAG, "创作输出：${cleaned.length} 字")
        if (cleaned.isEmpty()) throw StoryGenerationError.EmptyResponse
        return cleaned
    }

    // MARK: - 章节落库委托（实体抽到 [StoryChapterMaterializer]·prepareRewrite 留门面给 StoryReaderViewModel）

    /**
     * 删除最新章并还原故事状态，准备重新生成（委托 [StoryChapterMaterializer.prepareRewrite]）。
     * 调用方 [com.situ.aichat.ui.story.StoryReaderViewModel] 经此门面调用，签名逐字不变。
     *
     * **join 不变式**（卷一 chunk 2）：回滚圣经 / 恢复摘要都基于入参快照并整列写回，压缩 job 未跑完就动手
     * 会与压缩写回互相踩踏 ⇒ 先等本书压缩完成、再重读快照。重读拿不到（删书竞态）就回退入参快照继续，
     * 与改造前「拿旧快照跑」等价，后续写库自然走既有失败路。[latestChapter] 不重读——压缩不写章节表。
     */
    internal suspend fun prepareRewrite(
        story: StoryEntity,
        latestChapter: StoryChapterEntity,
        instruction: String?,
        nowMillis: Long,
    ) {
        storyCompressionCoordinator.joinCompression(story.id)
        val fresh = storyRepository.getStory(story.id) ?: story
        storyChapterMaterializer.prepareRewrite(fresh, latestChapter, instruction, nowMillis)
    }

    // MARK: - 故事级操作：续写 / 重开（1:1 iOS continueStory:160-176 / restartStory:178-207）

    /**
     * 手动续写故事（1:1 iOS `continueStory` :160-176）：状态决策见 [StoryGenerationPolicy.decideContinue]
     * （completed→serializing±扩展上限·重置 autoExtendCount / paused→serializing），并刷新 updatedAt + 清 cachedHasPendingChoice。
     * 调用方随后调 [generateNextChapter] 触发新章。latestChapterNumber 取 cachedLatestChapterNumber（缓存恒新）。
     * D1 安全：经 [StoryRepository.updateContinueState] 定向写。
     */
    internal suspend fun continueStory(story: StoryEntity, nowMillis: Long) {
        val newStatus = StoryGenerationPolicy.decideContinue(story.status)
        StoryStateTransitions.check(story.status, newStatus, "StoryGenerationService.continueStory")
        // 结局徽章修正（R1 🟡-2）：从已完结开启续篇时清 finalEndingType——续篇将重新走向完结，上一次结局类型不再代表本书；
        // 非完结起步（暂停）等值重写（不改）。materializeChapter 的 else-保留正确支撑「重写末章」路，错的只是续篇起点没清，故在此清。
        val nextFinalEndingType = if (story.status == StoryStatus.COMPLETED) null else story.finalEndingType
        storyRepository.updateContinueState(
            id = story.id,
            status = newStatus,
            // 卷二·单模式化：两列恒 null/0，等值重写（续写不再扩容上限）。
            maxChapters = story.maxChapters,
            autoExtendCount = story.autoExtendCount,
            finalEndingType = nextFinalEndingType,
            updatedAt = nowMillis,
        )
        Log.d(TAG, "续写故事：${story.status} → $newStatus")
    }

    /**
     * 重开故事：以原作**全部创作设定**新建一部「·重开」故事 + 复制角色，返回新故事供调用方导航并生成。
     *
     * 语义 = 同一部书的作者设定原样搬过去、只把叙事进度清零。实现取 [StoryEntity.copy] **全量继承**设定字段
     * （genre / 文风 / 人称 / 追更模式与解锁时间 / 世界书开关 / 自定义提示词 / 章长 / 连载上限 / 聊天影响权重…），
     * 仅显式重置：新 id、title 加「·重开」、状态归 serializing、createdAt/updatedAt = nowMillis，
     * 以及**所有叙事进度 / 缓存 / 一次性字段**（摘要 / 圣经 / 大纲 / 伏笔 / 结局快照 / cached* / 请求结局与重写指令）。
     *
     * 【维护不变式·别再退化成漏字段的老 bug】StoryEntity 将来新增字段时：
     * - 属于「作者创作设定」的 → **无需改这里**，copy 自动继承；
     * - 属于「叙事进度 / 运行时累积状态」的 → **必须在下面 reset 段补一行**，否则重开会把上一轮进度带进新书。
     *
     * （历史：旧实现照已退役的 iOS 1:1 用白名单只复制部分字段，静默丢了人称 / 追更 / 世界书开关 / 自定义提示词；
     * 按当前「只看好不好用」铁律，重开理应保留用户全部创作设定，故 2026-07-13 改为 copy-then-reset。）
     */
    internal suspend fun restartStory(story: StoryEntity, nowMillis: Long): StoryEntity {
        // 标题幂等加后缀：重开「原作」→「原作·重开」；再重开已带后缀的书不再叠加（endsWith 精确匹配，
        // 「原作·重开的续集」这类非后缀结尾仍照常追加），避免反复重开累成「·重开·重开·重开」。
        val restartTitle = if (story.title.endsWith(RESTART_SUFFIX)) story.title else "${story.title}$RESTART_SUFFIX"
        val copy = story.copy(
            id = UUID.randomUUID().toString(),
            title = restartTitle,
            status = StoryStatus.SERIALIZING,
            createdAt = nowMillis,
            updatedAt = nowMillis,
            // ↓ 叙事进度 / 运行时状态 / 一次性字段：重开=从头写，全部清零（此类新字段务必在此补一行）
            autoExtendCount = 0,
            storySummary = null,
            currentArc = null,
            characterStates = null,
            openThreads = null,
            storyBible = null,
            lastCompressedAtChapter = null,
            lastBibleCompressedAtChapter = null,
            storyOutline = null,
            pendingChapterBeats = null,
            pendingBeatsUserEdited = false,
            currentArcStartChapter = null,
            // 卷二 B2/J1 三列此前漏列（重开会带着旧弧线简史，更要命的是残留的收尾计划会让新书一上来就写终章弧）
            arcHistory = null,
            finaleEndingType = null,
            finaleEndingDetail = null,
            // 故事二期卷一账本族：写过什么/停在哪都属上一本书的进度
            intimacyLedger = null,
            sceneState = null,
            sceneLedger = null,
            requestedEndingType = null,
            requestedEndingDetail = null,
            rewriteInstruction = null,
            finalEndingType = null,
            cachedChapterCount = 0,
            cachedLatestChapterNumber = null,
            cachedLatestChapterTitle = null,
            cachedLatestChapterCreatedAt = null,
            cachedHasPendingChoice = false,
        )
        storyRepository.insertStory(copy)

        val roleCopies = storyRepository.getRoles(story.id).map { role ->
            StoryCharacterRoleEntity(
                storyId = copy.id,
                roleName = role.roleName,
                roleType = role.roleType,
                roleDescription = role.roleDescription,
                isUserRole = role.isUserRole,
                characterId = role.characterId,
                // 私下反差属「作者创作设定」（不是叙事进度），随角色原样带进新书
                intimatePersona = role.intimatePersona,
            )
        }
        if (roleCopies.isNotEmpty()) storyRepository.insertRoles(roleCopies)
        Log.d(TAG, "重开故事：${story.id} → ${copy.id}（${roleCopies.size} 角色）")
        return copy
    }

    // MARK: - 主编排：生成首章 / 续章 / 单章（1:1 iOS generateFirstChapter:99-120 / generateNextChapter:122-151 / generateChapter:209-311）

    /**
     * 生成第一章（1:1 iOS `generateFirstChapter` :99-120）：确保大纲 → 收集 prompt 原料（角色段/声音档案/主角动态状态）
     * → 构首章创作 prompt → [generateChapter] 跑管线。[onPreview] 解耦 11.1f 任务管理器（默认无预览）。
     *
     * 开头的 join + 重读见 [generateNextChapter]：首章的 job 表必空、join 空过，这里与续章保持**同一形状**——
     * 不变式只有单点可审才不会漏（将来给首章加压缩也不必再想起这件事）。
     */
    internal suspend fun generateFirstChapter(
        story: StoryEntity,
        nowMillis: Long,
        onPreview: ((String, Int) -> Unit)? = null,
        onPhase: ((StoryGenPhase) -> Unit)? = null,
    ): StoryChapterEntity {
        storyCompressionCoordinator.joinCompression(story.id)
        val fresh = storyRepository.getStory(story.id) ?: story
        val withOutline = ensureOutline(fresh, chapterNumber = 1, nowMillis)
        val config = creationConfig()
        val roles = storyRepository.getRoles(withOutline.id)
        val voiceProfiles = StoryVoiceBibleBuilder.buildVoiceProfiles(roles, storyCharacterDataCollector.collectVoiceCharacterData(roles))
        val (spectrum, quality) = storyCharacterDataCollector.collectProtagonistDynamicState(roles)
        // ST5 世界书联动：首章扫描 worldSetting+plotDirection；开关关/无书/激活异常 → null（prompt 零变化）。
        val worldInfoSection = storyWorldInfoService.buildWorldInfoSection(withOutline, roles, latestChapter = null)
        val firstPrompts = CustomStoryPrompts.decode(withOutline.customPromptsJson)
        val prompt = StoryGenerationPromptBuilder.buildFirstChapterCreationPrompt(
            story = withOutline,
            roles = roles,
            characterData = storyCharacterDataCollector.collectCharacterData(roles, nowMillis),
            voiceProfiles = voiceProfiles,
            protagonistSpectrum = spectrum,
            protagonistQuality = quality,
            worldInfoSection = worldInfoSection,
            globalBannedExpressions = globalBannedExpressions(),
            // 图纸二 D3：本书的章末选项开关（取值单源 = CustomStoryPrompts 的 effective* 谓词）
            choicesEnabled = firstPrompts?.effectiveChapterChoices == true,
            globalSceneBeats = globalSceneBeats(), // 故事二期卷一：全局两键原值直传
            globalTasteProfile = globalTasteProfile(),
        )
        val request = makeGenerationRequest(
            prompt = prompt,
            chapterNumber = 1,
            chapterLengthPreference = withOutline.chapterLengthPreference,
            // 首章不走 effectiveChapterLength（没有结局章之说），两个字数参数同为基础档。
            baseChapterLength = withOutline.chapterLengthPreference,
            isThinkingModel = config?.isThinkingModel ?: false,
            temperature = storyCreationTemperature(),
            maxOutputLength = config?.maxOutputLength ?: MaxOutputLength.AUTO,
            choicesEnabled = firstPrompts?.effectiveChapterChoices == true,
        )
        return generateChapter(withOutline, chapterNumber = 1, request = request, nowMillis = nowMillis, onPreview = onPreview, onPhase = onPhase)
    }

    /**
     * 终章弧「末章转正」前置步（卷二 J1）：本章若是收尾弧的最后一章，把预约的收尾计划**原子搬进**
     * requestedEnding 两列（[StoryRepository.promoteFinaleToEndingRequest]），其后完全复用既有结局章管线
     * （结局协议 prompt / 字数 ×1.5 / decideStatus → COMPLETED / finalEndingType 快照 / ST11 失败保留意图）。
     *
     * 三重门，缺一不可：
     * 1. 有收尾计划（finaleEndingType 非空）——否则本函数是零成本直通；
     * 2. **终章弧大纲已落库**——定收尾计划时大纲被清空，若这次大纲生成失败，currentArcStartChapter 还指着
     *    上一条普通弧的起点，据此算倒数会把「从容收尾」一步缩成一章；
     * 3. 倒数判定 == LAST（[StoryArcPlanning.finaleCountdown]）。
     *
     * 转正后**重读**故事：后续 prompt / 字数 / 状态机吃的都得是搬完之后的快照。
     * 幂等性由那条 UPDATE 自带——搬完 finale 两列即空，同一章重试不会二次转正。
     */
    internal suspend fun promoteFinaleIfLastChapter(
        story: StoryEntity,
        chapterNumber: Int,
        nowMillis: Long,
    ): StoryEntity {
        if (story.finaleEndingType == null || story.storyOutline.isNullOrEmpty()) return story
        val countdown = StoryArcPlanning.finaleCountdown(
            arcStart = story.currentArcStartChapter,
            plannedLength = StoryArcPlanning.parseArcPlannedLength(story.storyOutline),
            chapterNumber = chapterNumber,
        )
        if (countdown != StoryArcPlanning.FinaleCountdown.LAST) return story
        storyRepository.promoteFinaleToEndingRequest(story.id, nowMillis)
        Log.i(TAG, "终章弧末章转正：第 $chapterNumber 章走结局章协议 story=${story.id.take(8)}")
        return storyRepository.getStory(story.id) ?: story.copy(
            requestedEndingType = story.finaleEndingType,
            requestedEndingDetail = story.finaleEndingDetail,
            finaleEndingType = null,
            finaleEndingDetail = null,
        )
    }

    /**
     * 生成续章：章号 = 缓存最新章号 + 1；确保大纲 → **终章弧末章转正判定** → 收集 prompt 原料
     * （含聊天影响 + 上一章实体 + 前情摘要滑窗）→ 结局章字数 ×1.5（[StoryGenerationPolicy.effectiveChapterLength]）→ 跑管线。
     *
     * 转正放在构 prompt **之前**（且在 ensureOutline 之后——终章弧大纲此时才落库）：手动生成与追更自动连载
     * 两路都经本函数，故两路共享同一次转正，不必各写一遍。
     *
     * **join 不变式**（卷一 chunk 2）：开头先等上一章的后台压缩跑完，**再重读**故事快照。两步缺一不可——
     * 调用方（[StoryGenerationTaskManager] / [StoryAutoSerializeService]）手里的 story 是 join **之前**读的，
     * 只 join 不重读，本章落库仍会拿旧快照整列覆盖掉压缩刚写回的 storySummary/storyBible（水位线两列却已推进），
     * join 等于白做。收口在本函数内部 ⇒ 两条调用方一字不改，不变式单点可审。
     * 重读拿不到（删书竞态）就回退入参快照继续，与改造前等价。
     */
    internal suspend fun generateNextChapter(
        story: StoryEntity,
        nowMillis: Long,
        onPreview: ((String, Int) -> Unit)? = null,
        onPhase: ((StoryGenPhase) -> Unit)? = null,
    ): StoryChapterEntity {
        storyCompressionCoordinator.joinCompression(story.id)
        val fresh = storyRepository.getStory(story.id) ?: story
        val chapterNumber = (fresh.cachedLatestChapterNumber ?: 0) + 1
        val withOutline = promoteFinaleIfLastChapter(ensureOutline(fresh, chapterNumber, nowMillis), chapterNumber, nowMillis)
        val config = creationConfig()
        val roles = storyRepository.getRoles(withOutline.id)
        val voiceProfiles = StoryVoiceBibleBuilder.buildVoiceProfiles(roles, storyCharacterDataCollector.collectVoiceCharacterData(roles))
        val chatInfluence = storyChatInfluenceBuilder.extractChatInfluence(withOutline.chatInfluenceWeight, roles)
        // 图纸卷二 §3.2：末章取法从「拉整本书取 lastOrNull」换成单行 LIMIT 1；**全列不投影**——下面三处
        // prompt 输入（自由输入三明治 / 世界书扫描 / 上一章正文）都要 content，喂 LLM 的字节逐字节同源。
        val latestChapter = storyRepository.getLatestChapter(withOutline.id)
        val freeformDirective = StoryChoiceClassifier.freeformDirective(latestChapter) // 三明治判定（图纸 §3·自由输入取原文/否则 null）
        // 方向账本（图纸 2026-08-05 §3.1）：本弧内用户亲笔写过的走向清单。走轻投影 getChapterMetas（含 userChoice、
        // 正文位是占位空串）——**禁用 getChapters**，那会把整本正文拉进内存。无条目 → null → prompt 整段零注入。
        val directiveLedger = StoryChoiceClassifier.buildDirectiveLedger(
            chapters = storyRepository.getChapterMetas(withOutline.id),
            arcStartChapter = withOutline.currentArcStartChapter,
            excludeChapterNumber = latestChapter?.chapterNumber,
        )
        // ST5 世界书联动：续章扫描上一章尾段(剥标签)+用户选择+方向提示；开关关/无书/激活异常 → null（prompt 零变化）。
        val worldInfoSection = storyWorldInfoService.buildWorldInfoSection(withOutline, roles, latestChapter)
        val nextPrompts = CustomStoryPrompts.decode(withOutline.customPromptsJson)
        val prompt = StoryGenerationPromptBuilder.buildNextChapterCreationPrompt(
            story = withOutline,
            chapterNumber = chapterNumber,
            roles = roles,
            characterData = storyCharacterDataCollector.collectCharacterData(roles, nowMillis),
            voiceProfiles = voiceProfiles,
            chatInfluence = chatInfluence,
            latestChapter = latestChapter,
            chapterSummaries = storyRepository.getChapterSummaries(withOutline.id),
            worldInfoSection = worldInfoSection,
            freeformDirective = freeformDirective,
            globalBannedExpressions = globalBannedExpressions(),
            // 图纸二 D3：同首章，开关取值只走 effective* 谓词
            choicesEnabled = nextPrompts?.effectiveChapterChoices == true,
            globalSceneBeats = globalSceneBeats(),
            globalTasteProfile = globalTasteProfile(),
            pendingBeatsUserEdited = withOutline.pendingBeatsUserEdited, // 决定注入「用户指定节拍」还是「本章计划草稿」
            directiveLedger = directiveLedger,
        )
        // 结局章字数加大走 makeGenerationRequest（只影响创作 maxTokens；解析侧已与章长脱钩——图纸一 C2 第 3 层）。
        val effectiveLength = StoryGenerationPolicy.effectiveChapterLength(withOutline.chapterLengthPreference, withOutline.requestedEndingType)
        val request = makeGenerationRequest(
            prompt = prompt,
            chapterNumber = chapterNumber,
            chapterLengthPreference = effectiveLength,
            isThinkingModel = config?.isThinkingModel ?: false,
            temperature = storyCreationTemperature(),
            maxOutputLength = config?.maxOutputLength ?: MaxOutputLength.AUTO,
            freeformDirective = freeformDirective,
            // 末句要点复述用**基础**档位（结局章 ×1.5 由 endingChapterLengthRange 内部承担），
            // 与上面喂 maxTokens 的 effectiveLength 是两条路，不许混用。
            baseChapterLength = withOutline.chapterLengthPreference,
            requestedEndingType = withOutline.requestedEndingType,
            userChoice = StoryChoiceClassifier.presetChoiceForRecap(latestChapter),
            choicesEnabled = nextPrompts?.effectiveChapterChoices == true,
        )
        return generateChapter(withOutline, chapterNumber, request, nowMillis, onPreview, onPhase)
    }

    /**
     * 单章生成管线（1:1 iOS `generateChapter` :209-311）：流式创作 → 三级 resolvePayload → 截断续写 → materialize 落库 →
     * 摘要压缩。诊断信息走 Logcat（iOS 落 LogEntry，安卓未移植诊断日志 M01，全体 LLM 服务一致）。
     * （原「每章一次 LLM 一致性检查」已按契约 §8-J1 拍板退役删码 2026-07-02——结果只打日志、用户不可见、白烧 token。）
     */
    internal suspend fun generateChapter(
        story: StoryEntity,
        chapterNumber: Int,
        request: StoryGenerationRequest,
        nowMillis: Long,
        onPreview: ((String, Int) -> Unit)? = null,
        onPhase: ((StoryGenPhase) -> Unit)? = null,
    ): StoryChapterEntity {
        val creationConfig = creationConfig() ?: throw StoryGenerationError.NoApiConfig
        Log.d(TAG, "━━━ StoryGeneration 诊断 ━━━ 创作模型：${creationConfig.modelName}，思考模型：${if (creationConfig.isThinkingModel) "是" else "否"}")
        val startNanos = System.nanoTime()
        try {
            var creationFinishReason: String? = null
            val rawCreationOutput = requestCreation(request, creationConfig, onPreview) { creationFinishReason = it }
            if (StoryRefusalDetector.isLikelyRefusal(rawCreationOutput)) {
                Log.w(TAG, "疑似拒答输出（${rawCreationOutput.length} 字），不落库走失败路")
                throw StoryGenerationError.RefusalDetected
            }
            onPhase?.invoke(StoryGenPhase.FINALIZING)
            val structConfig = structuringConfig() ?: creationConfig
            val payload = storyPayloadResolver.resolvePayload(rawCreationOutput, chapterNumber, structConfig)

            // 截断检测双路（V8）：finish_reason 撞限且原始输出连 METADATA 分隔符都没吐出 → 必是正文中断；
            // 其余仍走句末标点启发式（撞限发生在元数据区时正文完整，续写反而画蛇添足——J4）。
            val cutInProse = LlmClient.isLengthTruncated(creationFinishReason) &&
                !rawCreationOutput.contains(StoryGenerationParsing.METADATA_DELIMITER, ignoreCase = true)
            val finalPayload = if (cutInProse || StoryGenerationParsing.isContentTruncated(payload.content)) {
                val completed = storyPayloadResolver.requestContinuation(payload.content, creationConfig, request.temperature)
                StoryGenerationParsing.payloadWithContinuation(payload, completed)
            } else {
                payload
            }

            val chapter = storyChapterMaterializer.materializeChapter(finalPayload, chapterNumber, story, nowMillis)
            onPhase?.invoke(StoryGenPhase.ARCHIVING)

            Log.d(TAG, "章节生成成功：第 $chapterNumber 章，总耗时 ${(System.nanoTime() - startNanos) / 1_000_000_000.0}s")

            // 两次压缩（摘要链 + 圣经）挪出关键路径（卷一 chunk 2）：登记 per-书后台 job，判定在 job 内、
            // 不触发即空过。**非挂起、立即返回** ⇒ materialize 之后到 return 之间不再有任何挂起点，
            // 取消不可能落在「已落库未登记」的窗口里；顺序由下一次生成/重写入口的 joinCompression 保住。
            storyCompressionCoordinator.scheduleAfterChapter(story, chapterNumber, this::creationConfig)
            return chapter
        } catch (e: Exception) {
            Log.e(TAG, "章节生成失败：第 $chapterNumber 章", e)
            throw e
        }
    }

    private companion object {
        const val TAG = "StoryGeneration"

        /** 重开故事标题后缀（[restartStory] 幂等追加：已带此后缀的不再叠加，避免「·重开·重开」越叠越长）。 */
        const val RESTART_SUFFIX = "·重开"

        /** 流式预览节流间隔（1:1 iOS 150ms）。 */
        const val PREVIEW_INTERVAL_NANOS = 150_000_000L
    }
}
