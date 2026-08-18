package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 故事正章创作温度纯函数单测（卷一 V1·图纸 §7 T1-1 / E1-E2）。默认 1.0（= DeepSeek V4-Pro 官方唯一推荐值），
 * clamp 区间复用聊天创造力的 [0,2]，非有限值（NaN/±∞）回退默认——手改 DataStore 或未来 UI bug 都兜住。
 *
 * 与 [LlmTemperatureTest] 分开写：两条温度是**独立设置**（聊天/语音 vs 故事创作），断言必须各自独立反推，
 * 否则将来任一侧改默认值时另一侧会被误判为「还对着」。
 */
class StoryCreationTemperatureTest {

    @Test
    fun default_isOnePointZero() { // E1 首装/从未设置
        assertEquals(1.0, AppSettings().sanitizedStoryCreationTemperature, 0.0)
        assertEquals(1.0, AppSettings.DEFAULT_STORY_CREATION_TEMPERATURE, 0.0)
    }

    @Test
    fun withinRange_passesThrough() {
        assertEquals(0.0, AppSettings(storyCreationTemperature = 0.0).sanitizedStoryCreationTemperature, 0.0)
        assertEquals(0.7, AppSettings(storyCreationTemperature = 0.7).sanitizedStoryCreationTemperature, 0.0)
        assertEquals(1.4, AppSettings(storyCreationTemperature = 1.4).sanitizedStoryCreationTemperature, 0.0)
        assertEquals(2.0, AppSettings(storyCreationTemperature = 2.0).sanitizedStoryCreationTemperature, 0.0)
    }

    @Test
    fun outOfRange_clampsToBounds() { // E2 越界
        assertEquals(0.0, AppSettings(storyCreationTemperature = -1.0).sanitizedStoryCreationTemperature, 0.0)
        assertEquals(2.0, AppSettings(storyCreationTemperature = 3.0).sanitizedStoryCreationTemperature, 0.0)
    }

    @Test
    fun nonFinite_fallsBackToDefault() { // E2 NaN/∞
        assertEquals(1.0, AppSettings(storyCreationTemperature = Double.NaN).sanitizedStoryCreationTemperature, 0.0)
        assertEquals(1.0, AppSettings(storyCreationTemperature = Double.POSITIVE_INFINITY).sanitizedStoryCreationTemperature, 0.0)
        assertEquals(1.0, AppSettings(storyCreationTemperature = Double.NEGATIVE_INFINITY).sanitizedStoryCreationTemperature, 0.0)
    }

    @Test
    fun storyTemperature_isIndependentOfChatTemperature() {
        // 只改故事温度：聊天温度不受影响（B1 聊天/语音温度路径零变）
        val onlyStory = AppSettings(storyCreationTemperature = 0.2)
        assertEquals(0.2, onlyStory.sanitizedStoryCreationTemperature, 0.0)
        assertEquals(1.0, onlyStory.sanitizedLlmTemperature, 0.0)
        // 只改聊天温度：故事温度维持默认
        val onlyChat = AppSettings(llmTemperature = 0.2)
        assertEquals(0.2, onlyChat.sanitizedLlmTemperature, 0.0)
        assertEquals(1.0, onlyChat.sanitizedStoryCreationTemperature, 0.0)
    }
}
