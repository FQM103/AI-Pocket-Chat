package com.situ.aichat.profile

import java.time.Instant
import java.time.Period
import java.time.ZoneId

/**
 * 角色「当前年龄」计算。1:1 iOS `AICharacter.currentAge`：
 *  - fixed 模式：fixedAge > 0 才返回，否则 null；
 *  - growing 模式：按生日到现在的**整年数**（生日缺失返回 null）。
 *
 * 纯函数（注入 now/zone 便于单测）。
 */
object CharacterAgeCalculator {

    fun currentAge(
        ageModeRaw: String,
        fixedAge: Int,
        birthdayMillis: Long?,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int? = when (ageModeRaw) {
        "fixed" -> if (fixedAge > 0) fixedAge else null
        // 默认及 "growing"：按生日算整年（与 iOS Calendar.dateComponents([.year]) 等价）。
        else -> {
            val birthday = birthdayMillis ?: return null
            val birth = Instant.ofEpochMilli(birthday).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            Period.between(birth, today).years
        }
    }
}
