package com.situ.aichat.ui.components

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 屏幕阅读器（触摸浏览）是否激活——TalkBack 开启时恒为 true（批6 复核 #3）。
 *
 * 用途=行为门控（如通话拨号点点 ticker：每 360ms 的语义变化对 live region 节点持续发事件，
 * TalkBack 默认配置下会反复重读状态文案——视障用户本就看不到点点，屏读激活时直接冻结 ticker
 * 从事件源头掐断，零体验损失）。与 [rememberReduceMotion] 的一次性读口径不同，这里用监听器
 * 热更：TalkBack 可在使用中开关，且行为门控的热更代价为零。
 */
@Composable
fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var enabled by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        if (manager == null) {
            onDispose {}
        } else {
            val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
            manager.addTouchExplorationStateChangeListener(listener)
            onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
        }
    }
    return enabled
}
