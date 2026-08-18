package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StoryWritingTechniques` tests (P11.1b), reverse-derived from iOS
 * `Services/StoryWritingTechniques.swift`: chapterLengthRange 区间、结局章字数 ×1.5、
 * pacingGuidance 进度阈值、人称 hasUserRole 分支、chapterRequirements 选择节点/结局/首章分支、
 * 续写衔接 + 末 mood 提取。
 *
 * 卷一（2026-07-26）换口径两处，期望值按图纸 §4.3/§4.6 规格重新手算（不抄实现输出）：
 * chapterLengthRange 由 when 分档表 → 「档位名义值 ±20%」公式（V4）；previousChapterEnding 由「末 400 字」→ 全文（V2）。
 */
class StoryWritingTechniquesTest {

    // ── chapterLengthRange = 档位名义值 ±20%（卷一 V4·图纸 §4.6：×4/5 与 ×6/5 整数算术）──
    // 期望值按规格手算，不抄实现：500→400-600 / 1500→1200-1800 / 3000→2400-3600（区间不得宽于规格）。

    @Test fun chapter_length_range_is_nominal_plus_minus_20_percent() {
        assertEquals(400 to 600, StoryWritingTechniques.chapterLengthRange(500))     // SHORT 档
        assertEquals(1200 to 1800, StoryWritingTechniques.chapterLengthRange(1500))  // MEDIUM 档（旧表名不符实：曾要 1800-2800）
        assertEquals(2400 to 3600, StoryWritingTechniques.chapterLengthRange(3000))  // LONG 档
        assertEquals(4000 to 6000, StoryWritingTechniques.chapterLengthRange(5000))  // EXTRA_LONG 档（2026-07-27 加档）
        // 结局章 effectiveChapterLength = 档位 ×1.5 后再入本函数
        assertEquals(1800 to 2700, StoryWritingTechniques.chapterLengthRange(2250))  // MEDIUM 结局
        assertEquals(3600 to 5400, StoryWritingTechniques.chapterLengthRange(4500))  // LONG 结局
        assertEquals(6000 to 9000, StoryWritingTechniques.chapterLengthRange(7500))  // EXTRA_LONG 结局
        // 旧 when 表的分档边界不再造成跳变：599/600 与 2499/2500 各自连续
        assertEquals(479 to 718, StoryWritingTechniques.chapterLengthRange(599))
        assertEquals(480 to 720, StoryWritingTechniques.chapterLengthRange(600))
        assertEquals(1999 to 2998, StoryWritingTechniques.chapterLengthRange(2499))
        assertEquals(2000 to 3000, StoryWritingTechniques.chapterLengthRange(2500))
    }

    @Test fun chapter_length_range_clamps_dirty_low_values() {
        // 脏值/0/负数：入参下限 100 → 恒有合理区间，绝不出现 0-0 或负区间
        assertEquals(80 to 120, StoryWritingTechniques.chapterLengthRange(100))
        assertEquals(80 to 120, StoryWritingTechniques.chapterLengthRange(0))
        assertEquals(80 to 120, StoryWritingTechniques.chapterLengthRange(-500))
    }

    // ── 结局章字数 = normalMax × 1.5（截断）──

    @Test fun requested_ending_target_is_normal_max_times_1_5() {
        // chapterLength 1500 → range (1200,1800)，normalMax=1800 → endingTarget=2700（卷一 V4 随公式变）
        val out = StoryWritingTechniques.requestedEndingRequirements(
            endingType = "ai", endingDetail = null, chapterNumber = 30, chapterLength = 1500,
        )
        assertTrue(out.contains("目标字数：1800-2700 字"))
        assertTrue(out.contains("isEnding 必须为 true"))
        assertTrue(out.contains("hasChoice 必须为 false"))
    }

    @Test fun requested_ending_custom_includes_detail_when_present() {
        val out = StoryWritingTechniques.requestedEndingRequirements(
            endingType = "custom", endingDetail = "两人重逢", chapterNumber = 10, chapterLength = 500,
        )
        assertTrue(out.contains("### 结局类型：用户指定方向"))
        assertTrue(out.contains("用户希望的结局方向：「两人重逢」"))
        // 500 → range (400,600)，normalMax=600 → 900（卷一 V4 随公式变）
        assertTrue(out.contains("目标字数：600-900 字"))
    }

    @Test fun requested_ending_custom_omits_detail_line_when_blank() {
        val out = StoryWritingTechniques.requestedEndingRequirements(
            endingType = "custom", endingDetail = "", chapterNumber = 10, chapterLength = 500,
        )
        assertFalse(out.contains("用户希望的结局方向"))
    }

    // ── 节奏指引（卷二·单模式化：原按进度四段的曲线随有限模式退役，收敛成恒定一句）──

    @Test fun pacing_guidance_is_a_single_generic_line() {
        // 期望从图纸 §0.1「pacingGuidance 收敛单句」重新打字，不引用实现常量。
        assertEquals(
            "- 节奏指引：保持每章有推进、有悬念，让读者想看下一章",
            StoryWritingTechniques.PACING_GUIDANCE,
        )
        // 四段曲线的阶段名一个都不许再出现（原「序章期/发展期/高潮期/收束期」）。
        for (stage in listOf("序章期", "发展期", "高潮期", "收束期")) {
            assertFalse("节奏指引不许再提有限模式阶段名：$stage", StoryWritingTechniques.PACING_GUIDANCE.contains(stage))
        }
    }

    // ── 人称规则 hasUserRole 分支 ──

    @Test fun narrative_person_second_branches_on_user_role() {
        val withRole = StoryWritingTechniques.narrativePersonRules("second", hasUserRole = true)
        val noRole = StoryWritingTechniques.narrativePersonRules("second", hasUserRole = false)
        assertTrue(withRole.contains("### 叙事人称：第二人称"))
        assertTrue(withRole.contains("「你」是用户扮演的角色"))
        assertTrue(noRole.contains("「你」不主动说话"))
        assertFalse(noRole.contains("是用户扮演的角色"))
    }

    @Test fun narrative_person_first_and_third() {
        assertTrue(StoryWritingTechniques.narrativePersonRules("first", false).contains("第一人称"))
        assertTrue(StoryWritingTechniques.narrativePersonRules("third", false).contains("第三人称"))
    }

    // ── 写作身份 / 类型技法分发 ──

    @Test fun writer_identity_dispatch_and_default() {
        assertTrue(StoryWritingTechniques.writerIdentity("古风").contains("古风小说名家"))
        assertTrue(StoryWritingTechniques.writerIdentity("网文爽文").contains("节奏即正义"))
        assertTrue(StoryWritingTechniques.writerIdentity("未知文风").contains("经验丰富的小说家"))
    }

    @Test fun genre_techniques_dispatch_and_custom_fallback() {
        assertTrue(StoryWritingTechniques.genreTechniques("言情").startsWith("【言情核心技法】"))
        assertTrue(StoryWritingTechniques.genreTechniques("恐怖").contains("安静→安静→微响→大安静→突发"))
        // T1-3 预裁决翻案（图纸 §7）：非预设题材不再返回 ""，改走兜底段（以【类型核心技法】开头且含题材名）
        val fallback = StoryWritingTechniques.genreTechniques("不存在的类型")
        assertTrue(fallback.startsWith("【类型核心技法】"))
        assertTrue(fallback.contains("不存在的类型"))
    }

    // ── 兜底技法 + 题材短名门限（图纸 §8 chunk 3·T1-2·E9/E10/E11）──

    @Test fun custom_genre_fallback_has_genre_and_three_points_blank_empty() {
        val out = StoryWritingTechniques.customGenreFallback("武侠")
        assertTrue(out.startsWith("【类型核心技法】"))
        assertTrue(out.contains("本故事的类型是「武侠」，这是全书不可动摇的基调："))
        assertTrue(out.contains("都必须服务于「武侠」类型的核心体验"))
        assertTrue(out.contains("不得喧宾夺主把故事漂移成另一种类型"))
        assertTrue(out.contains("本章要自然地把故事拉回来"))
        // blank → ""（保 E9 原行为）
        assertEquals("", StoryWritingTechniques.customGenreFallback(""))
        assertEquals("", StoryWritingTechniques.customGenreFallback("   "))
    }

    @Test fun genre_anchor_label_length_gate_is_12() {
        assertNull(StoryWritingTechniques.genreAnchorLabel(""))       // blank → null（E9）
        assertNull(StoryWritingTechniques.genreAnchorLabel("   "))    // blank → null
        assertEquals("宫", StoryWritingTechniques.genreAnchorLabel("宫"))                       // 1 字 → 嵌入
        assertEquals("一二三四五六七八九十一", StoryWritingTechniques.genreAnchorLabel("一二三四五六七八九十一"))   // 11 字
        assertEquals("一二三四五六七八九十一二", StoryWritingTechniques.genreAnchorLabel("一二三四五六七八九十一二")) // 12 字 → 嵌入（±1 精度）
        assertNull(StoryWritingTechniques.genreAnchorLabel("一二三四五六七八九十一二三"))               // 13 字 → null（E10/E11）
    }

    // ── 文字忌口默认文本 + 与风格原则的正交性（2026-07-30 换版·图纸 §7 T1-1）──
    // 原两例（universal_rules_contain_blacklist_and_no_leading_blank / …_byte_equivalent_recomposition）
    // 随合体常量 `universalWritingRules` 删除而重写：忌口不再拼进写作规则，两者各测各的。

    @Test fun banned_baseline_is_new_slim_version_without_deleted_entries() {
        val banned = StoryWritingTechniques.bannedExpressionsBaseline
        assertTrue(banned.startsWith("### 别写出 AI 味"))   // trimIndent 去掉了首换行
        assertTrue(banned.contains("映入眼帘"))
        assertTrue(banned.contains("嘴角勾起一抹弧度"))
        assertTrue(banned.contains("淡淡的、莫名的、缓缓地、静静地、默默地、似乎、仿佛、某种"))
        assertTrue(banned.endsWith("**同一个动作、比喻或成语，一章里别反复用。**"))

        // v2.1 重构：四条「打偷懒」的条目（泛化细节 / 冗余 / 强行升华 / 平均用力），全部题材中立
        assertTrue(banned.contains("**细节要具体**"))
        assertTrue(banned.contains("**同一个意思别说两遍**"))
        assertTrue(banned.contains("**别强行升华**"))
        assertTrue(banned.contains("**详略要拉开**"))
        // 「留钩子可以」必须在场：连载章末悬念不在打击范围，删了会误伤
        assertTrue(banned.contains("留钩子可以，升华不行"))
        // 三个叙事套路转场词（v2.1 新增，比形容词更露 AI 馅）
        assertTrue(banned.contains("与此同时、就在这时、然而事情并没有那么简单"))

        // 四处「正常汉语被误伤」已删（提案 §3）——加回去即回退调研结论
        assertFalse(banned.contains("不禁、"))
        assertFalse(banned.contains("深吸一口气"))
        assertFalse(banned.contains("不知不觉"))
        assertFalse(banned.contains("恍若隔世"))
        // 句式禁令整组删除（提案 §2 C-1：禁语法结构损害模型推理）
        assertFalse(banned.contains("不是X，而是Y"))
        assertFalse(banned.contains("没有X，没有Y"))
        assertFalse(banned.contains("TA不知道的是"))
        assertFalse(banned.contains("以\"此刻\"或\"仿佛\"开头"))
        assertFalse(banned.contains("禁止句式"))
        // v2.1 删除：叙事策略主张不进默认（show-don't-tell 变体·对意识流/心理小说是紧身衣）
        assertFalse(banned.contains("多用对话和动作推进"))
        assertFalse(banned.contains("少用旁白"))
    }

    @Test fun banned_baseline_matches_locked_contract_text_verbatim() {
        // 锁定文本逐字 pin（图纸 §9-A / 提案 §4 唯一出处）：期望值在此**重新打字**，不引用实现常量
        val expected = """
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
        assertEquals(expected, StoryWritingTechniques.bannedExpressionsBaseline)
    }

    @Test fun writing_principles_are_orthogonal_to_banned_baseline() {
        // J2：风格原则与文字忌口自此正交——风格段里不许再夹带忌口（防合体常量以别的形式复活）
        val principles = StoryWritingTechniques.writingPrinciples
        assertTrue(principles.startsWith("### 写作铁律"))
        assertTrue(principles.endsWith("6. 段落控制：别一段写到底，段落长短跟着内容走"))
        assertFalse(principles.contains("### 别写出 AI 味"))
        assertFalse(principles.contains("映入眼帘"))
        // 反向：叙事策略主张留在风格原则里（v2.1 把它从忌口移回此处的归属证明）
        assertTrue(principles.contains("展示而非告知"))
    }

    @Test fun writing_principles_have_no_conflict_or_dup_with_banned_baseline() {
        // 上下文融洽性整理（2026-07-30·dump 完整创作 prompt 逐段对读）：三条与忌口段打架/重复的已删或放松，
        // 本例是「防加回来」的钉子——加回任一条都会重新与 bannedExpressionsBaseline 冲突。
        val principles = StoryWritingTechniques.writingPrinciples

        // ① 句式禁令：与忌口同源的调研结论（禁语法结构损害推理），两侧都不许有
        assertFalse("否定式描写句式禁令已整条删除", principles.contains("不用否定式描写"))
        assertFalse(principles.contains("没有X，没有Y"))
        // ② 与忌口「同一个意思别说两遍」重复的那条已删（语义由忌口承接）
        assertFalse("行为后不解释情感=忌口那条的重复", principles.contains("信任读者的理解力"))
        assertFalse(principles.contains("行为描写之后不解释情感"))
        // ③ 硬性段落均匀化已放松——它与忌口「详略要拉开」方向相反，且可量化指标会架空忌口
        assertFalse("每段2-4句是硬性均匀化，与忌口详略要拉开打架", principles.contains("每段2-4句"))
        assertTrue(principles.contains("段落长短跟着内容走"))

        // 编号连续 1..6（删两条后重排，不许留空号）
        for (n in 1..6) assertTrue("缺编号 $n", principles.contains("\n$n. "))
        assertFalse("不应再有第 7、8 条", principles.contains("\n7. "))
    }

    // ── 章节要求：首章 / 普通章选择节点 / 人称主语提示 ──
    // 卷二·单模式化：原「结局章不设选择节点」「有限模式共 N 章」两例随 maxChapters 形参退役删除属预期
    // （结局章走 requestedEndingRequirements，不经 chapterRequirements）。

    @Test fun chapter_requirements_first_chapter() {
        val out = StoryWritingTechniques.chapterRequirements(
            chapterNumber = 1, chapterLength = 1500, isFirstChapter = true,
        )
        assertTrue(out.contains("- 这是第一章"))
        assertTrue(out.contains("### 章节结尾选择节点（必须）"))
        assertTrue(out.contains("本章目标字数：1200-1800 字"))
    }

    @Test fun chapter_requirements_choice_subject_hint_by_person() {
        val second = StoryWritingTechniques.chapterRequirements(2, 1500, false, "second", null)
        assertTrue(second.contains("choicePrompt 以「你决定……」"))

        val firstNamed = StoryWritingTechniques.chapterRequirements(2, 1500, false, "first", "林悦")
        assertTrue(firstNamed.contains("choicePrompt 以「林悦决定……」"))

        val firstUnnamed = StoryWritingTechniques.chapterRequirements(2, 1500, false, "first", null)
        assertTrue(firstUnnamed.contains("choicePrompt 以「我决定……」"))

        val thirdUnnamed = StoryWritingTechniques.chapterRequirements(2, 1500, false, "third", null)
        assertTrue(thirdUnnamed.contains("choicePrompt 以「接下来……」"))
    }

    @Test fun chapter_requirements_always_shows_infinite_label() {
        val out = StoryWritingTechniques.chapterRequirements(
            chapterNumber = 5, chapterLength = 1500, isFirstChapter = false,
        )
        assertTrue(out.contains("这是第 5 章（无限连载）"))
        assertFalse(out.contains("共 无限 章"))
    }

    /**
     * 单模式化看门狗：**任何**章号都不再产出有限模式的收束/结局指令，选择节点段恒在
     * （原「共 N 章 / 最后一章 / 距离结局很近 / 结局章不需要选择节点」四块已整体删除）。
     */
    @Test fun chapter_requirements_never_emits_finite_mode_lines() {
        for (chapter in listOf(2, 29, 30, 31, 100)) {
            val out = StoryWritingTechniques.chapterRequirements(
                chapterNumber = chapter, chapterLength = 1500, isFirstChapter = false,
            )
            assertTrue("第 $chapter 章仍须有选择节点段", out.contains("### 章节结尾选择节点（必须）"))
            assertFalse("第 $chapter 章不许出现结局章指令", out.contains("这是最后一章，写一个完整的结局"))
            assertFalse("第 $chapter 章不许出现临近结局收束令", out.contains("距离结局很近，开始收束前文埋下的伏笔"))
            assertFalse("第 $chapter 章不许出现结局章免选择节点", out.contains("这是结局章，不需要设置选择节点"))
            assertFalse("第 $chapter 章不许出现「共 N 章」", out.contains("章（共 "))
        }
    }

    // ── 角色使用纪律（圣经压缩保真优化 C1·图纸 §4.1/§5 E11-E13）──
    // 三条纪律逐字从图纸 §4.1 重新打字（不引用实现常量·PITFALLS §1e 锁定文本双保险）。

    private val disciplineHeading = "### 角色使用纪律"
    private val disciplinePreferExisting =
        "- 优先起用已有角色推动剧情；新鲜感优先从既有角色的新面向和关系变化里挖掘，不为新鲜感而发明新人物"
    private val disciplineOneNewPerChapter =
        "- 确因剧情需要引入新角色时，单章新增的命名角色原则上不超过一位（题材或场面确需时可例外，如群像、宴会戏）；" +
            "大纲、方向提示或用户选择的走向里已安排的登场不受此限"
    private val disciplineNoNameForExtras =
        "- 只出场一次的功能性角色用身份或泛称指代（服务员、司机、老板娘、隔壁阿姨等），不要为其起名字；" +
            "只有会再次出场、或与主要角色产生实质关系的人物才值得命名——名字是向读者许下的「此人重要，请记住」的承诺"

    /** A1/E11：首章还没有「已有角色」可优先起用 → 只发第三条（路人不起名），克制两条不发。 */
    @Test fun chapter_requirements_first_chapter_discipline_has_only_extras_rule() {
        val out = StoryWritingTechniques.chapterRequirements(
            chapterNumber = 1, chapterLength = 1500, isFirstChapter = true,
        )
        assertTrue(out.contains(disciplineHeading))
        assertTrue(out.contains(disciplineNoNameForExtras))
        assertFalse("首章不发「优先起用已有角色」", out.contains(disciplinePreferExisting))
        assertFalse("首章不发「单章新增不超过一位」", out.contains(disciplineOneNewPerChapter))
    }

    /** A2/E12：续章三条全带，且顺序 = 克制两条 → 路人不起名，整段落在选择节点段之前。 */
    @Test fun chapter_requirements_continuation_discipline_has_three_rules_in_order() {
        val out = StoryWritingTechniques.chapterRequirements(
            chapterNumber = 7, chapterLength = 1500, isFirstChapter = false,
        )
        val heading = out.indexOf(disciplineHeading)
        val prefer = out.indexOf(disciplinePreferExisting)
        val oneNew = out.indexOf(disciplineOneNewPerChapter)
        val extras = out.indexOf(disciplineNoNameForExtras)
        val choiceSection = out.indexOf("### 章节结尾选择节点（必须）")
        assertTrue("纪律段头必须在", heading >= 0)
        assertTrue("第一条必须在", prefer >= 0)
        assertTrue("第二条必须在", oneNew >= 0)
        assertTrue("第三条必须在", extras >= 0)
        assertTrue("段头在三条之前", heading < prefer)
        assertTrue("第一条在第二条之前", prefer < oneNew)
        assertTrue("第二条在第三条之前", oneNew < extras)
        assertTrue("整段在选择节点段之前", extras < choiceSection)
    }

    /** A3/E13：结局章走 requestedEndingRequirements，不带纪律段（J8：终章弧已有禁新角色令）。 */
    @Test fun requested_ending_requirements_has_no_character_discipline_section() {
        for (type in listOf("ai", "open", "custom")) {
            val out = StoryWritingTechniques.requestedEndingRequirements(
                endingType = type, endingDetail = "两人重逢", chapterNumber = 40, chapterLength = 1500,
            )
            assertFalse("$type 结局章不许出现纪律段头", out.contains(disciplineHeading))
            assertFalse("$type 结局章不许出现克制条", out.contains(disciplinePreferExisting))
            assertFalse("$type 结局章不许出现路人不起名条", out.contains(disciplineNoNameForExtras))
        }
    }

    // ── 续写衔接：上一章全文（卷一 V2）+ 末 mood 提取 + 去标签 ──

    @Test fun previous_chapter_ending_injects_full_text_and_last_mood() {
        val body = "[mood:warm]开头一段。[mood:tense]后面紧张起来。" + "字".repeat(500)
        val out = StoryWritingTechniques.previousChapterEnding(body, mood = "peaceful")
        assertTrue(out.startsWith("## 上一章全文（新章节必须从其结尾自然衔接，不要复述或改写上一章的内容）"))
        // 末 mood = tense（取最后一个标签，而非传入的 peaceful）
        assertTrue(out.endsWith("上一章结束时的氛围：tense"))
        // 标签已剥离，正文不含 [mood:...]
        assertFalse(out.contains("[mood:"))
        // 全文注入：首句与全部 500 个「字」都在（不再截末 400）
        assertTrue(out.contains("开头一段。"))
        assertTrue(out.contains("后面紧张起来。"))
        assertTrue(out.contains("字".repeat(500)))
    }

    @Test fun previous_chapter_ending_does_not_truncate_long_chapter() { // E5 5000+ 字结局章
        val body = "[mood:dark]序幕。" + "长".repeat(5_000) + "终幕。"
        val out = StoryWritingTechniques.previousChapterEnding(body, mood = "peaceful")
        assertTrue(out.contains("序幕。"))          // 首句在（旧实现会被末 400 字切掉）
        assertTrue(out.contains("长".repeat(5_000))) // 全文无截断
        assertTrue(out.contains("终幕。"))          // 末句在
        assertFalse(out.contains("["))
    }

    @Test fun previous_chapter_ending_falls_back_to_param_mood_when_no_tag() {
        val out = StoryWritingTechniques.previousChapterEnding("没有标签的纯文本", mood = "romantic")
        assertTrue(out.endsWith("上一章结束时的氛围：romantic"))
    }
}
