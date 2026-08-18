package com.situ.aichat.prompt.schedule

import kotlinx.serialization.Serializable

/**
 * LLM 生成日程返回的单事件 DTO（P5.1）。字段名 = iOS `ScheduleGenerationService.ScheduleEventData`
 * 与示例 JSON 完全一致（camelCase）。全字段给默认值，配合宽松解码容错残缺输出。
 */
@Serializable
data class ScheduleEventData(
    val startHour: Int = 0,
    val startMinute: Int = 0,
    val endHour: Int = 0,
    val endMinute: Int = 0,
    val periodLabel: String = "",
    val location: String = "",
    val activity: String = "",
    val moodEmoji: String = "",
    val moodText: String? = null,
    val innerThought: String? = null,
    val isPhoneAvailable: Boolean = true,
    val relatedCharacterName: String? = null,
)

/** json_object 模式的对象包：`{"events":[...]}`（对齐 iOS `ScheduleWrapper`）。 */
@Serializable
data class ScheduleEventWrapper(
    val events: List<ScheduleEventData> = emptyList(),
)
