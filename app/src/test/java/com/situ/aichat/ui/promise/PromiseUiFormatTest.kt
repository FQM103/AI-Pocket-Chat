package com.situ.aichat.ui.promise

import com.situ.aichat.R
import com.situ.aichat.data.local.entity.PromiseEntity
import com.situ.aichat.data.local.entity.PromiseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * UI 侧格式化助手纯逻辑（记忆改造三期·图纸 §3.6 / §7 T1-2）。断言从图纸 §3.6 独立反推：
 * dueDayDiff 本地日历日（E11/E19）/ sourceLabelRes（meeting_backfill 归见面）/ isManualResolution（证据空=手动）。
 * sourceLabelRes 返回 R.string int 常量——直接比常量，无需 Android 运行时。
 */
class PromiseUiFormatTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai") // UTC+8 无 DST·确定性

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val now = at(2026, 7, 10, 12, 0)

    private fun promise(evidence: String) = PromiseEntity(
        uuid = "p1", characterUuid = "c1", content = "约定", resolutionEvidence = evidence,
        createdAtMillis = 0L, updatedAtMillis = 0L,
    )

    // ── ① dueDayDiff（本地日历日·E11） ──

    @Test fun dueDayDiff_today0_tomorrow1_yesterdayMinus1() {
        // due 今天 15:00（晚于 now 12:00）→ 0：日历日差，非毫秒差。
        assertEquals(0L, PromiseUiFormat.dueDayDiff(at(2026, 7, 10, 15, 0), now, zone))
        assertEquals(1L, PromiseUiFormat.dueDayDiff(at(2026, 7, 11, 1, 0), now, zone))
        assertEquals(-1L, PromiseUiFormat.dueDayDiff(at(2026, 7, 9, 23, 0), now, zone))
    }

    // ── ② sourceLabelRes（四组合·meeting_backfill 归见面） ──

    @Test fun sourceLabelRes_chatAndMeeting_longAndShort() {
        assertEquals(R.string.promise_source_chat, PromiseUiFormat.sourceLabelRes(PromiseSource.CHAT, short = false))
        assertEquals(R.string.promise_source_chat_short, PromiseUiFormat.sourceLabelRes(PromiseSource.CHAT, short = true))
        assertEquals(R.string.promise_source_meeting, PromiseUiFormat.sourceLabelRes(PromiseSource.MEETING, short = false))
        assertEquals(R.string.promise_source_meeting_short, PromiseUiFormat.sourceLabelRes(PromiseSource.MEETING, short = true))
    }

    @Test fun sourceLabelRes_meetingBackfill_mapsToMeeting() {
        assertEquals(R.string.promise_source_meeting, PromiseUiFormat.sourceLabelRes(PromiseSource.MEETING_BACKFILL, short = false))
        assertEquals(R.string.promise_source_meeting_short, PromiseUiFormat.sourceLabelRes(PromiseSource.MEETING_BACKFILL, short = true))
    }

    // ── ③ isManualResolution（证据空=手动·D-4 闭环不变量） ──

    @Test fun isManualResolution_blankTrue_nonBlankFalse() {
        assertTrue(PromiseUiFormat.isManualResolution(promise("")))
        assertTrue(PromiseUiFormat.isManualResolution(promise("   ")))
        assertFalse(PromiseUiFormat.isManualResolution(promise("我改好啦")))
    }

    // ── format 冒烟：未加引号的 CJK 日期 pattern 资源（M月d日）能正常输出（非 ASCII 字母=字面量） ──

    @Test fun format_unquotedCjkPattern_outputsLiterals() {
        assertEquals("7月10日", PromiseUiFormat.format(now, "M月d日", zone))
    }
}
