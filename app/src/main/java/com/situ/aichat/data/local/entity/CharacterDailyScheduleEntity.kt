package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 角色某一天的日程（P5.1）。1:1 对齐 iOS `CharacterDailySchedule`（SwiftData @Model）。
 *
 * [date] 存「当天 0 点」的 epoch 毫秒（设备时区，等价 iOS `Calendar.current.startOfDay`），
 * 作为「角色 + 日期」唯一定位键。[generatedAt] 为 null 表示空壳（线下见面等路径预建，P10）；
 * 非 null = 已由 LLM 正式生成。天气列（P11）、经纬度时区（定位/高德落地后）暂为占位。
 * 删角色 → 级联删其日程 → 级联删日程下事件（见 [ScheduleEventEntity]）。
 */
@Entity(
    tableName = "character_daily_schedules",
    foreignKeys = [
        ForeignKey(
            entity = CharacterEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["characterUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["characterUuid", "date"]),
        Index("characterUuid"),
    ],
)
data class CharacterDailyScheduleEntity(
    @PrimaryKey val uuid: String,
    val characterUuid: String,
    val date: Long,                          // 当天 0 点 epoch 毫秒（设备时区）
    val cityName: String? = null,
    val weatherCondition: String? = null,    // 天气（P11）占位
    val weatherEmoji: String? = null,
    val temperatureHigh: Double? = null,
    val temperatureLow: Double? = null,
    val timezoneIdentifier: String? = null,  // 生成时角色所在时区（当前 = 设备时区）
    val generatedAt: Long? = null,           // null = 空壳未生成；非 null = 已正式生成
    val lastWeatherCheckAt: Long? = null,
    val isBackfilled: Boolean = false,
)
