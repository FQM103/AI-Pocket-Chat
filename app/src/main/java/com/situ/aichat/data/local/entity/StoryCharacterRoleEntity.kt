package com.situ.aichat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.situ.aichat.story.StoryRoleType
import java.util.UUID

/**
 * 故事里的一个角色（1:1 iOS `Models/StoryCharacterRole.swift` `@Model StoryCharacterRole` :11-36）。
 *
 * 可关联到一个已有的 AI 角色（[characterId] = `CharacterEntity` 的 uuid），也可是纯故事角色。
 * 排序「用户角色优先，再按名字」（iOS `Story.sortedCharacters` :171-178）做成 Repository/扩展、不入库。
 */
@Entity(
    tableName = "story_character_roles",
    foreignKeys = [
        ForeignKey(
            entity = StoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["storyId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("storyId")],
)
data class StoryCharacterRoleEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    /** 所属故事 [StoryEntity.id]（FK，级联删）。 */
    val storyId: String = "",
    val roleName: String = "",
    /** 角色类型 raw（[StoryRoleType]：protagonist/supporting/antagonist）。 */
    val roleType: String = StoryRoleType.SUPPORTING,
    val roleDescription: String? = null,
    /** 是否「用户扮演」的角色。 */
    val isUserRole: Boolean = false,
    /** 关联的 AI 角色 uuid（`CharacterEntity.uuid`）；null = 纯故事角色。 */
    val characterId: String? = null,
    /**
     * 私下反差（故事二期卷一·提案 §5.1）：该角色在重点场景里与人前判若两人的一面。
     * 非空时追加到角色段该角色行尾「私下反差：{文本}」（首/续章 + 弧线大纲三处共用的角色段）。
     * 编辑入口（角色弹层的反差栏 + AI 起草）归卷二，本卷只建列并让它随「重开」复制。
     */
    val intimatePersona: String? = null,
)
