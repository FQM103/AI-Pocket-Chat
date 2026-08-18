package com.situ.aichat.ui.diary

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.prompt.diary.DiaryExchangeService
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface

/** 信封位 UI 态（VM 包装：服务态 + 拆信中 + 失败一次性提示）。 */
data class DiaryExchangeUiState(
    val state: DiaryExchangeService.State = DiaryExchangeService.State.Hidden,
    val unlocking: Boolean = false,
    val failed: Boolean = false,
)

/**
 * 交换日记信封位（R4·契约 §1.1 S5·时间线当日置顶）。状态驱动四形态：没聊过（不可拆）/ 去写日记（引导发布）/
 * 拆开信（懒生成·loading「递日记」）/ 失败重试。信生成后本位隐藏——信笺卡在时间线自然展示。
 * 虚线信封边 = 票据隐喻延伸（全案唯一手账装饰家族）。
 */
@Composable
internal fun DiaryExchangeSlot(
    ui: DiaryExchangeUiState,
    onCompose: () -> Unit,
    onUnlock: () -> Unit,
) {
    val state = ui.state
    if (state is DiaryExchangeService.State.Hidden || state is DiaryExchangeService.State.Unlocked) return
    val colors = AppTheme.colors
    val dashColor = colors.accent.primary.copy(alpha = 0.45f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            // 承托改 appCardSurface；虚线信封边 drawBehind 移到其后（J3·叠于卡底上、内容下）。
            .appCardSurface()
            .drawBehind {
                drawRoundRect(
                    color = dashColor,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                )
            }
            .padding(16.dp),
    ) {
        when (state) {
            is DiaryExchangeService.State.NoChatToday -> {
                Icon(Icons.Filled.Email, contentDescription = null, tint = colors.text.secondary)
                Text(
                    stringResource(R.string.diary_exchange_slot_no_chat),
                    style = AppTheme.typography.secondary,
                    color = colors.text.secondary,
                )
            }
            is DiaryExchangeService.State.NeedPublish -> {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.accent.text)
                Text(
                    stringResource(R.string.diary_exchange_slot_unsealed, state.characterName),
                    style = AppTheme.typography.label,
                    color = colors.text.primary,
                )
                Text(
                    stringResource(R.string.diary_exchange_slot_need_publish),
                    style = AppTheme.typography.secondary,
                    color = colors.text.secondary,
                )
                ExchangePill(stringResource(R.string.diary_exchange_slot_cta_write), onCompose)
            }
            is DiaryExchangeService.State.ReadyToUnlock -> {
                Icon(Icons.Filled.Email, contentDescription = null, tint = colors.accent.text)
                Text(
                    stringResource(R.string.diary_exchange_slot_unsealed, state.characterName),
                    style = AppTheme.typography.label,
                    color = colors.text.primary,
                )
                if (ui.unlocking) {
                    Text(
                        stringResource(R.string.diary_exchange_slot_loading, state.characterName),
                        style = AppTheme.typography.secondary,
                        color = colors.text.secondary,
                    )
                } else {
                    Text(
                        stringResource(
                            if (ui.failed) R.string.diary_exchange_slot_failed else R.string.diary_exchange_slot_ready,
                        ),
                        style = AppTheme.typography.secondary,
                        color = if (ui.failed) colors.status.onError else colors.text.secondary,
                    )
                    ExchangePill(stringResource(R.string.diary_exchange_slot_cta_open), onUnlock)
                }
            }
        }
    }
}

/** 陶土渐变小胶囊 CTA（与「写一笔」同族·信封位内层）。 */
@Composable
private fun ExchangePill(label: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    Text(
        label,
        style = AppTheme.typography.label,
        color = colors.text.onAccent,
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(AppTheme.shapes.full)
            .background(Brush.linearGradient(listOf(colors.accent.gradientStart, colors.accent.gradientEnd)))
            .clickable(onClickLabel = label) { haptics.light(); onClick() }
            .heightIn(min = 36.dp)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

/**
 * 拆信揭晓入场（celebrate ζ0.5·全案唯一 celebrate 位·每屏一处·契约 §1.1 S5）：信笺卡首次出现时
 * 0.92→1 弹性放大。仅对「刚拆开的那封」生效一次（rememberSaveable 防重放）；reduceMotion → 直出。
 */
@Composable
internal fun Modifier.celebrateUnlockEntrance(enabled: Boolean): Modifier {
    if (!enabled) return this
    val reduceMotion = rememberReduceMotion()
    if (reduceMotion) return this
    var played by rememberSaveable { mutableStateOf(false) }
    val scale = remember { Animatable(if (played) 1f else 0.92f) }
    LaunchedEffect(Unit) {
        if (!played) {
            played = true
            scale.animateTo(1f, AppMotion.celebrateSpring())
        }
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
