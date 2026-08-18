package com.situ.aichat.redpacket

/**
 * 用户聊天内发红包的结果（VM→UI，供发送 sheet 按 case 显示拆开动画 / 余额不足提示 / 错误）。
 * 余额不足通常已被发送 sheet 预校验（观察 coinBalance），此 case 为防御性兜底。
 */
sealed interface RedPacketSendOutcome {
    data object Success : RedPacketSendOutcome
    data class InsufficientBalance(val need: Int, val have: Int) : RedPacketSendOutcome
    data class Failed(val message: String) : RedPacketSendOutcome
}
