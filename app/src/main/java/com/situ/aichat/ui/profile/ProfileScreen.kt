package com.situ.aichat.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.situ.aichat.R
import com.situ.aichat.ui.components.clickableScale
import com.situ.aichat.ui.components.contentMaxWidth
import com.situ.aichat.ui.designsystem.AppProfileIcons
import com.situ.aichat.ui.designsystem.AppTheme
import com.situ.aichat.ui.designsystem.appCardSurface
import com.situ.aichat.ui.designsystem.grainSurface

/**
 * 「我」Tab 仪表盘 v2（PROFILE 契约 §9·2026-07-12 过审）。
 *
 * 六段：大标题（进内容区随滚动走）→ 主角卡 [HeroCard]（拆在 ProfileHeroCard.kt·头像/昵称/bio/编辑 +
 * 陪伴统计行）→ 资产两格（动态/钱包·只放会生长的数字）→ 礼物一条（店+盒轻量双入口·恒定款数降小字）→
 * 设置条 → 底距。质感走设计语言 v2：gutter 20 / 卡内 16(hero 20) / [appCardSurface] 双层软影+发丝线+
 * 月光沿 / [grainSurface] 纸感微噪 / 自绘 1.7 线稿图标族 / 数字 tnum。
 *
 * 货币系统关闭时（拍板 2026-06-18）：隐藏钱包与礼物条，「我的动态」改全宽卡。空态（拍板 2026-07-12 ④）：
 * 动态 0 条 / 礼物盒 0 件不摆裸零，换温和引导句；角色数 0 时陪伴统计行整行隐藏。
 */
@Composable
fun ProfileScreen(
    onEditProfile: () -> Unit,
    onOpenUserMoments: () -> Unit,
    onOpenUserWallet: () -> Unit,
    onOpenGiftShop: () -> Unit,
    onOpenGiftBox: () -> Unit,
    onOpenSettings: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
    viewModel: ProfileDashboardViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val currencyEnabled by viewModel.currencyEnabled.collectAsStateWithLifecycle()
    val momentsCount by viewModel.momentsCount.collectAsStateWithLifecycle()
    val coinBalance by viewModel.coinBalance.collectAsStateWithLifecycle()
    val receivedGiftsCount by viewModel.receivedGiftsCount.collectAsStateWithLifecycle()
    val charactersCount by viewModel.charactersCount.collectAsStateWithLifecycle()
    val companionDays by viewModel.companionDays.collectAsStateWithLifecycle()
    val memoriesCount by viewModel.memoriesCount.collectAsStateWithLifecycle()
    val giftCatalogCount = viewModel.giftCatalogCount

    val colors = AppTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface.base)
            .grainSurface(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .contentMaxWidth()
                .padding(horizontal = 20.dp), // v2 军规：屏 gutter 恒 20
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.tab_profile),
                style = AppTheme.typography.titleLarge,
                color = colors.text.primary,
                modifier = Modifier
                    .padding(top = 20.dp, bottom = 4.dp)
                    .semantics { heading() },
            )

            HeroCard(
                name = profile?.nickname?.takeIf { it.isNotBlank() },
                avatarPath = profile?.avatarPath,
                bio = profile?.bio?.takeIf { it.isNotBlank() },
                charactersCount = charactersCount,
                companionDays = companionDays,
                memoriesCount = memoriesCount,
                onClick = onEditProfile,
            )

            if (currencyEnabled) {
                Row(
                    modifier = Modifier.height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatTile(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        icon = AppProfileIcons.Moments,
                        tileColor = colors.accent.primary.copy(alpha = ProfileTileAlphaClay),
                        iconColor = colors.accent.text,
                        title = stringResource(R.string.moment_user_moments_title),
                        value = momentsCount,
                        unit = stringResource(R.string.profile_box_moments_unit),
                        hint = stringResource(R.string.profile_box_moments_hint),
                        emptyText = stringResource(R.string.profile_box_moments_empty).takeIf { momentsCount == 0 },
                        onClick = onOpenUserMoments,
                    )
                    StatTile(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        icon = AppProfileIcons.Wallet,
                        tileColor = colors.economy.goldGradientStart.copy(alpha = ProfileTileAlphaGold),
                        iconColor = colors.economy.gold,
                        title = stringResource(R.string.wallet_title),
                        value = coinBalance,
                        unit = stringResource(R.string.profile_box_wallet_unit),
                        hint = stringResource(R.string.profile_box_wallet_hint),
                        valueColor = colors.economy.gold,
                        onClick = onOpenUserWallet,
                    )
                }
                GiftRow(
                    shopSub = stringResource(R.string.profile_box_shop_count, giftCatalogCount),
                    giftBoxSub = if (receivedGiftsCount > 0) {
                        stringResource(R.string.profile_box_giftbox_received, receivedGiftsCount)
                    } else {
                        stringResource(R.string.profile_box_giftbox_empty)
                    },
                    onOpenShop = onOpenGiftShop,
                    onOpenBox = onOpenGiftBox,
                )
            } else {
                // 货币关：只剩「我的动态」→ 全宽卡，不留半宽空格（拍板 2026-06-18）。
                MomentsWideCard(
                    count = momentsCount,
                    onClick = onOpenUserMoments,
                )
            }

            Spacer(Modifier.height(12.dp)) // 与 spacedBy 12 合计组间 24（资产组 → 设置组）
            SettingsEntryBar(onClick = onOpenSettings)
            Spacer(Modifier.height(bottomContentPadding))
        }
    }
}

/** 图标块 tint 档（浅色家族底·同族深色图标·对比断言在 ColorContrastTest）。 */
internal const val ProfileTileAlphaClay = 0.14f
internal const val ProfileTileAlphaGold = 0.30f

/** 资产格：图标块 + 标题 + 大数字（22/640/tnum）+ 单位 + 提示。0 值传 [emptyText] 时数字行换温和引导。 */
@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    tileColor: Color,
    iconColor: Color,
    title: String,
    value: Int,
    unit: String,
    hint: String,
    emptyText: String? = null,
    valueColor: Color = AppTheme.colors.text.primary,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .appCardSurface() // 圆角/裁剪单源在内（R1 🔵-3③）
            .clickableScale { onClick() }
            .padding(16.dp), // v2 军规：卡内 16
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconTile(icon, tileColor, iconColor)
        Spacer(Modifier.height(8.dp))
        Text(title, style = AppTheme.typography.label, color = colors.text.primary)
        if (emptyText != null) {
            // 空态不摆裸零（拍板 ④）：数字行换一句温和引导，行高与提示行相同。
            Text(
                emptyText,
                style = AppTheme.typography.secondary,
                color = colors.accent.text,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value.toString(),
                    style = AppTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = valueColor,
                )
                Text(
                    unit,
                    style = AppTheme.typography.caption,
                    color = colors.text.secondary,
                    modifier = Modifier.padding(start = 3.dp, bottom = 3.dp),
                )
            }
        }
        Text(hint, style = AppTheme.typography.caption, color = colors.text.secondary)
    }
}

/** 礼物一条：左店右盒轻量双入口（各自可点·中缝 0.5dp 竖发丝线·恒定款数只作小字）。 */
@Composable
private fun GiftRow(
    shopSub: String,
    giftBoxSub: String,
    onOpenShop: () -> Unit,
    onOpenBox: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .appCardSurface()
            .height(IntrinsicSize.Min),
    ) {
        GiftHalf(
            modifier = Modifier.weight(1f),
            icon = AppProfileIcons.Shop,
            title = stringResource(R.string.profile_box_shop_title),
            sub = shopSub,
            onClick = onOpenShop,
        )
        Box(
            Modifier
                .padding(vertical = 12.dp)
                .fillMaxHeight()
                .width(0.5.dp)
                .background(AppTheme.colors.text.primary.copy(alpha = 0.08f)),
        )
        GiftHalf(
            modifier = Modifier.weight(1f),
            icon = AppProfileIcons.GiftBox,
            title = stringResource(R.string.profile_box_giftbox_title),
            sub = giftBoxSub,
            onClick = onOpenBox,
        )
    }
}

@Composable
private fun GiftHalf(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    Row(
        // ripple 矩形由外层 appCardSurface 的收尾 clip 裁圆角（圆角单源·R1 🔵-3③）。
        modifier = modifier
            .clickableScale { onClick() }
            .padding(16.dp), // v2 军规上网格（R1 🟡-2：原 14/10 孤值打回）；16×2 + 内容 ≈52dp ≥48 触达
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent.text, modifier = Modifier.size(21.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = AppTheme.typography.label, color = colors.text.primary)
            Text(
                sub,
                style = AppTheme.typography.captionNumeric,
                color = colors.text.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            AppProfileIcons.ChevronRight,
            contentDescription = null,
            tint = colors.text.tertiary, // 纯装饰箭头（整半卡即点击区）
            modifier = Modifier.size(12.dp),
        )
    }
}

/** 货币关闭时「我的动态」的全宽卡（横向：图标块 + 标题/数量 + 箭头）。 */
@Composable
private fun MomentsWideCard(count: Int, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .appCardSurface()
            .clickableScale { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(
            AppProfileIcons.Moments,
            colors.accent.primary.copy(alpha = ProfileTileAlphaClay),
            colors.accent.text,
        )
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.moment_user_moments_title),
                style = AppTheme.typography.label,
                color = colors.text.primary,
            )
            val detail = if (count == 0) {
                stringResource(R.string.profile_box_moments_empty)
            } else {
                "$count ${stringResource(R.string.profile_box_moments_unit)} · ${stringResource(R.string.profile_box_moments_hint)}"
            }
            Text(detail, style = AppTheme.typography.captionNumeric, color = colors.text.secondary)
        }
        Icon(
            AppProfileIcons.ChevronRight,
            contentDescription = null,
            tint = colors.text.tertiary,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** 设置条：齿轮图标块（中性暖灰 sunken 底）+「设置」+ 分类预览 + 箭头 → 独立设置页。 */
@Composable
private fun SettingsEntryBar(onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .appCardSurface()
            .clickableScale { onClick() }
            .padding(16.dp), // v2 军规上网格（R1 🟡-2：原 vertical 14 孤值打回）
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(AppProfileIcons.Tune, colors.surface.sunken, colors.text.secondary)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_screen_title),
                style = AppTheme.typography.label,
                color = colors.text.primary,
            )
            Text(
                stringResource(R.string.profile_settings_entry_preview),
                style = AppTheme.typography.caption,
                color = colors.text.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            AppProfileIcons.ChevronRight,
            contentDescription = null,
            tint = colors.text.tertiary,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** 36dp 圆角方块图标（柔和家族色底 + 同族深色 1.7 线稿图标·浅底深字守 WCAG）。 */
@Composable
private fun IconTile(icon: ImageVector, tileColor: Color, iconColor: Color) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(tileColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
    }
}
