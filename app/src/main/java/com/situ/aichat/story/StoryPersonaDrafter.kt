package com.situ.aichat.story

import android.util.Log
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.model.ApiFunction
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.repository.ApiConfigRepository
import com.situ.aichat.data.repository.StoryRepository
import com.situ.aichat.diagnostics.ContextLogService
import com.situ.aichat.diagnostics.LogSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「私下反差」AI 起草（故事二期卷二·提案 §6.3·mockup 屏 5）：拿这个角色**已有的**人设起个草，
 * 填进角色弹层的反差栏给用户改。**不落库**——保存与否由用户决定。
 *
 * 失败一律吞成 null（无配置 / 网络炸 / 模型返回空），由调用方给一句提示；起草是锦上添花，绝不阻塞编辑。
 */
@Singleton
class StoryPersonaDrafter @Inject constructor(
    private val storyRepository: StoryRepository,
    private val characterDataCollector: StoryCharacterDataCollector,
    private val apiConfigRepository: ApiConfigRepository,
    private val contextLog: ContextLogService,
) {

    /**
     * 起草一段反差设定。
     *
     * @param roleName 弹层里**当前**的角色名（用户可能刚改过，不读库里的旧值）
     * @param roleDescription 弹层里当前的人设描述（空 → prompt 里明说「未填写」让模型合理想象）
     * @return 清洗后的草稿；null = 起草失败（调用方提示一句，栏位保持原样）
     */
    suspend fun draft(
        storyId: String,
        characterId: String?,
        roleName: String,
        roleDescription: String,
        nowMillis: Long,
    ): String? {
        val config = apiConfigRepository.resolveConfigValues(ApiFunction.STORY_CREATION) ?: run {
            Log.w(TAG, "反差起草跳过：未配置故事创作 API")
            return null
        }
        return try {
            val story = storyRepository.getStory(storyId)
            val linked = characterId?.let { id ->
                characterDataCollector.collectCharacterData(
                    listOf(StoryCharacterRoleEntity(characterId = id)),
                    nowMillis,
                )[id]
            }
            val messages = listOf(
                ChatMessageDto(role = "system", content = SYSTEM_PROMPT),
                ChatMessageDto(
                    role = "user",
                    content = buildUserMessage(
                        roleName = roleName,
                        roleDescription = roleDescription,
                        personality = linked?.personalityDescription,
                        backstory = linked?.backstory,
                        genre = story?.genre.orEmpty(),
                    ),
                ),
            )
            val response = contextLog.completion(
                source = LogSource.STORY_GENERATION,
                characterName = "",
                config = config,
                messages = messages,
                temperature = TEMPERATURE,
                // 思考模型 ×3：复用卷一压缩同款倍率函数，额度只防截断、不是「写更长」的邀请。
                maxTokens = StoryGenerationPromptBuilder.preferredCompressionMaxTokens(BASE_MAX_TOKENS, config.isThinkingModel),
            )
            StoryTextCleaning.cleanContentThinkingTags(response).trim().ifEmpty {
                Log.w(TAG, "反差起草返回为空")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "反差起草失败", e)
            null
        }
    }

    companion object {
        /** 物料 P（图纸 §3.5·**逐字锁定**）：改动是产品行为变更，须回图纸/提案过审。 */
        const val SYSTEM_PROMPT: String = "你是角色设定助手。根据这个故事角色的已有人设，起草一段「私下反差」设定：" +
            "写TA在私下场合与公开形象的反差——反应模式、语言习惯、行事方式。80–150 字，只输出设定本身，不要解释、不要引号。"

        /** 人设为空时的占位（让模型别硬编，按名字与题材合理想象）。 */
        const val DESCRIPTION_PLACEHOLDER: String = "（未填写，按角色名与故事类型合理想象）"

        /** 关联聊天角色的性格 / 背景各自的注入上限（起草只要个方向，不搬整张卡）。 */
        const val LINKED_FIELD_MAX_CHARS = 200

        const val TEMPERATURE = 0.7

        const val BASE_MAX_TOKENS = 500

        /** user message 模板（图纸 §3.5 逐字骨架）；关联聊天角色时才追性格/背景两行。 */
        internal fun buildUserMessage(
            roleName: String,
            roleDescription: String,
            personality: String?,
            backstory: String?,
            genre: String,
        ): String {
            val lines = mutableListOf<String>()
            lines.add("角色名：$roleName")
            lines.add("已有人设：${roleDescription.trim().ifEmpty { DESCRIPTION_PLACEHOLDER }}")
            personality?.takeIf { it.isNotBlank() }?.let { lines.add("性格：${it.take(LINKED_FIELD_MAX_CHARS)}") }
            backstory?.takeIf { it.isNotBlank() }?.let { lines.add("背景：${it.take(LINKED_FIELD_MAX_CHARS)}") }
            lines.add("本书题材：$genre")
            return lines.joinToString("\n")
        }

        private const val TAG = "StoryPersonaDrafter"
    }
}
