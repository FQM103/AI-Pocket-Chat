package com.situ.aichat.ui

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 深链跳联系人 Tab 落地 T2（13.10a 分享点选 / 13.10c QS 磁贴共用 [navigateToContactsTab]）。
 *
 * 规格（从分享落地语义独立反推，非照搬实现）：无论当前返回栈什么形状，调用后**联系人必须在栈顶可见**——
 * 分享选择条就画在联系人页里，联系人不露脸 = 分享内容对用户蒸发。四种真实栈形逐一验，
 * 外加一条「裸 Tab 切换模式会被 restoreState 叠回详情页」的金丝雀（它若转绿说明 Navigation 语义变了，
 * [navigateToContactsTab] 的弹栈兜底可再评估）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactsTabDeepLinkTest {

    private lateinit var nav: TestNavHostController

    /** 与 AIChatApp 同形的迷你图：chats(start) / contacts 两个顶级 Tab + chat/{id} 详情。 */
    @Before
    fun setUp() {
        nav = TestNavHostController(RuntimeEnvironment.getApplication())
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        nav.graph = nav.createGraph(startDestination = "chats") {
            composable("chats") {}
            composable("contacts") {}
            composable("chat/{id}") {}
        }
    }

    /** 底栏同款 Tab 切换（AIChatApp 底栏 onClick 的模式），用于搭出真实栈形。 */
    private fun NavHostController.tabSwitch(route: String) {
        navigate(route) {
            popUpTo(graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    private fun currentRoute() = nav.currentDestination?.route

    @Test
    fun `列表态直接落地联系人`() {
        // 栈 = [chats]
        nav.navigateToContactsTab("contacts")
        assertEquals("contacts", currentRoute())
    }

    @Test
    fun `从列表进的会话也落地联系人`() {
        nav.navigate("chat/x") // 栈 = [chats, chat/x]
        nav.navigateToContactsTab("contacts")
        assertEquals("contacts", currentRoute())
    }

    @Test
    fun `从联系人进的会话仍落地联系人_分享被吞真伤栈形`() {
        nav.tabSwitch("contacts")
        nav.navigate("chat/x") // 栈 = [chats, contacts, chat/x]：restoreState 会连 chat/x 一起恢复
        nav.navigateToContactsTab("contacts")
        assertEquals("contacts", currentRoute())
        // 压在联系人之上的会话必须已弹掉：回退一步应到 chats 而非 chat/x。
        nav.popBackStack()
        assertEquals("chats", currentRoute())
    }

    @Test
    fun `正停在联系人时幂等不重复压栈`() {
        nav.tabSwitch("contacts") // 栈 = [chats, contacts]
        nav.navigateToContactsTab("contacts")
        assertEquals("contacts", currentRoute())
        nav.popBackStack()
        assertEquals("chats", currentRoute())
    }

    @Test
    fun `金丝雀_裸Tab切换模式在真伤栈形下确实叠回详情页`() {
        nav.tabSwitch("contacts")
        nav.navigate("chat/x") // 栈 = [chats, contacts, chat/x]
        nav.tabSwitch("contacts") // 裸模式（修复前 AIChatApp 深链的写法）
        // Navigation 2.9.8 现状：restoreState 恢复整段栈，栈顶仍是详情页 = 深链不可用的根因。
        assertEquals("chat/{id}", currentRoute())
    }
}
