package com.situ.aichat.ui.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 1:1 port of iOS `Utilities/AvatarColor.swift` — deterministic per-name avatar color via a djb2
 * stable hash over an 8-hue palette (iOS system blue/purple/pink/orange/green/teal/indigo/cyan, same
 * order). Used by [CharacterAvatar] for the monogram fallback so each character/user without an avatar
 * image gets a stable, distinguishable color instead of one shared grey (matches iOS AvatarView).
 */
object AvatarColor {
    // iOS system colors (light variants), same order as AvatarColor.swift's palette.
    private val palette = listOf(
        Color(0xFF007AFF), // blue
        Color(0xFFAF52DE), // purple
        Color(0xFFFF2D55), // pink
        Color(0xFFFF9500), // orange
        Color(0xFF34C759), // green
        Color(0xFF30B0C7), // teal
        Color(0xFF5856D6), // indigo
        Color(0xFF32ADE6), // cyan
    )

    /** djb2 stable hash (cross-launch deterministic, unaffected by JVM hash randomization), as iOS. */
    fun color(name: String): Color {
        var hash = 5381UL
        for (b in name.toByteArray(Charsets.UTF_8)) {
            hash = ((hash shl 5) + hash) + (b.toInt() and 0xFF).toULong()
        }
        return palette[(hash % palette.size.toULong()).toInt()]
    }

    /** Subtle vertical gradient ≈ iOS `Color.gradient` (slightly lighter top → base bottom). */
    fun brush(name: String): Brush {
        val base = color(name)
        return Brush.verticalGradient(listOf(base.lighten(0.12f), base))
    }

    private fun Color.lighten(amount: Float): Color = Color(
        red = red + (1f - red) * amount,
        green = green + (1f - green) * amount,
        blue = blue + (1f - blue) * amount,
        alpha = alpha,
    )
}
