package com.situ.aichat.ui.story

import androidx.compose.ui.graphics.Color

/**
 * 类型 chip 选中色（1:1 iOS `StoryCreationCatalog.genreTint`，iOS 系统色真实值）。
 */
fun storyGenreTint(genre: String): Color = when (genre) {
    "言情" -> Color(0xFFFF2D55) // systemPink
    "悬疑" -> Color(0xFFFF9500) // systemOrange
    "奇幻" -> Color(0xFF5856D6) // systemIndigo
    "科幻" -> Color(0xFF007AFF) // systemBlue
    "都市" -> Color(0xFF8E8E93) // systemGray
    "恐怖" -> Color(0xFFFF3B30) // systemRed
    "校园" -> Color(0xFF34C759) // systemGreen
    "历史" -> Color(0xFFA2845E) // systemBrown
    "末日" -> Color(0xFF00C7BE) // systemMint
    else -> Color(0xFF32ADE6) // systemCyan
}
