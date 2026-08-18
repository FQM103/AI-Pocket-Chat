package com.situ.aichat.ui.story

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.situ.aichat.ui.components.rememberReduceMotion
import com.situ.aichat.ui.designsystem.AppMenu
import com.situ.aichat.ui.designsystem.appMenuHairline

/**
 * 故事族**长按玻璃菜单**的容器（ST10-4 过审造型：20dp 圆角 + 表面 94% 垫底 + 0.75dp 发丝边 + 软投影 + 216dp 宽）。
 *
 * **本造型 2026-08-06 起是全库浮层菜单的母版**：容器实现已收敛为设计系统件 [AppMenu]（M3 清零卷一·总契约 §2.3
 * 「数值逐字推广」）——本函数只剩薄委托，零像素变化。故事族保留这个名字是为了不动三处调用点的语义。
 */
@Composable
internal fun StoryGlassMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppMenu(expanded = expanded, onDismiss = onDismiss, content = content)
}

/** 玻璃菜单的发丝色（菜单内的分隔条与边框同源）——单源已上收到 [appMenuHairline]，本函数只剩薄委托。 */
@Composable
internal fun storyGlassMenuHairline(): Color = appMenuHairline()

/**
 * 长按菜单期的全屏轻压暗层（ST10-4 拍板②·恒黑 10%·纯视觉不消费点击——外点关闭由菜单 Popup 自带）。
 * 自 [StoryBookshelfScreen] 原样抽出（fade 150/220 与 reduceMotion 直显隐逐字保持），供书架与
 * 结局档案全览两处长按菜单共用。调用方放在内容之后、同一个填满的 Box 里。
 */
@Composable
internal fun StoryShelfMenuScrim(visible: Boolean) {
    if (rememberReduceMotion()) {
        if (visible) ShelfMenuScrimBox()
    } else {
        AnimatedVisibility(visible = visible, enter = fadeIn(tween(150)), exit = fadeOut(tween(220))) {
            ShelfMenuScrimBox()
        }
    }
}

@Composable
private fun ShelfMenuScrimBox() {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.10f)))
}
