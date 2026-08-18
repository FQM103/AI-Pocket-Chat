package com.situ.aichat.ui.profile

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.situ.aichat.R
import com.situ.aichat.ui.components.CharacterAvatar
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.designsystem.AppNavIcons
import com.situ.aichat.ui.designsystem.AppProfileIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface

/** 主角卡内发丝线（陶土系·纯装饰分隔）。 */
private const val HeroHairlineAlpha = 0.18f

/**
 * 「我」页主角卡（PROFILE 契约 §9.1·从 [ProfileScreen] 拆出控行数·只搬不改）：
 * 头像 + 昵称/bio + 「编辑」+ 陪伴统计行。一屏唯一大面积陶土（accent.container 染底 +
 * 135° 釉面高光·v2 §3②·气泡不上釉、这里是 hero 强调块）；raised 影档。
 */
@Composable
internal fun HeroCard(
    name: String?,
    avatarPath: String?,
    bio: String?,
    charactersCount: Int,
    companionDays: Int?,
    memoriesCount: Int,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    val heroBrush = if (colors.isDark) {
        SolidColor(colors.accent.container)
    } else {
        // 呼吸感染底：顶向 raised 提亮 35% → 底纯 container。
        Brush.verticalGradient(
            listOf(lerp(colors.accent.container, colors.surface.raised, 0.35f), colors.accent.container),
        )
    }
    val glazeAlpha = if (colors.isDark) 0.10f else 0.50f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appCardSurface(raised = true, background = heroBrush)
            .drawBehind {
                // 釉面高光：135° 对角，白 → 透明，40% 行程收束（起于左上角）。
                // 画矩形即可——圆角由 appCardSurface 收尾 clip 单源裁定（R1 🔵-3③）。
                drawRect(
                    brush = Brush.linearGradient(
                        0f to Color.White.copy(alpha = glazeAlpha),
                        0.4f to Color.Transparent,
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
            }
            .clickableScale { onClick() }
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeroAvatar(name = name, avatarPath = avatarPath, size = 68.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name ?: stringResource(R.string.profile_header_empty),
                    style = AppTheme.typography.titleMedium,
                    color = colors.text.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bio != null) {
                    Text(
                        bio,
                        style = AppTheme.typography.secondary,
                        color = colors.accent.onContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.Top).padding(start = 8.dp),
            ) {
                Text(
                    stringResource(R.string.profile_edit_label),
                    style = AppTheme.typography.caption,
                    color = colors.accent.onContainer,
                )
                Icon(
                    AppProfileIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.accent.onContainer,
                    modifier = Modifier.size(11.dp),
                )
            }
        }

        if (charactersCount > 0 && companionDays != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp)
                    .height(0.5.dp)
                    .background(colors.accent.text.copy(alpha = HeroHairlineAlpha)),
            )
            Row(Modifier.height(IntrinsicSize.Min)) {
                CompanionStat(
                    modifier = Modifier.weight(1f),
                    value = companionDays,
                    unit = stringResource(R.string.profile_stat_days_unit),
                    label = stringResource(R.string.profile_stat_days_label),
                )
                HeroStatDivider()
                CompanionStat(
                    modifier = Modifier.weight(1f),
                    value = charactersCount,
                    unit = stringResource(R.string.profile_stat_friends_unit),
                    label = stringResource(R.string.profile_stat_friends_label),
                )
                HeroStatDivider()
                CompanionStat(
                    modifier = Modifier.weight(1f),
                    value = memoriesCount,
                    unit = stringResource(R.string.profile_stat_memories_unit),
                    label = stringResource(R.string.profile_stat_memories_label),
                )
            }
        }
    }
}

/** 陪伴统计单元：数字（18/640/tnum·onContainer 深陶墨——accent.text 在 container 上实测 4.06 不达标）+ 单位 + label。 */
@Composable
private fun CompanionStat(modifier: Modifier = Modifier, value: Int, unit: String, label: String) {
    val colors = AppTheme.colors
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value.toString(),
                style = AppTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                color = colors.accent.onContainer,
            )
            if (unit.isNotEmpty()) {
                Text(
                    unit,
                    style = AppTheme.typography.caption,
                    color = colors.accent.onContainer,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                )
            }
        }
        Text(label, style = AppTheme.typography.caption, color = colors.accent.onContainer)
    }
}

/** 统计列间 0.5dp 竖发丝线。 */
@Composable
private fun HeroStatDivider() {
    Box(
        Modifier
            .padding(vertical = 4.dp)
            .fillMaxHeight()
            .width(0.5.dp)
            .background(AppTheme.colors.accent.text.copy(alpha = 0.16f)),
    )
}

/** 主角卡头像：有照片=照片；空态=深陶渐变底 + 昵称首字（onDeep）；无昵称退人形线稿。 */
@Composable
private fun HeroAvatar(name: String?, avatarPath: String?, size: Dp) {
    val colors = AppTheme.colors
    if (!avatarPath.isNullOrEmpty()) {
        CharacterAvatar(name = name ?: "我", avatarPath = avatarPath, size = size)
        return
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(colors.accent.deepStart, colors.accent.deepEnd))),
        contentAlignment = Alignment.Center,
    ) {
        val monogram = name?.trim()?.firstOrNull()?.toString()
        if (monogram != null) {
            Text(monogram, style = AppTheme.typography.titleMedium, color = colors.accent.onDeep)
        } else {
            Icon(
                AppNavIcons.Profile,
                contentDescription = null,
                tint = colors.accent.onDeep,
                modifier = Modifier.size(size * 0.46f),
            )
        }
    }
}
