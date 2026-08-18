@file:OptIn(ExperimentalMaterial3Api::class)

package com.situ.aichat.ui.story

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.model.UserStoryTemplatePayload
import com.situ.aichat.story.StoryChapterLength
import com.situ.aichat.story.StoryCreationCatalog
import com.situ.aichat.story.StoryEditableField
import com.situ.aichat.story.StoryNarrativePerson
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 书页「连载与管理」组里的**创作设定七行 + 存为我的模板行**（卷二：自退役的
 * `StorySettingsCreativeGroup` **只搬不改**——行、弹窗、草稿心智、题材空白兜底全部逐字照旧）。
 *
 * 七字段（题材/文风/人称/章长/聊天影响/世界观/剧情方向）仍走 [StorySettingsDraft]，离开书页由 `persist()`
 * 一次性落库；题材是自由文本 sheet（自定义题材合法），其余四项是封闭枚举单选弹窗。
 * 单独成文件是为了守住 [storyHubSettingsItems] 那一侧的行数上限（CLAUDE.md §2）。
 */
@Composable
internal fun ColumnScope.StoryHubCreativeRows(
    d: StorySettingsDraft,
    update: ((StorySettingsDraft) -> StorySettingsDraft) -> Unit,
    templateCount: Int,
    defaultTemplateName: String,
    onSaveTemplate: (String) -> Unit,
) {
    var dialog by remember { mutableStateOf<HubCreativeChoice?>(null) }
    var sheet by remember { mutableStateOf<HubCreativeTextField?>(null) }
    var namingTemplate by remember { mutableStateOf(false) }
    var atTemplateLimit by remember { mutableStateOf(false) }
    val unfilled = stringResource(R.string.story_create_unfilled)

    NavRow(stringResource(R.string.story_settings_genre_row), d.genre.ifBlank { unfilled }) { sheet = HubCreativeTextField.GENRE }
    RowDivider()
    NavRow(stringResource(R.string.story_settings_style_row), d.writingStyle.ifBlank { unfilled }) { dialog = HubCreativeChoice.STYLE }
    RowDivider()
    NavRow(stringResource(R.string.story_settings_person_row), narrativeName(d.narrativePerson)) { dialog = HubCreativeChoice.PERSON }
    RowDivider()
    NavRow(stringResource(R.string.story_settings_field_length), chapterLengthName(chapterLengthOf(d.chapterLengthPreference))) {
        dialog = HubCreativeChoice.LENGTH
    }
    RowDivider()
    NavRow(stringResource(R.string.story_settings_field_influence), chatInfluenceName(d.chatInfluenceWeight)) {
        dialog = HubCreativeChoice.INFLUENCE
    }
    RowDivider()
    NavRow(stringResource(R.string.story_settings_world_row), creativeRowSummary(d.worldSetting, unfilled)) { sheet = HubCreativeTextField.WORLD }
    RowDivider()
    NavRow(stringResource(R.string.story_settings_plot_row), creativeRowSummary(d.plotDirection, unfilled)) { sheet = HubCreativeTextField.PLOT }
    // 图纸四：存为「我的模板」——存的就是这一组的东西，落在组末最顺。到顶时点了直接弹上限提示。
    RowDivider()
    NavRow(stringResource(R.string.story_save_template_row), "") {
        if (templateCount >= UserStoryTemplatePayload.MAX_USER_TEMPLATES) atTemplateLimit = true else namingTemplate = true
    }

    when (dialog) {
        HubCreativeChoice.STYLE -> HubChoiceDialog(
            title = stringResource(R.string.story_settings_style_row),
            options = StoryCreationCatalog.writingStyles,
            current = d.writingStyle,
            label = { it },
            onSelect = { v -> update { it.copy(writingStyle = v) } },
            onDismiss = { dialog = null },
        )
        HubCreativeChoice.PERSON -> HubChoiceDialog(
            title = stringResource(R.string.story_settings_person_row),
            // 顺序照创建屏高级表单：第二人称（默认）→ 第一人称 → 第三人称
            options = listOf(StoryNarrativePerson.SECOND, StoryNarrativePerson.FIRST, StoryNarrativePerson.THIRD),
            current = d.narrativePerson,
            label = { narrativeName(it) },
            onSelect = { v -> update { it.copy(narrativePerson = v) } },
            onDismiss = { dialog = null },
        )
        HubCreativeChoice.LENGTH -> HubChoiceDialog(
            title = stringResource(R.string.story_settings_field_length),
            options = StoryChapterLength.entries,
            current = chapterLengthOf(d.chapterLengthPreference),
            label = { chapterLengthName(it) },
            onSelect = { v -> update { it.copy(chapterLengthPreference = v.words) } },
            onDismiss = { dialog = null },
        )
        HubCreativeChoice.INFLUENCE -> HubChoiceDialog(
            title = stringResource(R.string.story_settings_field_influence),
            options = StoryCreationCatalog.chatInfluenceWeights,
            current = d.chatInfluenceWeight,
            label = { chatInfluenceName(it) },
            onSelect = { v -> update { it.copy(chatInfluenceWeight = v) } },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }

    sheet?.let { field ->
        StoryTextEditorSheet(
            title = stringResource(field.titleRes),
            subtitle = when (field) {
                // 十个预设只作参考：题材本身是自由文本（自定义题材合法存在）
                HubCreativeTextField.GENRE -> stringResource(
                    R.string.story_settings_genre_sub,
                    StoryCreationCatalog.genres.joinToString(" / "),
                )
                else -> null
            },
            placeholder = stringResource(field.placeholderRes),
            initialText = when (field) {
                HubCreativeTextField.GENRE -> d.genre
                HubCreativeTextField.WORLD -> d.worldSetting
                HubCreativeTextField.PLOT -> d.plotDirection
            },
            maxLength = null,
            fillDefaultLabel = null,
            fillDefault = null,
            onConfirm = { value ->
                update {
                    when (field) {
                        // 题材空白不在这里兜底：persist 侧统一回退原值（绝不落空题材·图纸 §3.1）
                        HubCreativeTextField.GENRE -> it.copy(genre = value.trim())
                        HubCreativeTextField.WORLD -> it.copy(worldSetting = value)
                        HubCreativeTextField.PLOT -> it.copy(plotDirection = value)
                    }
                }
            },
            onDismiss = { sheet = null },
        )
    }

    if (namingTemplate) {
        HubSaveTemplateDialog(
            defaultName = defaultTemplateName,
            onConfirm = { onSaveTemplate(it); namingTemplate = false },
            onDismiss = { namingTemplate = false },
        )
    }

    if (atTemplateLimit) {
        AppDialog(
            onDismissRequest = { atTemplateLimit = false },
            title = stringResource(R.string.story_save_template_row),
            body = stringResource(R.string.story_save_template_limit),
            confirmText = stringResource(R.string.action_confirm),
            onConfirm = { atTemplateLimit = false },
        )
    }
}

/** 命名弹窗（造型照既有 `RenameDialog`：标题 + 说明 + 单行输入 + 保存/取消；名字空白时保存键禁用·E9）。 */
@Composable
private fun HubSaveTemplateDialog(defaultName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(defaultName)) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.story_save_template_row),
        confirmText = stringResource(R.string.action_save),
        onConfirm = { onConfirm(value.text) },
        confirmEnabled = value.text.trim().isNotEmpty(),
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.story_save_template_msg), style = AppTheme.typography.secondary, color = AppTheme.colors.text.secondary)
                AppTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = stringResource(R.string.story_save_template_name_label),
                )
            }
        },
    )
}

/** 四个封闭枚举字段（单选弹窗）。 */
private enum class HubCreativeChoice { STYLE, PERSON, LENGTH, INFLUENCE }

/** 三个自由文本字段（[StoryTextEditorSheet]）。世界观/剧情方向复用创建屏同一对标题与占位词条。 */
private enum class HubCreativeTextField(val titleRes: Int, val placeholderRes: Int) {
    GENRE(R.string.story_settings_genre_row, R.string.story_settings_genre_placeholder),
    WORLD(R.string.story_field_world_title, R.string.story_field_world_placeholder),
    PLOT(R.string.story_field_plot_title, R.string.story_field_plot_placeholder),
}

/** 行尾值摘要：空 → 「未填写」；否则首 12 字（换行折成空格，长文补省略号）——与设定 Tab 值标同一口径单源。 */
internal fun creativeRowSummary(text: String, emptyLabel: String): String {
    if (text.isBlank()) return emptyLabel
    return StoryEditableField.flattenEcho(text)
}

/** 章长存的是字数（Int 列），四档枚举按 words 反查；查不到按中档显示（同既有回显口径）。 */
internal fun chapterLengthOf(words: Int): StoryChapterLength =
    StoryChapterLength.entries.firstOrNull { it.words == words } ?: StoryChapterLength.MEDIUM

/** 单选弹窗（造型照既有 `ReminderChooserDialog`：标题 + 选项行列表 + 取消）。 */
@Composable
private fun <T> HubChoiceDialog(
    title: String,
    options: List<T>,
    current: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = title,
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            Column {
                options.forEach { option ->
                    ChoiceOptionRow(label(option), selected = option == current) { onSelect(option); onDismiss() }
                }
            }
        },
    )
}
