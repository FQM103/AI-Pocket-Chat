package com.situ.aichat.ui.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Phase 0 全局换装桥：把 Fable-5 暖中性 + 陶土玫 token 灌进 M3 [ColorScheme] 槽位，让现有 110 文件
 * `MaterialTheme.*` 读取点一夜换暖装（未迁移屏也不再是工程师风 Monet），压缩阴阳脸窗口。
 * 已迁移组件直接读 [AppTheme].colors，不经 M3。本桥属设计系统基建（lint 围栏白名单），非 feature 代码。
 */
internal fun brandLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Palette.Clay,
    onPrimary = Palette.OnClayInk, // 深墨字 on 陶土填充（微信式·白字 2.96:1 不达 4.5·WCAG 决议）
    primaryContainer = Palette.ClayWhisper,
    onPrimaryContainer = Palette.ClayInk,
    secondary = Palette.ClayDeep, // 陶土功能深档（白字 5.3:1·中间调 #A8765F 死区不可作文字底）
    onSecondary = Palette.White,
    secondaryContainer = Palette.Linen,
    onSecondaryContainer = Palette.Ink,
    tertiary = Palette.Gold,
    onTertiary = Palette.White,
    tertiaryContainer = Palette.WarnContainer,
    onTertiaryContainer = Palette.OnWarn,
    background = Palette.Porcelain,
    onBackground = Palette.Ink,
    // 沉浸决议（2026-06-13 用户拍板）：surface=background 同色——顶栏/列表等铬面与底无缝（微信式整屏一底），
    // 白色只留给「内容纸张」（AI 气泡/卡片走 AppTheme.colors.surface.raised，不经此槽位）。
    surface = Palette.Porcelain,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Linen,
    onSurfaceVariant = Palette.InkSoft,
    surfaceTint = Palette.Clay,
    outline = Palette.InkFaint,
    outlineVariant = Palette.LinenDeep,
    error = Palette.OnError,
    onError = Palette.White,
    errorContainer = Palette.ErrorContainer,
    onErrorContainer = Palette.OnError,
    inverseSurface = Palette.Ink,
    inverseOnSurface = Palette.Porcelain,
    inversePrimary = Palette.ClayLight,
    scrim = Palette.Scrim,
    // M3 tonal 面阶（NavigationBar/Sheet/Menu/SearchBar 等读 surfaceContainer 族——不映射会落回
    // 基线薰衣草灰）：暖中性等感知步长，仅桥接用不进 Palette/semantic 层。
    surfaceBright = Palette.Porcelain,
    surfaceDim = Color(0xFFE0D9CF),
    surfaceContainerLowest = Palette.White,
    surfaceContainerLow = Color(0xFFF6F1EA),
    surfaceContainer = Palette.Linen,
    surfaceContainerHigh = Color(0xFFECE5DB),
    surfaceContainerHighest = Color(0xFFE7E0D5),
)

internal fun brandDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Palette.Clay, // 深档填充也保浅陶配深墨字（#A8765F 死区配深字仅 3.71:1）
    onPrimary = Palette.OnClayInk,
    primaryContainer = Palette.ClayWhisperDark,
    onPrimaryContainer = Palette.ClayCream,
    secondary = Palette.ClayLight,
    onSecondary = Palette.OnClayInk,
    secondaryContainer = Palette.Bark,
    onSecondaryContainer = Palette.Cream,
    tertiary = Palette.GoldDark,
    onTertiary = Palette.Espresso,
    tertiaryContainer = Palette.WarnContainerDark,
    onTertiaryContainer = Palette.OnWarnDark,
    background = Palette.Espresso,
    onBackground = Palette.Cream,
    // 沉浸决议：深档同口径 surface=background（深暖灰一底到边）。
    surface = Palette.Espresso,
    onSurface = Palette.Cream,
    surfaceVariant = Palette.Bark,
    onSurfaceVariant = Palette.Sand,
    surfaceTint = Palette.ClayDark,
    outline = Palette.Taupe,
    outlineVariant = Palette.BarkLine,
    error = Palette.OnErrorDark,
    onError = Palette.Espresso,
    errorContainer = Palette.ErrorContainerDark,
    onErrorContainer = Palette.OnErrorDark,
    inverseSurface = Palette.Cream,
    inverseOnSurface = Palette.Espresso,
    inversePrimary = Palette.Clay,
    scrim = Palette.Scrim,
    surfaceBright = Color(0xFF3A342C),
    surfaceDim = Palette.Espresso,
    surfaceContainerLowest = Color(0xFF0F0C0A),
    surfaceContainerLow = Palette.Coffee,
    surfaceContainer = Color(0xFF211D18),
    surfaceContainerHigh = Palette.BarkLine,
    surfaceContainerHighest = Color(0xFF36312A),
)

/**
 * 青花主题（多主题·见 FABLE5_THEME_QINGHUA_PROPOSAL.md）M3 换装桥·浅档（瓷白 + 钴蓝点睛）。
 * 让未迁移屏的 `MaterialTheme.*` 读取点也变青花；已迁移组件直接读 [AppTheme].colors。tonal 面阶为冷中性等感知步长（仅桥接用·不进 Palette/semantic）。
 */
internal fun brandQinghuaLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Palette.Cobalt,
    onPrimary = Palette.OnCobalt, // 近白字 on 钴蓝填充（钴蓝足够深·≥4.5）
    primaryContainer = Palette.CobaltContainer,
    onPrimaryContainer = Palette.CobaltOnContainer,
    secondary = Palette.CobaltText, // 钴蓝功能深档（白字达标·on 瓷白作文字达 4.5）
    onSecondary = Palette.White,
    secondaryContainer = Palette.MistBlue,
    onSecondaryContainer = Palette.InkBlue,
    tertiary = Palette.Gold,
    onTertiary = Palette.White,
    tertiaryContainer = Palette.WarnContainer,
    onTertiaryContainer = Palette.OnWarn,
    background = Palette.PorcelainBlue,
    onBackground = Palette.InkBlue,
    // 沉浸决议：surface=background 同色（瓷白整屏一底·白色只留内容纸张走 AppTheme.colors.surface.raised）。
    surface = Palette.PorcelainBlue,
    onSurface = Palette.InkBlue,
    surfaceVariant = Palette.MistBlue,
    onSurfaceVariant = Palette.InkBlueSoft,
    surfaceTint = Palette.Cobalt,
    outline = Palette.InkBlueFaint,
    outlineVariant = Color(0xFFD7DFEC),
    error = Palette.OnError,
    onError = Palette.White,
    errorContainer = Palette.ErrorContainer,
    onErrorContainer = Palette.OnError,
    inverseSurface = Palette.InkBlue,
    inverseOnSurface = Palette.PorcelainBlue,
    inversePrimary = Palette.CobaltGradStart,
    scrim = Palette.Scrim,
    surfaceBright = Palette.PorcelainBlue,
    surfaceDim = Color(0xFFDCE2EC),
    surfaceContainerLowest = Palette.White,
    surfaceContainerLow = Color(0xFFF1F4FA),
    surfaceContainer = Palette.MistBlue,
    surfaceContainerHigh = Color(0xFFE1E8F3),
    surfaceContainerHighest = Color(0xFFDAE2EF),
)

/**
 * 青花主题 M3 换装桥·深档（青花·夜墨青底 + 略提亮钴蓝）。
 */
internal fun brandQinghuaDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Palette.CobaltGradStartDark, // 深钴蓝填充配白字 ≥4.5（M3 实底钮·CobaltBright 留给 surfaceTint/装饰）
    onPrimary = Palette.OnCobalt,
    primaryContainer = Palette.CobaltContainerDark,
    onPrimaryContainer = Palette.CobaltOnContainerDark,
    secondary = Palette.CobaltTextDark,
    onSecondary = Palette.NightInk,
    secondaryContainer = Palette.NightSunken,
    onSecondaryContainer = Palette.MoonCream,
    tertiary = Palette.GoldDark,
    onTertiary = Palette.NightInk,
    tertiaryContainer = Palette.WarnContainerDark,
    onTertiaryContainer = Palette.OnWarnDark,
    background = Palette.NightInk,
    onBackground = Palette.MoonCream,
    surface = Palette.NightInk,
    onSurface = Palette.MoonCream,
    surfaceVariant = Palette.NightSunken,
    onSurfaceVariant = Palette.MoonCreamSoft,
    surfaceTint = Palette.CobaltBright,
    outline = Palette.MoonCreamFaint,
    outlineVariant = Palette.NightStroke,
    error = Palette.OnErrorDark,
    onError = Palette.NightInk,
    errorContainer = Palette.ErrorContainerDark,
    onErrorContainer = Palette.OnErrorDark,
    inverseSurface = Palette.MoonCream,
    inverseOnSurface = Palette.NightInk,
    inversePrimary = Palette.Cobalt,
    scrim = Palette.Scrim,
    surfaceBright = Color(0xFF2E3849),
    surfaceDim = Palette.NightInk,
    surfaceContainerLowest = Color(0xFF0A0E16),
    surfaceContainerLow = Palette.NightRaised,
    surfaceContainer = Color(0xFF1C2433),
    surfaceContainerHigh = Palette.NightStroke,
    surfaceContainerHighest = Color(0xFF323D4E),
)
