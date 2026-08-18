package com.situ.aichat.ui.story

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.story.StoryChapterDraft
import com.situ.aichat.story.StoryTextSanitizer
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTheme

/** 正文只读区封顶高度：超出内部滚动，保证底部「换回这一版」永远够得着（C1 教训：sheet 按钮不许被顶出屏幕）。 */
private val PROSE_MAX_HEIGHT = 380.dp

/**
 * 「上一版」只读回翻弹层（C3·图纸三 §4 画面②）。
 *
 * 展示重写前保留的那一版：标题行「上一版 ·〈旧稿标题〉」+ 副行说明 + 正文只读滚动
 * （过 [StoryTextSanitizer] 剥沉浸标签与尾部元数据——生肉零泄漏是全故事域红线）；
 * 底部「换回这一版」→ 确认弹窗，弹窗文案如实告知「只换正文与选项，故事记忆仍以最新一次生成为准」。
 *
 * 纯展示 + 一个回调，不持有任何业务状态：互换本体在 [StoryReaderViewModel.restorePreviousDraft]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoryPreviousDraftSheet(
    draft: StoryChapterDraft,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirming by remember { mutableStateOf(false) }
    val prose = remember(draft.content) { StoryTextSanitizer.sanitize(draft.content.orEmpty()) }
    val title = draft.title?.takeIf { it.isNotBlank() }

    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.story_prev_draft_title) + (title?.let { " · $it" } ?: ""),
                style = AppTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.story_prev_draft_sub),
                style = AppTheme.typography.secondary,
                color = AppTheme.colors.text.secondary,
            )
            // 正文只读：衬线体与阅读器正文同族；封顶后内部滚动。
            Text(
                text = prose,
                style = AppTheme.typography.body,
                fontFamily = FontFamily.Serif,
                color = AppTheme.colors.text.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = PROSE_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            )
            AppButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.story_prev_draft_restore))
            }
        }
    }

    if (confirming) {
        AppDialog(
            onDismissRequest = { confirming = false },
            title = stringResource(R.string.story_prev_draft_confirm_title),
            body = stringResource(R.string.story_prev_draft_confirm_msg),
            confirmText = stringResource(R.string.story_prev_draft_confirm_action),
            onConfirm = { confirming = false; onRestore() },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { confirming = false },
        )
    }
}
