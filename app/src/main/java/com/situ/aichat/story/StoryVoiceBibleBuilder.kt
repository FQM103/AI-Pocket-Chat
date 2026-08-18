package com.situ.aichat.story

import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity

/**
 * 一个故事角色的「声音档案」原料（已解析），供 [StoryVoiceBibleBuilder] 格式化。
 *
 * 对应 iOS 在 `StoryVoiceBibleBuilder` 内从关联 AICharacter 直接读的字段；安卓由 11.1e 生成服务预先收集
 * （CharacterEntity 字段 + 结构化记忆 nickname/insideJoke 解码 + CharacterRepository.currentRelationship），
 * 再传入纯格式化器，保持本类零 DB 依赖、100% 可单测。
 */
data class StoryVoiceCharacterData(
    val personalityDescription: String,
    val speakingStyle: String,
    val catchphrases: String,
    val exampleDialogues: String,
    val systemPrompt: String,
    /** 结构化记忆：角色对用户的称呼。 */
    val nicknameFromChar: String,
    /** 结构化记忆：专属梗。 */
    val insideJoke: String,
    /** 当前关系名（`CharacterRepository.currentRelationship` 结果，可空）。 */
    val currentRelationship: String?,
)

/**
 * 从角色数据构建声音档案，注入创作提示词以保持角色声音一致性（1:1 iOS `Services/StoryVoiceBibleBuilder.swift`）。
 * 纯代码、不调用 LLM、零额外开销；这里是纯格式化器（数据由调用方预先解析）。
 */
internal object StoryVoiceBibleBuilder {

    /**
     * 为故事中的所有角色构建声音档案文本块（1:1 iOS `buildVoiceProfiles` :9-88）。
     *
     * @param roles 故事角色（内部按「用户角色优先、再按名字」排序 = iOS `Story.sortedCharacters`）
     * @param dataByCharacterId 关联 AI 角色的已解析声音原料，按 `characterId` 索引（无关联或查不到则只出角色基本设定）
     */
    fun buildVoiceProfiles(
        roles: List<StoryCharacterRoleEntity>,
        dataByCharacterId: Map<String, StoryVoiceCharacterData>,
    ): String {
        val profiles = sortedStoryRoles(roles).map { role ->
            formatRoleProfile(role, role.characterId?.let { dataByCharacterId[it] })
        }
        return if (profiles.isEmpty()) "" else profiles.joinToString("\n\n")
    }

    private fun formatRoleProfile(role: StoryCharacterRoleEntity, data: StoryVoiceCharacterData?): String {
        val parts = mutableListOf<String>()
        parts.add("【${role.roleName}】")

        val roleTypeLabel = when (role.roleType) {
            StoryRoleType.PROTAGONIST -> "主角"
            StoryRoleType.ANTAGONIST -> "反派"
            else -> "配角"
        }
        parts.add("定位：$roleTypeLabel")

        if (role.isUserRole) {
            parts.add("（用户扮演角色）")
        }

        if (!role.roleDescription.isNullOrEmpty()) {
            parts.add("设定：${role.roleDescription}")
        }

        // 从关联的 AI 角色提取深度信息（声音辨识度的核心来源）
        if (data != null) {
            if (data.personalityDescription.isNotEmpty()) {
                parts.add("性格特征：${data.personalityDescription}")
            }

            // 说话风格（完整注入，不截断——声音辨识度的关键）
            val speakingStyle = data.speakingStyle.trim()
            if (speakingStyle.isNotEmpty()) {
                parts.add("说话风格：$speakingStyle")
            }

            // 口头禅（用户设定的，比 insideJoke 更稳定）
            val catchphrases = data.catchphrases.trim()
            if (catchphrases.isNotEmpty()) {
                parts.add("口头禅/习惯用语：$catchphrases")
            }

            // 示例对话（最有效的声音定义方式·卷一 V6 放宽到 1000 字）
            val examples = data.exampleDialogues.trim()
            if (examples.isNotEmpty()) {
                val excerpt = if (examples.length > 1000) examples.take(1000) + "…" else examples
                parts.add("对话风格示例：$excerpt")
            }

            // 高级设定（systemPrompt，卷一 V6 放宽到 1200 字）
            val prompt = data.systemPrompt.trim()
            if (prompt.isNotEmpty()) {
                val excerpt = if (prompt.length > 1200) prompt.take(1200) + "…" else prompt
                parts.add("高级设定参考：$excerpt")
            }

            // 结构化记忆（称呼、梗）
            if (data.nicknameFromChar.isNotEmpty()) {
                parts.add("对用户的称呼：${data.nicknameFromChar}")
            }
            if (data.insideJoke.isNotEmpty()) {
                parts.add("专属梗：${data.insideJoke}")
            }

            if (!data.currentRelationship.isNullOrEmpty()) {
                parts.add("与用户的关系：${data.currentRelationship}")
            }
        }

        return parts.joinToString("\n")
    }
}
