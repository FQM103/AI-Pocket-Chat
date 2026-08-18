package com.situ.aichat.ui.chat

import androidx.compose.ui.geometry.Rect
import com.situ.aichat.data.local.entity.MessageEntity
import com.situ.aichat.data.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ③ 长按沉浸菜单（契约 FABLE5_CHAT_TELEGRAM_MOTION_PROPOSAL §3·M2）——断言从「只换壳」冻结清单独立反推：
 * 动作显示条件与旧 DropdownMenu 逐字等价（§3.3 矩阵）、级联数学（Telegram cascade 波长 4）、菜单定位钳制、
 * 复制口径三分支（日程剥标签/结构化卡绝不露 JSON/普通原样）。
 */
class ChatImmersiveMenuTest {

    // ---- 动作面冻结（§3.3：复制/引用/删除=恒有；重新生成=仅 AI；改成邀约=AI 普通文本+双非线下） ----

    @Test
    fun `actions - user plain text gets copy quote delete only`() {
        val actions = immersiveMenuActions(
            isUser = true, kind = MessageKind.PLAIN_TEXT,
            isOfflineModeActive = false, messageIsOffline = false,
        )
        assertEquals(
            listOf(ImmersiveMenuAction.COPY, ImmersiveMenuAction.QUOTE, ImmersiveMenuAction.DELETE),
            actions,
        )
    }

    @Test
    fun `actions - ai plain text gets all five`() {
        val actions = immersiveMenuActions(
            isUser = false, kind = MessageKind.PLAIN_TEXT,
            isOfflineModeActive = false, messageIsOffline = false,
        )
        assertEquals(
            listOf(
                ImmersiveMenuAction.COPY, ImmersiveMenuAction.QUOTE, ImmersiveMenuAction.REGENERATE,
                ImmersiveMenuAction.CONVERT_TO_INVITE, ImmersiveMenuAction.DELETE,
            ),
            actions,
        )
    }

    @Test
    fun `actions - convert hidden while offline meeting active`() {
        val actions = immersiveMenuActions(
            isUser = false, kind = MessageKind.PLAIN_TEXT,
            isOfflineModeActive = true, messageIsOffline = false,
        )
        assertFalse(ImmersiveMenuAction.CONVERT_TO_INVITE in actions)
        assertTrue(ImmersiveMenuAction.REGENERATE in actions) // 重新生成只看 isUser，不受线下影响（冻结现状）
    }

    @Test
    fun `actions - convert hidden for offline message`() {
        val actions = immersiveMenuActions(
            isUser = false, kind = MessageKind.PLAIN_TEXT,
            isOfflineModeActive = false, messageIsOffline = true,
        )
        assertFalse(ImmersiveMenuAction.CONVERT_TO_INVITE in actions)
    }

    @Test
    fun `actions - ai schedule card has regenerate but no convert`() {
        val actions = immersiveMenuActions(
            isUser = false, kind = MessageKind.SCHEDULE_CARD,
            isOfflineModeActive = false, messageIsOffline = false,
        )
        assertEquals(
            listOf(
                ImmersiveMenuAction.COPY, ImmersiveMenuAction.QUOTE,
                ImmersiveMenuAction.REGENERATE, ImmersiveMenuAction.DELETE,
            ),
            actions,
        )
    }

    @Test
    fun `action labels frozen verbatim`() {
        assertEquals("复制", immersiveMenuActionLabel(ImmersiveMenuAction.COPY))
        assertEquals("引用", immersiveMenuActionLabel(ImmersiveMenuAction.QUOTE))
        assertEquals("重新生成", immersiveMenuActionLabel(ImmersiveMenuAction.REGENERATE))
        assertEquals("改成邀约", immersiveMenuActionLabel(ImmersiveMenuAction.CONVERT_TO_INVITE))
        assertEquals("删除", immersiveMenuActionLabel(ImmersiveMenuAction.DELETE))
    }

    // ---- 级联数学（Telegram cascade 波长 4：窗口=4/n、起点按序错峰） ----

    @Test
    fun `cascade - zero at start and one at end for every item`() {
        for (i in 0 until 5) {
            assertEquals(0f, cascadeProgress(0f, i, 5))
            assertEquals(1f, cascadeProgress(1f, i, 5))
        }
    }

    @Test
    fun `cascade - earlier item leads later item mid-flight`() {
        val p0 = cascadeProgress(0.1f, 0, 5)
        val p4 = cascadeProgress(0.1f, 4, 5)
        assertTrue("首项应先行（p0=$p0 p4=$p4）", p0 > p4)
    }

    @Test
    fun `cascade - few items degrade to synchronized (window clamped to 1)`() {
        // n≤4 时窗口=min(1, 4/n)=1 → 全项同步（Telegram 同款退化）。
        assertEquals(cascadeProgress(0.3f, 0, 3), cascadeProgress(0.3f, 2, 3))
    }

    @Test
    fun `cascade - clamped to unit range`() {
        assertEquals(0f, cascadeProgress(-0.5f, 2, 5))
        assertEquals(1f, cascadeProgress(1.5f, 2, 5))
    }

    // ---- 菜单定位（§3.2：贴气泡对齐缘·下方优先·放不下翻上方·超长气泡钳屏内） ----

    private val screenW = 1080
    private val screenH = 2400
    private val margin = 16
    private val gap = 24

    @Test
    fun `offset - below bubble aligned to end for user message`() {
        val bubble = Rect(500f, 800f, 1000f, 950f)
        val off = immersiveMenuOffset(bubble, menuW = 400, menuH = 500, screenW, screenH, alignEnd = true, margin, gap)
        assertEquals(1000 - 400, off.x) // 右缘对齐气泡右缘
        assertEquals(950 + gap, off.y) // 气泡下方 gap 处
    }

    @Test
    fun `offset - aligned to start for ai message`() {
        val bubble = Rect(60f, 800f, 700f, 950f)
        val off = immersiveMenuOffset(bubble, menuW = 400, menuH = 500, screenW, screenH, alignEnd = false, margin, gap)
        assertEquals(60, off.x)
    }

    @Test
    fun `offset - flips above when bottom overflows`() {
        val bubble = Rect(500f, 1900f, 1000f, 2200f)
        val off = immersiveMenuOffset(bubble, menuW = 400, menuH = 500, screenW, screenH, alignEnd = true, margin, gap)
        assertEquals(1900 - gap - 500, off.y) // 翻到气泡上方
    }

    @Test
    fun `offset - clamped horizontally to screen margin`() {
        val bubble = Rect(0f, 800f, 300f, 950f) // 贴左缘的窄气泡·右对齐会算出负 x
        val off = immersiveMenuOffset(bubble, menuW = 400, menuH = 500, screenW, screenH, alignEnd = true, margin, gap)
        assertEquals(margin, off.x)
    }

    @Test
    fun `offset - huge bubble spanning screen keeps menu inside`() {
        val bubble = Rect(100f, -500f, 1000f, 3000f) // 高于一屏的超长消息
        val off = immersiveMenuOffset(bubble, menuW = 400, menuH = 500, screenW, screenH, alignEnd = false, margin, gap)
        assertTrue(off.y >= gap)
        assertTrue(off.y + 500 <= screenH - gap)
    }

    // ---- 复制口径三分支（自 ChatMessageRow 原样搬迁·冻结） ----

    private fun message(kind: MessageKind, content: String) = MessageEntity(
        messageUUID = "m1",
        conversationUuid = "c1",
        roleRaw = "assistant",
        content = content,
        timestamp = 1_000L,
        messageKindRaw = kind.raw,
    )

    @Test
    fun `copy - schedule card strips calendar tags and trims`() {
        val text = messageCopyText(message(MessageKind.SCHEDULE_CARD, " 下午三点去公园散步 [#E1] "))
        assertFalse(text.contains("[#E"))
        assertFalse(text.startsWith(" "))
        assertTrue(text.contains("下午三点去公园散步"))
    }

    @Test
    fun `copy - structured card never leaks raw json`() {
        val raw = """{"amount":520,"note":"藏起来的金额"}"""
        val text = messageCopyText(message(MessageKind.RED_PACKET, raw))
        assertFalse(text.contains("{"))
        assertFalse(text.contains("520"))
    }

    @Test
    fun `copy - plain text verbatim`() {
        assertEquals("今晚一起看晚霞", messageCopyText(message(MessageKind.PLAIN_TEXT, "今晚一起看晚霞")))
    }
}
