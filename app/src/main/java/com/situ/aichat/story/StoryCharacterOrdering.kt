package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import java.text.Collator
import java.util.Locale

/**
 * 故事角色排序（1:1 iOS `Story.sortedCharacters` :171-178）：用户角色优先，再按名字升序。
 *
 * 名字用中文 [Collator]（≈ iOS `localizedCompare`，拼音序）；排序仅影响档案/角色段展示顺序，非行为关键。
 * 抽到此处共用：[StoryVoiceBibleBuilder] 与 [StoryGenerationPromptBuilder] 的角色段都按同一序，避免重复实现。
 */
internal fun sortedStoryRoles(roles: List<StoryCharacterRoleEntity>): List<StoryCharacterRoleEntity> {
    val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
    return roles.sortedWith(
        compareByDescending<StoryCharacterRoleEntity> { it.isUserRole }
            .thenComparator { a, b -> collator.compare(a.roleName, b.roleName) },
    )
}
