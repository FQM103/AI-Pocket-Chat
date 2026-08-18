package com.situ.aichat.ui.designsystem

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.situ.aichat.ui.components.LocalAppHaptics

/**
 * 全局开关（**M3 [Switch] 的触觉包壳**·乙 1）：视觉/语义/手势全托 M3，只加一层指尖反馈——
 * 开 = [com.situ.aichat.ui.components.AppHaptics.light]（脆）、关 = [com.situ.aichat.ui.components.AppHaptics.soft]（柔）。
 * 方向感做进触觉里：不用看屏幕，指尖就知道刚才是开还是关。
 *
 * 为什么包壳而不是 17 处各加两行：那是 34 行重复逻辑，且往后每加一个新 Switch 必漏
 * （CLAUDE.md §2「同一段逻辑只写一处」）。签名与 M3 [Switch] **同形**，故 17 处收编只需机械换名，
 * 参数原样透传、零参数手术、零视觉分叉（设计语言 §5「行为重组件包壳 M3 调参数不重写」口径）。
 *
 * [onCheckedChange] 为 null = 纯显示态（如整行 `toggleable` 已接管点击的 [com.situ.aichat.ui.components.SettingsSwitchRow]）：
 * **直接透传 null，不包装**——此时开关自己不可点，包了也永不触发，徒增一层 lambda（E10）。
 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
) {
    val haptics = LocalAppHaptics.current
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange?.let { callback ->
            { value -> if (value) haptics.light() else haptics.soft(); callback(value) }
        },
        modifier = modifier,
        enabled = enabled,
        colors = colors,
    )
}
