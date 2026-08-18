package com.situ.aichat.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.situ.aichat.data.local.entity.ConversationEntity
import com.situ.aichat.ui.chat.ChatListViewModel
import com.situ.aichat.ui.chat.ChatRow
import com.situ.aichat.ui.theme.AIPocketChatTheme
import com.situ.aichat.util.DateFormatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ChatRow] 第一行空间分配行为测试（KDoc 规格：名字优先取空间、状态空间不足先省略，
 * 时间戳恒显示在行尾）。2026-07-17 用户真机截图报缺陷：日程状态超长时角色名被挤到 0 宽，
 * 第一行只剩「• 状态…」——根因是 weight 挂在名字上（weighted 子项最后测量、只分剩余空间）。
 *
 * 放 androidTest 而非 Robolectric 单测：挤压的触发前提是「文字固有宽超过可用宽」，而
 * Robolectric 字形宽失真（中文≈0.76dp、ASCII 10 字符≈3.8dp，与 AssistantBubbleMorphTest:42
 * 注释互证），前提在其中恒不成立、黑盒断言无区分力；模拟器真字体才测得出。
 * 断言与字形绝对宽度解耦：同一实例把状态从 null 切到超长，名字宽度必须不变。
 */
@RunWith(AndroidJUnit4::class)
class ChatRowNameVisibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private val name = "小满"
    private val longStatus = "铺开瑜伽垫，做一小时的瑜伽和普拉提，拉伸放松身心，顺便冥想十五分钟收尾"
    private val relStrings = DateFormatters.RelativeTimeStrings(
        justNow = "刚刚",
        minutesAgo = "%1\$d 分钟前",
        hoursAgo = "%1\$d 小时前",
        yesterday = "昨天 %1\$s",
    )

    private fun testRow() = ChatListViewModel.Row(
        conversation = ConversationEntity(
            uuid = "c1",
            title = name,
            characterUuid = "ch1",
            creationDate = 0L,
            lastMessageDate = 0L,
            lastMessagePreview = "预览文本",
            lastMessageRole = "assistant",
        ),
        character = null,
    )

    @Test
    fun longScheduleStatus_nameKeepsFullWidth_timestampStaysVisible() {
        var status by mutableStateOf<String?>(null)
        compose.setContent {
            AIPocketChatTheme(darkTheme = false) {
                ChatRow(row = testRow(), scheduleStatus = status, nowMillis = 0L, relStrings = relStrings)
            }
        }
        // 头像占位只渲染首字（CharacterAvatar），不与全名撞匹配；宽度取语义节点 size（px），同环境比较无需换算。
        val nameWidth = {
            compose.onNodeWithText(name, useUnmergedTree = true).fetchSemanticsNode().size.width
        }
        val freeWidth = nameWidth()
        // 前提检查：字形宽必须可测（两个汉字远大于 8px），否则「宽度不变」断言恒真、失去区分力。
        assertTrue("字形宽未测出（=$freeWidth px），断言失去区分力", freeWidth > 8)

        compose.runOnIdle { status = longStatus }
        compose.waitForIdle()

        val squeezedWidth = nameWidth()
        assertEquals(
            "状态超长时角色名被挤压（自由宽 $freeWidth px → 挤压后 $squeezedWidth px）：名字必须始终完整显示",
            freeWidth,
            squeezedWidth,
        )
        // 规格另两条：时间戳恒显示在行尾；状态自身仍在（布局上被省略号截断，语义文本不变）。
        compose.onNodeWithText("刚刚", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(longStatus, useUnmergedTree = true).assertIsDisplayed()
    }
}
