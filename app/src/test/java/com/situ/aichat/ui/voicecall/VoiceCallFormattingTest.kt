package com.situ.aichat.ui.voicecall

import org.junit.Assert.assertEquals
import org.junit.Test

/** Call-timer formatting, reverse-derived from iOS `durationText` (VoiceCallView.swift:398-401). */
class VoiceCallFormattingTest {

    @Test
    fun zero() = assertEquals("00:00", voiceCallDurationText(0))

    @Test
    fun secondsOnly_padded() = assertEquals("00:05", voiceCallDurationText(5))

    @Test
    fun minuteRollover() = assertEquals("01:05", voiceCallDurationText(65))

    @Test
    fun justUnderTenMinutes() = assertEquals("09:59", voiceCallDurationText(599))

    @Test
    fun longCall_minutesNotClampedToTwoDigits() = assertEquals("100:00", voiceCallDurationText(6000))

    @Test
    fun negative_flooredToZero() = assertEquals("00:00", voiceCallDurationText(-3))
}
