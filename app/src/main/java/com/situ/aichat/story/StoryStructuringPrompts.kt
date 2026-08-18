package com.situ.aichat.story

/**
 * 故事生成「两步法第二步」结构化提示词（自 [StoryGenerationPromptBuilder] 抽出 · 文件瘦身，**行为零改 / 逐字不变**）。
 *
 * 把创作模型的纯文本输出整理成合法 JSON；纯字符串模板、零内部依赖。经
 * [StoryGenerationPromptBuilder.buildStructuringPrompt] / [StoryGenerationPromptBuilder.buildMetadataStructuringPrompt]
 * 薄委托调用（公开 API 与单测点名签名逐字不变）。
 */

// MARK: - 两步法第二步：结构化 prompt（纯文本输出 → JSON，1:1 iOS `+Structuring.swift`）

/**
 * 结构化提示词：把创作模型的纯文本输出整理成合法 JSON（1:1 iOS buildStructuringPrompt :8-45）。
 *
 * 含插值 [rawCreationOutput] 居中，故拆「静态头 + 原文 + 静态尾」拼接（两静态块各自 trimIndent、皆无插值，
 * 规避 trimIndent 与插值同用的缩进塌缩）。`\n`/`\"` 在 raw string 里即字面反斜杠，正是要喂给 LLM 的转义示意。
 */
internal fun buildStoryStructuringPrompt(rawCreationOutput: String): String {
    val header = """
        你是一个文本结构化助手。请把以下故事创作输出整理成合法 JSON 对象。

        ## 创作输出原文
    """.trimIndent()
    val footer = """
        ## 输出要求
        请严格按以下 JSON 格式输出，只输出 JSON，不要解释：
        {
          "title": "从 METADATA 的 title 字段提取",
          "teaser": "从 METADATA 的 teaser 字段提取，没有则为 null",
          "mood": "从 METADATA 的 mood 字段提取",
          "content": "故事正文部分（---METADATA--- 之前的所有内容，保持原样，包括沉浸标签）",
          "hasChoice": true或false,
          "choicePrompt": "从 METADATA 提取，没有则为 null",
          "choiceOptions": ["选项A", "选项B", "选项C"] 或 null,
          "summary": "从 METADATA 的 summary 字段提取",
          "currentArc": "从 METADATA 的 currentArc 字段提取",
          "characterStates": "从 METADATA 的 characterStates 字段提取",
          "openThreads": "从 METADATA 的 openThreads 字段提取",
          "nextChapterBeats": "从 METADATA 的 nextChapterBeats 字段提取，没有则为 null",
          "intimacyUpdates": "从 METADATA 的 intimacyUpdates 字段提取，没有则为 null",
          "sceneEndState": "从 METADATA 的 sceneEndState 字段提取，没有则为 null",
          "sceneTag": "从 METADATA 的 sceneTag 字段提取，没有则为 null",
          "isEnding": true或false
        }

        ## JSON 安全规则
        1. content 字段中的所有换行必须写成 \n
        2. 对话引号用中文引号""或「」，不要用英文双引号
        3. JSON 字符串值中的英文双引号必须转义为 \"
        4. 只输出 JSON 本身，不要包裹 markdown 代码块，不要添加 ```json 标记
        5. 确保输出是可被 JSON.parse() 直接解析的合法 JSON

        ## hasChoice 推断规则
        - 如果原文有 choiceA/choiceB 或 choicePrompt，则 hasChoice 必须为 true
        - 如果原文没有明确写 hasChoice 但有选项内容，也必须设为 true
        - 只有 isEnding 为 true 时才允许 hasChoice 为 false
    """.trimIndent()
    return "$header\n$rawCreationOutput\n\n$footer"
}

/**
 * 元数据结构化提示词：只处理 METADATA 部分（不含正文），比 [buildStructuringPrompt] 省约 90% token、更快更可靠
 * （1:1 iOS buildMetadataStructuringPrompt :49-91）。同样拆静态头/尾 + 插值居中。
 */
internal fun buildStoryMetadataStructuringPrompt(metadataText: String): String {
    val header = """
        你是元数据提取助手。将以下 key:value 文本转换为一行紧凑 JSON。

        ## 输入
    """.trimIndent()
    val footer = """
        ## 输出格式
        {"title":string,"teaser":string|null,"mood":string,"hasChoice":boolean,"choicePrompt":string|null,"choiceOptions":[string]|null,"summary":string|null,"currentArc":string|null,"characterStates":string|null,"openThreads":string|null,"nextChapterBeats":string|null,"intimacyUpdates":string|null,"sceneEndState":string|null,"sceneTag":string|null,"isEnding":boolean}

        ## 完整示例

        输入：
        title: 雨夜的真相
        teaser: 当真相浮出水面
        mood: tense
        summary: 林悦发现了苏晴的秘密
        currentArc: 关系面临考验
        characterStates: 林悦（震惊、犹豫）；苏晴（心虚、害怕）
        openThreads: 苏晴手机里的未读消息；咖啡馆老板的反常态度
        nextChapterBeats: 两人约好周末去美术馆；途中偶遇林晓雨引出旧事，感情线小幅升温；无重点场景，为下一个路标铺垫
        intimacyUpdates: [近况]她开始主动整理他的衣领
        sceneEndState: 无
        sceneTag: 无
        hasChoice: true
        choicePrompt: 你决定...
        choiceA: 追上去质问她
        choiceB: 假装什么都没看到
        isEnding: false

        输出：
        {"title":"雨夜的真相","teaser":"当真相浮出水面","mood":"tense","hasChoice":true,"choicePrompt":"你决定...","choiceOptions":["追上去质问她","假装什么都没看到"],"summary":"林悦发现了苏晴的秘密","currentArc":"关系面临考验","characterStates":"林悦（震惊、犹豫）；苏晴（心虚、害怕）","openThreads":"苏晴手机里的未读消息；咖啡馆老板的反常态度","nextChapterBeats":"两人约好周末去美术馆；途中偶遇林晓雨引出旧事，感情线小幅升温；无重点场景，为下一个路标铺垫","intimacyUpdates":"[近况]她开始主动整理他的衣领","sceneEndState":"无","sceneTag":"无","isEnding":false}

        ## 规则（逐条遵守）
        1. 只输出一行紧凑 JSON，不要换行，不要 markdown 代码块，不要任何解释
        2. 字符串值用英文双引号，值内部的双引号转义为 \"
        3. null 不加引号：正确 "teaser":null  错误 "teaser":"null"
        4. boolean 不加引号：正确 "hasChoice":true  错误 "hasChoice":"true"
        5. choiceA/choiceB/choiceC/choiceD 合并为 choiceOptions 数组
        6. 找不到的字段输出 null，不要省略任何字段
        7. mood 必须是以下值之一：warm/tense/romantic/dark/peaceful/excited/melancholy/mysterious/nostalgic/horror/dreamy
        8. nextChapterBeats 是可选字段，格式应保留原文，不要改写
        9. 最后一个字段后面不要加逗号（尾逗号非法）
        10. 如果有 choiceA/choiceB 或 choicePrompt，则 hasChoice 必须为 true（即使原文漏写了 hasChoice）
    """.trimIndent()
    return "$header\n$metadataText\n\n$footer"
}
