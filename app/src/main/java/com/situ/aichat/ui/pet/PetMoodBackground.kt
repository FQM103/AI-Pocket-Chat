package com.situ.aichat.ui.pet

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.situ.aichat.ui.theme.LocalIsDarkTheme
import com.situ.aichat.data.local.entity.CharacterPetEntity
import com.situ.aichat.pet.PetNeglectPhase
import com.situ.aichat.pet.neglectPhase
import java.time.LocalTime

/** 宠物整体心情（决定背景渐变），1:1 iOS `PetMoodType`。 */
enum class PetMoodType {
    HAPPY, CONTENT, SAD, HUNGRY, SICK;

    companion object {
        /** 优先级：sick/ranAway → sick > hunger≥70 → hungry > happiness<30 → sad > happiness≥80 → happy > content。 */
        fun from(pet: CharacterPetEntity): PetMoodType = when {
            pet.neglectPhase == PetNeglectPhase.SICK || pet.neglectPhase == PetNeglectPhase.RAN_AWAY -> SICK
            pet.hunger >= 70 -> HUNGRY
            pet.happiness < 30 -> SAD
            pet.happiness >= 80 -> HAPPY
            else -> CONTENT
        }
    }
}

/**
 * 全屏心情渐变背景（1:1 iOS `PetMoodBackground`）：随心情 0.6s 平滑变色 + 叠时间感知色层（晨橘/夕阳/夜蓝）。
 * 5 套 3 色竖直渐变，dark 模式整体 opacity 0.62。
 */
@Composable
fun PetMoodBackground(mood: PetMoodType, modifier: Modifier = Modifier) {
    val isDark = LocalIsDarkTheme.current
    val alpha = if (isDark) 0.62f else 1.0f
    val targets = moodColors(mood).map { it.copy(alpha = alpha) }
    // 3 个渐变色各自 0.6s 平滑过渡（对齐 iOS .animation(.easeInOut(0.6), value: mood)）。
    val c0 by animateColorAsState(targets[0], tween(600), label = "moodC0")
    val c1 by animateColorAsState(targets[1], tween(600), label = "moodC1")
    val c2 by animateColorAsState(targets[2], tween(600), label = "moodC2")

    val overlay = remember { timeOfDayOverlay(LocalTime.now().hour) }

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c0, c1, c2))),
    ) {
        if (overlay != Color.Transparent) {
            Box(Modifier.fillMaxSize().background(overlay))
        }
    }
}

/** 心情渐变 3 色（颜色 1:1 SPEC §2.24 / iOS Color(hex:)）。 */
private fun moodColors(mood: PetMoodType): List<Color> = when (mood) {
    PetMoodType.HAPPY -> listOf(Color(0xFFFFF0E8), Color(0xFFFFD6C0), Color(0xFFFFC0A0)) // 暖粉桃
    PetMoodType.CONTENT -> listOf(Color(0xFFFBF4EA), Color(0xFFF2E6D6), Color(0xFFE9D9C5)) // M1·Fable-5：暖奶油（替 iOS 冷蓝紫·满足=温馨）
    PetMoodType.SAD -> listOf(Color(0xFFEAE3DB), Color(0xFFD8CEC3), Color(0xFFC6BAAC)) // M1·Fable-5：暖灰褐（替 iOS 冷灰蓝·难过=低饱和但暖）
    PetMoodType.HUNGRY -> listOf(Color(0xFFF0E0D0), Color(0xFFE0D0C0), Color(0xFFD0C0B0)) // 暖琥珀
    PetMoodType.SICK -> listOf(Color(0xFFE0D8D0), Color(0xFFD0C8C0), Color(0xFFC0B8B0)) // 苍白灰
}

/** 时间感知叠加色层（1:1 iOS）：6-12 晨橘0.06 / 18-22 夕阳橘0.08 / 22-6 夜蓝0.15 / 下午无。 */
private fun timeOfDayOverlay(hour: Int): Color = when {
    hour in 6..11 -> Color(0xFFFFA500).copy(alpha = 0.06f) // 晨橘（orange）
    hour in 18..21 -> Color(0xFFFFA500).copy(alpha = 0.08f) // 夕阳橘
    hour >= 22 || hour < 6 -> Color(0xFF1A1A3E).copy(alpha = 0.15f) // 夜蓝
    else -> Color.Transparent
}
