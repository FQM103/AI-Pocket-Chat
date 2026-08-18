package com.situ.aichat.ui.starfield

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.situ.aichat.R
import com.situ.aichat.ui.designsystem.AppTypography
import com.situ.aichat.ui.offline.MeetingSky
import com.situ.aichat.ui.offline.MeetingSkyBackdrop
import com.situ.aichat.ui.offline.OfflineMoodKind
import com.situ.aichat.ui.offline.skyBucketForHour
import com.situ.aichat.ui.promise.PromiseUiFormat
import java.time.Instant
import java.time.ZoneId

/**
 * 记忆星空统一详情 sheet（图纸 2026-07-16-记忆星空 §4.8·J2）：三类星共用一张深玻璃卡，**只读重温**——
 * 无任何编辑 / 兑现操作（操作留各自主场），只给一条跳转链接。
 *
 * **D-0 已知偏差**（图纸 §9 明示的降级）：mockup 的 `blur(14px)` 在 Compose ModalBottomSheet 无背景
 * 实时模糊等价物 → 以半透明纯色 [SheetContainer] + 1dp 描边近似深玻璃观感。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StarfieldDetailSheet(
    star: StarNode,
    characterUuid: String,
    onDismiss: () -> Unit,
    onOpenMeetings: (String) -> Unit,
    onOpenPromises: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetContainer,
        shape = RoundedCornerShape(SHEET_CORNER_DP.dp),
        modifier = Modifier.border(1.dp, SheetStroke, RoundedCornerShape(SHEET_CORNER_DP.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    star.displayTitle(),
                    style = AppTypography.kaiQuote.copy(fontSize = 16.5.sp, lineHeight = 22.sp),
                    color = WarmWhite,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    star.metaLine(),
                    fontSize = 10.5.sp,
                    color = WarmWhite.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 6.dp),
                )
                // 正文：见面 = summary 首 60 字；里程碑 = reason；约定无正文。
                if (star.type != StarType.PROMISE && star.body.isNotBlank()) {
                    Text(
                        star.body.take(BODY_MAX_CHARS),
                        style = AppTypography.kaiQuote.copy(fontSize = 12.5.sp, lineHeight = (12.5f * 1.85f).sp),
                        color = WarmWhite.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                star.linkResId()?.let { linkRes ->
                    Text(
                        stringResource(linkRes),
                        fontSize = 11.5.sp,
                        color = HaloMeeting,
                        modifier = Modifier
                            .padding(top = 14.dp)
                            .clickable {
                                onDismiss()
                                if (star.type == StarType.MEETING) onOpenMeetings(characterUuid) else onOpenPromises(characterUuid)
                            },
                    )
                }
            }
            if (star.type == StarType.MEETING) {
                MeetingWindow(star, Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** 右浮窗景 58×74dp（§4.8）：天色取谱链 = 时段桶 × 心情（[MeetingSky] 只调用不修改）。 */
@Composable
private fun MeetingWindow(star: StarNode, modifier: Modifier = Modifier) {
    val hour = remember(star.timestampMillis) {
        Instant.ofEpochMilli(star.timestampMillis).atZone(ZoneId.systemDefault()).hour
    }
    MeetingSkyBackdrop(
        spec = MeetingSky.spec(skyBucketForHour(hour), OfflineMoodKind.fromRaw(star.moodRaw)),
        seed = star.id.hashCode(),
        startMillis = star.timestampMillis,
        modifier = modifier.width(58.dp).height(74.dp).clip(RoundedCornerShape(12.dp)),
    )
}

/** 见面标题空（activity/location 皆空）→ 补「一次见面」资源文案（施工日志 D-8）。 */
@Composable
private fun StarNode.displayTitle(): String =
    title.ifBlank { if (type == StarType.MEETING) stringResource(R.string.starfield_meeting_untitled) else "" }

/**
 * meta 行（§4.8 锁定）：
 * 见面 =「M 月 d 日 · 时段词 · 心情词 · 发起方」（legacy 无时间 → 日期显示「更早」·E4）；
 * 约定 =「M 月 d 日兑现」；里程碑 =「M 月 d 日 · phase(有则)」。
 */
@Composable
private fun StarNode.metaLine(): String {
    val pattern = stringResource(R.string.starfield_date_pattern_md)
    val date = if (timestampMillis <= 0L) {
        stringResource(R.string.starfield_legacy_date)
    } else {
        PromiseUiFormat.format(timestampMillis, pattern)
    }
    return when (type) {
        StarType.PROMISE -> stringResource(R.string.starfield_meta_promise, date)
        StarType.MILESTONE -> listOfNotNull(date, phase.takeIf { it.isNotBlank() }).joinToString(META_SEPARATOR)
        StarType.MEETING -> {
            val zone = ZoneId.systemDefault()
            val timeOfDay = if (timestampMillis <= 0L) {
                null
            } else {
                stringResource(timeOfDayResOf(Instant.ofEpochMilli(timestampMillis).atZone(zone).hour))
            }
            // TODO(图纸未覆盖): §4.8 meta 行点名有「发起方」一段但未给文案 → 取「你约的 / TA 约的」，
            // 未知（initiatedByUser == null）时整段省略。见施工日志 TODO-2，留复核裁决。
            val initiator = when (initiatedByUser) {
                true -> stringResource(R.string.starfield_meta_initiator_user)
                false -> stringResource(R.string.starfield_meta_initiator_character)
                null -> null
            }
            listOfNotNull(date, timeOfDay, stringResource(moodResOf(moodRaw)), initiator).joinToString(META_SEPARATOR)
        }
    }
}

private fun StarNode.linkResId(): Int? = when (type) {
    StarType.MEETING -> R.string.starfield_link_meeting
    StarType.PROMISE -> R.string.starfield_link_promise
    StarType.MILESTONE -> null
}

/** 时段词映射（§4.8 锁定）：5-8 清晨 / 9-11 上午 / 12-13 中午 / 14-17 下午 / 18-21 晚上 / 其余 深夜。 */
internal fun timeOfDayResOf(hour: Int): Int = when (hour) {
    in 5..8 -> R.string.starfield_tod_early
    in 9..11 -> R.string.starfield_tod_morning
    in 12..13 -> R.string.starfield_tod_noon
    in 14..17 -> R.string.starfield_tod_afternoon
    in 18..21 -> R.string.starfield_tod_evening
    else -> R.string.starfield_tod_late
}

/** 心情词映射（§4.8 锁定·与天色卡词汇一致）：解析走 [OfflineMoodKind.fromRaw] 单源（未知值兜底 NEUTRAL·E15）。 */
internal fun moodResOf(moodRaw: String): Int = when (OfflineMoodKind.fromRaw(moodRaw)) {
    OfflineMoodKind.WARM -> R.string.starfield_mood_warm
    OfflineMoodKind.SWEET -> R.string.starfield_mood_sweet
    OfflineMoodKind.MELANCHOLIC -> R.string.starfield_mood_melancholic
    OfflineMoodKind.AWKWARD -> R.string.starfield_mood_awkward
    OfflineMoodKind.NEUTRAL -> R.string.starfield_mood_neutral
}

private const val SHEET_CORNER_DP = 20
private const val BODY_MAX_CHARS = 60
private const val META_SEPARATOR = " · "
