package com.situ.aichat.story

/**
 * 故事圣经结构化压缩的纯逻辑（长篇稳定性 L1·契约 FABLE5_STORY_LONGFORM_STABILITY_PROPOSAL §3）。
 *
 * 圣经现状 = 逐章追加的「第N章角色：…／第N章伏笔：…」流水账（[StoryGenerationPolicy.buildBibleAppendix]），
 * 只增不减且每章全量注入 prompt。本对象提供：按章号水位线切分（[split]）、触发判定（[shouldCompress]）、
 * 压缩提示词（[buildBibleCompressionPrompt]，输出「主要/次要/已淡出角色 + 伏笔账本」结构）、
 * 压缩结果组装（[assembleCompressedBible]）、点名对账闸的点名册（[extractArchiveNames]）。
 * LLM/DB 编排在 [StoryBibleCompressor]。零依赖、100% 可单测。
 *
 * 关键约束：**最近 [KEEP_RAW_RECENT_CHAPTERS] 章的逐章行永远留在尾段不压缩**——重写回滚
 * （[StoryGenerationPolicy.rollbackBible] 按行前缀删最新章）只作用于最新章，恒在尾段 → 回滚机制零改动不失效；
 * 近期细节也保持原粒度（与前情滑窗「最近 5 章全量」口径对齐）。
 */
internal object StoryBibleCompression {

    /** 触发条件一：未压缩尾段覆盖章数 ≥ 此值（对齐弧线续接节律；短篇永不触发 = 行为与现版本一致）。 */
    const val COMPRESSION_CHAPTER_INTERVAL = 12

    /** 触发条件二：未压缩尾段总字数 > 此值（稀疏尾段不白烧 LLM）。 */
    const val COMPRESSION_CHAR_THRESHOLD = 2_000

    /** 永远保留原始逐章行的最近章数（重写回滚安全 + 近期细节保粒度）。 */
    const val KEEP_RAW_RECENT_CHAPTERS = 5

    // MARK: - 档案分级与配额（圣经压缩保真优化 2026-07-27·图纸 §3.2·取代旧「1200/1500 字全员平摊」死天花板）

    /** 主要角色每人档案配额（字）。 */
    const val ARCHIVE_MAIN_CHAR_QUOTA = 80

    /** 次要角色每人一行配额（字）。 */
    const val ARCHIVE_MINOR_CHAR_QUOTA = 25

    /** 档案全文绝对上限（字·不含尾段逐章行）。 */
    const val ARCHIVE_TOTAL_CHAR_BUDGET = 2_500

    /** 代码拒收线 = 上限倍数（诚实微超放行，离谱才拒）。 */
    const val ARCHIVE_REJECT_MULTIPLIER = 2

    /** 代码拒收线（字）。 */
    const val ARCHIVE_REJECT_CHAR_LIMIT = ARCHIVE_TOTAL_CHAR_BUDGET * ARCHIVE_REJECT_MULTIPLIER

    /** 淡出阈值（章）：整整一条最长普通弧未露面（单源锚定弧线上限）。 */
    const val FADED_ABSENCE_CHAPTERS = StoryArcPlanning.ARC_LENGTH_MAX

    /** 已淡出名单行前缀（prompt 模板与 [extractArchiveNames] 提取器共用·单源）。 */
    const val FADED_LINE_PREFIX = "已淡出："

    /** 已了结伏笔行前缀（prompt 模板与单源锁测试共用·单源）。 */
    const val RESOLVED_LINE_PREFIX = "已了结："

    /** 逐章行前缀（[StoryGenerationPolicy.buildBibleAppendix] 的输出格式），捕获组 1 = 章号。 */
    private val chapterLineRegex = Regex("""^第(\d+)章(?:角色|伏笔)：""")

    /** 记名段的段头（这三段内的 `- 名字｜…` 行才被 [extractArchiveNames] 认作角色行）。 */
    private val nameSectionHeadings = listOf("【主要角色】", "【次要角色】", "【角色档案】")

    /** 记名段内的角色行：行首 `-` + 可选空白 + 名字 + 全角竖线。捕获组 1 = 候选名。 */
    private val archiveNameRegex = Regex("""^-\s*([^｜]+)｜""")

    /** 候选名合法长度窗（1 字名与超长候选一律跳过·fail-open 防误杀）。 */
    private val nameLengthWindow = 2..12

    /** 圣经按水位线切分的结果（[split] 的产物），三段均保持原行序。 */
    data class BibleSplit(
        /** 基底：结构化档案 / 用户手编文本 / 章号 ≤ 水位线的残留逐章行——一并作为「已有档案记录」送 LLM 整理。 */
        val base: String,
        /** 待压缩的新逐章行（水位线 < 章号 ≤ compressThrough）。 */
        val compressLines: List<String>,
        /** 保留原样的近期逐章行（章号 > compressThrough）。 */
        val keepLines: List<String>,
    )

    /** 行首逐章行的章号；非逐章行 → null。 */
    fun chapterNumberOf(line: String): Int? =
        chapterLineRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()

    /** 本轮压缩覆盖到的章号（最新章往前留 [KEEP_RAW_RECENT_CHAPTERS] 章）。 */
    fun compressThroughChapter(latestChapterNumber: Int): Int = latestChapterNumber - KEEP_RAW_RECENT_CHAPTERS

    /**
     * 是否触发圣经压缩：距上次压缩 ≥ [COMPRESSION_CHAPTER_INTERVAL] 章，且尾段（章号 > 水位线的逐章行）
     * 总字数 > [COMPRESSION_CHAR_THRESHOLD]。圣经为空恒 false。
     */
    fun shouldCompress(bible: String?, lastBibleCompressedAtChapter: Int?, latestChapterNumber: Int): Boolean {
        if (bible.isNullOrEmpty()) return false
        val watermark = lastBibleCompressedAtChapter ?: 0
        if (latestChapterNumber - watermark < COMPRESSION_CHAPTER_INTERVAL) return false
        val tailChars = bible.lineSequence()
            .filter { (chapterNumberOf(it) ?: 0) > watermark }
            .sumOf { it.length }
        return tailChars > COMPRESSION_CHAR_THRESHOLD
    }

    /**
     * 按水位线切分圣经（保持原行序）：逐章行按章号归入待压缩（水位线 < N ≤ [compressThroughChapter]）
     * 或保留尾段（N > compressThrough）；其余一切行（结构化档案/用户手编/≤水位线残留）归基底。
     */
    fun split(bible: String, lastBibleCompressedAtChapter: Int?, compressThroughChapter: Int): BibleSplit {
        val watermark = lastBibleCompressedAtChapter ?: 0
        val baseLines = mutableListOf<String>()
        val compressLines = mutableListOf<String>()
        val keepLines = mutableListOf<String>()
        for (line in bible.lines()) {
            val n = chapterNumberOf(line)
            when {
                n == null || n <= watermark -> baseLines.add(line)
                n <= compressThroughChapter -> compressLines.add(line)
                else -> keepLines.add(line)
            }
        }
        return BibleSplit(
            base = baseLines.joinToString("\n").trim(),
            compressLines = compressLines,
            keepLines = keepLines,
        )
    }

    /** 压缩后的新圣经 = LLM 输出的结构化档案 + 空行 + 保留尾段行（尾段空则只有档案）。 */
    fun assembleCompressedBible(compressedBase: String, keepLines: List<String>): String {
        val base = compressedBase.trim()
        if (keepLines.isEmpty()) return base
        return base + "\n\n" + keepLines.joinToString("\n")
    }

    /**
     * 圣经压缩提示词：把「已有档案记录 + 新增逐章记录」整理为「主要/次要/已淡出 + 伏笔账本」结构
     * （圣经压缩保真优化 2026-07-27·图纸 §4.2）。
     *
     * 治的两处慢性失真：① 每轮整体重写造成的「复印件式磨损」→ 条 3 命令未出场者的档案行**原样照抄**；
     * ② 死天花板对全员平摊 → 条 4 改人头配额（主要 [ARCHIVE_MAIN_CHAR_QUOTA] / 次要 [ARCHIVE_MINOR_CHAR_QUOTA]）
     * + 绝对上限 [ARCHIVE_TOTAL_CHAR_BUDGET]，挤压顺序「已淡出 → 次要 → 主要」。
     *
     * 输出仅回存 storyBible 列再作 prompt 输入，无解析器结构化消费（METADATA 红线零碰）；
     * 唯一硬格式约束 = 不得输出「第N章角色：/第N章伏笔：」行首格式（防污染 [split] 切分与重写回滚前缀匹配）。
     *
     * ⚠️ **弱耦合对（图纸 §6 登记）**：本模板的档案行格式（`- 名字｜…`、[FADED_LINE_PREFIX] 行）↔
     * [extractArchiveNames] 提取器——两侧共用 [FADED_LINE_PREFIX] 常量，`StoryBibleCompressionTest` 的
     * 单源锁测试（T1-D）钉住同步。失效模式 fail-open（提取器变哑不误杀），故不升 REDLINES 表。
     */
    fun buildBibleCompressionPrompt(existingBase: String, newChapterLines: String, throughChapter: Int, genre: String): String {
        val lines = mutableListOf<String>()
        lines.add("你是一个故事编辑助手。请把以下「已有档案记录」和「新增逐章记录」整理合并为一份结构化的故事档案。")
        lines.add("这是一部「$genre」类型的故事。整理档案时，保留与该类型核心体验相关的关系状态与情感线索。")
        lines.add("")
        lines.add("### 已有档案记录（可能是结构化档案、逐章流水账或作者手写笔记）")
        lines.add(existingBase.ifEmpty { "（首次整理，无已有记录）" })
        lines.add("")
        lines.add("### 新增逐章记录（截至第${throughChapter}章）")
        lines.add(newChapterLines)
        lines.add("")
        lines.add("## 整理要求")
        lines.add("1. 输出分为以下几段，严格按此格式（没有内容的段整段省略）：")
        lines.add("【主要角色】（反复出场、牵动主线、或与主角有持续关系的角色）")
        lines.add("- 名字｜身份要点（身份/外貌关键特征/与主角关系）｜当前状态｜关键往事一句｜最后出场：第N章")
        lines.add("【次要角色】（出过场但戏份轻的角色）")
        lines.add("- 名字｜一句话身份｜最后出场：第N章")
        lines.add("【已淡出】（最后出场距截至章已超过${FADED_ABSENCE_CHAPTERS}章、且非主要的角色；可多行，每行以「${FADED_LINE_PREFIX}」开头）")
        lines.add("${FADED_LINE_PREFIX}名字（一句话身份·第M章后未再出场）、名字（一句话身份·第M章后未再出场）")
        lines.add("【伏笔账本】")
        lines.add("- 伏笔内容（埋设：第N章｜状态：未回收）")
        lines.add("${RESOLVED_LINE_PREFIX}伏笔短语（第A章→第B章）、伏笔短语（第A章→第B章）")
        lines.add("2. 记录中出现过的每个角色都必须落在上述某一段里，一个都不许丢（宁可精简措辞也不可删人）；同一角色信息冲突时以更晚章节为准")
        lines.add(
            "3. 在「新增逐章记录」里没有出现的角色 = 本轮没有新戏份：这类角色的档案行必须从「已有档案记录」原样照抄，" +
                "禁止改写措辞、禁止精简；只有有新戏份的角色才允许更新档案行。角色在段落之间迁移（升入主要／降入已淡出）时按目标段格式重写，不受本条限制",
        )
        lines.add(
            "4. 篇幅配额：主要角色每人不超过${ARCHIVE_MAIN_CHAR_QUOTA}字，次要角色每人一行不超过${ARCHIVE_MINOR_CHAR_QUOTA}字，" +
                "全文控制在${ARCHIVE_TOTAL_CHAR_BUDGET}字以内；逼近上限时先压缩「已淡出」和「次要角色」，「主要角色」的配额最后才动",
        )
        lines.add("5. 已回收的伏笔一律并入「${RESOLVED_LINE_PREFIX}」行，只留短语并标注（埋设章→回收章），不逐条展开；未回收的伏笔逐条保留")
        lines.add("6. 不要输出以「第N章角色：」或「第N章伏笔：」开头的行")
        lines.add("7. 只输出档案本身，不要解释，不要添加任何前后缀")
        return lines.joinToString("\n")
    }

    /**
     * ⑤点名对账闸的点名册：从**旧基底**提取角色名（圣经压缩保真优化·图纸 §3.3）。
     * [StoryBibleCompressor] 拿它核对压缩产物「一个人都没丢」，丢名即整份拒收保旧圣经。
     *
     * **全程 fail-open**（图纸 §0.1 J2–J4）：任何提不出干净名字的行一律跳过，绝不抛异常——
     * 闸的失效模式必须是「守卫变哑」而不是「误杀好压缩」（误杀的代价 = 白烧调用 + 熔断后档案停整理）。
     * 故：只认记名段（[nameSectionHeadings]）内的 `- 名字｜` 行（伏笔条目行「- 神秘信件（埋设：…）」
     * 与无段头的手编笔记天然落空），候选名长度须落在 [nameLengthWindow]。
     *
     * 新增逐章行里的名字**不入册**（自由文本无可靠名字边界）——新角色本轮靠 prompt 条 2 进档案，
     * 下一轮起才被本闸守住。
     *
     * ⚠️ 与 [buildBibleCompressionPrompt] 的模板互为弱耦合对，见该函数 KDoc。
     */
    fun extractArchiveNames(base: String): Set<String> {
        val names = mutableSetOf<String>()
        var inNameSection = false
        for (raw in base.lines()) {
            val line = raw.trim()
            if (line.startsWith("【")) {
                inNameSection = nameSectionHeadings.any { line.startsWith(it) }
                continue
            }
            // 已淡出名单行（任意段内均认·可带「- 」前缀）：载荷按「、」切分，每段取全角括号前的名字。
            val fadedPayload = line.removePrefix("- ").trim().takeIf { it.startsWith(FADED_LINE_PREFIX) }
                ?.removePrefix(FADED_LINE_PREFIX)
            if (fadedPayload != null) {
                for (entry in fadedPayload.split("、")) {
                    addNameIfValid(names, entry.substringBefore("（"))
                }
                continue
            }
            if (inNameSection) {
                archiveNameRegex.find(line)?.groupValues?.getOrNull(1)?.let { addNameIfValid(names, it) }
            }
        }
        return names
    }

    /** 候选名清洗 + 长度窗过滤（窗外一律丢弃·fail-open）。 */
    private fun addNameIfValid(names: MutableSet<String>, candidate: String) {
        val name = candidate.trim()
        if (name.length in nameLengthWindow) names.add(name)
    }
}
