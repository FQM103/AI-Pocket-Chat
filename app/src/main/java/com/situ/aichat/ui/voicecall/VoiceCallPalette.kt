package com.situ.aichat.ui.voicecall

import androidx.compose.ui.graphics.Color
import com.situ.aichat.ui.designsystem.Palette

/**
 * 「暖夜通话」恒暗调色板——通话屏的字色/氛围单源（FABLE5_VOICE_CALL_REDESIGN_PROPOSAL.md D-1/D-6）。
 * 通话屏与梦剧场同理**恒暗**（打电话=这个 App 熄了灯的房间），不随浅色主题翻转，故不走 AppTheme 映射表，
 * 而是从 [Palette] primitive 取暖夜值收在这一处——原先散落各文件的硬编码蓝紫全部退场。
 */
internal object VoiceCallPalette {
    /** 背景底（= 深暖灰 surface 底）与更深的渐变终点。 */
    val base = Palette.Espresso
    val baseDeep = Color(0xFF0D0B09)

    /** 恒暗字色三档（= 深色主题 text primary/secondary/tertiary 同值）。 */
    val textHi = Palette.Cream
    val textMid = Palette.Sand
    val textLo = Palette.Taupe

    /** 陶土暖光（辉光/光斑/状态点）与波形三阶。 */
    val glow = Palette.Clay
    val waveBright = Palette.ClayLight
    val waveMid = Palette.Clay
    val waveDeep = Palette.ClayDeep

    /** 深玻璃（字幕条/控制钮）：暖咖底 + 暖白发丝描边。 */
    val glass = Palette.Coffee
    val warmWhite = Palette.OnClayDark

    /** 字幕角色区分：TA=暖白、你=陶土浅档（D-3）。 */
    val subtitleUser = Palette.ClayLight

    /** 控制钮开启态：陶土浅档渐变填充 + 深墨图标（微信式浅底深字·D-4）。 */
    val controlOnStart = Palette.ClayLight
    val controlOnEnd = Palette.Clay
    val controlOnIcon = Palette.OnClayInk

    /** 挂断主钮（status.error 家族的恒暗大元素双 stop·D-4）。 */
    val hangUpStart = Color(0xFFCE6254)
    val hangUpEnd = Color(0xFFC25549)

    /** 状态行错误短句（琥珀·D-2）。 */
    val amber = Color(0xFFD9A05B)
}
