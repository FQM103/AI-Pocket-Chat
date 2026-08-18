@file:OptIn(ExperimentalMaterial3Api::class)

package com.situ.aichat.ui.story

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.story.StoryRoleType
import com.situ.aichat.ui.designsystem.AppButton
import com.situ.aichat.ui.designsystem.AppButtonStyle
import com.situ.aichat.ui.designsystem.AppDialog
import com.situ.aichat.ui.designsystem.AppDialogTone
import com.situ.aichat.ui.designsystem.AppSegmentedControl
import com.situ.aichat.ui.designsystem.AppSheet
import com.situ.aichat.ui.designsystem.AppTextArea
import com.situ.aichat.ui.designsystem.AppTextField
import com.situ.aichat.ui.designsystem.AppTheme
import kotlinx.coroutines.launch

/**
 * 角色编辑弹层（图纸二 D1·2026-08-01 过审 mockup 画面②）——**设定页与创建屏共用**：
 * 角色名 + 角色定位（三段）+ 人设描述，底部「保存」，编辑既有行时另给一个移出/删除口。
 *
 * 权限矩阵由调用方按行的来源传入（图纸 §3.2）：
 * - **本书专属角色**：名字/定位/描述/移出 全开
 * - **关联聊天角色**：名字只读（名字归 `CharacterEntity` 本体管），定位/描述可改、可移出本书
 * - **「我」（isUserRole）**：只开描述——用户角色的存在性由创建时的「我也参演」语义管着，人称段依赖它，设定页不拆台
 *
 * 自建角色的外貌/口癖/称呼一律写进**一段自由描述**（2026-08-01 用户拍板：不分字段、零迁移），
 * 提示词侧零改动即可消费（`StoryPromptSections` 对 characterId=null 的行本就只读 roleDescription）。
 *
 * 故事二期卷二加一栏「私下反差」（提案 §5.1/§6.3·mockup 屏 5）：写她私下与人前的反差，落
 * `StoryCharacterRoleEntity.intimatePersona`。「我」那一行不显示（反差是女主侧设定）；创建屏也不显示
 * （角色还没落库、上下文太薄，AI 起草与该栏一并留给书页·图纸 J5）。
 */
@Composable
internal fun StoryRoleEditorSheet(
    initialName: String,
    initialType: String,
    initialDescription: String,
    /** 「私下反差」初值；[showPersona] 为 false 时忽略。 */
    initialPersona: String,
    /** 是否显示反差栏（「我」这一行与创建屏都不显示·图纸 J5/§4.5）。 */
    showPersona: Boolean,
    /**
     * AI 起草回调（拿弹层里**当前**的名字与人设去起草）；null = 不给起草钮
     * （创建屏恒 null；书页在没有故事创作 API 配置时也传 null）。返回 null = 起草失败。
     */
    onDraftPersona: (suspend (name: String, description: String) -> String?)?,
    isNew: Boolean,
    nameEditable: Boolean,
    /** 名字不可改时的解释文案（两种锁定原因不同：关联聊天角色 / 「我」这一行）；可改时传 null。 */
    nameLockedHint: String?,
    typeEditable: Boolean,
    /** null = 不给移出/删除口（新建态、「我」这一行）。 */
    onRemove: (() -> Unit)?,
    /** 已落库的行（设定页）移出前先弹确认；创建屏还没落库，直接从表单移除。 */
    removeNeedsConfirm: Boolean,
    onSave: (name: String, type: String, description: String, persona: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initialName) }
    var type by remember { mutableStateOf(initialType) }
    var description by remember { mutableStateOf(initialDescription) }
    var persona by remember { mutableStateOf(initialPersona) }
    var drafting by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (isNew) R.string.story_role_editor_title_new else R.string.story_role_editor_title_edit),
                style = AppTheme.typography.titleMedium,
            )

            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.story_role_editor_name),
                enabled = nameEditable,
                supportingText = if (nameEditable) null else nameLockedHint,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.story_role_editor_type), style = AppTheme.typography.label, color = AppTheme.colors.text.primary)
            AppSegmentedControl(
                options = listOf(StoryRoleType.PROTAGONIST, StoryRoleType.SUPPORTING, StoryRoleType.ANTAGONIST),
                selected = type,
                onSelect = { type = it },
                enabled = typeEditable,
                modifier = Modifier.fillMaxWidth(),
                label = { value -> stringResource(roleTypeLabelRes(value)) },
            )

            Text(stringResource(R.string.story_role_editor_desc), style = AppTheme.typography.label, color = AppTheme.colors.text.primary)
            AppTextArea(
                value = description,
                onValueChange = { description = it },
                placeholder = stringResource(R.string.story_role_editor_desc_hint),
                minHeight = 120.dp,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            if (showPersona) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.story_role_persona_label),
                        style = AppTheme.typography.label,
                        color = AppTheme.colors.text.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    HubTagChip(stringResource(R.string.story_hub_tag_new), highlighted = true)
                    Spacer(Modifier.weight(1f))
                    if (onDraftPersona != null) {
                        DraftPersonaButton(drafting) {
                            drafting = true
                            scope.launch {
                                val drafted = onDraftPersona(name.trim(), description)
                                if (drafted != null) {
                                    persona = drafted
                                } else {
                                    Toast.makeText(context, R.string.story_role_persona_failed, Toast.LENGTH_SHORT).show()
                                }
                                drafting = false
                            }
                        }
                    }
                }
                AppTextArea(
                    value = persona,
                    onValueChange = { persona = it },
                    placeholder = stringResource(R.string.story_role_persona_placeholder),
                    minHeight = 100.dp,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.story_role_persona_hint),
                    style = AppTheme.typography.caption.copy(fontSize = 10.5.sp),
                    color = AppTheme.colors.text.tertiary,
                )
            }

            AppButton(
                onClick = { onSave(name.trim(), type, description, persona); onDismiss() },
                style = AppButtonStyle.Primary,
                // 空名字的角色没法被提示词引用，保存置灰（E11）
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_save)) }

            if (onRemove != null) {
                AppButton(
                    onClick = { if (removeNeedsConfirm) confirmRemove = true else { onRemove(); onDismiss() } },
                    style = AppButtonStyle.Text,
                    danger = true,
                ) {
                    Text(stringResource(if (removeNeedsConfirm) R.string.story_role_editor_remove else R.string.action_delete))
                }
            }
        }
    }

    if (confirmRemove && onRemove != null) {
        AppDialog(
            onDismissRequest = { confirmRemove = false },
            title = stringResource(R.string.story_role_editor_remove_title),
            body = stringResource(R.string.story_role_editor_remove_body),
            confirmText = stringResource(R.string.story_role_editor_remove),
            onConfirm = { confirmRemove = false; onRemove(); onDismiss() },
            confirmTone = AppDialogTone.Danger,
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { confirmRemove = false },
        )
    }
}

/** ✦ AI 起草胶囊钮：起草中换文案并禁用（防重入·E7）。 */
@Composable
private fun DraftPersonaButton(drafting: Boolean, onClick: () -> Unit) {
    AppButton(
        onClick = onClick,
        style = AppButtonStyle.Tonal,
        enabled = !drafting,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            stringResource(if (drafting) R.string.story_role_persona_drafting else R.string.story_role_persona_draft),
            style = AppTheme.typography.caption.copy(fontSize = 11.sp),
        )
    }
}

/** 角色定位文案（创建屏 `RoleTypeSelector` 同一组词条）。 */
internal fun roleTypeLabelRes(type: String): Int = when (type) {
    StoryRoleType.PROTAGONIST -> R.string.story_role_protagonist
    StoryRoleType.ANTAGONIST -> R.string.story_role_antagonist
    else -> R.string.story_role_supporting
}
