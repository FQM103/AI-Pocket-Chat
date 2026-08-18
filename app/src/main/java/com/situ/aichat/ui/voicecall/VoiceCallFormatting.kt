package com.situ.aichat.ui.voicecall

/** Call-duration label `mm:ss` (1:1 iOS `durationText`: `String(format: "%02d:%02d", s/60, s%60)`, floored ≥ 0). */
internal fun voiceCallDurationText(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
