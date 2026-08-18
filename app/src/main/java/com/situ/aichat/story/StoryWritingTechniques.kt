package com.situ.aichat.story

/**
 * 按 genre × writingStyle 分发的写作技巧提示词模块（1:1 iOS `Services/StoryWritingTechniques.swift`）。
 *
 * 全为静态提示词文本 + 几个纯函数（字数档/节奏/结局/续写衔接），无三方依赖；供 11.1d Prompt 构建消费。
 * 所有中文串逐字复刻 iOS；多行串用 [trimIndent] 还原 Swift 多行字面量的「去公共缩进、无首尾换行」语义，
 * 含插值的选择块按 iOS 逐行 append（避开 trimIndent 与插值同用的缩进计算冲突）。
 */
internal object StoryWritingTechniques {

    // MARK: - 写作身份（根据 writingStyle）

    fun writerIdentity(writingStyle: String): String = when (writingStyle) {
        "轻松幽默" ->
            "你是一位擅长都市轻喜剧的小说家。你的文字节奏轻快、对话机智幽默，善于在日常场景中制造反差笑点。你写的角色都有鲜明的性格缺陷，但让人忍不住喜欢。"
        "严肃文学" ->
            "你是一位追求文学性的纯文学作家。你注重意象与隐喻，擅长通过细微的动作和环境描写揭示人物内心。你的文字克制而有力，不煽情、不说教，让读者自己感受。"
        "网文爽文" ->
            "你是一位顶尖网络小说作者，精通「节奏即正义」的爽文写法。开篇即冲突，主角遇到压制就要反击打脸，每章必有一个让读者拍案叫绝的爽点或反转钩子。绝不拖沓，大开大合。"
        "日系轻小说" ->
            "你是一位日系轻小说作者。你的段落简短（2-3句一段），故事由对话驱动，角色之间充满吐槽和互动。叙述轻快活泼，战斗描写热血，日常场景有趣，善用内心独白制造喜感。"
        "哥特暗黑" ->
            "你是一位哥特暗黑风格的小说家。你善于用环境渲染压迫感——腐朽的建筑、扭曲的影子、不明来源的声响。恐惧来自暗示和未知，而非直白的血腥。你的角色都背负秘密，命运交织。"
        "古风" ->
            "你是一位古风小说名家。你的用词典雅含蓄，善用诗词意象点染情绪（落花、明月、长亭、烟雨）。场景描写有工笔画的精致感，对话含蓄内敛，情感在礼教与真心之间拉扯。"
        else ->
            "你是一位经验丰富的小说家，文笔成熟，善于通过细节和对话展现人物性格和情感冲突。"
    }

    // MARK: - 类型技巧（根据 genre）

    fun genreTechniques(genre: String): String = when (genre) {
        "言情" -> """
            【言情核心技法】
            - 情感描写是第一优先级
            - 暧昧要写潜台词：角色说的和想的不一样，让读者替角色着急
            - 心动要写生理反应：心跳加速、不敢对视、手指微颤、耳尖发红
            - 冲突要写内心拉扯：理智与感情的矛盾，让角色在"应该"和"想要"之间挣扎
            - 感官侧重触觉和微表情：指尖碰触、呼吸变化、目光闪避
            - 对话中的停顿、欲言又止、话题转移都是情感表达
        """.trimIndent()
        "悬疑" -> """
            【悬疑核心技法】
            - 严格控制信息释放：每章给读者一个真线索和一个误导
            - 用环境细节暗示异常：一杯没喝完的咖啡、反常的安静、门没关好
            - 禁止角色直接说出真相，让读者自己推理
            - 制造"不对劲"的感觉比直接制造恐惧更有效
            - 结尾必须留下一个让读者"等等，那这个怎么解释？"的疑问
            - 时间线、证词、细节必须前后一致，不能有逻辑漏洞
        """.trimIndent()
        "奇幻" -> """
            【奇幻核心技法】
            - 世界观通过角色日常展示，不要用旁白解释规则
            - 魔法/能力通过使用场景让读者自然理解，不写说明书
            - 每个奇幻元素必须有代价或限制，免费的力量没有戏剧性
            - 地名、种族名、术语首次出现时用语境暗示含义，不要括号注释
            - 奇幻元素服务于人物成长和情感冲突，不是用来炫设定
        """.trimIndent()
        "科幻" -> """
            【科幻核心技法】
            - 科技融入生活细节：角色如何通讯、出行、吃饭，自然展示未来世界
            - 不写技术说明书，通过角色的行为和选择展现科技影响
            - 技术的双刃剑效应是冲突的核心来源
            - 科幻设定要有内在逻辑一致性
            - 人性在技术变革中的反应才是真正的故事
        """.trimIndent()
        "都市" -> """
            【都市核心技法】
            - 场景要接地气：写具体的天气、时间段、空间（不必真实地名，但要有真实感）
            - 角色面对的是当代年轻人真实的困境：职场、租房、关系、孤独感
            - 对话要有口语感，可以带网络用语但不堆砌
            - 用城市里的小物件和小场景制造氛围：便利店、地铁、深夜外卖、雨天打车
        """.trimIndent()
        "恐怖" -> """
            【恐怖核心技法】
            - 恐惧来自"差一点看到"而非"直接看到"——暗示比展示更可怕
            - 感官侧重听觉和嗅觉：楼上的脚步声、腐朽的甜腻气息、脖子后面的冷气
            - 正常场景中的微小异常比怪物更可怕：照片里多了一个人、时钟倒着走
            - 节奏公式：安静→安静→微响→大安静→突发
            - 让恐惧有层次：先不安，再怀疑，再恐惧，最后崩溃
        """.trimIndent()
        "校园" -> """
            【校园核心技法】
            - 捕捉青春期特有的情绪放大效应——小事在十几岁的人眼里就是天大的事
            - 场景围绕教室、走廊、天台、便利店、操场等校园空间展开
            - 友情的试探、暗恋的小心思、考试的焦虑、和父母的矛盾，要写得细腻且有共鸣
            - 对话要有学生腔：省略、口头禅、跳跃式话题
            - 时间感很重要：课间十分钟、放学后、周末
        """.trimIndent()
        "历史" -> """
            【历史核心技法】
            - 用符合时代的称谓（官职、敬语）和器物（衣食住行），但自然融入不堆砌
            - 历史事件是背景舞台，人物命运才是核心
            - 可以虚构人物和细节，但大时代氛围要可信
            - 用当时的价值观驱动角色行为，不要让古人有现代思维
            - 历史知识点通过对话和行动自然带出，不做历史课
        """.trimIndent()
        "末日" -> """
            【末日核心技法】
            - 通过匮乏推动剧情——缺水、缺药、缺信任
            - 废墟中的细节暗示过去的文明：半截广告牌、长满苔藓的游乐场、空荡的超市货架
            - 人性在极端环境下的选择才是真正的故事：分享还是独占？信任还是背叛？
            - 希望和绝望交替出现，不要一直绝望（读者会疲劳）
            - 生存细节要具体：怎么净水、怎么生火、怎么判断食物是否安全
        """.trimIndent()
        "日常" -> """
            【日常核心技法】
            - 没有大事件，靠细节和氛围取胜
            - 一顿饭、一次散步、一句无意间的话，都能推动人物关系微妙变化
            - 节奏像呼吸一样自然，不需要每章都有冲突
            - 每章至少一个"会心一笑"或"心头一暖"的小瞬间
            - 季节、天气、食物、音乐——用这些日常元素营造氛围
        """.trimIndent()
        else -> customGenreFallback(genre)
    }

    /**
     * 自定义题材（不在预设 10 类白名单）的兜底类型技法（图纸 L3）：把用户填的类型名钉成全书不可动摇的基调锚，
     * 防「武侠」几章后漂成悬疑。genre 为空 → 返回 ""（= 原 `else -> ""` 行为，anchorLabel 也判空退回原状）；
     * 独立段落不受 [genreAnchorLabel] 12 字门限（长题材名照样成段）。{genre} 用 story.genre 原文，不经 anchorLabel。
     * 逐行拼接（不 trimIndent+插值·遵本文件既有约定，避缩进计算冲突）。
     */
    fun customGenreFallback(genre: String): String {
        if (genre.isBlank()) return ""
        return listOf(
            "【类型核心技法】",
            "本故事的类型是「$genre」，这是全书不可动摇的基调：",
            "- 每一章的冲突设计、剧情推进、新角色引入与伏笔铺设，都必须服务于「$genre」类型的核心体验",
            "- 悬念与支线只能作为佐料，不得喧宾夺主把故事漂移成另一种类型",
            "- 若前文已经偏离「$genre」的基调，本章要自然地把故事拉回来",
        ).joinToString("\n")
    }

    /** 句中嵌入用题材短名（引导语/伏笔平衡行·图纸 L8/L9）：blank 或 length > 12 → null（嵌入点退回原句）。 */
    fun genreAnchorLabel(genre: String): String? =
        genre.takeIf { it.isNotBlank() && it.length <= 12 }

    // MARK: - 通用写作规则（所有类型共享）

    /**
     * 展示/对话/节奏三组风格原则——用户自定义「写作规则」时被整段接管（随规则一起被替换）。
     *
     * 文字忌口改造（2026-07-30）：由 `private` 提为 `internal`，供创建页「写作规则」编辑器的「填入默认」直接取用
     * ——原先灌的是「风格原则 + 忌口」合体串，保存后忌口会被再拼一次进 prompt（双重注入 bug）。
     * 风格原则与文字忌口自此正交：风格随用户，忌口走 [StoryPromptSections.resolvedBannedExpressions] 三层取值。
     *
     * **上下文融洽性整理（2026-07-30·dump 完整创作 prompt 逐段对读后）**：原 8 条压到 6 条，理由逐条留档，
     * 勿凭「看着少了」把它们加回来——三处都是与紧邻的忌口段（[bannedExpressionsBaseline]）打架或重复：
     * - 原 3「不用否定式描写（禁止"没有X，没有Y，只有Z"句式）」**整条删**：这正是「禁语法结构会损害模型
     *   推理」要根除的句式禁令（提案 §2 C-1）。忌口那侧已按此结论删光句式禁令，此处留着 = 同一原则只贯彻一半。
     * - 原 4「行为描写之后不解释情感」**整条删**：与忌口「同一个意思别说两遍」是同一条规则，两段隔 15 行
     *   各说一遍——本身就在犯忌口点名的毛病，且重复禁令会加重 backfire。语义由忌口那条承接，未丢失。
     * - 原 8「每段2-4句」→ 现 6「别一段写到底，段落长短跟着内容走」：原文是**硬性均匀化**，与忌口
     *   「详略要拉开·别每个场景都一样满」方向相反；且「2-4句」是可量化硬指标，模型天然更服从它，
     *   会直接架空忌口那条。放松后两侧一致，「别一段写到底」这个有效约束仍在。
     */
    internal val writingPrinciples: String = """
        ### 写作铁律（每一句都必须遵守）

        **展示原则**
        1. 展示而非告知：不写"她很伤心"，写她的行为——"她把咖啡搅了很久，直到凉透"
        2. 感官优先：每个重要场景至少调动2种感官（视觉、听觉、嗅觉、触觉、味觉）

        **对话原则**
        3. 对话带动作：不写"他说""她说"，用动作衔接——"他背过身去""她攥紧了手机"后接对话
        4. 对话推动剧情：每句对话要么揭示性格、要么推进关系、要么暗示信息，三者至少占一

        **节奏原则**
        5. 长短句交替：紧张时用短句（3-7字），抒情时用长句，制造呼吸感
        6. 段落控制：别一段写到底，段落长短跟着内容走
    """.trimIndent()

    /**
     * 「文字忌口」的**内置默认文本**——与任何文风正交，只防 AI 味；用户可在设置页整段改写或清空
     * （三层取值 = 本故事 › 全局 › 本默认，唯一实现点 [StoryPromptSections.resolvedBannedExpressions]）。
     *
     * 内容出处 = 契约 FABLE5_STORY_WORD_TASTE_PROPOSAL §4（**v2.1 重构版**·用户 2026-07-30 拍板）。
     *
     * **设计判据：通用 = 所有题材的交集，不是并集。** 每条都必须过「能不能想出一个反例题材」这一关——
     * 想得出就不进默认。故本文本只打「偷懒」（泛化细节 / 冗余 / 强行升华 / 平均用力 / 陈词），
     * **不含任何叙事策略主张**：v2.0 曾有的「多用对话推进，少用旁白解释心理」已删除——那是 show-don't-tell
     * 的变体，对意识流 / 心理小说 / 推理解谜是紧身衣（勒古恩《On Rules of Writing》即批评该规则让写手
     * 「不敢描写自己创造的世界」），且它本就属于 [writingPrinciples]（第 1、4 条已有），不该从「可整段替换」
     * 的地方搬到「默认恒附加」的地方。想要文风指导的用户走「写作规则 / 写作身份」字段。
     *
     * **一个字都别顺手改**：① 删掉的四处误伤（不禁 / 深吸一口气 / 不知不觉 / 恍若隔世）是正常汉语；
     * ② 整组删掉的句式禁令是因为「禁语法结构会损害模型推理」（提案 §2 C-1 实证），加回去就是回退调研结论；
     * ③「别强行升华」特意写明「留钩子可以」——连载小说的章末悬念不在打击范围。
     *
     * 长篇稳定性 L4b（O1 拍板 2026-07-08）原为「用户自定义写作规则时恒附加、不可撤」，
     * 经提案 §7 D-2 修订为「**默认恒附加、用户可撤**」：用户主动清空是明示意图，应尊重。
     */
    val bannedExpressionsBaseline: String = """
        ### 别写出 AI 味

        **细节要具体**：不写「美丽的花园」「古老的宅子」这种谁都能填的形容，
        写具体能看见的东西——三株半死的月季、门轴上新换的铜合页。

        **同一个意思别说两遍**：已经用动作或对话表现过的，别再补一句解释。

        **别强行升华**：场景该停就停。不要在段末、章末拔高一句、点破意思、
        或用一句意味深长的话收尾（留钩子可以，升华不行）。

        **详略要拉开**：要紧处慢下来写足，过场几句带过；别每个场景都一样满。

        **这些套话不要用**：
        映入眼帘、心中一动、一股暖流、嘴角微微上扬、嘴角勾起一抹弧度、
        目光深邃、时光荏苒、心头涌上一股莫名的情绪、时间仿佛凝固、
        内心掀起了波澜、心中五味杂陈、默默地看着这一切、
        眼中闪过一丝（任何东西）、空气中弥漫着（任何东西）、
        与此同时、就在这时、然而事情并没有那么简单

        **这些词删掉不影响句意时就删掉**：
        淡淡的、莫名的、缓缓地、静静地、默默地、似乎、仿佛、某种

        **同一个动作、比喻或成语，一章里别反复用。**
    """.trimIndent()

    // MARK: - 人称规则

    fun narrativePersonRules(person: String, hasUserRole: Boolean): String = when (person) {
        "first" -> """
            ### 叙事人称：第一人称
            - 以"我"为叙事视角，用主角的口吻和认知范围来叙述
            - 只能写"我"看到的、听到的、想到的，不能写其他角色私下的想法
            - "我"的声音要始终一致：用词习惯、思维方式、情绪表达风格
            - "我"可以主动说话，对话时不需要标注"我说"
        """.trimIndent()
        "third" -> """
            ### 叙事人称：第三人称
            - 以第三人称叙述，用角色的名字指代
            - 每个场景聚焦一个角色的视角，不要在同一段里跳切视角
            - 可以描写聚焦角色的内心想法，但用"他/她想"而非全知旁白
            - 不要每句话都以"他/她"开头，多用动作和对话驱动
        """.trimIndent()
        else -> {
            // 第二人称
            val base = """
                ### 叙事人称：第二人称
                - 以"你"为叙事视角，读者通过"你"的感受体验故事
                - 描写"你"的动作、想法和感受，让读者产生代入感
            """.trimIndent()
            base + if (hasUserRole) {
                "\n- 「你」是用户扮演的角色，可以有对话，但要让「你」的性格和设定一致"
            } else {
                "\n- 「你」不主动说话，通过内心活动和行为推动剧情"
            }
        }
    }

    // MARK: - 章节结构要求

    /**
     * 普通章的章节要求（结局章走 [requestedEndingRequirements]，不经此处）。
     *
     * 卷二·单模式化：原按 `maxChapters` 分岔的「共 N 章 / 最后一章写完整结局 / 距结局很近开始收束 /
     * 结局章不设选择节点」四块随有限模式整体退役删除——无限连载没有预定终点，收尾改由终章弧承担
     * （[StoryArcPlanning]），故本函数不再接收 maxChapters，选择节点段恒输出。
     */
    fun chapterRequirements(
        chapterNumber: Int,
        chapterLength: Int,
        isFirstChapter: Boolean,
        narrativePerson: String = "second",
        userRoleName: String? = null,
        /**
         * 图纸二 D3：false = 本书关掉章末选项 —— 「章节结尾选择节点」整块换成紧凑的关闭态三行（M1），
         * 人称分支 choiceSubjectHint 随整块一起跳过。**默认 true 时输出与改动前逐字节相同**（T1 回归钉）。
         */
        choicesEnabled: Boolean = true,
    ): String {
        val lines = mutableListOf<String>()
        lines.add("## 章节要求")

        // 字数引导：给出目标范围，让 AI 自然控制节奏，在合适的地方收尾
        val (targetMin, targetMax) = chapterLengthRange(chapterLength)
        lines.add("- 本章目标字数：$targetMin-$targetMax 字。请合理安排叙事节奏，在这个范围内找到一个自然的段落/场景结尾收束，不要在句子中间断开")

        if (isFirstChapter) {
            lines.add("- 这是第一章")
            lines.add("- 开头3句话必须抓住读者：一个动作、一个悬念、或一个反常的细节")
            lines.add("- 快速建立「此刻正在发生什么」，不要用大段背景描述开场")
            lines.add("- 在本章内让读者认识核心角色并感受到主线冲突的影子")
        } else {
            lines.add("- 这是第 $chapterNumber 章（无限连载）")
            lines.add("- 至少包含一个推动主线的事件和一个加深角色关系的场景")
        }

        // 角色使用纪律（圣经压缩保真优化·上游节流）：名册只增不减是长篇档案失真的源头之一——
        // 章级先节流（克制冒新人 + 路人不起名），档案侧再治理。首章还没有「已有角色」可优先起用，
        // 故克制两条只发给续章；结局章走 requestedEndingRequirements 不经此处（终章弧已有禁新角色令）。
        lines.add("")
        lines.add("### 角色使用纪律")
        if (!isFirstChapter) {
            lines.add("- 优先起用已有角色推动剧情；新鲜感优先从既有角色的新面向和关系变化里挖掘，不为新鲜感而发明新人物")
            lines.add("- 确因剧情需要引入新角色时，单章新增的命名角色原则上不超过一位（题材或场面确需时可例外，如群像、宴会戏）；大纲、方向提示或用户选择的走向里已安排的登场不受此限")
        }
        lines.add("- 只出场一次的功能性角色用身份或泛称指代（服务员、司机、老板娘、隔壁阿姨等），不要为其起名字；只有会再次出场、或与主要角色产生实质关系的人物才值得命名——名字是向读者许下的「此人重要，请记住」的承诺")

        lines.add("")
        if (!choicesEnabled) {
            // M1（图纸 §4 锁定文本）：关闭态紧凑段——不问读者、不列选项，推进交给阅读器常驻的「继续连载」。
            lines.add("### 章节结尾（本书已关闭章末选择）")
            // 2026-08-05 M-E5：beats 统一草稿化后，关选项书同样要输出 nextChapterBeats（禁的只是四个 choice 字段）
            lines.add("- hasChoice 必须为 false；不要输出 choicePrompt、choiceA、choiceB、choiceC、choiceD 字段；nextChapterBeats 照常输出下一章计划草稿")
            lines.add("- 结尾要让人急着看下一章，优先收在重钩子上：悬念悬停在将揭晓未揭晓的边缘、关键时刻被打断、新的变数刚露头、转折的前夜；重点场景刚写完的章可以用余韵收，但余韵里也要埋下文的引子。不向读者提问、不列选项")
            return lines.joinToString("\n")
        }
        lines.add("### 章节结尾选择节点（必须）")
        val choiceSubjectHint: String = when (narrativePerson) {
            "first" -> if (userRoleName != null) {
                "choicePrompt 以「${userRoleName}决定……」或类似句式开头"
            } else {
                "choicePrompt 以「我决定……」或类似句式开头"
            }
            "third" -> if (userRoleName != null) {
                "choicePrompt 以「${userRoleName}会……」开头"
            } else {
                "choicePrompt 以「接下来……」开头"
            }
            else -> "choicePrompt 以「你决定……」或「你注意到……你决定……」开头"
        }

        // 以下整块逐行 add（joinToString("\n") 结果等价），避免 trimIndent+插值冲突。
        lines.add("每章结尾必须设置一个选择节点（hasChoice 必须为 true），给出 2-3 个选项。")
        lines.add(choiceSubjectHint)
        lines.add("")
        lines.add("**选择质量标准：**")
        lines.add("- 每个选项都合理，没有明显的\"正确答案\"")
        lines.add("- 选项之间方向明显不同，不只是措辞差异")
        lines.add("- 选择对后续剧情有实际影响")
        lines.add("- 选择自然融入章节结尾的情境")
        lines.add("")
        lines.add("**选择类型（轮换使用，避免连续两章用同一类型）：**")
        lines.add("1. 行动选择：下一步做什么（去某处/找某人/调查某事）")
        lines.add("2. 对话选择：对TA说什么（直接质问/试探/假装不知道）")
        lines.add("3. 情感选择：内心如何反应（愤怒反击/冷静分析/选择原谅）")
        lines.add("4. 策略选择：打算怎么做（公开对抗/暗中调查/寻求帮助）")
        lines.add("5. 价值权衡：在两个重要目标间取舍（保护自己 vs 帮助朋友）")
        lines.add("6. 氛围选择：不影响主线但建立角色认同（选择回忆/关注哪个细节）")
        lines.add("")
        lines.add("**禁止的选择设计：**")
        lines.add("- 被动选择（\"被抓走\"不是选择）")
        lines.add("- 无意义选择（\"走左边还是走右边\"没有信息差）")
        lines.add("- 有明显最优解的选择")

        return lines.joinToString("\n")
    }

    /**
     * 目标字数区间 = 档位名义值 ±20%（2026-07-26 卷一 V4：对齐档位，整数算术；入参下限 100 防脏值）。
     *
     * 上限 100_000 是**溢出闸**（R1 复核 🔵）：原先只钳下限，`Int.MAX_VALUE` 会让 `safe * 6` 溢出成负数，
     * 区间渲染为「0--1」。最大档位才 5000，十万的上限对真实数据完全无感；而这句话如今站在
     * user message 的注意力峰值位（[buildCreationUserMessage]），不能让脏值把它变成一句胡话。
     */
    fun chapterLengthRange(chapterLength: Int): Pair<Int, Int> {
        val safe = chapterLength.coerceIn(100, 100_000)
        return (safe * 4 / 5) to (safe * 6 / 5)
    }

    /**
     * 结局章目标字数区间 = 普通章上限 → 上限 ×1.5（比普通章更长，保证收束质量）。
     * [chapterLength] 取**基础**档位值（非 [StoryGenerationPolicy.effectiveChapterLength] 的 ×1.5 放大值）。
     *
     * 抽为单源供两处共用：本文件 [requestedEndingRequirements] 的 system 段，与
     * [buildCreationUserMessage] 的末句要点复述——两处字数**必须逐字一致**，否则模型收到自相矛盾的指标。
     */
    fun endingChapterLengthRange(chapterLength: Int): Pair<Int, Int> {
        val (_, normalMax) = chapterLengthRange(chapterLength)
        return normalMax to (normalMax.toDouble() * 1.5).toInt()
    }

    // MARK: - 用户请求结局章节要求

    /** 用户手动触发结局时的写作指令（1:1 iOS `requestedEndingRequirements` :305-356）。
     *  注：iOS 签名另有 narrativePerson/userRoleName 两参但函数体未用到，移植时省去（避免死参）。 */
    fun requestedEndingRequirements(
        endingType: String,
        endingDetail: String?,
        chapterNumber: Int,
        chapterLength: Int,
    ): String {
        val lines = mutableListOf<String>()

        lines.add("## ⚠️ 这是最终章（结局章）——请不遗余力地写出一个完美的结局")
        lines.add("")

        // 结局类型指令
        when (endingType) {
            "open" -> {
                lines.add("### 结局类型：开放式结局")
                lines.add("- 不需要把所有线索收完，留给读者想象空间")
                lines.add("- 关键情感线要有一个让人回味的收束，但不必给出明确答案")
                lines.add("- 结尾留一个意味深长的画面或对白，让读者久久回味")
            }
            "custom" -> {
                lines.add("### 结局类型：用户指定方向")
                if (!endingDetail.isNullOrEmpty()) {
                    lines.add("用户希望的结局方向：「$endingDetail」")
                }
                lines.add("- 在尊重用户期望的前提下，自由发挥细节，让结局自然而有力")
                lines.add("- 所有能回收的伏笔都要回收，角色弧线要完整")
            }
            else -> {
                // ai 类型
                lines.add("### 结局类型：AI 自由发挥")
                lines.add("- 根据你对当前剧情走向、角色状态和未解线索的理解，写出你认为最完美的结局")
                lines.add("- 所有重要伏笔必须回收，角色弧线要完整闭合")
                lines.add("- 给读者一个满足的情感释放")
            }
        }
        lines.add("")

        // 结局章字数加大（1.5 倍）——区间算式抽为 [endingChapterLengthRange] 单源，与末句要点复述共用。
        val (normalMax, endingTarget) = endingChapterLengthRange(chapterLength)

        lines.add("### 结局章写作要求")
        lines.add("- 这是第 $chapterNumber 章，也是最终章")
        lines.add("- 目标字数：$normalMax-$endingTarget 字（比普通章节更长，保证收束质量）")
        lines.add("- 这是读者看到的最后一章，质量最重要，用户体验是第一位")
        lines.add("- 回应第一章的设定和悬念，给角色和读者一个情感释放")
        lines.add("- 给主要角色一个有说服力的归宿")
        lines.add("- 最后一段要有回味悠长的画面或对白")
        lines.add("- isEnding 必须为 true")
        lines.add("- hasChoice 必须为 false（结局章不需要选择节点）")

        return lines.joinToString("\n")
    }

    // MARK: - 重写指令

    /** 重写最新章节时追加的提示词（1:1 iOS `rewriteInstruction` :361-370）。 */
    fun rewriteInstruction(instruction: String?): String {
        val lines = mutableListOf<String>()
        lines.add("## 重写指令")
        lines.add("用户对上一次生成的本章内容不满意，请重新创作本章。")
        if (!instruction.isNullOrEmpty()) {
            lines.add("用户的补充要求：「$instruction」")
        }
        lines.add("请在保持剧情连贯性的前提下，创作一个全新的版本。不要重复上一版的写法和情节安排。")
        return lines.joinToString("\n")
    }

    // MARK: - 续写专用规则

    /** 上一章全文（剥标签）+ 氛围——喂全文以延续文风嗓音（2026-07-26 卷一 V2：原「末 400 字」放宽为全文）。 */
    fun previousChapterEnding(content: String, mood: String): String {
        val cleaned = stripMarkupTags(content).trim()
        val lastMood = extractLastMood(content) ?: mood
        return "## 上一章全文（新章节必须从其结尾自然衔接，不要复述或改写上一章的内容）\n" +
            cleaned +
            "\n\n上一章结束时的氛围：" + lastMood
    }

    /** 续章专用的衔接与一致性规则（1:1 iOS `continuationRules` :396-402）。 */
    val continuationRules: String = """
        ### 续写规则
        1. 开头必须自然衔接上一章结尾的场景和氛围，不要突兀切换；紧张氛围应延续再逐步转变
        2. 避免重复：段落层面——连续两段不以同一角色名或代词开头；章节层面——不重复前文用过的比喻和情节模式；对话层面——变换对话节奏，不要总是一问一答
        3. 每章至少引入一个新的场景元素（地点/物件/天气/时间段）
        4. 写完自查：时间线、角色言行、世界观规则是否与前情一致
    """.trimIndent()

    /**
     * 节奏指引（无限连载通用一句）。
     *
     * 卷二·单模式化：原按 `chapterNumber/maxChapters` 进度分四段（序章期/发展期/高潮期/收束期）的曲线随
     * 有限模式整体退役——无终点的故事没有固定的「收束期」，弧线级的收束由 [StoryArcPlanning] 的弧末收束令承担。
     */
    const val PACING_GUIDANCE: String = "- 节奏指引：保持每章有推进、有悬念，让读者想看下一章"

    // MARK: - 内部工具

    /** 匹配 `[tag:value]` 和 `[/tag]` 标记（1:1 iOS `stripMarkupTags` 正则 :428）。 */
    private val markupTagRegex = Regex("""\[/?[a-zA-Z_][a-zA-Z0-9_]*(?::[^\]]*)?]""")

    /** `[mood:xxx]` 标记（1:1 iOS `extractLastMood` 正则 :437）。mood 值恒 ASCII，\w ASCII 语义与 iOS 等价。 */
    private val moodTagRegex = Regex("""\[mood:(\w+)]""")

    /** 去掉标记标签，只留纯文本。 */
    private fun stripMarkupTags(text: String): String = markupTagRegex.replace(text, "")

    /** 从章节内容中提取最后一个 [mood:xxx] 的值。 */
    private fun extractLastMood(content: String): String? =
        moodTagRegex.findAll(content).lastOrNull()?.groupValues?.get(1)
}
