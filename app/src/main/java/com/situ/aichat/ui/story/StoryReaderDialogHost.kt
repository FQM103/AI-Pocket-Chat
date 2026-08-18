package com.situ.aichat.ui.story

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.ui.designsystem.AppDialog

// ── 阅读器屏级弹窗接线层 ──
// 从 StoryReaderScreen 抽出（只搬不改·参数与原局部变量同名，弹窗体字节级不变）。
// 弹窗「长相字典」（ReaderDialog 枚举 + ReaderDialogs 分发）在 StoryReaderDialogs.kt；本文件只管屏级接线。

/** VM 态直驱的两个提示弹窗：askNext（选择已存 → 问是否生成下一章）与 error（失败提示·retryable 给重试键）。 */
@Composable
internal fun StoryReaderAlerts(
    askNext: Boolean,
    error: StoryReaderError?,
    viewModel: StoryReaderViewModel,
) {
    // 选择已存 → 问是否立刻生成下一章。
    if (askNext) {
        AppDialog(
            onDismissRequest = viewModel::dismissAskNext,
            title = stringResource(R.string.story_alert_next_title),
            body = stringResource(R.string.story_alert_next_msg),
            confirmText = stringResource(R.string.story_alert_next_now),
            onConfirm = viewModel::generateNext,
            dismissText = stringResource(R.string.story_alert_next_later),
            onDismiss = viewModel::dismissAskNext,
        )
    }

    // 错误提示。生成失败（retryable）给重试键；操作类失败维持只有确认键。
    error?.let { err ->
        AppDialog(
            onDismissRequest = viewModel::dismissError,
            title = stringResource(R.string.story_alert_error_title),
            body = err.message,
            confirmText = if (err.retryable) {
                stringResource(R.string.story_alert_error_retry)
            } else {
                stringResource(R.string.action_confirm)
            },
            onConfirm = if (err.retryable) viewModel::retryGeneration else viewModel::dismissError,
            dismissText = if (err.retryable) stringResource(R.string.action_confirm) else null,
            onDismiss = viewModel::dismissError,
        )
    }
}

/**
 * ⋮ 菜单弹窗族的流转宿主：[ReaderDialogs] 的全部接线（两步收尾 / 跳过延迟提交的状态流转都在这里）。
 *
 * [dialogState] / [pendingGracefulState] 是屏侧共享态（章末各区与建议卡也写它们），经 MutableState
 * 传入并在体内 `by` 桥接，块内读写保持原字节；「跳过选择」标志除声明外只在本块读写，连声明整体搬入。
 * [onGoToChoice] 要滚动列表（碰屏的 scope/listState），留屏侧构造注入。
 */
@Composable
internal fun StoryReaderDialogHost(
    dialogState: MutableState<ReaderDialog?>,
    pendingGracefulState: MutableState<Boolean>,
    currentChapter: StoryChapterEntity?,
    story: StoryEntity?,
    chapters: List<StoryChapterEntity>,
    onGoToChoice: () -> Unit,
    viewModel: StoryReaderViewModel,
) {
    var dialog by dialogState
    var pendingGracefulFinale by pendingGracefulState
    // 结局前「跳过选择」延迟提交标志（ST10-4）：EndingPending 点「跳过」只置此标志并切结局三选，
    // 真正的跳过写库与结局请求在用户选定结局类型那一刻绑定提交；任何取消路径复位 = 完全无痕。
    var pendingSkipForEnding by remember { mutableStateOf(false) }

    // 菜单触发的对话框。
    ReaderDialogs(
        dialog = dialog,
        currentHasPendingChoice = currentChapter?.let { it.hasChoice && it.userChoice == null } == true,
        storyTitle = story?.title,
        chapterSummary = currentChapter?.chapterSummary,
        characterStates = story?.characterStates,
        onSaveChapterSummary = { dialog = null; viewModel.saveChapterSummary(it) },
        onDismiss = { dialog = null; pendingSkipForEnding = false; pendingGracefulFinale = false },
        onFinishStory = { dialog = null; viewModel.finishStory() },
        onForceContinue = { dialog = null; viewModel.forceContinue() },
        // 结局类型三选是两条收尾路共用的第二步：从容收尾 → 定计划（不立刻生成）；立即结局 → 老 requestEnding 一字不动。
        onEnding = { type, detail ->
            dialog = null
            if (pendingGracefulFinale) {
                viewModel.planFinale(type, detail)
            } else {
                viewModel.requestEnding(type, detail, skipPendingChoice = pendingSkipForEnding)
            }
            pendingSkipForEnding = false
            pendingGracefulFinale = false
        },
        onSkipChoiceThenEnding = {
            pendingSkipForEnding = true
            dialog = ReaderDialog.EndingPicker
        },
        onGoToChoice = onGoToChoice,
        onOpenEndingCustom = { dialog = ReaderDialog.EndingCustom },
        onOpenRewriteInstruction = { dialog = ReaderDialog.RewriteInstruction },
        onRewrite = { instruction -> dialog = null; viewModel.rewrite(instruction) },
        // 收尾方式二选（§4.4 画面④）：两条路都接既有的结局类型三选，只是选完之后的落点不同。
        onPickGracefulFinale = {
            pendingGracefulFinale = true
            dialog = ReaderDialog.EndingPicker
        },
        onPickImmediateEnding = {
            pendingGracefulFinale = false
            // 立即结局 = 老行为原样：末章还挂着未答选择时，先走「跳过选择」那道既有闸。
            val latest = chapters.lastOrNull()
            dialog = if (latest != null && latest.hasChoice && latest.userChoice == null) {
                ReaderDialog.EndingPending
            } else {
                ReaderDialog.EndingPicker
            }
        },
        onCancelFinale = { dialog = null; viewModel.cancelFinale() },
    )
}
