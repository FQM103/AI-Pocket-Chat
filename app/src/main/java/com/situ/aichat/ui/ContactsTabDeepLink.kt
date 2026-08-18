package com.situ.aichat.ui

import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

/**
 * 深链跳「联系人」Tab 并**保证它真的落到栈顶可见**。两个来源共用（与
 * [com.situ.aichat.notification.NotificationNavigator.requestContacts] 对应）：
 * 分享给角色的通用分享点选收件角色（13.10a）、快捷设置磁贴「找角色」（13.10c）。
 *
 * 为什么不能只用底栏同款 `popUpTo+saveState+restoreState` 模式：当联系人 Tab 之上还压着详情页时
 * （如从联系人进的会话，栈 = [chats, contacts, chat/x]），`restoreState` 恢复的是**弹出的整段栈**——
 * 详情页连同联系人一起原样叠回，栈顶仍是详情页，联系人根本没露脸。对分享落地这意味着：文本已暂存、
 * 导航"已执行"，用户却什么都没看到 = 分享内容看似被吞（2026-07-02 模拟器实证）。
 *
 * 深链的语义是「让用户看到联系人列表」，故恢复后若栈顶不是联系人，把压在其上的页面弹掉。
 * 仍保留 `restoreState`：联系人自身的状态（搜索词/滚动位置，经其 NavBackStackEntry 保存）照常恢复。
 */
internal fun NavController.navigateToContactsTab(contactsRoute: String) {
    navigate(contactsRoute) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    if (currentDestination?.route != contactsRoute) {
        popBackStack(contactsRoute, inclusive = false)
    }
}
