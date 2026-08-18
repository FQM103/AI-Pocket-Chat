package com.situ.aichat.ui.story

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.BuildConfig
import com.situ.aichat.diagnostics.perf.FrameSceneObserver
import com.situ.aichat.diagnostics.perf.PerfScenes
import com.situ.aichat.story.StoryChapterDraft
import com.situ.aichat.story.StoryChoiceClassifier
import com.situ.aichat.story.StoryContentParser
import com.situ.aichat.story.StoryNarrativePerson
import com.situ.aichat.story.StoryReaderRenderItem
import com.situ.aichat.story.StoryReaderTypography
import com.situ.aichat.story.StoryStatus
import com.situ.aichat.story.StoryVisualPerformance
import com.situ.aichat.story.isUnlocked
import com.situ.aichat.story.StoryArcPlanning
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 故事阅读器主屏（源自 iOS `StoryReaderView`）。三层：纸面背景 → 对比遮罩 → 正文滚动 → 底部章节导航胶囊；
 * 外加顶栏（章节胶囊 + 菜单）、生成中遮罩、锁态遮罩。
 * （2026-08-03 格式块精简：天气粒子层与屏幕特效 overlay 随氛围演出层整族退役。）
 */
@Composable
fun StoryReaderScreen(
    onBack: () -> Unit,
    onGoToChat: () -> Unit,
    /** 卷三 §4.6：⋮ 菜单瘦身后唯一保留的导航——跳这本书的「书页」（档案 / 设定双 Tab）。 */
    onOpenBookHub: (storyId: String) -> Unit,
    viewModel: StoryReaderViewModel = hiltViewModel(),
) {
    // 性能采集·尺 3（卷 0）：本屏在被观测名单里（M15 长章首帧到出字）。采集关时零成本。
    FrameSceneObserver(PerfScenes.STORY_READER)
    val story by viewModel.story.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val currentChapter by viewModel.currentChapter.collectAsStateWithLifecycle()
    val currentChapterId by viewModel.currentChapterId.collectAsStateWithLifecycle()
    val activeGeneration by viewModel.activeGeneration.collectAsStateWithLifecycle()
    val userRoleName by viewModel.userRoleName.collectAsStateWithLifecycle()
    val selectedChoiceText by viewModel.selectedChoiceText.collectAsStateWithLifecycle()
    val pendingActive by viewModel.pendingActive.collectAsStateWithLifecycle()
    val pendingRemaining by viewModel.pendingRemainingSeconds.collectAsStateWithLifecycle()
    val askNext by viewModel.askGenerateNextChapter.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val readingAnimationsEnabled by viewModel.readingAnimationsEnabled.collectAsStateWithLifecycle()
    val fontSizeIndex by viewModel.fontSizeIndex.collectAsStateWithLifecycle()
    val recapSummary by viewModel.recapSummary.collectAsStateWithLifecycle()
    // C3：本章槽里的上一版（VM 侧解码·失败视同无槽 E6）。菜单项显隐与回翻弹层共用。
    val previousDraft by viewModel.previousDraft.collectAsStateWithLifecycle()
    val typography = remember(fontSizeIndex) { StoryReaderTypography.forIndex(fontSizeIndex) }

    val isDark = LocalIsDarkTheme.current
    val reduceMotion = rememberReduceMotion()
    val context = LocalContext.current
    val performance = remember(readingAnimationsEnabled, reduceMotion) {
        StoryVisualPerformance.current(readingAnimationsEnabled, reduceMotion)
    }

    // P15.2 #5：阅读期间保持屏幕常亮（长章节沉浸阅读不触屏不自动息屏；iOS 未做，安卓地板非天花板）。
    // 复用 VoiceCallScreen 同款模板，离开阅读器即恢复。
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val haptics = rememberStoryReaderHaptics()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 「就此完结」结果一次性提示（成功入档 / 生成中拒绝·照书架同款）。
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { resId -> Toast.makeText(context, resId, Toast.LENGTH_SHORT).show() }
    }

    val narrativePerson = story?.narrativePerson ?: StoryNarrativePerson.SECOND
    val chapterKey = currentChapter?.id

    val renderItems = remember(chapterKey, currentChapter?.content) {
        currentChapter?.let {
            // 观测点只在 debug 包收集：release 传 null 走 StoryContentParser 的「零额外工作」快路。
            val diagnostics = if (BuildConfig.DEBUG) mutableListOf<String>() else null
            val blocks = StoryContentParser.parse(it.content, diagnostics)
            if (!diagnostics.isNullOrEmpty()) {
                // §7/§11 观测点：渲染期解析剥离计数（只打标签名+位置，正文内容绝不进日志）
                Log.i("StoryReader", "解析剥离 ${diagnostics.size} 处 $diagnostics")
            }
            StoryReaderRenderItem.make(blocks)
        } ?: emptyList()
    }

    // 切章：待选章选择反馈触感。
    LaunchedEffect(chapterKey) {
        val ch = currentChapter ?: return@LaunchedEffect
        if (ch.hasChoice && ch.userChoice == null) haptics.selection()
    }

    // 滚动持久化（恢复 / 防抖保存 / 切章 flush·搬去 StoryReaderScrollPersistence，行为不变）。
    StoryReaderScrollPersistence(
        chapterKey = chapterKey,
        currentChapter = currentChapter,
        listState = listState,
        viewModel = viewModel,
    )

    val isScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    // 阅读进度（底部胶囊「62% · 还剩 X 分钟」）：视口底边模型，取参在 StoryReaderProgressBridge——
    // 滚到底（章末矮项堆全可见）恒 100% + 0 分钟，胶囊按既有分支只显「100%」。
    val progressPercent by remember {
        derivedStateOf { StoryReaderProgressBridge.percent(listState.layoutInfo) }
    }
    val hasRecap = recapSummary != null
    val remainingMinutes by remember(renderItems, hasRecap) {
        derivedStateOf { StoryReaderProgressBridge.remainingMinutes(listState.layoutInfo, hasRecap, renderItems) }
    }
    // 沉浸式工具栏显隐（顶栏 + 底部翻页条统一受控）：进入/切章先展示，约 2.5s 未操作或一旦滚动即隐入沉浸；
    // 点击阅读区在显/隐间切换（chromeUserToggled 防止进场保底计时覆盖用户的手动选择）。
    var chromeVisible by remember { mutableStateOf(true) }
    var chromeUserToggled by remember { mutableStateOf(false) }
    // ⋮ 菜单展开时顶栏强制保持：防 2.5s 保底计时把锚点藏掉、菜单悬空。
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(chapterKey) {
        chromeUserToggled = false
        chromeVisible = true
        delay(2_500)
        if (!chromeUserToggled && !menuOpen) chromeVisible = false
    }
    LaunchedEffect(isScrolling) { if (isScrolling) chromeVisible = false }

    val dialogState = remember { mutableStateOf<ReaderDialog?>(null) }
    var dialog by dialogState
    var showCustomChoice by remember { mutableStateOf(false) }
    /** 「查看上一版」弹层（C3·图纸三 §4 画面②）；槽为空或 JSON 损坏时菜单项本就不出现。 */
    var showPreviousDraft by remember { mutableStateOf(false) }
    /** 「本章操作」浮层展开态（卷三 §3.4）：它是锚定浮层不是弹窗族，故不进 [ReaderDialog] 枚举；
     *  托管在屏侧是因为承载它的列表项滚出可视区会被回收，state 留在项里会丢。 */
    var showChapterActionsMenu by remember { mutableStateOf(false) }
    /** 导演台展开态（卷三 §3.4）：sheet 不属弹窗族，同样不进 [ReaderDialog] 枚举。 */
    var showDirector by remember { mutableStateOf(false) }
    /** 递增即让推进区输入卡呼吸一次（建议卡「还想继续写」滚过来后的指路信号·ST11 §4.2）。 */
    var breatheTrigger by remember { mutableIntStateOf(0) }
    // 收尾方式标志（卷二 §4.4 画面④）：结局类型三选是两条路共用的第二步，用它区分选完之后是
    // 「定收尾计划」（从容收尾）还是「立即写结局章」（老 requestEnding）。任何取消路径复位。
    // （「跳过选择」延迟提交标志只在弹窗接线块内读写，随之搬进 StoryReaderDialogHost。）
    val pendingGracefulState = remember { mutableStateOf(false) }
    var pendingGracefulFinale by pendingGracefulState

    val chapterIndex = chapters.indexOfFirst { it.id == currentChapterId }
    val isGenerating = activeGeneration != null
    val isLatestChapter = currentChapter?.id == chapters.lastOrNull()?.id
    // 卷三 §3.3：章末「本章操作」行与顶栏 ⋮ 同源的两个门——旧值原样提为局部变量（同一算法不写两处）。
    // C3 §0.2-6：单槽只在重写发生处有意义 ⇒ 与 canRewrite 同位，外加「槽里真有货」。
    val canRewrite = isLatestChapter && !isGenerating && chapters.isNotEmpty()
    val canViewPreviousDraft = isLatestChapter && !isGenerating && previousDraft != null
    // 「收尾中 · 本弧第 K/L 章」的两个数字（无收尾计划 → null → 推进区显示金调「准备收尾」胶囊）。
    val finaleProgress = story?.let { s ->
        s.finaleEndingType?.let {
            val total = StoryArcPlanning.effectiveArcLength(
                StoryArcPlanning.parseArcPlannedLength(s.storyOutline),
                isFinale = true,
            )
            // 终章弧大纲还没落库时 arcStart 还指着上一条普通弧 → 一律先显示第 1 章，别报个吓人的大数。
            val index = if (s.storyOutline.isNullOrEmpty()) {
                1
            } else {
                StoryArcPlanning.arcIndex(s.currentArcStartChapter, (s.cachedLatestChapterNumber ?: 0))
                    .coerceIn(1, total)
            }
            StoryFinaleProgress(current = index, total = total)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val containerHeight = maxHeight

        StoryMoodBackground(isDark)
        StoryReadingOverlay(isDark)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // 轻触正文空白区切换工具栏显隐；落在选项按钮等子元素上的点击由其自行消费，不会触发此处。
                    detectTapGestures(onTap = {
                        chromeUserToggled = true
                        chromeVisible = !chromeVisible
                    })
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            // 顶部留状态栏高度：沉浸态下首行不被状态栏图标压住；上滑时正文仍会滚进透明状态栏区域显示。
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = 96.dp,
            ),
        ) {
            item(key = "cover") {
                ChapterCover(
                    chapter = currentChapter,
                    isDark = isDark,
                    containerHeight = containerHeight,
                    modifier = Modifier
                        .widthIn(max = StoryReaderLayout.maxContentWidth)
                        .padding(horizontal = StoryReaderLayout.horizontalPadding),
                )
            }
            // 卷三 C3：「上回说到」——隔了一阵回来才出（判定见 StoryReaderViewModel.recapSummary），封面之后正文之前。
            recapSummary?.let { summary ->
                item(key = "recap") {
                    // 收起只记在本次进程内（图纸 J5）：重开 App 若仍超阈值会重新展开，语义 = 「又隔了一阵」。
                    var recapExpanded by rememberSaveable(story?.id, chapterKey) { mutableStateOf(true) }
                    Box(
                        Modifier
                            .widthIn(max = StoryReaderLayout.maxContentWidth)
                            .padding(horizontal = StoryReaderLayout.horizontalPadding)
                            .padding(bottom = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        StoryRecapStrip(
                            summary = summary,
                            expanded = recapExpanded,
                            isDark = isDark,
                            onToggle = { recapExpanded = it },
                        )
                    }
                }
            }
            // key 含章节 id：渲染项 id 是章内局部（0,1,2…），切章须避免跨章 key 碰撞复用旧块（致 reveal/onVisible/特效不重触发）。
            items(renderItems, key = { "$chapterKey#${it.id}" }) { item ->
                StoryRenderBlock(
                    item = item,
                    animationsEnabled = performance.allowsRevealAnimations,
                    animatedTextEnabled = performance.allowsAnimatedText,
                    isDark = isDark,
                    typography = typography,
                    modifier = Modifier
                        .widthIn(max = StoryReaderLayout.maxContentWidth)
                        .fillMaxWidth()
                        .padding(horizontal = StoryReaderLayout.horizontalPadding),
                )
            }
            // 建议完结卡（ST11 §3.4）：渲染顺序 正文 → 建议卡 → 选择区/推进区。与选择区可并存（矛盾输出）。
            storyEndingSuggestItem(
                chapter = currentChapter,
                isLatestChapter = isLatestChapter,
                storyStatus = story?.status,
                isDark = isDark,
                onGracefulFinale = {
                    haptics.light()
                    pendingGracefulFinale = true
                    dialog = ReaderDialog.EndingPicker
                },
                onFinish = { haptics.light(); dialog = ReaderDialog.ArchiveConfirm },
                onKeepWriting = {
                    haptics.light()
                    breatheTrigger += 1
                    scope.launch {
                        listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                    }
                },
            )
            // 卷三 §4.1（D-14 锁定次序）：建议卡之后、选择区之前——① 本章操作行 → ② 三档快评行。
            storyChapterEndZoneItems(
                chapter = currentChapter,
                isLatestChapter = isLatestChapter,
                isDark = isDark,
                canRewrite = canRewrite,
                canViewPreviousDraft = canViewPreviousDraft,
                // §3.3：现状恒 true——本章小结对任何章都可编辑（历史章也能改）。
                canEditSummary = true,
                actionsExpanded = showChapterActionsMenu,
                onActionsExpandedChange = { showChapterActionsMenu = it },
                onRewrite = { dialog = ReaderDialog.RewriteConfirm },
                onViewPreviousDraft = { showPreviousDraft = true },
                onEditChapterSummary = { dialog = ReaderDialog.ChapterSummary },
                onRate = viewModel::rateChapter,
            )
            // 完结门（ST10-4）：已完结的书不再渲染**未答**选择——结局清洗前的历史脏数据里可能残留幽灵选择，
            // 一点会经 submitChoice 把书从已完结拉回连载中；已答选择照常显示（选择回顾态）。
            currentChapter
                ?.takeIf { it.hasChoice && (it.userChoice != null || story?.status != StoryStatus.COMPLETED) }
                ?.let { ch ->
                item(key = "choice") {
                    StoryChoiceSection(
                        chapter = ch,
                        isDark = isDark,
                        narrativePerson = narrativePerson,
                        userRoleName = userRoleName,
                        selectedChoiceText = selectedChoiceText,
                        onSubmit = { haptics.light(); viewModel.submitChoice(it) },
                        onOpenCustomInput = { showCustomChoice = true },
                        modifier = Modifier
                            .widthIn(max = StoryReaderLayout.maxContentWidth)
                            .fillMaxWidth()
                            .padding(horizontal = StoryReaderLayout.horizontalPadding),
                    )
                }
            }
            // 章末推进区（ST11 §3.4）：非完结末章、且无待答选择时的方向盘（治「无选项末章一片空白」）。
            storyContinueZoneItem(
                chapter = currentChapter,
                isLatestChapter = isLatestChapter,
                storyStatus = story?.status,
                isDark = isDark,
                breatheTrigger = breatheTrigger,
                finaleProgress = finaleProgress,
                // 草稿卡（图纸 2026-08-05 U-2）：既有 story 读点直透传，零新 StateFlow、VM 零改动（J8）
                draftBeats = story?.pendingChapterBeats,
                draftUserEdited = story?.pendingBeatsUserEdited == true,
                // 卷三 §4.5：输入卡改指导演台（走向 + 节拍两栏）；选择区的自由输入入口不动，仍走 showCustomChoice。
                onWriteClick = { haptics.light(); showDirector = true },
                onFlowClick = viewModel::forceContinue,
                onFinaleClick = { dialog = ReaderDialog.FinaleMethod },
                onCancelFinaleClick = { dialog = ReaderDialog.FinaleCancelConfirm },
            )
        }

        BottomCapsule(
            hasPrev = chapterIndex > 0,
            hasNext = chapterIndex in 0 until (chapters.size - 1),
            showContinueArc = chapterIndex == chapters.lastIndex && story?.status == StoryStatus.COMPLETED,
            progressPercent = progressPercent,
            remainingMinutes = remainingMinutes,
            // 反悔窗口内让位给底部撤销条（二者同占底部中央·互斥）。
            visible = chromeVisible && !pendingActive,
            onPrev = viewModel::goPrevious,
            onNext = viewModel::goNext,
            onContinueArc = viewModel::continueCompletedStory,
            isDark = isDark,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // 章末选择的非阻塞撤销条（J2·屏级浮层·浮于内容之上不挡阅读）。
        StoryUndoBar(
            choiceText = selectedChoiceText.orEmpty(),
            remainingSeconds = pendingRemaining,
            visible = pendingActive && selectedChoiceText != null,
            onCancel = { haptics.light(); viewModel.cancelPendingChoice() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // 锁态遮罩（追更未到点章节）。1Hz 时钟只给「有解锁时刻」的章起——自由模式书 unlockAt 恒 null，
        // 整本书零时钟（原来不分书都每秒重组一次本屏）。**不许再加「未解锁才起」条件**：到点自动揭开正是
        // 靠这口时钟驱动重判，加了就永远揭不开。键取章 id → 切章重启时钟。
        currentChapter?.takeIf { it.unlockAt != null }?.let { ch ->
            val lockNow by produceState(System.currentTimeMillis(), ch.id) {
                while (true) {
                    value = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            if (!ch.isUnlocked(lockNow)) LockedOverlay(ch, lockNow)
        }

        activeGeneration?.let { gen -> GenerationOverlay(gen, onGoToChat) }

        // 顶栏受 chromeVisible 控制（点击唤出 / 滚动隐入沉浸）；生成中强制保留，确保遮罩下仍可返回 / 操作。
        AnimatedVisibility(
            visible = chromeVisible || isGenerating || menuOpen,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                chapter = currentChapter,
                storyTitle = story?.title,
                isDark = isDark,
                readingAnimationsEnabled = readingAnimationsEnabled,
                fontSizeIndex = fontSizeIndex,
                onBack = onBack,
                // 卷三 §4.6：storyId 还没解析出来时整行不显示（空书页无意义）。
                onOpenBookHub = story?.id?.let { sid -> { onOpenBookHub(sid) } },
                // 开关/字号拨动带轻触觉（菜单本体保持纯视觉，反馈统一在屏级回调）。
                onToggleAnimations = { haptics.light(); viewModel.setReadingAnimations(it) },
                onSetFontSizeIndex = { haptics.selection(); viewModel.setFontSizeIndex(it) },
                onMenuExpandedChange = { menuOpen = it },
            )
        }
    }

    // 自由输入面板。
    if (showCustomChoice) {
        StoryCustomChoiceSheet(
            prompt = currentChapter?.choicePrompt ?: storyDefaultChoicePrompt(narrativePerson, userRoleName),
            hint = storyCustomChoiceHint(narrativePerson, userRoleName),
            onConfirm = { haptics.light(); viewModel.submitChoice(it) },
            onDismiss = { showCustomChoice = false },
        )
    }

    // 导演台（卷三 §4.4 + 已存走向 2026-08-06 §4.5）：走向栏双路——没答过走 submitChoice 创建路（逐字节同旧路），
    // 已答过走覆盖直写 + 撤回；节拍栏走单条定向写。三条写各自独立提交。
    if (showDirector) {
        // 导演台只从末章推进区打开（zone 仅末章渲染）⇒ 走向取当前章即末章；哨兵不当预填文本（准创建态）。
        val latestUserChoice = currentChapter?.userChoice
        StoryDirectorSheet(
            beats = story?.pendingChapterBeats,
            beatsUserEdited = story?.pendingBeatsUserEdited == true,
            savedDirection = latestUserChoice?.takeUnless { StoryChoiceClassifier.isSentinel(it) },
            directionCommitted = latestUserChoice != null,
            onSubmitFlow = { haptics.light(); viewModel.submitChoice(it) },
            onOverwriteDirection = { haptics.light(); viewModel.overwriteDirection(it) },
            onWithdrawDirection = { haptics.light(); viewModel.withdrawDirection() },
            onSaveBeats = viewModel::saveChapterBeats,
            onRestoreAiBeats = viewModel::restoreAiBeats,
            onDismiss = { showDirector = false },
        )
    }

    // C3「上一版」回翻弹层（换回后重读章 → 自然回顶部）。
    previousDraft?.takeIf { showPreviousDraft }?.let { draft ->
        StoryPreviousDraftSheet(
            draft = draft,
            onRestore = { showPreviousDraft = false; haptics.light(); viewModel.restorePreviousDraft() },
            onDismiss = { showPreviousDraft = false },
        )
    }

    // VM 态触发的两个提示弹窗（生成下一章确认 / 错误·搬去 StoryReaderDialogHost，行为不变）。
    StoryReaderAlerts(askNext = askNext, error = error, viewModel = viewModel)

    // ⋮ 菜单弹窗族（接线宿主搬去 StoryReaderDialogHost，行为不变；共享态经 MutableState 桥接）。
    StoryReaderDialogHost(
        dialogState = dialogState,
        pendingGracefulState = pendingGracefulState,
        currentChapter = currentChapter,
        story = story,
        chapters = chapters,
        onGoToChoice = {
            // 「带我去做选择」（ST10-4）：不再是只关弹窗的假按钮——关闭并滚到章末选择区（列表末项；
            // 程序滚动经既有 isScrolling 联动自动隐入沉浸 chrome）。
            dialog = null
            scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
        },
        viewModel = viewModel,
    )
}
