package com.situ.aichat.ui.voicecall

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.situ.aichat.R
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.designsystem.Palette
import kotlinx.coroutines.launch

/**
 * VU1「暖夜门厅」拦截 sheet（§7.1.1·已过审 O-D=恒暗）：角色无可用音色时点通话根本不进拨号屏，弹这张恒暗底片
 * 预演通话氛围 + 温柔引导去配音色 / 语音服务，次入口「先用字幕通话」照常开打（O-A）。**恒暗不读 AppTheme**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceCallPreflightSheet(
    info: VoicePreflightInfo,
    onPrimary: () -> Unit,
    onSubtitleOnly: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = Palette.Bark,
        // R1 🟡-3：契约 §7.1.1 明文「背后聊天屏压 62% 暗化 scrim」（=mockup dimmer rgba(13,11,9,.62)）——
        // 图纸漏抄该条致施工用了 M3 默认；按冲突裁决序（契约>图纸）修正，色取恒暗单源 baseDeep 非裸色。
        scrimColor = VoiceCallPalette.baseDeep.copy(alpha = 0.62f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = VoiceCallPalette.warmWhite.copy(alpha = 0.18f)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // §7.1.1 落值：深暖灰渐变 #242019→#1C1916 铺满内容列。
                .background(Brush.verticalGradient(listOf(Palette.Bark, Palette.Coffee)))
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 头像区：88dp 容器居中——底层静光环（72dp 陶土径向辉光·blur 10dp·无动画）+ 上层 72dp 头像。
            Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .blur(10.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(VoiceCallPalette.glow.copy(alpha = 0.32f), Color.Transparent),
                            ),
                            CircleShape,
                        ),
                )
                CharacterAvatar(name = info.characterName, avatarPath = info.avatarPath, size = 72.dp)
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.voice_call_preflight_title),
                color = VoiceCallPalette.textHi,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )

            Spacer(Modifier.height(6.dp))
            Text(
                text = when (info.need) {
                    VoiceSetupNeed.CHARACTER_VOICE ->
                        stringResource(R.string.voice_call_preflight_body_character, info.characterName)
                    VoiceSetupNeed.GLOBAL_CONFIG ->
                        stringResource(R.string.voice_call_preflight_body_global)
                },
                color = VoiceCallPalette.textMid,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            Spacer(Modifier.height(16.dp))
            PrimaryCta(
                text = stringResource(
                    when (info.need) {
                        VoiceSetupNeed.CHARACTER_VOICE -> R.string.voice_call_preflight_cta_character
                        VoiceSetupNeed.GLOBAL_CONFIG -> R.string.voice_call_preflight_cta_global
                    },
                ),
                onClick = onPrimary,
            )

            Spacer(Modifier.height(12.dp))
            // O-A 次入口：「先用字幕通话」弱化文字钮（textLo + 细下划线=链接式次入口·非强调）。
            Text(
                text = stringResource(R.string.voice_call_preflight_subtitle_only),
                color = VoiceCallPalette.textLo,
                fontSize = 13.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onSubtitleOnly)
                    .padding(vertical = 14.dp),
            )

            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.action_cancel),
                color = VoiceCallPalette.textMid,
                fontSize = 13.sp,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(vertical = 14.dp),
            )
        }
    }
}

/** 主 CTA：48dp 陶土浅档渐变胶囊 + 深墨字（D-4 开启态语义）；按压 0.92 AppMotion lively（照 CallToggleButton）。 */
@Composable
private fun PrimaryCta(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = AppMotion.livelySpring(),
        label = "preflightCtaPress",
    )
    Box(
        modifier = Modifier
            .scale(scale)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(listOf(VoiceCallPalette.controlOnStart, VoiceCallPalette.controlOnEnd)),
            )
            // R1 🔵-1：补 ripple 与 CallToggleButton（范式源）一致——按压反馈=缩放+涟漪双通道。
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = VoiceCallPalette.controlOnIcon,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** VU1 门状态：待弹的拦截 sheet（选中态只存 id + 判定素材，不叠弹——覆盖写天然幂等·E5）。 */
private data class PendingPreflightSheet(
    val conversationUuid: String,
    val characterUuid: String,
    val info: VoicePreflightInfo,
)

/**
 * 拨号门：包在 [rememberVoiceCallStarter] 外层——先 suspend 判定（[VoiceCallPreflightViewModel.check]·本地只读·
 * J3 fail-open），有可用音色 → 原封走内层 starter（权限舞步/服务时序零变）；否则弹 [VoiceCallPreflightSheet]。
 */
@Composable
fun rememberPreflightVoiceCallStarter(
    onCallStarted: (characterUuid: String) -> Unit,
    onOpenCharacterVoiceSettings: (String) -> Unit,
    onOpenTtsConfig: () -> Unit,
    preflightVm: VoiceCallPreflightViewModel,
): (conversationUuid: String, characterUuid: String) -> Unit {
    val innerStarter = rememberVoiceCallStarter(onCallStarted)
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingPreflightSheet?>(null) }

    pending?.let { sheet ->
        VoiceCallPreflightSheet(
            info = sheet.info,
            onPrimary = {
                pending = null
                when (sheet.info.need) {
                    VoiceSetupNeed.CHARACTER_VOICE -> onOpenCharacterVoiceSettings(sheet.characterUuid)
                    VoiceSetupNeed.GLOBAL_CONFIG -> onOpenTtsConfig()
                }
            },
            onSubtitleOnly = {
                pending = null
                innerStarter(sheet.conversationUuid, sheet.characterUuid) // O-A：照常开打·不自动重拨
            },
            onDismiss = { pending = null },
        )
    }

    return remember(innerStarter, preflightVm, scope) {
        { conversationUuid, characterUuid ->
            scope.launch {
                val info = preflightVm.check(characterUuid)
                if (info == null) innerStarter(conversationUuid, characterUuid)
                else pending = PendingPreflightSheet(conversationUuid, characterUuid, info)
            }
        }
    }
}
