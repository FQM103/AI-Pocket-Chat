package com.situ.aichat.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.prompt.diary.DiaryGuideAnswers
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * U2① 三问引导 sheet（撰写页·契约 §6.2）：升级「让 TA 帮你起个头」为一张温柔的三问 bottom sheet——
 * 事 / 感觉 / 未说出口，**都可留空**（全空 = 按今天聊天+日程写·行为同旧一键直写）。答案经 [onGenerate]
 * → `VM.generateAiDraft(guide)` 注入生成（add-only·§5 安全）。楷体问句 + 陶土「帮我写一段」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreeQuestionGuideSheet(
    onDismiss: () -> Unit,
    onGenerate: (DiaryGuideAnswers) -> Unit,
) {
    val colors = AppTheme.colors
    var event by rememberSaveable { mutableStateOf("") }
    var feeling by rememberSaveable { mutableStateOf("") }
    var unsaid by rememberSaveable { mutableStateOf("") }
    AppSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.diary_guide_title), style = AppTheme.typography.titleSmall, color = colors.text.primary)
            Text(stringResource(R.string.diary_guide_subtitle), style = AppTheme.typography.kaiQuote, color = colors.text.secondary)
            GuideField(stringResource(R.string.diary_guide_q_event), event, stringResource(R.string.diary_guide_hint_event)) { event = it }
            GuideField(stringResource(R.string.diary_guide_q_feeling), feeling, stringResource(R.string.diary_guide_hint_optional)) { feeling = it }
            GuideField(stringResource(R.string.diary_guide_q_unsaid), unsaid, stringResource(R.string.diary_guide_hint_optional)) { unsaid = it }
            AppButton(
                onClick = { onGenerate(DiaryGuideAnswers(event, feeling, unsaid)) },
                style = AppButtonStyle.Primary,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.diary_guide_generate)) }
            Text(
                stringResource(R.string.diary_guide_footer),
                style = AppTheme.typography.caption,
                color = colors.text.tertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 单个引导问句（楷体·陶土字）+ 无框软填充输入（M2 纸面手法·surface.sunken 软底·placeholder tertiary）。 */
@Composable
private fun GuideField(question: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(question, style = AppTheme.typography.kaiQuote, color = colors.accent.text)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppTheme.shapes.small)
                .background(colors.surface.sunken)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = AppTheme.typography.body.copy(color = colors.text.primary),
                cursorBrush = SolidColor(colors.accent.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(placeholder, style = AppTheme.typography.body, color = colors.text.tertiary)
                    }
                    inner()
                },
            )
        }
    }
}
