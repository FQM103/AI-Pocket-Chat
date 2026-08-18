package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 月度回顾（日记重设计 R5·契约 §2 F4）：AI 为某个自然月写的信式小结。**独立轻实体**、
 * 不混入 `diary_entries`（避免污染既有谓词）。每月一篇（[monthStartMillis] 唯一索引·IGNORE 插入幂等）。
 * 心情分布随文快照（[moodCountsJson]=`emoji→次数` 的 JSON 对象），展示时不再回查。
 */
@Entity(
    tableName = "monthly_reviews",
    indices = [Index(value = ["monthStartMillis"], unique = true)],
)
data class MonthlyReviewEntity(
    @PrimaryKey val uuid: String,
    /** 该月 1 日 0 点（设备时区·epoch 毫秒）。 */
    val monthStartMillis: Long,
    /** 信式小结正文（楷体展示）。 */
    val content: String = "",
    /** 心情分布快照 JSON（`{"😊":8,"😌":6}`·可空串=无心情数据）。 */
    val moodCountsJson: String = "",
    val generatedAt: Long = System.currentTimeMillis(),
)
