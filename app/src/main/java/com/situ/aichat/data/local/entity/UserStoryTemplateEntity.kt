package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 「我的模板」——用户从某本书里存下的**整套创作设定**（图纸四 §3.2·2026-08-01 用户拍板扩围）。
 *
 * 载体设计：整套设定序列化成单列 JSON（[payloadJson] = `UserStoryTemplatePayload`），
 * **加字段零迁移**（decode 走 `ignoreUnknownKeys`）。故意不复用 `com.situ.aichat.story.StoryTemplate`——
 * 那是代码内置的只读目录（12 套·带 tagline/roleHint/coverMotif），形态与用途都不同。
 *
 * 无外键、与故事表零关联：**删模板不影响已开的书，删书也不影响模板**（图纸 §5·E10）。
 * 上限 [com.situ.aichat.data.model.UserStoryTemplatePayload.MAX_USER_TEMPLATES]，由存入端把关。
 */
@Entity(tableName = "user_story_templates")
data class UserStoryTemplateEntity(
    @PrimaryKey val uuid: String,
    /** 用户起的模板名（存入时非空才可保存；默认取当时的书名）。 */
    val name: String,
    /** 存下的时刻（epoch millis）——模板墙按此倒序排、卡片副标题显示日期。 */
    val createdAt: Long,
    /** [com.situ.aichat.data.model.UserStoryTemplatePayload] 的 JSON 串。 */
    val payloadJson: String,
)
