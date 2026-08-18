package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 角色专属通知文案模板（P6.1b，对齐 iOS `Models/NotificationTemplate.swift`）。由 LLM 按角色性格 /
 * 说话风格批量生成；发通知时从对应 category 随机取一条未用的，同分类用完后整批重置循环。
 *
 * 分类：streak_remind / streak_urgent / streak_broken / morning / evening / random /
 * pet_hungry / pet_sick / pet_milestone（宠物 3 类生成后存着，P8 才消费——一并生成避免重生成）。
 */
@Entity(
    tableName = "notification_templates",
    indices = [Index(value = ["characterId", "category"])],
)
data class NotificationTemplateEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    /** 关联角色 uuid（对应 [CharacterEntity.uuid]）。 */
    val characterId: String,
    /** 场景分类（见类注释）。 */
    val category: String,
    /** 文案内容（角色口吻，约 10-25 字）。 */
    val content: String,
    /** 是否已使用过（同 category 全部用完后整批重置）。 */
    val isUsed: Boolean = false,
    /** 创建时间（epoch millis）。 */
    val createdAt: Long = System.currentTimeMillis(),
)
