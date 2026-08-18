package com.situ.aichat.ui.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.situ.aichat.ui.components.AppMotion
import com.situ.aichat.ui.components.LocalAppHaptics
import com.situ.aichat.ui.components.rememberReduceMotion

/**
 * 确认弹窗的确认钮语气（M3 清零总契约 §2.1·拍板③）。
 * - [Primary]：陶土渐变药丸（[AppButtonStyle.Primary]）——常规确认。
 * - [Danger]：深琥珀实底（[AppButtonStyle.Warning]）——动作不可恢复（删除/清空/移除/退出不保存/恢复默认）。
 *   **不用大红**：琥珀 = 「不可撤销」警示，血红 error 留给错误态。
 */
enum class AppDialogTone { Primary, Danger }

/**
 * Fable-5 确认弹窗「纸卡」（M3 清零卷一·总契约 §2.1·2026-07-17 草图过审）。
 *
 * 底座 = 平台 [Dialog]（`usePlatformDefaultWidth = false` 拿全宽度确定性——平台默认宽随 ROM 漂移），
 * 皮 = 既有纸卡配方 [appCardSurface] `raised` + [grainSurface]，圆角 20dp（= [AppShapes.overlay] 值）。
 * 返回键 / 点外部关闭由平台 Dialog 提供，语义与被它取代的 M3 `AlertDialog` 等价。
 *
 * 结构自上而下：标题（[title]·[AppTypography.titleSmall]）→ 10dp → 正文（[body] 纯文字自带滚动与
 * 400dp 高度帽防长文顶破屏 / [content] 自定义槽**不包滚动**，由站点自持防双重嵌套）→ 20dp → 按钮排。
 * [body] 与 [content] 二选一；都传时 [body] 先渲染。
 *
 * 按钮排右对齐 `spacedBy(10.dp, End)`，顺序 [幽灵取消][确认]；传了 [neutralText] 的三动作站点，
 * 辅助动作 `AppButton(style = Text)` 靠左 + `Spacer(weight 1f)` 撑开。
 * [confirmText] 与 [dismissText] **皆 null** → 整排不渲染（进行中 / 纯展示弹窗）；只传 [dismissText]
 * = 「取消是唯一底部钮」的选择类弹窗——渲染**幽灵单钮排**（取消恒幽灵、绝不升主药丸；R1 D-3 修订
 * 2026-08-06）；[dismissText] 为 null → 无取消钮（肯定语义单钮站）。[onDismiss] 为 null 时取消钮回调走
 * [onDismissRequest]。
 *
 * a11y：[title] 非空时根节点带 `paneTitle`；各钮触达 48dp 与触觉由 [AppButton] / [AppDialogGhostButton] 自带。
 */
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    title: String?,
    modifier: Modifier = Modifier,
    body: String? = null,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmTone: AppDialogTone = AppDialogTone.Primary,
    confirmEnabled: Boolean = true,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    neutralText: String? = null,
    onNeutral: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AppDialogCard(onDismissRequest = onDismissRequest, modifier = modifier, paneTitleText = title) {
        if (title != null) {
            Text(title, style = AppTypography.titleSmall, color = AppTheme.colors.text.primary)
        }
        val hasContentArea = body != null || content != null
        if (title != null && hasContentArea) Spacer(Modifier.height(10.dp))
        if (body != null) {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(body, style = AppTypography.dialogBody, color = AppTheme.colors.text.secondary)
            }
        }
        content?.invoke(this)
        if (confirmText != null || dismissText != null) {
            // 图纸 §4.1：正文→按钮排 20dp。无正文的「纯标题 + 按钮」站点同取 20dp（见 §11 D-7）。
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (neutralText != null) {
                    AppButton(onClick = { onNeutral?.invoke() }, style = AppButtonStyle.Text) { Text(neutralText) }
                    Spacer(Modifier.weight(1f))
                }
                if (dismissText != null) {
                    AppDialogGhostButton(text = dismissText, onClick = onDismiss ?: onDismissRequest)
                }
                if (confirmText != null) {
                    AppButton(
                        onClick = { onConfirm?.invoke() },
                        style = if (confirmTone == AppDialogTone.Danger) AppButtonStyle.Warning else AppButtonStyle.Primary,
                        enabled = confirmEnabled,
                    ) { Text(confirmText) }
                }
            }
        }
    }
}

/**
 * 裸纸卡逃生口（完全非标弹窗站点用）：同容器、同纸卡、同内边距，只是不预设标题 / 正文 / 按钮排结构。
 * 内部动作钮请照 [AppDialog] 口径用 [AppButton] / [AppDialogGhostButton]，别退回 M3 `TextButton`。
 */
@Composable
fun AppDialogShell(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppDialogCard(onDismissRequest = onDismissRequest, modifier = modifier, paneTitleText = null, content = content)
}

/** [AppDialog] / [AppDialogShell] 共用的纸卡容器（[paneTitleText] 非空时挂 a11y 窗格名）。 */
@Composable
private fun AppDialogCard(
    onDismissRequest: () -> Unit,
    modifier: Modifier,
    paneTitleText: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .let { if (paneTitleText != null) it.semantics { paneTitle = paneTitleText } else it }
                // appCardSurface 收尾自带 clip（圆角单源），调用侧不再叠 Surface/clip。
                .appCardSurface(raised = true, cornerRadius = 20.dp)
                .grainSurface()
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
            content = content,
        )
    }
}

/**
 * 幽灵药丸（弹窗族**专属**三级钮·总契约 §2.1）：透明底 + 1dp 15% 墨色描边 + 次级字。
 *
 * **有意不入 [AppButtonStyle] 全局枚举**——按钮族契约「无冷灰描边」不动，幽灵只在浮层语境成立，
 * 收在本文件内防全 App 滥用。手感骨架逐项照 [AppButton]：按压 0.97 [AppMotion.calmSpring]
 * （[rememberReduceMotion] 门控）+ 品牌 ripple + `haptics.light()`；[enabled]=false 降 40% 透明且不可点。
 */
@Composable
internal fun AppDialogGhostButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    val haptics = LocalAppHaptics.current
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && enabled && !reduceMotion) 0.97f else 1f,
        animationSpec = AppMotion.calmSpring(),
        label = "appDialogGhostPress",
    )
    Row(
        modifier = Modifier
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = { haptics.light(); onClick() },
            )
            .minimumInteractiveComponentSize()
            .clip(AppShapes.full)
            .border(1.dp, colors.text.primary.copy(alpha = 0.15f), AppShapes.full)
            .alpha(if (enabled) 1f else 0.4f)
            .heightIn(min = 40.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = AppTypography.label, color = colors.text.secondary)
    }
}
