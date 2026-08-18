package com.situ.aichat.ui.moments

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import androidx.compose.ui.res.stringResource
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.MomentCommentEntity
import com.situ.aichat.data.local.entity.MomentPostWithRelations
import com.situ.aichat.data.model.MomentAuthorType
import com.situ.aichat.data.model.imagePaths
import com.situ.aichat.moments.MomentCommentTreeBuilder
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppMomentIcons
import com.situ.aichat.ui.designsystem.AppShapes
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import kotlinx.coroutines.delay
import com.situ.aichat.util.DateFormatters
import com.situ.aichat.util.rememberTimeTick

/**
 * 朋友圈帖子卡片（M06 7.2.7，对齐 iOS `MomentPostCard`）：头部（头像 / 名字 / 相对时间）+ 正文 + 图片网格
 * + 互动栏（点赞头像 ≤5 叠加 + 点赞按钮 + 评论数）+ 评论预览（顶层最后 2 条 + “查看全部 N 条”）。
 *
 * 纯展示组件：点赞 / 删除等写库动作经回调上抛由 ViewModel 完成（界面不直接碰数据层）。在列表 / 详情（7.2.8）
 * / 角色动态页（7.2.8）共用。[onCharacterTap] 非空时头部头像+名可点（→ 角色动态页，7.2.8 接线；列表传 null）。
 *
 * 用户作者的**名字标签固定显示「我」**（对齐 iOS `String(localized:"Me")`），头像走真实用户头像；[userName]
 * 仅用于无头像时的字母占位。
 */
@Composable
fun MomentPostCard(
    post: MomentPostWithRelations,
    characterDict: Map<String, CharacterEntity>,
    userName: String,
    userAvatarPath: String?,
    onToggleLike: () -> Unit,
    modifier: Modifier = Modifier,
    onCharacterTap: ((String) -> Unit)? = null,
) {
    val entity = post.post
    val likes = post.likes
    val comments = post.comments
    val isUserAuthor = MomentAuthorType.fromRaw(entity.authorTypeRaw) == MomentAuthorType.USER
    val authorCharacter = if (!isUserAuthor) entity.characterUuid?.let { characterDict[it] } else null

    val meLabel = stringResource(R.string.moment_author_me)
    val aiLabel = stringResource(R.string.moment_author_ai)
    val authorLabel = if (isUserAuthor) meLabel else (authorCharacter?.name ?: aiLabel)
    val authorAvatarPath = if (isUserAuthor) userAvatarPath else authorCharacter?.avatarPath
    val monogramName = if (isUserAuthor) userName.ifBlank { meLabel } else authorLabel

    val hasUserLike = likes.any { MomentAuthorType.fromRaw(it.authorTypeRaw) == MomentAuthorType.USER }
    val characterLikes = likes.mapNotNull { like ->
        if (MomentAuthorType.fromRaw(like.authorTypeRaw) == MomentAuthorType.CHARACTER) {
            like.characterUuid?.let { characterDict[it] }
        } else {
            null
        }
    }

    Column(
        // Fable-5 卡皮（MOMENTS_FEED 契约 §2.1）：appCardSurface = 双层软影 + 发丝描边 + 呼吸白（深色平色+发丝线），
        // 圆角 16 单源在内；内边距 16 / 元素间距 12 沿用。
        modifier = modifier
            .fillMaxWidth()
            .appCardSurface()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderRow(
            label = authorLabel,
            monogramName = monogramName,
            avatarPath = authorAvatarPath,
            timeText = DateFormatters.relativeTimeString(
                millis = entity.timestamp,
                nowMillis = rememberTimeTick(), // moments-ui-10：信息流相对时间每 60s 自动刷新（= iOS MomentPostCard 读 TimeTick）

                strings = DateFormatters.RelativeTimeStrings(
                    justNow = stringResource(R.string.relative_time_just_now),
                    minutesAgo = stringResource(R.string.relative_time_minutes_ago),
                    hoursAgo = stringResource(R.string.relative_time_hours_ago),
                    yesterday = stringResource(R.string.relative_time_yesterday),
                ),
            ),
            onTap = if (!isUserAuthor && entity.characterUuid != null && onCharacterTap != null) {
                { onCharacterTap(entity.characterUuid) }
            } else {
                null
            },
        )

        if (entity.content.isNotEmpty()) {
            Text(entity.content, style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.text.primary)
        }

        val images = entity.imagePaths
        if (images.isNotEmpty()) {
            MomentImageGrid(imagePaths = images)
        }

        InteractionBar(
            likesCount = likes.size,
            commentsCount = comments.size,
            hasUserLike = hasUserLike,
            characterLikes = characterLikes,
            userMonogramName = userName.ifBlank { meLabel },
            userAvatarPath = userAvatarPath,
            onToggleLike = onToggleLike,
        )

        if (comments.isNotEmpty()) {
            CommentPreview(
                comments = comments,
                totalCount = comments.size,
                characterDict = characterDict,
                meLabel = meLabel,
                aiLabel = aiLabel,
            )
        }
    }
}

@Composable
private fun HeaderRow(
    label: String,
    monogramName: String,
    avatarPath: String?,
    timeText: String,
    onTap: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // P1-21：onClickLabel=「查看 TA 的动态」。iOS :149 label=「{name}的头像」有意不照搬（把导航钮
            // 误标成头像且抹掉名字朗读）；安卓播报「{名字}，双击查看 TA 的动态」=信息严格超集（铁律#1 不降级）。
            modifier = if (onTap != null) {
                Modifier.clickable(onClickLabel = stringResource(R.string.a11y_moment_open_author), onClick = onTap)
            } else {
                Modifier
            },
        ) {
            CharacterAvatar(name = monogramName, avatarPath = avatarPath, size = 34.dp) // moments-ui-8：1:1 iOS AvatarSize.mini(34)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.text.primary)
        }
        Spacer(Modifier.weight(1f))
        // 时间戳 = 功能小字走 secondary（设计语言 §1.4：tertiary 纯装饰不承载信息）。
        Text(timeText, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.text.secondary)
    }
}

@Composable
private fun InteractionBar(
    likesCount: Int,
    commentsCount: Int,
    hasUserLike: Boolean,
    characterLikes: List<CharacterEntity>,
    userMonogramName: String,
    userAvatarPath: String?,
    onToggleLike: () -> Unit,
) {
    // P1-10：爱心点赞弹跳 + 轻触觉（=iOS MomentPostCard.swift:194-205）。观察 hasUserLike false→true
    // 转变而非行点击——长按菜单点赞（消费方 DropdownMenu=iOS contextMenu 同路径 :90-98）同样弹跳+触觉；
    // prevLiked 首组合=当前值，LazyColumn 回收重入/Flow 回灌不误弹。触觉仅新增点赞（iOS toggleLike :314-323
    // 仅 insert 分支触发动画；iOS :205 sensoryFeedback 双沿各响一次属实现副产物，有意不复刻、单数口径）。
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    var likeBounce by remember { mutableStateOf(false) }
    var prevLiked by remember { mutableStateOf(hasUserLike) }
    LaunchedEffect(hasUserLike) {
        if (hasUserLike && !prevLiked) {
            haptics.light() // = iOS :205 .sensoryFeedback(.impact(weight: .light))
            if (!reduceMotion) {
                likeBounce = true
                delay(300) // = iOS triggerLikeAnimation :326-334 Task.sleep(0.3) 后回落
                likeBounce = false
            }
        } else if (!hasUserLike) {
            // 300ms 内取消赞会重启本协程掐死 delay——显式归位防心卡 1.3（iOS 靠不被取消的残留 Task 自愈，结构不同）。
            likeBounce = false
        }
        prevLiked = hasUserLike
    }
    val likeScale by animateFloatAsState(
        targetValue = if (likeBounce) 1.3f else 1f, // = iOS :194 scaleEffect(1.3 : 1.0)
        animationSpec = AppMotion.likeBounceSpring(), // ζ0.5/k≈438.65 = iOS spring(0.3, bounce 0.5)
        label = "likeBounce",
    )
    val likedState = stringResource(R.string.a11y_moment_liked)
    val notLikedState = stringResource(R.string.a11y_moment_not_liked)
    Row(verticalAlignment = Alignment.CenterVertically) {
        LikeAvatarList(
            likesCount = likesCount,
            hasUserLike = hasUserLike,
            characterLikes = characterLikes,
            userMonogramName = userMonogramName,
            userAvatarPath = userAvatarPath,
        )
        Spacer(Modifier.weight(1f))

        // 点赞按钮（P1-21：toggleable(Role.Switch)+stateDescription，范本 SettingsSwitchRow；
        // Icon cd 保持 moment_unlike/moment_like 切换=iOS :206 文案逐字，合并后并入行节点播报）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .toggleable(value = hasUserLike, role = Role.Switch, onValueChange = { onToggleLike() })
                .semantics { stateDescription = if (hasUserLike) likedState else notLikedState }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Icon(
                // 自绘描边/填充双态心（契约 §2.1·D3 拍板）：已赞 = 深陶填充（accent.text·守 ≤3 常驻彩），未赞 = secondary 描边。
                imageVector = if (hasUserLike) AppMomentIcons.HeartFilled else AppMomentIcons.Heart,
                contentDescription = stringResource(if (hasUserLike) R.string.moment_unlike else R.string.moment_like),
                tint = if (hasUserLike) AppTheme.colors.accent.text else AppTheme.colors.text.secondary,
                // scale 只挂 18dp 图标不包计数 Text（iOS :194 scaleEffect 仅作用于 Image）；ζ0.5 过冲到 ≈1.35 再回=iOS 同感勿钳。
                modifier = Modifier.size(18.dp).scale(likeScale),
            )
            if (likesCount > 0) {
                Text("$likesCount", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.text.secondary)
            }
        }

        Spacer(Modifier.width(16.dp))

        // 评论数
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                imageVector = AppMomentIcons.CommentBubble,
                contentDescription = stringResource(R.string.moment_comments_label),
                tint = AppTheme.colors.text.secondary,
                modifier = Modifier.size(18.dp),
            )
            if (commentsCount > 0) {
                Text("$commentsCount", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.text.secondary)
            }
        }
    }
}

@Composable
private fun LikeAvatarList(
    likesCount: Int,
    hasUserLike: Boolean,
    characterLikes: List<CharacterEntity>,
    userMonogramName: String,
    userAvatarPath: String?,
) {
    if (likesCount <= 0) return
    val stroke = AppTheme.colors.surface.raised
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        characterLikes.take(5).forEach { character ->
            Box(modifier = Modifier.border(1.5.dp, stroke, CircleShape)) {
                CharacterAvatar(name = character.name, avatarPath = character.avatarPath, size = 22.dp)
            }
        }
        if (hasUserLike) {
            Box(modifier = Modifier.border(1.5.dp, stroke, CircleShape)) {
                CharacterAvatar(name = userMonogramName, avatarPath = userAvatarPath, size = 22.dp)
            }
        }
    }
    // 叠加头像最多展示 6（5 角色 + 用户自己），其余以「+N」表示（口径对齐 iOS）。
    val extraCount = likesCount - minOf(characterLikes.size + (if (hasUserLike) 1 else 0), 6)
    if (extraCount > 0) {
        Spacer(Modifier.width(8.dp))
        Text("+$extraCount", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.text.secondary)
    }
}

@Composable
private fun CommentPreview(
    comments: List<MomentCommentEntity>,
    totalCount: Int,
    characterDict: Map<String, CharacterEntity>,
    meLabel: String,
    aiLabel: String,
) {
    // 「评论小笺」（契约 §2.1·D4 拍板）：分隔线退场，预览坐进 sunken 圆角 8 内衬（手账内衬感）；
    // 「查看全部」深陶 accent.text（×sunken 实测 4.53:1 ≥4.5·ColorContrastTest 看门）。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.small)
            .background(AppTheme.colors.surface.sunken)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 口径与详情页一致：顶层 + 孤儿（父已删 / 跨帖污染）都算「顶层」，取最后 2 条预览。
        // remember(comments)：滚动重组不再每次重建评论树（对齐详情页 MomentDetailScreen 同款包法，K6）。
        val topComments = remember(comments) { MomentCommentTreeBuilder.topLevelOrOrphaned(comments).takeLast(2) }
        topComments.forEach { comment ->
            CommentPreviewRow(
                authorLabel = momentAuthorName(comment.authorTypeRaw, comment.characterUuid, characterDict, meLabel, aiLabel),
                content = comment.content,
            )
        }
        if (totalCount > 2) {
            Text(
                stringResource(R.string.moment_view_all_comments, totalCount),
                style = MaterialTheme.typography.labelMedium,
                color = AppTheme.colors.accent.text,
            )
        }
    }
}

@Composable
private fun CommentPreviewRow(authorLabel: String, content: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(authorLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.text.primary)
        Text(
            content,
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.text.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
