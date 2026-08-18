package com.situ.aichat.ui.schedule

import com.situ.aichat.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P1-18（批6）：日程行 a11y 纯函数单测——cd 拼接（null/空白段跳过·「·」连接·序固定）、
 * 心情 emoji 映射（聊天族经 tts.EmotionType 单源 + 日程生活流补充表 + 未识别 null）、
 * 三级兜底链（moodText → 标签 → 透传）。
 */
class ScheduleRowA11yTest {

    // MARK: - cd 拼接

    @Test
    fun `all six segments join with middle dot in visual order`() {
        val cd = ScheduleRowA11y.contentDescription(
            periodLabel = "清晨",
            activityText = "煮咖啡，听播客",
            location = "家里",
            mood = "惬意",
            innerThought = "今天想早点进入状态",
            interactionLabel = "和你的互动",
        )
        assertEquals("清晨·煮咖啡，听播客·家里·惬意·今天想早点进入状态·和你的互动", cd)
    }

    @Test
    fun `null and blank segments are skipped without double dots`() {
        assertEquals(
            "上午·写代码·公司",
            ScheduleRowA11y.contentDescription("上午", "写代码", "公司", null, null, null),
        )
        assertEquals(
            "上午·写代码·公司",
            ScheduleRowA11y.contentDescription("上午", "写代码", "公司", "  ", "", null),
        )
        // 必填段为空白也被过滤（防御：入库前 trim，但拼接自身要稳）。
        assertEquals("写代码", ScheduleRowA11y.contentDescription("", "写代码", " ", null, null, null))
    }

    // MARK: - moodResId 映射

    @Test
    fun `chat family emojis resolve through single-source EmotionType`() {
        assertEquals(R.string.a11y_mood_happy, ScheduleRowA11y.moodResId("😊"))
        assertEquals(R.string.a11y_mood_sigh, ScheduleRowA11y.moodResId("🤦"))
        // 批5 登记分叉传导：😮‍💨 prefix 兜底 → SIGH（安卓有意行为）；裸 😮 精确 → SHOCKED。
        assertEquals(R.string.a11y_mood_sigh, ScheduleRowA11y.moodResId("😮‍💨"))
        assertEquals(R.string.a11y_mood_shocked, ScheduleRowA11y.moodResId("😮"))
    }

    @Test
    fun `schedule supplement table covers lifestyle emojis outside chat set`() {
        // few-shot 实证 emoji（iOS/安卓 prompt 同款示例 😴 安眠 / ☕ 惬意）。
        assertEquals(R.string.a11y_mood_sleepy, ScheduleRowA11y.moodResId("😴"))
        assertEquals(R.string.a11y_mood_relaxed, ScheduleRowA11y.moodResId("☕"))
        // 聊天集漏的常用脸补丁。
        assertEquals(R.string.a11y_mood_sad, ScheduleRowA11y.moodResId("😭"))
        assertEquals(R.string.a11y_mood_happy, ScheduleRowA11y.moodResId("😀"))
    }

    @Test
    fun `unknown blank and null emojis give no label`() {
        assertNull(ScheduleRowA11y.moodResId("🦖"))
        assertNull(ScheduleRowA11y.moodResId(""))
        assertNull(ScheduleRowA11y.moodResId(null))
    }

    // MARK: - moodSegment 三级兜底

    @Test
    fun `mood segment prefers moodText then label then raw emoji`() {
        assertEquals("安眠", ScheduleRowA11y.moodSegment("安眠", "困倦", "😴"))
        assertEquals("困倦", ScheduleRowA11y.moodSegment(null, "困倦", "😴"))
        assertEquals("困倦", ScheduleRowA11y.moodSegment("  ", "困倦", "😴"))
        // 标签缺失 → 原 emoji 透传（TalkBack 读 CLDR 名）。
        assertEquals("🦖", ScheduleRowA11y.moodSegment(null, null, "🦖"))
        // 全空 → null（段跳过）。
        assertNull(ScheduleRowA11y.moodSegment(null, null, ""))
    }
}
