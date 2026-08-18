package com.situ.aichat.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.data.local.entity.DiaryEntryEntity
import com.situ.aichat.data.model.DiaryVisibility
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 票据式日记卡（日记重设计 R1·契约 §1.1 S1）：心情色带 3dp + 日期肩（大数字 `dd` tnum + 今天/昨天/周几 +
 * 心情 emoji）+ 正文摘录 + 缩略图 + 页脚（AI/宠物/草稿徽章 + 评论数 + 时间 + 可见性）。时间线 / 日历选中日共用。
 * 海拔=明度分层 + 1dp 描边（不投影·设计语言 §3）。
 */
@Composable
fun DiaryEntryCard(
    entry: DiaryEntryEntity,
    commentCount: Int,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
    reactionCount: Int = 0,
    /** R3：草稿一键发布（非 null 且 entry.isDraft 时页脚出「发布」快捷钮）。 */
    onPublish: (() -> Unit)? = null,
    /** R4：交换日记信笺形态——作者角色名（非 null = TA 写的信：楷体摘录 + 「{名}的日记」标）。 */
    authorName: String? = null,
    /** U3：孤儿信（作者角色已删·§6.3 O2）——true 时「{名}的日记」头后缀「· 故友的信」淡标。 */
    isOrphan: Boolean = false,
) {
    val colors = AppTheme.colors
    val images = entry.imagePaths
    val cardCd = diaryEntryA11yDescription(entry, commentCount, reactionCount, authorName, isOrphan)
    Row(
        modifier = modifier
            .semantics { contentDescription = cardCd }
            .fillMaxWidth()
            .appCardSurface()
            .height(IntrinsicSize.Min)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(AppTheme.shapes.full)
                .background(diaryMoodBand(entry.moodEmoji) ?: colors.surface.sunken),
        )
        TicketDateBlock(entry.timestamp, entry.moodEmoji)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (authorName != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.diary_exchange_card_author, authorName),
                        style = AppTheme.typography.caption,
                        color = colors.accent.text,
                    )
                    if (isOrphan) {
                        // U3 故人来信淡标（§6.3 O2·text.secondary·无警示色·「·」纯装饰分隔）。
                        Text("·", style = AppTheme.typography.caption, color = colors.text.secondary)
                        Text(
                            stringResource(R.string.diary_exchange_orphan_label),
                            style = AppTheme.typography.caption,
                            color = colors.text.secondary,
                        )
                    }
                }
            }
            Text(
                text = entry.content.ifEmpty { stringResource(R.string.diary_no_content) },
                // R4：TA 的信用楷体摘录（契约 §1 手法4·kaiQuote 14/20）。
                style = if (authorName != null) AppTheme.typography.kaiQuote else AppTheme.typography.listPreview,
                color = colors.text.primary,
                maxLines = if (preview) 3 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            if (images.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    images.take(4).forEach { DiaryThumbnail(it, modifier = Modifier.size(44.dp)) }
                    if (images.size > 4) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(AppTheme.shapes.small)
                                .background(colors.surface.sunken),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("+${images.size - 4}", style = AppTheme.typography.caption, color = colors.text.secondary)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry.isAutoGenerated) {
                    Text(stringResource(R.string.diary_ai_label), style = AppTheme.typography.caption, color = colors.accent.text)
                }
                if (entry.isPetDiary) {
                    Text(stringResource(R.string.diary_pet_diary), style = AppTheme.typography.caption, color = colors.accent.text)
                }
                if (entry.isDraft) DraftBadge()
                if (entry.isDraft && onPublish != null) {
                    Text(
                        stringResource(R.string.diary_compose_publish),
                        style = AppTheme.typography.caption,
                        color = colors.accent.onContainer,
                        modifier = Modifier
                            .clip(AppTheme.shapes.full)
                            .background(colors.accent.container)
                            .clickable(onClickLabel = stringResource(R.string.diary_compose_publish)) { onPublish() }
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
                if (reactionCount > 0) {
                    Text(
                        "♥ $reactionCount",
                        style = AppTheme.typography.captionNumeric,
                        color = colors.accent.text,
                    )
                }
                if (commentCount > 0) {
                    Text(
                        stringResource(R.string.diary_comments_header, commentCount),
                        style = AppTheme.typography.caption,
                        color = colors.text.secondary,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    formatDiaryDate(entry.timestamp, "HH:mm"),
                    style = AppTheme.typography.captionNumeric,
                    color = colors.text.secondary,
                )
                Icon(
                    diaryVisibilityIcon(entry.visibilityRaw),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.text.secondary,
                )
            }
        }
    }
}

/** 日期肩：`dd` 大数字（tnum 防跳位）+ 今天/昨天/周几 + 心情 emoji（装饰·卡级 cd 已覆盖）。 */
@Composable
private fun TicketDateBlock(timestamp: Long, moodEmoji: String?) {
    val colors = AppTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            formatDiaryDate(timestamp, stringResource(R.string.diary_fmt_day_number)),
            style = AppTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = colors.text.primary,
        )
        Text(rememberDiaryDayLabel(timestamp), style = AppTheme.typography.caption, color = colors.text.secondary)
        moodEmoji?.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = AppTheme.typography.secondary)
        }
    }
}

/** 今天 / 昨天 / 周几（票据日期肩 + 交换日记复用）。 */
@Composable
internal fun rememberDiaryDayLabel(timestamp: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (day) {
        today -> stringResource(R.string.diary_today)
        today.minusDays(1) -> stringResource(R.string.diary_yesterday)
        else -> formatDiaryDate(timestamp, stringResource(R.string.diary_fmt_weekday), zone)
    }
}

/**
 * P1-16：日记卡/紧凑行整卡 TalkBack 文案（原 DiaryUiComponents 迁入·文案与合并逻辑不变）。日期锚句 +
 * 草稿/宠物/AI/心情 + 正文全文 + 图片数 + 评论数 + 可见性，null/空字段跳过，全角逗号分隔；消费方
 * combinedClickable 已 mergeDescendants（卡内绝不再加），本 cd 在同节点上替换合并杂音。
 */
@Composable
internal fun diaryEntryA11yDescription(
    entry: DiaryEntryEntity,
    commentCount: Int,
    reactionCount: Int = 0,
    authorName: String? = null,
    isOrphan: Boolean = false,
): String {
    val date = formatDiaryDate(entry.timestamp, stringResource(R.string.diary_fmt_ymd))
    return buildList {
        add(stringResource(R.string.a11y_diary_entry_date, date))
        authorName?.let { add(stringResource(R.string.diary_exchange_card_author, it)) }
        if (isOrphan) add(stringResource(R.string.diary_exchange_orphan_label))
        if (entry.isDraft) add(stringResource(R.string.diary_draft))
        if (entry.isPetDiary) add(stringResource(R.string.diary_pet_diary))
        if (entry.isAutoGenerated) add(stringResource(R.string.diary_ai_generated))
        entry.moodEmoji?.takeIf { it.isNotEmpty() }?.let { add(stringResource(R.string.a11y_diary_mood, it)) }
        entry.moodText?.takeIf { it.isNotEmpty() }?.let { add(it) }
        add(entry.content.ifEmpty { stringResource(R.string.diary_no_content) })
        entry.imagePaths.size.takeIf { it > 0 }?.let { add(stringResource(R.string.a11y_diary_images, it)) }
        if (reactionCount > 0) add(stringResource(R.string.a11y_diary_reactions, reactionCount))
        if (commentCount > 0) add(stringResource(R.string.a11y_diary_comments, commentCount))
        add(
            stringResource(
                if (DiaryVisibility.fromRaw(entry.visibilityRaw) == DiaryVisibility.PRIVATE) {
                    R.string.diary_visibility_private
                } else {
                    R.string.diary_visibility_open
                },
            ),
        )
    }.joinToString("，")
}
