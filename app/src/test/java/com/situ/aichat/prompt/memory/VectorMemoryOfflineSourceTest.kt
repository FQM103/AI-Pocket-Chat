package com.situ.aichat.prompt.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 向量检索见面来源标 + 剥标签纯函数看门（图纸 §3.8 / §7 T2-5·E10）：
 * - [VectorMemoryService.offlineCandidateContent]：见面消息剥线下标签、剥后空跳过（null）；非见面原样；
 * - [VectorMemoryService.formatRetrievalSnippet]：见面片段打「· 线下见面」来源标，非见面原样；
 *   说话人标注 = 传入的真名（2026-07-12 拍板：user→用户名 / assistant→角色名，绝不再写死「用户/角色」）。
 * 写侧嵌入零改（历史向量不重算）——本测试只验读侧行为。
 */
class VectorMemoryOfflineSourceTest {

    // ── offlineCandidateContent ──

    @Test fun nonOffline_returnsRenderedVerbatim() {
        assertEquals("你好呀", VectorMemoryService.offlineCandidateContent("你好呀", isOffline = false))
    }

    @Test fun offline_stripsLineTags_keepsPlainText() {
        val raw = "[环境]咖啡馆里很安静[/环境]\n[对话]好久不见[/对话]"
        val out = VectorMemoryService.offlineCandidateContent(raw, isOffline = true)
        assertTrue("应保留纯文本", out!!.contains("好久不见"))
        assertFalse("不应残留标签", out.contains("[环境]"))
        assertFalse("不应残留标签", out.contains("[对话]"))
    }

    @Test fun offline_emptyAfterStrip_returnsNull() {
        // 全是标签壳（剥后为空）→ null（调用方 continue 跳过·E10）。
        assertNull(VectorMemoryService.offlineCandidateContent("[过渡]", isOffline = true))
        assertNull(VectorMemoryService.offlineCandidateContent("   ", isOffline = true))
    }

    // ── formatRetrievalSnippet ──

    @Test fun offline_snippet_addsSourceLabel_assistantUsesCharacterName() {
        assertEquals(
            "[2026-04-18 15:30 · 线下见面] 夏晴子：好久不见",
            VectorMemoryService.formatRetrievalSnippet(
                "2026-04-18 15:30", "assistant", "好久不见", isOffline = true,
                userName = "司徒", characterName = "夏晴子",
            ),
        )
    }

    @Test fun nonOffline_snippet_noLabel_userUsesUserName() {
        assertEquals(
            "[2026-04-18 15:30] 司徒：在吗",
            VectorMemoryService.formatRetrievalSnippet(
                "2026-04-18 15:30", "user", "在吗", isOffline = false,
                userName = "司徒", characterName = "夏晴子",
            ),
        )
    }

    @Test fun snippet_fallbackNamePassesThroughVerbatim() {
        // 昵称空的兜底解析（→「用户」）发生在调用方（pb_user_fallback），格式函数对传入名原样使用。
        assertEquals(
            "[2026-04-18 15:30] 用户：在吗",
            VectorMemoryService.formatRetrievalSnippet(
                "2026-04-18 15:30", "user", "在吗", isOffline = false,
                userName = "用户", characterName = "夏晴子",
            ),
        )
    }
}
