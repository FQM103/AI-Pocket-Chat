package com.situ.aichat.ui.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1：天色调色系统（图纸 2026-07-10-见面回忆那晚的天色 SKY-1）。
 * 规格独立反推自契约 FABLE5_MEETING_MEMORY_SKY_PROPOSAL §2：时段桶边界、情绪键解析、25 组合结构不变量。
 */
class MeetingSkyPaletteTest {

    @Test
    fun `时段桶边界 - 与契约分档逐点一致`() {
        val expected = mapOf(
            0 to SkyBucket.LATE_NIGHT, 4 to SkyBucket.LATE_NIGHT,
            5 to SkyBucket.DAWN, 7 to SkyBucket.DAWN,
            8 to SkyBucket.DAY, 15 to SkyBucket.DAY,
            16 to SkyBucket.DUSK, 18 to SkyBucket.DUSK,
            19 to SkyBucket.NIGHT, 22 to SkyBucket.NIGHT,
            23 to SkyBucket.LATE_NIGHT,
        )
        for ((hour, bucket) in expected) {
            assertEquals("hour=$hour", bucket, skyBucketForHour(hour))
        }
    }

    @Test
    fun `情绪键解析 - 大小写不敏感且未知回退平淡`() {
        assertEquals(OfflineMoodKind.WARM, OfflineMoodKind.fromRaw("warm"))
        assertEquals(OfflineMoodKind.WARM, OfflineMoodKind.fromRaw("WARM"))
        assertEquals(OfflineMoodKind.SWEET, OfflineMoodKind.fromRaw("Sweet"))
        assertEquals(OfflineMoodKind.MELANCHOLIC, OfflineMoodKind.fromRaw("melancholic"))
        assertEquals(OfflineMoodKind.AWKWARD, OfflineMoodKind.fromRaw("awkward"))
        assertEquals(OfflineMoodKind.NEUTRAL, OfflineMoodKind.fromRaw(null))
        assertEquals(OfflineMoodKind.NEUTRAL, OfflineMoodKind.fromRaw("neutral"))
        assertEquals(OfflineMoodKind.NEUTRAL, OfflineMoodKind.fromRaw("咕噜咕噜"))
    }

    @Test
    fun `25 组合结构不变量`() {
        for (bucket in SkyBucket.entries) {
            for (kind in OfflineMoodKind.entries) {
                val spec = MeetingSky.spec(bucket, kind)
                val tag = "$bucket×$kind"
                assertEquals("$tag 停色恒三档", 3, spec.stops.size)
                assertEquals("$tag 只有白天是浅底", bucket == SkyBucket.DAY, spec.skyIsLight)
                if (spec.skyIsLight) {
                    assertEquals("$tag 浅底配深墨", MeetingSky.Ink, spec.textColor)
                    assertTrue("$tag 浅底无纱", !spec.bottomHaze)
                } else {
                    assertEquals("$tag 深底配暖白", MeetingSky.WarmWhite, spec.textColor)
                    assertTrue("$tag 暖白桶必挂底纱", spec.bottomHaze)
                }
                val nightly = bucket == SkyBucket.NIGHT || bucket == SkyBucket.LATE_NIGHT
                assertEquals("$tag 只有夜桶有月", nightly, spec.moonAlpha > 0f)
                if (!nightly) assertEquals("$tag 非夜桶月 alpha 恒 0", 0f, spec.moonAlpha, 0f)
            }
        }
    }

    @Test
    fun `情绪特例 - 平淡纱月·微涩满月·白天温暖光晕·微妙起雾`() {
        assertEquals(0.6f, MeetingSky.spec(SkyBucket.LATE_NIGHT, OfflineMoodKind.NEUTRAL).moonAlpha, 0.0001f)
        assertEquals(1f, MeetingSky.spec(SkyBucket.NIGHT, OfflineMoodKind.MELANCHOLIC).moonAlpha, 0f)
        assertEquals(SkyWeather.SUN_HALO, MeetingSky.spec(SkyBucket.DAY, OfflineMoodKind.WARM).weather)
        assertEquals(SkyWeather.GLOW_BANDS, MeetingSky.spec(SkyBucket.NIGHT, OfflineMoodKind.WARM).weather)
        assertEquals(SkyWeather.FOG, MeetingSky.spec(SkyBucket.DUSK, OfflineMoodKind.AWKWARD).weather)
        assertEquals(SkyWeather.NONE, MeetingSky.spec(SkyBucket.DAWN, OfflineMoodKind.MELANCHOLIC).weather)
    }

    @Test
    fun `微涩微妙星减半且微涩更亮`() {
        val base = MeetingSky.spec(SkyBucket.LATE_NIGHT, OfflineMoodKind.WARM).starCount
        assertEquals(base / 2, MeetingSky.spec(SkyBucket.LATE_NIGHT, OfflineMoodKind.MELANCHOLIC).starCount)
        assertEquals(base / 2, MeetingSky.spec(SkyBucket.LATE_NIGHT, OfflineMoodKind.AWKWARD).starCount)
        assertEquals(0.2f, MeetingSky.spec(SkyBucket.LATE_NIGHT, OfflineMoodKind.MELANCHOLIC).starAlphaBoost, 0f)
        assertEquals(0f, MeetingSky.spec(SkyBucket.LATE_NIGHT, OfflineMoodKind.AWKWARD).starAlphaBoost, 0f)
    }
}
