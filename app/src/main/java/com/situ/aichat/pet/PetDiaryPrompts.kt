package com.situ.aichat.pet

import com.situ.aichat.data.local.entity.CharacterPetEntity

/**
 * 宠物日记/状态的纯函数文案装配（1:1 iOS `PetDiaryGenerationService.buildPetDiaryPrompt`/`petMoodEmoji`
 * + `DiaryGenerationService.buildPetContextForDiary`）。无 IO，便于单测（断言反推 iOS 字面 + 状态档位边界）。
 *
 * 场景覆盖（iOS `ScenePromptOverrideService(.petDiary)` 的 wordCount/tone/emoji/extraRules）在安卓随 J4
 * ScenePromptOverride 销案（无场景系统）；现直接用 iOS 的 fallback 默认值（与日记 overrides=emptyMap 一致）。
 */
object PetDiaryPrompts {

    // iOS ScenePromptOverride fallback 默认值
    private const val DEFAULT_WORD_COUNT_RANGE = "100-200"
    private const val DEFAULT_TONE_STYLE = "可爱、天真，符合动物的视角（不要用太复杂的词汇）"
    private const val DEFAULT_EMOJI_POLICY = "可以用 emoji"

    /**
     * 第一人称宠物视角日记 system prompt（1:1 iOS `buildPetDiaryPrompt`）：介绍 + 要求 + 我今天的状态
     * （自然语言档位）+ 最近 5 条成长日志 + 已学技能 + 最近 3 个散步纪念品 + 输出约束。
     */
    internal fun buildPetDiaryPrompt(
        pet: CharacterPetEntity,
        ownerName: String,
        wordCountRange: String = DEFAULT_WORD_COUNT_RANGE,
        toneStyle: String = DEFAULT_TONE_STYLE,
        emojiPolicy: String = DEFAULT_EMOJI_POLICY,
        extraRules: String = "",
    ): String {
        val lines = mutableListOf<String>()
        lines.add("你是一只叫${pet.name}的${pet.species.displayName}，性格${pet.personalityType.displayName}。")
        lines.add("你的主人是$ownerName。请以你（宠物）的视角写今天的日记。")
        lines.add("")
        lines.add("## 要求")
        lines.add("- 用第一人称（我），$wordCountRange 字")
        lines.add("- 语气$toneStyle")
        lines.add("- $emojiPolicy")
        lines.add("- 不要暴露这是 AI 生成的")
        lines.add("- 如果今天没什么特别的事，就写写心情和日常")
        for (line in extraRules.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) lines.add("- $trimmed")
        }
        lines.add("")

        // 宠物状态（自然语言档位）
        lines.add("## 我今天的状态")
        lines.add("- 饱不饱：${if (pet.hunger <= 30) "吃得饱饱的" else if (pet.hunger >= 70) "好饿啊" else "有点饿了"}")
        lines.add("- 干不干净：${if (pet.cleanliness >= 70) "香香的" else "有点脏了"}")
        lines.add("- 开不开心：${if (pet.happiness >= 70) "很开心！" else if (pet.happiness <= 30) "不太开心" else "还行吧"}")
        lines.add("- 身体：${if (pet.health >= 70) "健健康康" else "有点不舒服"}")

        // 最近成长日志（最多 5 条）
        val recentLogs = pet.growthLog.takeLast(5)
        if (recentLogs.isNotEmpty()) {
            lines.add("")
            lines.add("## 最近发生的事")
            for (log in recentLogs) lines.add("- ${log.summary}")
        }

        // 已学技能
        val tricks = pet.metadata.learnedTricks
        if (tricks.isNotEmpty()) {
            val trickNames = PetTrickMilestones.milestones
                .filter { tricks.contains(it.trickId) }
                .map { it.name }
            lines.add("")
            lines.add("我会的技能：${trickNames.joinToString("、")}")
        }

        // 散步纪念品（最近 3 个）
        val recentSouvenirs = pet.metadata.souvenirs.takeLast(3)
        if (recentSouvenirs.isNotEmpty()) {
            lines.add("")
            lines.add("最近散步捡到的宝贝：${recentSouvenirs.joinToString("、") { "${it.emoji}${it.name}" }}")
        }

        lines.add("")
        lines.add("只输出日记内容，不要标题和额外解释。")
        return lines.joinToString("\n")
    }

    /** 从宠物状态推导心情 emoji（1:1 iOS `petMoodEmoji`）。 */
    internal fun petMoodEmoji(pet: CharacterPetEntity): String = when {
        pet.happiness >= 80 -> "😸"
        pet.happiness <= 30 -> "😿"
        pet.hunger >= 70 -> "🍽️"
        else -> "🐾"
    }

    /**
     * 角色日记里注入的宠物状态摘要（1:1 iOS `DiaryGenerationService.buildPetContextForDiary`）：每只宠物一行
     * `{名字}（{种类}）：{心情}，{成长阶段}阶段`。无宠物 → ""（section 省略）。
     */
    internal fun buildPetContextForDiary(pets: List<CharacterPetEntity>): String {
        if (pets.isEmpty()) return ""
        return pets.joinToString("\n") { pet ->
            val mood = when {
                pet.happiness >= 70 -> "开心"
                pet.happiness <= 30 -> "不开心"
                else -> "还好"
            }
            "${pet.name}（${pet.species.displayName}）：$mood，${pet.growthStage.displayName}阶段"
        }
    }
}
