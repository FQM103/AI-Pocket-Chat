package com.situ.aichat.ui.voicecall

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.designsystem.Palette

/**
 * 底部三控制钮（FABLE5_VOICE_CALL_REDESIGN_PROPOSAL.md D-4/D-7）：字幕、挂断、外放。
 *  - 字幕/外放 = 56dp 深玻璃圆钮；**开启态 = 陶土浅档渐变填充 + 深墨图标**（微信式浅底深字·对比达标），
 *    关闭态 = 暖白 10% 玻璃 + 暖白图标——开关一眼可辨（D-7）。
 *  - 挂断 = 64dp status.error 双 stop 主钮，永远最大最醒目。
 *  - 按压缩放走 AppMotion lively 弹簧（微反馈档）。
 * 外放开关默认关=听筒；开启走 `AudioFocusController.setSpeakerEnabled` 真路由（D-7 拍板·真机批验出声出口）。
 */
@Composable
fun VoiceCallControls(
    isSpeakerEnabled: Boolean,
    isSubtitleVisible: Boolean,
    onToggleSubtitle: () -> Unit,
    onHangUp: () -> Unit,
    onToggleSpeaker: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleBadge: Boolean = false,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // VU2：字幕通话模式下字幕钮右上叠琥珀小点角标（信息由钉行播报·纯装饰）。
        Box {
            CallToggleButton(
                icon = if (isSubtitleVisible) Icons.Filled.Subtitles else Icons.Outlined.Subtitles,
                // P1-17：动态 1:1 iOS（VoiceCallView.swift:265 Show/Hide transcript）。
                contentDescription = stringResource(
                    if (isSubtitleVisible) R.string.a11y_call_hide_transcript else R.string.a11y_call_show_transcript,
                ),
                isOn = isSubtitleVisible,
                onClick = onToggleSubtitle,
            )
            if (subtitleBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-2).dp)
                        .size(8.dp)
                        .background(VoiceCallPalette.amber, CircleShape)
                        .border(2.dp, Palette.Espresso, CircleShape),
                )
            }
        }
        CallHangUpButton(onClick = onHangUp)
        CallToggleButton(
            icon = if (isSpeakerEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.PhoneInTalk,
            // P1-17：iOS 逐字（:304「切换到听筒/扬声器模式」）；voice_call_action_speaker_* 为 CallStyle 通知共用不动。
            contentDescription = stringResource(
                if (isSpeakerEnabled) R.string.a11y_call_switch_to_earpiece else R.string.a11y_call_switch_to_speaker,
            ),
            isOn = isSpeakerEnabled,
            onClick = onToggleSpeaker,
        )
    }
}

/** 56dp 深玻璃开关钮：开=陶土浅档填充+深墨图标，关=暖白 10% 玻璃+暖白图标。 */
@Composable
private fun CallToggleButton(
    icon: ImageVector,
    contentDescription: String,
    isOn: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = AppMotion.livelySpring(),
        label = "callTogglePress",
    )
    // 效果轴恒 ζ1.0：颜色过渡用 calm 临界阻尼（永不过冲）。
    val iconTint by animateColorAsState(
        targetValue = if (isOn) VoiceCallPalette.controlOnIcon else VoiceCallPalette.warmWhite,
        animationSpec = AppMotion.calmSpring(),
        label = "callToggleTint",
    )
    Box(
        modifier = Modifier
            .scale(scale)
            .size(56.dp)
            .clip(CircleShape)
            .background(
                if (isOn) {
                    Brush.linearGradient(
                        colors = listOf(VoiceCallPalette.controlOnStart, VoiceCallPalette.controlOnEnd),
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            VoiceCallPalette.warmWhite.copy(alpha = 0.10f),
                            VoiceCallPalette.warmWhite.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .border(1.dp, VoiceCallPalette.warmWhite.copy(alpha = if (isOn) 0f else 0.10f), CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button, // P1-17：iOS .buttonStyle 自带 button trait，安卓补报「按钮」
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** 64dp 挂断主钮（status.error 恒暗双 stop·D-4）。 */
@Composable
private fun CallHangUpButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = AppMotion.livelySpring(),
        label = "callHangUpPress",
    )
    Box(
        modifier = Modifier
            .scale(scale)
            .size(64.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(VoiceCallPalette.hangUpStart, VoiceCallPalette.hangUpEnd),
                ),
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.CallEnd,
            contentDescription = stringResource(R.string.voice_call_action_hang_up),
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}
