package com.situ.aichat.ui.offline

import com.situ.aichat.offline.OfflineContentBlock
import com.situ.aichat.offline.OfflineContentParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `buildImmersiveInputMessage` 测试（P10.2e-3），反推 iOS `OfflineImmersiveInputView.sendMessage`：
 * 跳过空步骤、逐步骤 trim、包成 `[标签]…[/标签]` 换行连接；并 round-trip 经 `parseUserBlocks` 守标签名一致。
 */
class OfflineImmersiveInputLogicTest {

    @Test fun combines_nonempty_steps_into_tags() {
        assertEquals(
            "[环境]雨夜[/环境]\n[动作]坐下[/动作]\n[对话]你好[/对话]\n[内心]紧张[/内心]",
            buildImmersiveInputMessage(listOf("雨夜", "坐下", "你好", "紧张")),
        )
    }

    @Test fun skips_empty_and_blank_steps() {
        assertEquals("[动作]走近[/动作]", buildImmersiveInputMessage(listOf("", "走近", "   ", "")))
        assertEquals("", buildImmersiveInputMessage(listOf("", "", "", "")))
    }

    @Test fun trims_each_step() {
        assertEquals("[环境]咖啡馆[/环境]", buildImmersiveInputMessage(listOf("  咖啡馆  ", "", "", "")))
    }

    @Test fun round_trips_through_parse_user_blocks() {
        // 组合输出经 parseUserBlocks 必须解析回正确块（对话→UserAction），守 4 标签名与解析器一致。
        val combined = buildImmersiveInputMessage(listOf("雨夜", "坐下", "你好", "紧张"))
        assertEquals(
            listOf(
                OfflineContentBlock.Environment("雨夜"),
                OfflineContentBlock.Action("坐下"),
                OfflineContentBlock.UserAction("你好"),
                OfflineContentBlock.InnerMonologue("紧张"),
            ),
            OfflineContentParser.parseUserBlocks(combined),
        )
    }
}
