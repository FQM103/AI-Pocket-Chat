package com.situ.aichat.story

/**
 * 故事生成「内容标记规则 + 创作输出格式」（自 [StoryGenerationPromptBuilder] 抽出 · 文件瘦身）。
 *
 * **2026-08-03 格式块精简（用户拍板·图纸 `2026-08-03-B序固化与格式块精简.md` §3.1/3.2）**：氛围演出类标记
 * （`[mood:]` / `[weather:]` / `[effect:]` / `[pause:]`）连同阅读器视觉层整链退役，标记只剩**排版类**三种——
 * `[scene:]` / `[text:style]…[/text]` 七样式 / `[chapter_end]`；本书「沉浸氛围标记」开关与最小标记集版一并拆除
 * （完整版是唯一一版）。提示词里**有意不提**已删标签名（负面提及反而诱导输出），旧章里的残留标签由
 * [StoryContentParser] 的未知标签剥离（E5）+ [StoryTextSanitizer] 兜底剥净。
 * METADATA 的 `mood:` 字段是**另一条根**（章级基色·35 键红线），不在本次砍面内，逐字保留。
 *
 * 早前有意修订留痕：2026-07-26 卷一 V5 把「### 使用节奏（非常重要）」的每章次数硬摊派改成
 * 「### 使用原则（跟随剧情自然使用，不设次数指标）」软引导，并把 `[chapter_end]` 补录进可用标记清单。
 *
 * 纯字符串模板、零内部依赖。经 [StoryGenerationPromptBuilder.appendMarkupRules] /
 * [StoryGenerationPromptBuilder.appendCreationOutputFormat] 薄委托调用。
 *
 * §5 强耦合：标签词表与 ---METADATA--- 格式被 StoryContentParser / StoryMetadataParser 解析依赖。
 * 长篇稳定性 L2 起（契约 FABLE5_STORY_LONGFORM_STABILITY_PROPOSAL §4）有意增补指令行（伏笔继承/状态字数弹性）——
 * 字段名与 METADATA 格式仍零改动，仅加行为约束，不再逐字锚定 iOS。
 */

// MARK: - 内容标记规则（唯一版·2026-08-03 精简后）

internal fun appendStoryMarkupRules(lines: MutableList<String>) {
    lines.add("## 内容标记（严格遵守以下规则）")
    lines.add("")
    lines.add("### 格式铁律")
    lines.add("1. 所有标记必须用半角方括号和半角冒号：[tag:value]")
    lines.add("2. 正文中禁止出现 [ 或 ] 字符（书名号用《》）")
    lines.add("3. 文字样式标记必须成对使用：[text:style]内容[/text]")
    lines.add("4. 每个标记独占一行，前后各空一行")
    lines.add("5. 标记内不允许有空格，如 [text:whisper] 而不是 [text: whisper]")
    lines.add("6. 只能使用下方列出的标记，禁止自创任何标记类型或标记值")
    lines.add("")
    lines.add("### 可用标记（完整列表）")
    lines.add("")
    lines.add("场景切换（描述文字简短，不超过10个字）：")
    lines.add("[scene:三小时后·卧室]")
    lines.add("")
    lines.add("文字样式（必须用[/text]关闭，内容不超过20字）：")
    lines.add("[text:whisper]低语内容[/text]")
    lines.add("[text:shout]喊叫内容[/text]")
    lines.add("[text:thought]内心独白[/text]")
    lines.add("[text:trembling]颤抖语气[/text]")
    lines.add("[text:angry]愤怒语气[/text]")
    lines.add("[text:excited]兴奋语气[/text]")
    lines.add("[text:emphasis]重点强调[/text]")
    lines.add("")
    lines.add("章节收尾装饰（可选，全章至多一次，放在正文最末一行）：")
    lines.add("[chapter_end]")
    lines.add("")
    lines.add("### 使用原则（跟随剧情自然使用，不设次数指标）")
    lines.add("- text 样式：留给最值得强调的语句，点缀即可")
    lines.add("- scene：时间或地点变化时使用")
    lines.add("- 以上标记都不是必须的——本章剧情用不上就不用，不要为凑标记而打断叙事")
    lines.add("")
    // 示例正文有意写成「具体动作 + 有信息量的对话 + 不泛化的景物」——模型会连示例的文风一起模仿，
    // 旧版「她轻轻叹了口气 / 窗外的萤火虫开始飞舞」自带万能修饰词与泛化动词，正好抵消文字忌口段。
    // ⚠️ 标记本身（[text:whisper]）与排版是格式演示，一字不许动。
    lines.add("### 正确示例")
    lines.add("她把杯子推过来，指节压在桌沿。")
    lines.add("")
    lines.add("[text:whisper]\"我等到十一点，就走了。\"[/text]")
    lines.add("")
    lines.add("### 禁止事项")
    lines.add("- 禁止：[text:emphasis]重点[text:emphasis] （用两个开标签代替关闭标签）")
    lines.add("- 禁止：[text: whisper] （标签内有空格）")
    lines.add("- 禁止：[text:crying] （自创不存在的标记值）")
    lines.add("- 禁止：[bgm:piano] （自创不存在的标记类型）")
    lines.add("- 禁止：在 title、teaser、choicePrompt、choiceOptions 中使用任何标记")
    lines.add("- 禁止：嵌套标记如 [text:whisper][text:thought]...[/text][/text]")
}

// MARK: - 创作专用输出格式（两步法第一步，1:1 iOS appendCreationOutputFormat :258-309）

/**
 * @param choicesEnabled 图纸二 D3：false = 本书关掉章末选项 —— 示例块的选择字段整组不发、「### 选择分支」
 *   换成紧凑的关闭态两行（M2a/M2b）。**默认 true 时输出与改动前逐字节相同**（T1 回归钉）。
 *   METADATA 字段定义与解析端零改动：这里只是「要求模型别输出可选字段」。
 */
internal fun appendStoryCreationOutputFormat(lines: MutableList<String>, choicesEnabled: Boolean = true) {
    lines.add("")
    lines.add("## 输出格式")
    lines.add("请直接输出故事内容，不需要 JSON 格式。按以下结构组织：")
    lines.add("")
    lines.add("1. 直接写故事正文（可包含 [scene:xxx]、[text:style]...[/text]、[chapter_end] 标记）")
    lines.add("2. 故事正文写完后，另起一行输出分隔符 ---METADATA---")
    lines.add("3. 分隔符之后，按以下格式逐行输出元数据（每行一个字段，字段名和值用冒号分隔）：")
    lines.add("")
    lines.add("示例：")
    if (choicesEnabled) {
        lines.add(
            """
            你坐在咖啡馆靠窗的位置...（故事正文，自由书写）

            她抬起头，目光与你相遇...

            [chapter_end]

            ---METADATA---
            title: 相亲对象是前男友的妹妹？
            teaser: 当你以为只是普通的相亲，却遇见了最不该遇见的人
            mood: tense
            summary: 男主被迫参加第七次相亲，在咖啡馆遇到了意想不到的人
            currentArc: 男主对相亲感到厌倦，但这次相亲对象让他产生了不同的感觉
            characterStates: 男主（疲惫但暗生期待）；女主（神秘、似乎认识男主）
            openThreads: 女主的真实身份；短信中提到的白裙女孩是否就是她
            hasChoice: true
            choicePrompt: 你注意到她似乎在偷看你，你决定...
            choiceA: 主动打招呼
            choiceB: 假装没看见，继续等
            choiceC: 找借口离开
            nextChapterBeats: 两人约好周末去美术馆；途中偶遇林晓雨引出旧事，感情线小幅升温；无重点场景，为下一个路标铺垫
            intimacyUpdates: [近况]她开始主动整理他的衣领
            sceneEndState: 无
            sceneTag: 无
            isEnding: false
            """.trimIndent(),
        )
    } else {
        // M2a：正文三行与 METADATA 前七字段与开启态示例逐字相同，只是 hasChoice 改 false、五个选择字段整组不出现。
        lines.add(
            """
            你坐在咖啡馆靠窗的位置...（故事正文，自由书写）

            她抬起头，目光与你相遇...

            [chapter_end]

            ---METADATA---
            title: 相亲对象是前男友的妹妹？
            teaser: 当你以为只是普通的相亲，却遇见了最不该遇见的人
            mood: tense
            summary: 男主被迫参加第七次相亲，在咖啡馆遇到了意想不到的人
            currentArc: 男主对相亲感到厌倦，但这次相亲对象让他产生了不同的感觉
            characterStates: 男主（疲惫但暗生期待）；女主（神秘、似乎认识男主）
            openThreads: 女主的真实身份；短信中提到的白裙女孩是否就是她
            hasChoice: false
            nextChapterBeats: 两人约好周末去美术馆；途中偶遇林晓雨引出旧事，感情线小幅升温；无重点场景，为下一个路标铺垫
            intimacyUpdates: [近况]她开始主动整理他的衣领
            sceneEndState: 无
            sceneTag: 无
            isEnding: false
            """.trimIndent(),
        )
    }
    lines.add("")
    if (choicesEnabled) {
        lines.add("### 选择分支（最重要，必须严格遵守）")
        lines.add("- 除非是最终结局章（isEnding: true），每章结尾必须提供选择分支")
        lines.add("- 必须输出以下四个字段，缺一不可：")
        lines.add("  hasChoice: true")
        lines.add("  choicePrompt: 选择提示文（引导用户做选择的一句话）")
        lines.add("  choiceA: 第一个选项")
        lines.add("  choiceB: 第二个选项")
        lines.add("- choiceC 为可选的第三个选项，建议提供")
        // 导演手记重构（图纸 2026-08-05 M-E1）：beats 从「每选项一条方向表」统一改成单一「本章计划草稿」
        // （开/关选项同一格式），下一章由它承载「打算写什么」，点选项时以选择为准、草稿降级为参考。
        lines.add("- nextChapterBeats 必须输出：下一章的计划草稿，单行 2-4 句——顺着本章结尾与用户的走向，写下一章打算写什么、有无重点场景；朝大纲中最近的一个尚未实现的路标推进")
        lines.add("- 只有结局章（isEnding: true）才允许 hasChoice: false")
    } else {
        // M2b（2026-08-05 M-E3：beats 从禁止名单移入「仍必须输出」）
        lines.add("### 选择分支（本书已关闭）")
        lines.add("- hasChoice 固定输出 false；禁止输出 choicePrompt、choiceA-D 字段")
        lines.add("- nextChapterBeats 仍必须输出：下一章的计划草稿，单行 2-4 句——顺着本章结尾与用户的走向，写下一章打算写什么、有无重点场景；朝大纲中最近的一个尚未实现的路标推进")
    }
    lines.add("")
    lines.add("### 伏笔与角色状态（保持长篇连续性）")
    lines.add("- openThreads 必须继承上一章清单中仍未解决的条目（措辞可精简），再追加本章新增伏笔；只有确已解决的条目才可移除")
    lines.add("- characterStates 覆盖本章出场的每个角色，每条 10-25 字；角色较多时总长可放宽到 150 字")
    lines.add("")
    // 故事二期卷一（D-1 红线修订）：三个**可选**字段的说明，逐字取提案 §4.1 物料 D。
    // 解析端全程容忍缺失/「无」/老章（→ null），既有 13 字段的格式说明与示例一字未改。
    lines.add("### 关系叙事字段（叙事连续性）")
    lines.add("intimacyUpdates: [里程碑]或[近况]开头的 0–3 条本章人物关系新进展，分号分隔；多位主要角色时条目以角色名开头；无新进展写 无")
    lines.add("sceneEndState: 一行章末场景状态，格式「地点｜在场人物及状态要点」；章末已离开该场景写 无")
    lines.add("sceneTag: 一行本章重点场景标签，格式「场景·地点·要点」；本章无重点场景写 无")
    lines.add("")
    lines.add("### 格式规则")
    lines.add("- 故事正文部分自由书写，专注于创作质量，不要考虑格式问题")
    lines.add("- 标记（[scene:xxx]、[text:style] 等）直接嵌入正文中")
    lines.add("- ---METADATA--- 分隔符必须独占一行，前后各空一行")
    lines.add("- 元数据部分每行格式：字段名: 值（冒号后有一个空格）")
    lines.add("- 不要输出 JSON，不要输出 markdown 代码块，不要添加任何解释性文字")
}
