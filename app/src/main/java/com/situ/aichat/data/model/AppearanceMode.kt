package com.situ.aichat.data.model

/**
 * 深浅外观模式（1:1 iOS `Models/AppSettings.swift` 的 `AppearanceMode`，raw 串对齐 "system"/"light"/"dark"）。
 *
 * iOS 把 `AppearanceMode.colorScheme` 映射为 SwiftUI `preferredColorScheme`（nil = 跟随系统）；
 * 安卓这里映射为 Compose 的 `darkTheme: Boolean`——跟随系统时由 `isSystemInDarkTheme()` 决定。
 *
 * 纯枚举 + 纯函数，无 Compose 依赖，便于单测反推 iOS 语义。
 */
enum class AppearanceMode(val raw: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    /**
     * 解析为是否走深色（纯函数，反推 iOS `colorScheme` 语义）：
     * 跟随系统 → 交给系统 `systemInDark`、浅色 → false、深色 → true。
     */
    fun resolveDarkTheme(systemInDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** 解析持久化 raw 串，未知/空回退跟随系统（1:1 iOS `AppearanceMode(rawValue:) ?? .system`）。 */
        fun fromRaw(raw: String?): AppearanceMode =
            entries.firstOrNull { it.raw == raw } ?: SYSTEM
    }
}

/**
 * 主题配色家族（Fable-5 多主题·见 FABLE5_THEME_QINGHUA_PROPOSAL.md）。与 [AppearanceMode] 深浅**正交**：
 * 配色管色相家族（暖陶/青花…），深浅管明暗，两两组合成各自的 AppColors。raw 串持久化，未知/空回退默认暖陶。
 */
enum class ThemePalette(val raw: String) {
    CLAY("clay"),       // 暖陶玫（默认·设计语言主强调 #BE8A76）
    QINGHUA("qinghua"); // 青花蓝（白多蓝点睛·瓷白底 + 钴蓝）

    companion object {
        fun fromRaw(raw: String?): ThemePalette =
            entries.firstOrNull { it.raw == raw } ?: CLAY
    }
}

/**
 * 根部主题读取的外观快照：主题配色 + 深浅模式 + Material You 动态取色开关。
 *
 * iOS 这一项是「多主题 currentThemeID」。安卓 2026-06-30 起开放多主题配色（[palette]·反转旧
 * 「不做多主题换肤」决策）；[palette] 与 [mode] 深浅正交。动态取色（[useDynamicColor]）仍为安卓特有 opt-in。
 */
data class AppearanceState(
    val mode: AppearanceMode = AppearanceMode.SYSTEM,
    // Fable-5 Phase 0：默认关动态取色=品牌调色板，Monet 降 opt-in（设计语言 §1.5）。
    val useDynamicColor: Boolean = false,
    // 主题配色家族（默认暖陶·与深浅正交·见 FABLE5_THEME_QINGHUA_PROPOSAL.md）。
    val palette: ThemePalette = ThemePalette.CLAY,
) {
    companion object {
        /** 加载完成前的默认值 = 当前现状（跟随系统 + 默认暖陶），保证无回归、无首帧闪烁。 */
        val DEFAULT = AppearanceState()
    }
}
