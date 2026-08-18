package com.situ.aichat.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界深链 navigator 流（W9a 图纸 §5 E11·§7 T2-2 的 navigator 部分）：requestWorld 置真 → consumeWorld 归假。
 * 收集端导航 + 禁 restoreState 属 T4（[com.situ.aichat.ui.AIChatApp] 装机验）。
 */
class NotificationNavigatorWorldTest {

    @Test
    fun requestWorld_thenConsume_togglesPendingFlag() {
        val nav = NotificationNavigator()
        assertFalse("初始未挂起", nav.pendingWorld.value)
        nav.requestWorld()
        assertTrue("请求后置真", nav.pendingWorld.value)
        nav.consumeWorld()
        assertFalse("消费后归假（不残留致重导航）", nav.pendingWorld.value)
    }
}
