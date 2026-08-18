package com.situ.aichat.ui.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.situ.aichat.ui.components.AppMotion

/**
 * Fable-5 设计系统**对外唯一 API**（Glovo 式混合架构·见 [FABLE5_DESIGN_LANGUAGE.md] §5）。
 *
 * 组件经 `AppTheme.colors / typography / shapes / spacing / motion` 取 token，**禁直引 [Palette]、禁在
 * feature 代码 import androidx.compose.material3 配色**（行为重组件 TextField/Sheet/Menu 仍包壳 M3）。
 * 触觉经现有 `LocalAppHaptics`（[com.situ.aichat.ui.components.LocalAppHaptics]）。
 *
 * colors 随主题（深/浅/AMOLED）切换；typography/shapes/spacing/motion 主题无关。
 */
object AppTheme {

    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current

    val typography: AppTypography get() = AppTypography

    val shapes: AppShapes get() = AppShapes

    val spacing: AppSpacing get() = AppSpacing

    val motion: AppMotion get() = AppMotion
}
