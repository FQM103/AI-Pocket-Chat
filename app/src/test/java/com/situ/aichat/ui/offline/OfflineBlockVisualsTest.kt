package com.situ.aichat.ui.offline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `OfflineBlockVisuals` 纯函数测试（P10.2e-1），断言反推 iOS `OfflineBlockAnimations.swift`：
 * 环境关键词→emoji 映射 25 组（含优先级：先匹配的高优先组胜出）+ 无命中兜底 🌿；
 * 时间流逝文本兜底「一段时间后」。
 */
class OfflineBlockVisualsTest {

    @Test fun environment_icon_each_group_representative_keyword() {
        // 25 组各取一个代表关键词（1:1 iOS environmentIcon 映射表顺序）。
        assertEquals("🌧", environmentIcon("外面在下雨"))
        assertEquals("❄️", environmentIcon("飘起了雪花"))
        assertEquals("🍃", environmentIcon("一阵微风"))
        assertEquals("☀️", environmentIcon("阳光很好"))
        assertEquals("🌙", environmentIcon("月光洒下"))
        assertEquals("✨", environmentIcon("满天繁星"))
        assertEquals("☕", environmentIcon("点了杯拿铁"))
        assertEquals("🧋", environmentIcon("喝着奶茶"))
        assertEquals("🍷", environmentIcon("在酒吧里"))
        assertEquals("🍽", environmentIcon("吃火锅"))
        assertEquals("🌸", environmentIcon("樱花树下"))
        assertEquals("🌊", environmentIcon("海边的浪"))
        assertEquals("⛰", environmentIcon("登山途中"))
        assertEquals("🌳", environmentIcon("公园的草地"))
        assertEquals("🏪", environmentIcon("楼下便利店"))
        assertEquals("🎬", environmentIcon("看电影"))
        assertEquals("📚", environmentIcon("逛书店"))
        assertEquals("🌃", environmentIcon("深夜里"))
        assertEquals("🌅", environmentIcon("清晨醒来"))
        assertEquals("🌇", environmentIcon("黄昏时分"))
        assertEquals("🤫", environmentIcon("很安静"))
        assertEquals("🔥", environmentIcon("很温暖"))
        assertEquals("🧊", environmentIcon("有点冷"))
        assertEquals("🎵", environmentIcon("放着音乐"))
        assertEquals("🚗", environmentIcon("路过的汽车"))
    }

    @Test fun environment_icon_default_when_no_keyword() {
        assertEquals("🌿", environmentIcon("一个普通的描写"))
        assertEquals("🌿", environmentIcon(""))
    }

    @Test fun environment_icon_priority_first_group_wins() {
        // 「星」(组6) 排在「晚上」(组18) 之前 → 同时含两者时返回 ✨。
        assertEquals("✨", environmentIcon("晚上一起看星星"))
        // 「月」(组5) 排在「咖啡」(组7) 之前 → 返回 🌙。
        assertEquals("🌙", environmentIcon("月光下喝咖啡"))
        // 「雨」(组1) 排在「咖啡」(组7) 之前 → 返回 🌧。
        assertEquals("🌧", environmentIcon("下雨的咖啡馆"))
    }

    @Test fun format_time_skip_display_fallback_and_trim() {
        assertEquals("一段时间后", formatTimeSkipDisplay(""))
        assertEquals("一段时间后", formatTimeSkipDisplay("   \n  "))
        assertEquals("半小时后", formatTimeSkipDisplay("半小时后"))
        assertEquals("三天后", formatTimeSkipDisplay("  三天后  "))
    }
}
