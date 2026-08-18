package com.situ.aichat.ui.story

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.story.StoryEndingType
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTheme

// ── 阅读器菜单触发的对话框组（续写 / 结局选择 / 重写 / 本章小结 / 角色现状）──
// 从 StoryReaderScreen 抽出（纯搬 composable·只改 ReaderDialogs private→internal 供主屏跨文件调用）。

/** 阅读器本地菜单触发的对话框类型。 */
internal enum class ReaderDialog {
    Continue, EndingPicker, EndingCustom, EndingPending, RewriteConfirm, RewriteInstruction,

    /** 建议卡「就此完结」的确认（ST11 §4.3·复用书架归档确认的四条文案，零新文案零新样式）。 */
    ArchiveConfirm,

    /** 收尾**方式**二选（卷二 §4.4 画面④）：从容收尾 / 立即结局 → 再进既有的结局类型三选。 */
    FinaleMethod,

    /** 「取消收尾」确认（卷二 §4.4）。 */
    FinaleCancelConfirm,

    /** 「本章小结」编辑（C4·对当前打开的那一章生效）与「角色现状」只读速览（D5）·图纸三 §4 画面③。 */
    ChapterSummary, CharacterStates,
}

/** 角色现状只读区封顶高度：内容长时内部滚动，确认键不被顶出弹窗。 */
private val STATES_MAX_HEIGHT = 320.dp

@Composable
internal fun ReaderDialogs(
    dialog: ReaderDialog?,
    currentHasPendingChoice: Boolean,
    storyTitle: String?,
    chapterSummary: String?,
    characterStates: String?,
    onSaveChapterSummary: (String) -> Unit,
    onDismiss: () -> Unit,
    onFinishStory: () -> Unit,
    onForceContinue: () -> Unit,
    onEnding: (String, String?) -> Unit,
    onSkipChoiceThenEnding: () -> Unit,
    onGoToChoice: () -> Unit,
    onOpenEndingCustom: () -> Unit,
    onOpenRewriteInstruction: () -> Unit,
    onRewrite: (String?) -> Unit,
    onPickGracefulFinale: () -> Unit,
    onPickImmediateEnding: () -> Unit,
    onCancelFinale: () -> Unit,
) {
    when (dialog) {
        ReaderDialog.Continue -> AppDialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.story_alert_continue_title),
            body = if (currentHasPendingChoice) {
                stringResource(R.string.story_alert_continue_pending_msg)
            } else {
                stringResource(R.string.story_alert_continue_msg)
            },
            confirmText = stringResource(R.string.story_alert_continue_confirm),
            onConfirm = onForceContinue,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
        )

        // 建议卡「就此完结」的确认（ST11 §4.3）：复用书架长按「完结归档」的同一套文案与样式——
        // 同一个动作（都走 StoryArchiver），用户看到的问法就该一模一样。
        ReaderDialog.ArchiveConfirm -> AppDialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.story_archive_confirm_title),
            body = stringResource(R.string.story_archive_confirm_msg, storyTitle.orEmpty()),
            confirmText = stringResource(R.string.story_archive_confirm_action),
            onConfirm = onFinishStory,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
        )

        // 卷二 §4.4 画面④：收尾**方式**二选（从容收尾=推荐金调卡 / 立即结局=老行为），选完再进既有的
        // 结局类型三选（EndingPicker 原样复用）。推进区「准备收尾」与菜单「请求结局」共用这一个入口。
        ReaderDialog.FinaleMethod -> AppDialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.story_finale_sheet_title),
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            content = {
                Column {
                    FinaleMethodOption(
                        title = stringResource(R.string.story_finale_option_graceful),
                        description = stringResource(R.string.story_finale_option_graceful_desc),
                        recommended = true,
                        onClick = onPickGracefulFinale,
                    )
                    Spacer(Modifier.height(9.dp))
                    FinaleMethodOption(
                        title = stringResource(R.string.story_finale_option_immediate),
                        description = stringResource(R.string.story_finale_option_immediate_desc),
                        recommended = false,
                        onClick = onPickImmediateEnding,
                    )
                }
            },
        )

        // 「取消收尾」确认（复用既有确认弹窗样式·文案 §4.4）。
        ReaderDialog.FinaleCancelConfirm -> AppDialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.story_finale_cancel),
            body = stringResource(R.string.story_finale_cancel_confirm),
            confirmText = stringResource(R.string.story_finale_cancel),
            onConfirm = onCancelFinale,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
        )

        ReaderDialog.EndingPicker -> AppDialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.story_ending_picker_title),
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            content = {
                Column {
                    Text(stringResource(R.string.story_ending_picker_msg))
                    Spacer(Modifier.height(12.dp))
                    AppButton(onClick = { onEnding(StoryEndingType.OPEN, null) }, style = AppButtonStyle.Text, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.story_ending_open))
                    }
                    AppButton(onClick = { onEnding(StoryEndingType.AI, null) }, style = AppButtonStyle.Text, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.story_ending_ai))
                    }
                    AppButton(onClick = onOpenEndingCustom, style = AppButtonStyle.Text, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.story_ending_custom))
                    }
                }
            },
        )

        ReaderDialog.EndingCustom -> TextInputDialog(
            title = stringResource(R.string.story_ending_custom_title),
            message = stringResource(R.string.story_ending_custom_msg),
            hint = stringResource(R.string.story_ending_custom_hint),
            confirmLabel = stringResource(R.string.action_confirm),
            onConfirm = { onEnding(StoryEndingType.CUSTOM, it) },
            onDismiss = onDismiss,
        )

        // ST10-4：竖排双钮（主=跳过直写结局·次=真的带用户滚到选择区，不再是只关弹窗的假按钮）。
        ReaderDialog.EndingPending -> AppDialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.story_ending_pending_title),
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            content = {
                Column {
                    Text(stringResource(R.string.story_ending_pending_msg))
                    Spacer(Modifier.height(12.dp))
                    AppButton(onClick = onSkipChoiceThenEnding, style = AppButtonStyle.Primary, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.story_ending_pending_skip))
                    }
                    Spacer(Modifier.height(8.dp))
                    AppButton(onClick = onGoToChoice, style = AppButtonStyle.Tonal, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.story_ending_pending_go))
                    }
                }
            },
        )

        ReaderDialog.RewriteConfirm -> AppDialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.story_rewrite_title),
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            content = {
                Column {
                    Text(stringResource(R.string.story_rewrite_msg))
                    Spacer(Modifier.height(12.dp))
                    AppButton(onClick = { onRewrite(null) }, style = AppButtonStyle.Text, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.story_rewrite_direct))
                    }
                    AppButton(onClick = onOpenRewriteInstruction, style = AppButtonStyle.Text, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.story_rewrite_with_instruction))
                    }
                }
            },
        )

        ReaderDialog.RewriteInstruction -> TextInputDialog(
            title = stringResource(R.string.story_rewrite_instruction_title),
            message = stringResource(R.string.story_rewrite_instruction_msg),
            hint = stringResource(R.string.story_rewrite_instruction_hint),
            confirmLabel = stringResource(R.string.story_rewrite_instruction_confirm),
            onConfirm = { onRewrite(it.ifEmpty { null }) },
            onDismiss = onDismiss,
        )

        // C4（图纸三 §4 画面③）：复用同一个多行输入弹窗，只多了「初值回显」——改的是当前章的小结。
        ReaderDialog.ChapterSummary -> TextInputDialog(
            title = stringResource(R.string.story_reader_menu_summary),
            message = stringResource(R.string.story_summary_edit_msg),
            hint = stringResource(R.string.story_summary_edit_hint),
            confirmLabel = stringResource(R.string.action_save),
            initialText = chapterSummary.orEmpty(),
            onConfirm = onSaveChapterSummary,
            onDismiss = onDismiss,
        )

        // D5（图纸三 §4 画面③）：只读速览，零新数据面——显示的就是 story 级滚动值 characterStates。
        ReaderDialog.CharacterStates -> AppDialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.story_reader_menu_states),
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = onDismiss,
            content = {
                Text(
                    text = characterStates?.takeIf { it.isNotBlank() } ?: stringResource(R.string.story_states_empty),
                    modifier = Modifier
                        .heightIn(max = STATES_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                )
            },
        )

        null -> Unit
    }
}

/**
 * 收尾方式的一张可点选项卡（§4.4 画面④）：标题 + 说明两行。
 *
 * [recommended]=true 走金调（底 8%/边 28% = 建议完结卡同族 alpha，标题取金）；false 走弹窗默认的中性描边卡。
 * 金色取设计系统的 [com.situ.aichat.ui.designsystem.AppColors] 强调族——弹窗不在阅读器心情纸面上，
 * 不能用推进区那套随心情换肤的金。
 */
@Composable
private fun FinaleMethodOption(
    title: String,
    description: String,
    recommended: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val gold = colors.economy.gold
    Surface(
        onClick = onClick,
        shape = AppShapes.medium,
        color = if (recommended) gold.copy(alpha = SUGGEST_GOLD_FILL_ALPHA) else Color.Transparent,
        border = BorderStroke(
            0.75.dp,
            if (recommended) gold.copy(alpha = SUGGEST_GOLD_LINE_ALPHA) else colors.surface.stroke,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                title,
                color = if (recommended) gold else colors.text.primary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                description,
                color = colors.text.secondary,
                fontSize = 12.5.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    message: String,
    hint: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialText: String = "",
) {
    var text by remember { mutableStateOf(initialText) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmText = confirmLabel,
        onConfirm = { onConfirm(text.trim()); onDismiss() },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            Column {
                Text(message)
                Spacer(Modifier.height(12.dp))
                AppTextArea(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = hint,
                    minHeight = 80.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}
