package com.situ.aichat.ui.story

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.story.StoryRoleType
import com.situ.aichat.ui.designsystem.AppTheme

/**
 * 参演角色的两处呈现（图纸二 D1·2026-08-01 过审 mockup 画面①③），共用同一个 [StoryRoleEditorSheet]：
 * - [StoryRolesSection]：设定页「参演角色」组——已落库的角色行（可编辑 / 移出）+「添加角色」；改动即时落库
 * - [StoryCustomRolesBlock]：创建屏「故事专属角色」块——攒在表单里，随「开始创作」一次性落库
 *
 * 行造型：角色名 + 类型徽章（既有 pill 造型：accent container + accent 字）+ 来源小字（次级色）+ ›。
 * 零新视觉 token。两个块都放在这一个文件里，是为了守住创建屏 +30 行的硬顶（图纸 §9-⑥）。
 */
@Composable
internal fun StoryRolesSection(
    storyId: String,
    roles: List<StoryCharacterRoleEntity>,
    onSave: (StoryCharacterRoleEntity) -> Unit,
    onDelete: (String) -> Unit,
    /** 「私下反差」AI 起草（卷二）；null = 没有故事创作 API 配置 → 起草钮隐藏（提案 §6.3）。 */
    onDraftPersona: (suspend (role: StoryCharacterRoleEntity, name: String, description: String) -> String?)? = null,
) {
    var editing by remember { mutableStateOf<StoryCharacterRoleEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    val userSuffix = stringResource(R.string.story_settings_role_user_suffix)

    SettingsGroup(
        header = stringResource(R.string.story_settings_roles),
        footer = stringResource(R.string.story_roles_footer),
    ) {
        if (roles.isEmpty()) {
            Text(
                stringResource(R.string.story_settings_no_roles),
                style = AppTheme.typography.secondary,
                color = AppTheme.colors.text.secondary,
                modifier = Modifier.padding(14.dp),
            )
        } else {
            roles.forEach { role ->
                RoleRow(
                    name = role.roleName + if (role.isUserRole) userSuffix else "",
                    roleType = role.roleType,
                    source = when {
                        role.isUserRole -> null
                        role.characterId != null -> stringResource(R.string.story_roles_source_chat)
                        else -> stringResource(R.string.story_roles_source_custom)
                    },
                ) { editing = role }
                RowDivider()
            }
        }
        AddRoleRow(stringResource(R.string.story_roles_add)) { adding = true }
    }

    editing?.let { role ->
        StoryRoleEditorSheet(
            initialName = role.roleName,
            initialType = role.roleType,
            initialDescription = role.roleDescription.orEmpty(),
            initialPersona = role.intimatePersona.orEmpty(),
            // 反差是女主侧设定，「我」这一行不给（图纸 §4.5）
            showPersona = !role.isUserRole,
            onDraftPersona = onDraftPersona?.let { draft -> { name, desc -> draft(role, name, desc) } },
            isNew = false,
            // 权限矩阵（图纸 §3.2）：名字归聊天角色本体管；「我」这一行只开描述
            nameEditable = !role.isUserRole && role.characterId == null,
            nameLockedHint = when {
                role.isUserRole -> stringResource(R.string.story_role_editor_name_locked_user)
                role.characterId != null -> stringResource(R.string.story_role_editor_name_locked)
                else -> null
            },
            typeEditable = !role.isUserRole,
            onRemove = if (role.isUserRole) null else ({ onDelete(role.id) }),
            removeNeedsConfirm = true,
            onSave = { name, type, description, persona ->
                onSave(
                    role.copy(
                        roleName = name,
                        roleType = type,
                        roleDescription = description.ifBlank { null },
                        // 「我」那一行没有反差栏，原值原样带过去，绝不被空草稿清掉
                        intimatePersona = if (role.isUserRole) role.intimatePersona else persona.trim().ifBlank { null },
                    ),
                )
            },
            onDismiss = { editing = null },
        )
    }

    if (adding) {
        StoryRoleEditorSheet(
            initialName = "",
            initialType = StoryRoleType.SUPPORTING,
            initialDescription = "",
            initialPersona = "",
            showPersona = true,
            // 新角色还没落库，起草只吃弹层里当前填的名字与人设（characterId 恒 null）
            onDraftPersona = onDraftPersona?.let { draft ->
                { name, desc -> draft(StoryCharacterRoleEntity(storyId = storyId), name, desc) }
            },
            isNew = true,
            nameEditable = true,
            nameLockedHint = null,
            typeEditable = true,
            onRemove = null,
            removeNeedsConfirm = false,
            onSave = { name, type, description, persona ->
                onSave(
                    StoryCharacterRoleEntity(
                        storyId = storyId,
                        roleName = name,
                        roleType = type,
                        roleDescription = description.ifBlank { null },
                        isUserRole = false,
                        characterId = null,
                        intimatePersona = persona.trim().ifBlank { null },
                    ),
                )
            },
            onDismiss = { adding = false },
        )
    }
}

/** 创建屏「故事专属角色」块（开书前攒在表单里，删除无需确认——还没落库）。 */
@Composable
internal fun StoryCustomRolesBlock(
    customRoles: List<CustomRoleDraft>,
    onAdd: (CustomRoleDraft) -> Unit,
    onUpdate: (Int, CustomRoleDraft) -> Unit,
    onRemove: (Int) -> Unit,
) {
    var editingIndex by remember { mutableIntStateOf(-1) }
    var adding by remember { mutableStateOf(false) }
    val c = AppTheme.colors

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.story_create_custom_roles_label), style = AppTheme.typography.label, color = c.text.primary)
        customRoles.forEachIndexed { index, draft ->
            RoleRow(
                name = draft.name,
                roleType = draft.type,
                source = stringResource(R.string.story_roles_source_custom),
            ) { editingIndex = index }
        }
        AddRoleRow(stringResource(R.string.story_create_add_custom_role)) { adding = true }
        Text(stringResource(R.string.story_create_custom_roles_footer), style = AppTheme.typography.secondary, color = c.text.secondary)
    }

    customRoles.getOrNull(editingIndex)?.let { draft ->
        val index = editingIndex
        StoryRoleEditorSheet(
            initialName = draft.name,
            initialType = draft.type,
            initialDescription = draft.description,
            initialPersona = "",
            // 创建屏不给反差栏与起草钮：角色还没落库、上下文太薄，这一栏留给书页（图纸 J5）
            showPersona = false,
            onDraftPersona = null,
            isNew = false,
            nameEditable = true,
            nameLockedHint = null,
            typeEditable = true,
            onRemove = { onRemove(index) },
            removeNeedsConfirm = false,
            onSave = { name, type, description, _ -> onUpdate(index, CustomRoleDraft(name, type, description)) },
            onDismiss = { editingIndex = -1 },
        )
    }

    if (adding) {
        StoryRoleEditorSheet(
            initialName = "",
            initialType = StoryRoleType.SUPPORTING,
            initialDescription = "",
            initialPersona = "",
            showPersona = false,
            onDraftPersona = null,
            isNew = true,
            nameEditable = true,
            nameLockedHint = null,
            typeEditable = true,
            onRemove = null,
            removeNeedsConfirm = false,
            onSave = { name, type, description, _ -> onAdd(CustomRoleDraft(name, type, description)) },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun RoleRow(name: String, roleType: String, source: String?, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            name,
            style = AppTheme.typography.body,
            color = c.text.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RoleTypeBadge(roleType)
        source?.let { Text(it, style = AppTheme.typography.caption, color = c.text.secondary) }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.text.tertiary)
    }
}

/** 类型徽章（主角/配角/反派）——既有故事域 pill 造型：accent 容器底 + accent 字。 */
@Composable
private fun RoleTypeBadge(roleType: String) {
    val c = AppTheme.colors
    Text(
        stringResource(roleTypeLabelRes(roleType)),
        style = AppTheme.typography.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
        color = c.accent.text,
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(c.accent.container)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun AddRoleRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = AppTheme.typography.body,
        color = AppTheme.colors.accent.text,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
    )
}
