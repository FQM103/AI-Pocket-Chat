package com.situ.aichat.ui.offline

/**
 * 线下沉浸内容块的纯展示辅助（1:1 iOS `OfflineBlockAnimations.swift` 的两个全局函数）。
 *
 * 与 Compose 无关、纯字符串逻辑——故单独成文件、设 `internal`，单测可在普通 JVM 直接反推 iOS 验证
 * （环境关键词→emoji 映射 25 组、时间流逝兜底文案）。
 */

/** 环境关键词→氛围 emoji 映射，按优先级从高到低逐组匹配（1:1 iOS `environmentIcon(for:)`）。 */
private val environmentIconMappings: List<Pair<List<String>, String>> = listOf(
    // 天气/自然
    listOf("雨", "下雨", "雨滴", "雨声") to "🌧",
    listOf("雪", "下雪", "雪花") to "❄️",
    listOf("风", "微风", "大风", "凉风") to "🍃",
    listOf("阳光", "日光", "太阳", "晴") to "☀️",
    listOf("月", "月光", "月亮", "月色") to "🌙",
    listOf("星", "星空", "星光", "繁星") to "✨",
    // 场所
    listOf("咖啡", "拿铁", "美式", "卡布奇诺") to "☕",
    listOf("奶茶", "珍珠", "果茶") to "🧋",
    listOf("酒", "啤酒", "红酒", "威士忌", "酒吧") to "🍷",
    listOf("饭", "餐", "食", "吃", "菜", "火锅", "烤肉", "面", "关东煮", "夜宵") to "🍽",
    listOf("花", "花香", "桂花", "玫瑰", "樱花") to "🌸",
    listOf("海", "海边", "沙滩", "浪") to "🌊",
    listOf("山", "山上", "山顶", "登山") to "⛰",
    listOf("公园", "草地", "树", "绿地") to "🌳",
    listOf("便利店", "超市", "商店") to "🏪",
    listOf("电影", "影院", "荧幕") to "🎬",
    listOf("书", "书店", "图书", "阅读") to "📚",
    // 时间/氛围
    listOf("夜", "深夜", "夜晚", "夜色", "晚上") to "🌃",
    listOf("早", "清晨", "早晨", "黎明") to "🌅",
    listOf("傍晚", "黄昏", "日落", "夕阳") to "🌇",
    listOf("安静", "寂静", "静谧", "宁静") to "🤫",
    listOf("温暖", "暖", "温馨") to "🔥",
    listOf("凉", "冷", "寒") to "🧊",
    // 声音
    listOf("音乐", "歌", "旋律", "乐声") to "🎵",
    listOf("车", "汽车", "车声", "车辆") to "🚗",
)

/** 根据环境描述文本匹配对应氛围 emoji，无命中→通用自然氛围「🌿」（1:1 iOS `environmentIcon`）。 */
internal fun environmentIcon(text: String): String {
    for ((keywords, icon) in environmentIconMappings) {
        for (keyword in keywords) {
            if (text.contains(keyword)) return icon
        }
    }
    return "🌿"
}

/** 从时间跳跃文本提取时间描述，空→「一段时间后」（1:1 iOS `formatTimeSkipDisplay`）。 */
internal fun formatTimeSkipDisplay(text: String): String {
    val trimmed = text.trim()
    return trimmed.ifEmpty { "一段时间后" }
}
