package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 日程里的一个时段事件（P5.1）。1:1 对齐 iOS `ScheduleEvent`（SwiftData @Model）。
 *
 * [eventTypeRaw]：planned（计划，生成默认）/ actual（回顾补生成）/ userInteraction（聊天写回，P5 后续）。
 * [sourceRaw]：generated（LLM 生成）/ weatherAdjusted（天气微调，P11）/ chatDriven（聊天写回，后续）。
 * 起止时间为 epoch 毫秒。删日程 → 级联删其事件。
 */
@Entity(
    tableName = "schedule_events",
    foreignKeys = [
        ForeignKey(
            entity = CharacterDailyScheduleEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["scheduleUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("scheduleUuid"),
        Index("startTime"),
    ],
)
data class ScheduleEventEntity(
    @PrimaryKey val uuid: String,
    val scheduleUuid: String,
    val startTime: Long,
    val endTime: Long,
    val periodLabel: String = "",
    val location: String = "",
    val activity: String = "",
    val moodEmoji: String = "",
    val moodText: String? = null,
    val innerThought: String? = null,
    val isPhoneAvailable: Boolean = true,
    val eventTypeRaw: String = "planned",
    val relatedCharacterNames: String? = null,
    val relatedMessageUUID: String? = null,
    val sourceRaw: String = "generated",
    val sortOrder: Int = 0,
)
