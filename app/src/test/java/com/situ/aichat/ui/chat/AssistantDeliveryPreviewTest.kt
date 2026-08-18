package com.situ.aichat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [assistantDeliveryPreview] 规格锁定：助手投递收尾写入会话列表预览的纯决策。
 * 核心断言反推方案 A——见面期 AI 叙事正文（带 [叙述]/[对话]/[场景:…] 沉浸标签）绝不外显进日常聊天列表预览；
 * 非线下普通消息行为与原 finalizeDelivery 逐字一致（表情包标签转 [表情包]、截 50 字）。
 */
class AssistantDeliveryPreviewTest {

    @Test
    fun `线下叙事回合返回null·不把任何沉浸标签写进预览`() {
        val offlineNarrative = "[场景:咖啡馆][时间:傍晚][叙述]灯光昏黄[/叙述][对话]你来啦[动作]笑了笑"
        val out = assistantDeliveryPreview(offlineNarrative, isOffline = true)
        // null = 调用方不写预览（改 touchLastMessageDate），原始标签与角色扮演正文绝无机会进会话列表。
        assertNull("线下回合必须返回 null（不写预览）", out)
    }

    @Test
    fun `线下回合即便正文是干净表情包也一律不写预览`() {
        // isOffline 一票否决：见面期产生的一切助手消息都不进日常聊天预览，与正文是否带标签无关。
        assertNull(assistantDeliveryPreview("[sticker:happy] 嗯嗯", isOffline = true))
    }

    @Test
    fun `在线普通消息原样显示·不返回null`() {
        assertEquals("晚上好呀", assistantDeliveryPreview("晚上好呀", isOffline = false))
    }

    @Test
    fun `在线消息表情包标签转表情包占位`() {
        val out = assistantDeliveryPreview("看这个[sticker:wink]", isOffline = false)
        assertEquals("看这个[表情包]", out)
        assertFalse("不得残留 sticker 原始标签", out!!.contains("sticker:"))
    }

    @Test
    fun `在线消息截断到50字`() {
        val long = "字".repeat(80)
        assertEquals(50, assistantDeliveryPreview(long, isOffline = false)!!.length)
    }

    @Test
    fun `防御回归·假设线下标签漏到在线路径也不会被当占位泄漏额外内容`() {
        // 在线路径不剥线下叙事标签（上游 preserveOfflineTags=false 已剥净）；此处仅锁定：在线决策不引入新行为，
        // 只做贴纸替换 + 截断。若日后误把线下内容走到在线分支，至少不会再被 take(50) 之外的逻辑放大。
        val out = assistantDeliveryPreview("[对话]你好", isOffline = false)
        assertEquals("[对话]你好", out)
    }
}
