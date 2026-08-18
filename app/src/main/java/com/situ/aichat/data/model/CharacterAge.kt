package com.situ.aichat.data.model

import com.situ.aichat.data.local.entity.CharacterEntity
import java.time.Instant
import java.time.Period
import java.time.ZoneId

/**
 * 角色当前年龄（1:1 iOS `AICharacter.currentAge` :211-221）。
 *
 * - `fixed` 模式：`fixedAge > 0` → `fixedAge`，否则 null。
 * - `growing` 模式（默认）：生日到 [now] 的整年数（按系统时区取日期，对齐 iOS `Calendar.current`）；无生日 → null。
 *
 * 注入 [now] 而非内部取 `Instant.now()`，保持纯函数可单测（与 [com.situ.aichat.prompt.PromptBuilder] 共用此实现，
 * 故事生成 11.1e 收集 [com.situ.aichat.story.StoryCharacterSectionData] 时也调用此处，避免重复年龄计算）。
 */
internal fun CharacterEntity.currentAge(now: Instant): Int? = when (ageModeRaw) {
    "fixed" -> if (fixedAge > 0) fixedAge else null
    else -> birthday?.let { bday ->
        val zone = ZoneId.systemDefault()
        val birthDate = Instant.ofEpochMilli(bday).atZone(zone).toLocalDate()
        val nowDate = now.atZone(zone).toLocalDate()
        Period.between(birthDate, nowDate).years
    }
}
