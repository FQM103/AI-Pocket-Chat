package com.situ.aichat.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.situ.aichat.R

/**
 * 设置页通用开关行（可选前置图标 + 标题 + 可选副标题 + 尾部 Switch；整行可点切换）。多个设置页复用。
 * 用 M3 ListItem，与 ProfileScreen 的列表项观感一致。icon 默认空 = 既有调用点观感不变
 * （SETTINGS_REORG D7：设置主页两处开关行传图标，与紧凑行左缘对齐）。
 *
 * 无障碍（P15·P0-10）：整行 `toggleable(role = Role.Switch)`，Switch 传 `onCheckedChange = null`（不独立获焦），
 * 消除 TalkBack 双焦点；`stateDescription` 播报开/关态——对齐 iOS 原生 Toggle 自带的 isToggle trait + 状态朗读。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val onText = stringResource(R.string.a11y_switch_on)
    val offText = stringResource(R.string.a11y_switch_off)
    val haptics = LocalAppHaptics.current
    ListItem(
        leadingContent = icon?.let {
            { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
        },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let {
            { Text(it, style = MaterialTheme.typography.bodySmall) }
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
        // 透明底：卡壳内 ListItem 默认 containerColor 会画一块不透明矩形盖住呼吸白（J2）；
        // 设置主页现 appCardSurface 内亦不变（透明=透出组卡同色底）。
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                // 开=脆、关=柔（乙 1·H6，同 AppSwitch H5 口径）。触觉挂在整行 toggleable 上——
                // 尾部 Switch 是 onCheckedChange=null 的纯显示件，点击本就由这里接管。
                onValueChange = { value ->
                    if (value) haptics.light() else haptics.soft()
                    onCheckedChange(value)
                },
            )
            .semantics { stateDescription = if (checked) onText else offText },
    )
}
