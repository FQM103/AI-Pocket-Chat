package com.situ.aichat.story

import com.situ.aichat.data.model.MaxOutputLength
import com.situ.aichat.data.remote.llm.ChatMessageDto

/**
 * 章节创作「请求组装 + 错误类型」（自 [StoryGenerationService] 抽出 · **只搬不改**，行为与可见性逐字不变）。
 *
 * 拆出理由：[StoryGenerationService] 已达 596 行、逼近逻辑层 600 硬顶（CLAUDE.md §2），而本卷需在服务内加接线；
 * 三个符号（错误密封类 / 请求 data class / 纯函数组装器）与服务的协程编排职责无关，同包 internal 可见性不变，
 * 调用方与测试的 import 零改动。
 */

/** 章节生成错误（1:1 iOS `StoryGenerationError` :389-410，message 即 iOS errorDescription 用户文案）。 */
sealed class StoryGenerationError(message: String) : Exception(message) {
    object NoApiConfig : StoryGenerationError("未配置故事创作 API，无法生成章节。请在「功能 API 分配」中设置。")
    object EmptyResponse : StoryGenerationError("小说生成返回为空。")
    object InvalidResponse : StoryGenerationError("小说生成返回格式无效。")
    object JsonRepairFailed : StoryGenerationError("小说生成结果无法修复为合法 JSON。")
    object Timeout : StoryGenerationError("章节生成超时，请检查网络或 API 状态后重试。")

    /** 服务商拒答（图纸一 C2b · [StoryRefusalDetector] 判定）：本章不落库，走既有失败路由用户手动重试。 */
    object RefusalDetected : StoryGenerationError(
        "服务商拒绝了这次生成，本章内容未保存。可点「重试」按原定走向再试一次；" +
            "若反复被拒，试试换个说法描述本章走向，或在「功能 API 分配」里换一个服务商。",
    )
}

/** 一次章节创作请求（1:1 iOS `StoryGenerationRequest` :6-10）。 */
data class StoryGenerationRequest(
    val messages: List<ChatMessageDto>,
    val maxTokens: Int,
    val temperature: Double,
)

/**
 * 组装章节创作请求（1:1 iOS `makeGenerationRequest` :313-332）：system=创作 prompt，user=[buildCreationUserMessage]，
 * maxTokens 走 [StoryGenerationPromptBuilder.preferredCreationMaxTokens]，temperature 由调用方传入（故事创作温度设置）。纯函数，可测。
 *
 * ⚠️ 两个字数参数**不是一回事，不许混用**（混用会让末句字数与 system 段静默打架）：
 * - [chapterLengthPreference]：续章路收 [StoryGenerationPolicy.effectiveChapterLength] 的**放大后**值
 *   （结局章 = 基础档 ×1.5）；首章路恒等于基础档。**只喂 maxTokens**。
 * - [baseChapterLength]：恒为**基础**档位值，只喂末句要点复述的字数（结局章的 ×1.5 由
 *   [StoryWritingTechniques.endingChapterLengthRange] 内部承担，调用方不要预先放大）。
 *
 * 它**故意没有默认值**（R1 复核 🟡：原先默认 `= chapterLengthPreference`，而续章调用点那个位置
 * 传的正是放大后的值 —— 默认行为指向错值，漏传即静默打架且无测试会红）。新增调用点必须显式想清楚传哪个。
 */
internal fun makeGenerationRequest(
    prompt: String,
    chapterNumber: Int,
    chapterLengthPreference: Int,
    baseChapterLength: Int,
    isThinkingModel: Boolean,
    temperature: Double,
    maxOutputLength: MaxOutputLength = MaxOutputLength.AUTO,
    freeformDirective: String? = null,
    requestedEndingType: String? = null,
    userChoice: String? = null,
    /** 图纸二 D3：本书的章末选项开关（谓词 `CustomStoryPrompts.effectiveChapterChoices`），透传给末句要点。 */
    choicesEnabled: Boolean = true,
): StoryGenerationRequest = StoryGenerationRequest(
    messages = listOf(
        ChatMessageDto(role = "system", content = prompt),
        ChatMessageDto(
            role = "user",
            content = buildCreationUserMessage(
                chapterNumber = chapterNumber,
                baseChapterLength = baseChapterLength,
                isEndingChapter = requestedEndingType != null,
                freeformDirective = freeformDirective,
                userChoice = userChoice,
                choicesEnabled = choicesEnabled,
            ),
        ),
    ),
    maxTokens = StoryGenerationPromptBuilder.preferredCreationMaxTokens(chapterLengthPreference, isThinkingModel, maxOutputLength),
    temperature = temperature,
)

/** 末句要点复述的小标题（锁定文本·测试侧另重打一遍字面量与本常量比对，互为双保险）。 */
internal const val CREATION_RECAP_HEADER = "本章要点（务必落实）："

/**
 * 组装发给模型的**最后一条** user message（2026-07-27 用户拍板：只优化末句，system prompt 内部顺序不动）。
 *
 * 为什么要复述：这是整个请求里注意力最高的位置，原先 90% 的调用只发一句「请继续创作下一章。」等于空着；
 * 而真正没有兜底的三件事（写多长 / 顺哪个方向 / 结尾收成什么样）都埋在 system prompt 里被 120 行
 * 标记规则与输出格式压在后面。标记与 METADATA 格式跑偏还有两步法第二步结构化兜底，创作指标跑偏无从补救。
 *
 * 四态：
 * - **首章**：开场语 + 字数 + 选择节点。
 * - **续章·预设点选**：开场语 + 字数 + 「上一章我选择了…」+ 选择节点。
 * - **续章·用户亲笔走向**（[freeformDirective] 非 null）：保留既有三明治强指令逐字不变，其后追要点；
 *   **不再重复 [userChoice]**——自由输入时两者是同一段文本（[StoryChoiceClassifier.freeformDirective]）。
 * - **结局章**（[isEndingChapter]）：字数走 [StoryWritingTechniques.endingChapterLengthRange]，
 *   **禁止**出现「必须给选项」（system 段明写 `hasChoice 必须为 false`）。
 *
 * [baseChapterLength] 取**基础**档位值；结局章的 ×1.5 由 endingChapterLengthRange 内部承担，调用方不要预先放大。
 *
 * 首章恒不可能是结局章（`requestedEndingType` 只在续章路设置，首章调用点也不传），故
 * `chapterNumber == 1` 时**强制忽略** [isEndingChapter] —— 否则末句会说「这是最终章…hasChoice 为 false」
 * 而 system 段的 `chapterRequirements` 说「每章结尾必须设置选择节点」，两句直接打架（R1 复核 🔵 潜伏项）。
 */
internal fun buildCreationUserMessage(
    chapterNumber: Int,
    baseChapterLength: Int,
    isEndingChapter: Boolean,
    freeformDirective: String? = null,
    userChoice: String? = null,
    /**
     * 图纸二 D3：false = 本书关掉章末选项 —— 非结局章的末句改成关闭态一行（M4），与 system 段的
     * [StoryWritingTechniques.chapterRequirements] 关闭态一致，绝不两句打架。结局章路本就 hasChoice=false，
     * 不受本开关影响（那两行原样保留）。默认 true 时输出逐字节不变。
     */
    choicesEnabled: Boolean = true,
): String {
    val lines = mutableListOf<String>()
    val isFirstChapter = chapterNumber == 1
    // 首章的 system 段恒走 chapterRequirements（永不看 requestedEndingType），末句必须跟它保持一致。
    val endingChapter = isEndingChapter && !isFirstChapter

    lines.add(
        when {
            isFirstChapter -> "请开始创作第一章。"
            // 三明治 user message 末端强指令（图纸 L2 → 2026-08-05 M-C2 换文：「任务书」口径与 system 段 M-C1 对齐）。
            freeformDirective != null ->
                "请继续创作下一章。我已亲笔指定本章的剧情走向：「$freeformDirective」——这是本章的任务书，必须照此推进；系统提供的大纲与方向提示仅供参考。"
            else -> "请继续创作下一章。"
        },
    )
    lines.add("")
    lines.add(CREATION_RECAP_HEADER)

    val (minWords, maxWords) = if (endingChapter) {
        StoryWritingTechniques.endingChapterLengthRange(baseChapterLength)
    } else {
        StoryWritingTechniques.chapterLengthRange(baseChapterLength)
    }
    lines.add("- 目标字数 $minWords-$maxWords 字，在这个范围里找自然的场景结尾收束")

    // 预设点选的方向重申：自由输入已在开场语里给过最高优先级，不重复；结局章也照给（结局同样要顺着上一章的选择收）。
    if (freeformDirective == null && !isFirstChapter && !userChoice.isNullOrBlank()) {
        lines.add("- 上一章我选择了「$userChoice」，本章要落实这个方向")
    }

    if (endingChapter) {
        lines.add("- 这是最终章：回收伏笔、给角色归宿，isEnding 为 true")
        lines.add("- 不设选择节点，hasChoice 为 false")
    } else if (choicesEnabled) {
        lines.add("- 结尾必须设置选择节点，给出 2-3 个方向明显不同的选项")
    } else {
        // M4（图纸 §4 锁定文本）
        lines.add("- 本书不设章末选择，hasChoice 为 false，结尾留重钩子让人急着看下一章")
    }

    return lines.joinToString("\n")
}
