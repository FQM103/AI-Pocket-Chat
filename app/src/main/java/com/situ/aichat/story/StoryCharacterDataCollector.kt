package com.situ.aichat.story

import com.situ.aichat.data.local.dao.CharacterDao
import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.model.PersonalitySpectrum
import com.situ.aichat.data.model.RelationshipQuality
import com.situ.aichat.data.model.StructuredMemory
import com.situ.aichat.data.model.currentAge
import com.situ.aichat.data.model.personalitySpectrum
import com.situ.aichat.data.model.relationshipQuality
import com.situ.aichat.data.repository.CharacterRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 故事角色段原料收集器（从 [StoryGenerationService] 抽出·11.1e-3 角色段原料收集簇）。
 *
 * 把各故事角色关联的 AI 角色身份/声音档案/主角动态状态预收集成纯数据，喂给 [StoryGenerationPromptBuilder]
 * 的 d-4/d-5 纯函数 prompt 构建器。纯只读（CharacterDao + CharacterRepository），不碰生成主流程、不写库；
 * 各方法按 characterId 去重索引，无关联或查不到的角色不入表（纯故事角色由其 roleDescription 兜底）。
 */
@Singleton
class StoryCharacterDataCollector @Inject constructor(
    private val characterDao: CharacterDao,
    private val characterRepository: CharacterRepository,
) {
    /**
     * 收集各故事角色关联 AI 角色的身份原料（[StoryCharacterSectionData]），按 characterId 去重索引；
     * 无关联或查不到的角色不入表（纯故事角色 → 由其 roleDescription 兜底）。
     */
    internal suspend fun collectCharacterData(
        roles: List<StoryCharacterRoleEntity>,
        nowMillis: Long,
    ): Map<String, StoryCharacterSectionData> {
        val now = Instant.ofEpochMilli(nowMillis)
        val result = mutableMapOf<String, StoryCharacterSectionData>()
        for (role in roles) {
            val cid = role.characterId
            if (cid.isNullOrEmpty() || result.containsKey(cid)) continue
            val character = characterDao.getByUuid(cid) ?: continue
            result[cid] = character.toSectionData(now)
        }
        return result
    }

    private fun CharacterEntity.toSectionData(now: Instant): StoryCharacterSectionData =
        StoryCharacterSectionData(
            gender = gender,
            age = currentAge(now),
            occupation = occupation,
            appearanceDescription = appearanceDescription,
            personalityDescription = personalityDescription,
            backstory = backstory,
        )

    /**
     * 收集各故事角色关联 AI 角色的「声音档案」原料（[StoryVoiceCharacterData]），按 characterId 去重索引
     * （1:1 iOS `StoryVoiceBibleBuilder.buildVoiceProfiles` 内逐角色取 AICharacter 的过程；安卓抽到生成服务预收集）。
     * 结构化记忆解码取称呼/梗，关系名取 [CharacterRepository.currentRelationship]（= 末条里程碑名）。
     */
    internal suspend fun collectVoiceCharacterData(
        roles: List<StoryCharacterRoleEntity>,
    ): Map<String, StoryVoiceCharacterData> {
        val result = mutableMapOf<String, StoryVoiceCharacterData>()
        for (role in roles) {
            val cid = role.characterId
            if (cid.isNullOrEmpty() || result.containsKey(cid)) continue
            val character = characterDao.getByUuid(cid) ?: continue
            val structured = StructuredMemory.decode(character.structuredMemoryJSON)
            result[cid] = StoryVoiceCharacterData(
                personalityDescription = character.personalityDescription,
                speakingStyle = character.speakingStyle,
                catchphrases = character.catchphrases,
                exampleDialogues = character.exampleDialogues,
                systemPrompt = character.systemPrompt,
                nicknameFromChar = structured.nicknameFromChar,
                insideJoke = structured.insideJoke,
                currentRelationship = characterRepository.currentRelationship(cid),
            )
        }
        return result
    }

    /**
     * 取主角的性格谱系 + 关系质量（仅第一章动态状态参考用；1:1 iOS `appendInitialDynamicState` :77-111）：
     * 按排序后的角色取首个 protagonist → 关联 AI 角色 → 解码两套维度。无主角/无关联/查不到 → (null, null)。
     */
    internal suspend fun collectProtagonistDynamicState(
        roles: List<StoryCharacterRoleEntity>,
    ): Pair<PersonalitySpectrum?, RelationshipQuality?> {
        val protagonist = sortedStoryRoles(roles).firstOrNull { it.roleType == StoryRoleType.PROTAGONIST }
        val cid = protagonist?.characterId
        if (cid.isNullOrEmpty()) return null to null
        val character = characterDao.getByUuid(cid) ?: return null to null
        return character.personalitySpectrum to character.relationshipQuality
    }
}
