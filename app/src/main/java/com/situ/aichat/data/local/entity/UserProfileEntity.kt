package com.situ.aichat.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Mirrors the iOS `UserProfile` @Model. Singleton row (id = 1). */
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = 1,
    val nickname: String = "",
    val bio: String = "",
    val avatarPath: String? = null,
    val cityName: String? = null,
    val cityLatitude: Double? = null,
    val cityLongitude: Double? = null,
    val birthday: Long? = null,
    /** 「希望 TA 怎么待你」相处偏好（四小件·2026-07-16）：全局一份不分角色，注入 persona 段；空=不注入。 */
    @ColumnInfo(defaultValue = "''") val companionPreference: String = "",
)
