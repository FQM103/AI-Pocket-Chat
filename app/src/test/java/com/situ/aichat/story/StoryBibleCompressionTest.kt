package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圣经结构化压缩纯逻辑 T1（长篇稳定性 L1·契约 FABLE5_STORY_LONGFORM_STABILITY_PROPOSAL §3/§8）。
 * 断言从契约独立反推：切分归属/触发双条件/水位线语义/回滚交互（最新章恒可回滚）。
 */
class StoryBibleCompressionTest {

    /** 造一段 [from], [to] 章的逐章流水账，每章两行（角色+伏笔），单行 ~90 字保证跨 12 章必超字数阈值。 */
    private fun flowingBible(from: Int, to: Int): String =
        (from..to).joinToString("\n") { n ->
            "第${n}章角色：主角（状态$n·${"细节".repeat(40)}）\n第${n}章伏笔：悬念$n（${"线索".repeat(40)}）"
        }

    // ── chapterNumberOf ──

    @Test
    fun `逐章行前缀识别章号_角色与伏笔两式`() {
        assertEquals(7, StoryBibleCompression.chapterNumberOf("第7章角色：主角（平静）"))
        assertEquals(12, StoryBibleCompression.chapterNumberOf("第12章伏笔：神秘信件"))
    }

    @Test
    fun `非逐章行不识别_含正文中段提及章号`() {
        assertNull(StoryBibleCompression.chapterNumberOf("【角色档案】"))
        assertNull(StoryBibleCompression.chapterNumberOf("- 林悦｜女主｜最后出场：第8章"))
        assertNull(StoryBibleCompression.chapterNumberOf("用户手写的备注 第3章角色：不算（非行首）"))
        assertNull(StoryBibleCompression.chapterNumberOf(""))
    }

    // ── shouldCompress 触发双条件 ──

    @Test
    fun `空圣经不触发`() {
        assertFalse(StoryBibleCompression.shouldCompress(null, null, 20))
        assertFalse(StoryBibleCompression.shouldCompress("", null, 20))
    }

    @Test
    fun `间隔不足12章不触发_字数再多也不发`() {
        val bible = flowingBible(1, 11)
        assertFalse(StoryBibleCompression.shouldCompress(bible, null, 11))
    }

    @Test
    fun `间隔够但尾段字数不超2000不触发`() {
        // 12 章但每章只有一条短行 → 字数远低于阈值
        val sparse = (1..12).joinToString("\n") { "第${it}章伏笔：短" }
        assertFalse(StoryBibleCompression.shouldCompress(sparse, null, 12))
    }

    @Test
    fun `间隔与字数双满足即触发_水位线null按0算`() {
        assertTrue(StoryBibleCompression.shouldCompress(flowingBible(1, 12), null, 12))
    }

    @Test
    fun `已压缩过_只按水位线之后的尾段判定`() {
        // 水位线 7：尾段只有 8-13 章 → 距上次 13-7=6 < 12 不触发
        val bible = "【角色档案】\n- 主角｜…\n\n" + flowingBible(8, 13)
        assertFalse(StoryBibleCompression.shouldCompress(bible, 7, 13))
        // 距上次 19-7=12 且尾段（8-19 章行）超字数 → 触发
        val longer = "【角色档案】\n- 主角｜…\n\n" + flowingBible(8, 19)
        assertTrue(StoryBibleCompression.shouldCompress(longer, 7, 19))
    }

    // ── split 三段归属 ──

    @Test
    fun `切分_基底含档案与手编_压缩段与保留段按章号划界`() {
        val bible = listOf(
            "【角色档案】",
            "- 林悦｜女主｜最后出场：第5章",
            "用户手写备注",
            "第6章角色：主角（低落）",
            "第9章伏笔：旧照片",
            "第12章角色：林悦（回归）",
        ).joinToString("\n")
        // 水位线 5，压缩至第 9 章（假设 latest=14）
        val split = StoryBibleCompression.split(bible, 5, 9)
        assertEquals("【角色档案】\n- 林悦｜女主｜最后出场：第5章\n用户手写备注", split.base)
        assertEquals(listOf("第6章角色：主角（低落）", "第9章伏笔：旧照片"), split.compressLines)
        assertEquals(listOf("第12章角色：林悦（回归）"), split.keepLines)
    }

    @Test
    fun `切分_水位线以下的残留逐章行归基底一并送整理`() {
        val bible = "第3章角色：主角（初见）\n第8章角色：主角（热恋）"
        val split = StoryBibleCompression.split(bible, 5, 9)
        assertEquals("第3章角色：主角（初见）", split.base)
        assertEquals(listOf("第8章角色：主角（热恋）"), split.compressLines)
        assertTrue(split.keepLines.isEmpty())
    }

    @Test
    fun `压缩覆盖章号_最新章往前留5章`() {
        assertEquals(9, StoryBibleCompression.compressThroughChapter(14))
    }

    // ── assemble + 回滚交互 ──

    @Test
    fun `组装_档案加尾段以空行分隔_无尾段仅档案`() {
        assertEquals(
            "【角色档案】\n- 主角｜…\n\n第13章角色：主角（成长）",
            StoryBibleCompression.assembleCompressedBible(" 【角色档案】\n- 主角｜… \n", listOf("第13章角色：主角（成长）")),
        )
        assertEquals("【角色档案】", StoryBibleCompression.assembleCompressedBible("【角色档案】", emptyList()))
    }

    @Test
    fun `压缩后重写回滚仍有效_最新章行在尾段被删_档案零损`() {
        // latest=14，压缩至 9 → 尾段留 10-14 章行；用户重写第 14 章 → rollbackBible(14)
        val compressed = StoryBibleCompression.assembleCompressedBible(
            "【角色档案】\n- 主角｜…｜最后出场：第9章",
            listOf("第13章角色：主角（沉思）", "第14章角色：主角（决裂）", "第14章伏笔：断掉的项链"),
        )
        val rolledBack = StoryGenerationPolicy.rollbackBible(compressed, 14)
        assertEquals("【角色档案】\n- 主角｜…｜最后出场：第9章\n\n第13章角色：主角（沉思）", rolledBack)
    }

    // ── 压缩 prompt ──

    @Test
    fun `压缩prompt_含结构模板_截至章号_禁逐章行格式`() {
        val prompt = StoryBibleCompression.buildBibleCompressionPrompt("旧档案", "第6章角色：主角（低落）", 9, "言情")
        // 保真优化预裁决（图纸 §7）：两段制【角色档案】→ 三段制【主要角色】/【次要角色】/【已淡出】
        assertTrue(prompt.contains("【主要角色】"))
        assertTrue(prompt.contains("【次要角色】"))
        assertTrue(prompt.contains("【伏笔账本】"))
        assertTrue(prompt.contains("截至第9章"))
        assertTrue(prompt.contains("旧档案"))
        assertTrue(prompt.contains("一个都不许丢"))
        // E18 红线：输出约束行逐字仍在（题材锚只进输入侧头部）
        assertTrue(prompt.contains("不要输出以「第N章角色：」或「第N章伏笔：」开头的行"))
        // 图纸 L5 题材锚行（含题材名·T2-4）
        assertTrue(prompt.contains("这是一部「言情」类型的故事。整理档案时，保留与该类型核心体验相关的关系状态与情感线索。"))
    }

    @Test
    fun `压缩prompt_首次整理无已有记录时给占位说明`() {
        val prompt = StoryBibleCompression.buildBibleCompressionPrompt("", "第1章角色：主角", 9, "宫斗")
        assertTrue(prompt.contains("（首次整理，无已有记录）"))
        assertTrue(prompt.contains("这是一部「宫斗」类型的故事。"))
    }

    // ── T1-B 新版 prompt：分级/配额/只搬不改/淡出模板（圣经压缩保真优化·图纸 §4.2）──
    // 期望值从图纸 §4.2 重新打字，数值再与实现常量互钉（双保险 pin·PITFALLS §1e）。

    @Test
    fun `压缩prompt_三段制与伏笔账本段头齐全_淡出阈值来自弧线上限`() {
        val prompt = StoryBibleCompression.buildBibleCompressionPrompt("旧档案", "第6章角色：主角", 9, "言情")
        assertTrue(prompt.contains("1. 输出分为以下几段，严格按此格式（没有内容的段整段省略）："))
        assertTrue(prompt.contains("【主要角色】（反复出场、牵动主线、或与主角有持续关系的角色）"))
        assertTrue(prompt.contains("- 名字｜身份要点（身份/外貌关键特征/与主角关系）｜当前状态｜关键往事一句｜最后出场：第N章"))
        assertTrue(prompt.contains("【次要角色】（出过场但戏份轻的角色）"))
        assertTrue(prompt.contains("- 名字｜一句话身份｜最后出场：第N章"))
        // 淡出阈值 = 15 章，且必须单源自弧线上限（写死字面量会被后半句抓住）
        assertTrue(prompt.contains("【已淡出】（最后出场距截至章已超过15章、且非主要的角色；可多行，每行以「已淡出：」开头）"))
        assertEquals(15, StoryBibleCompression.FADED_ABSENCE_CHAPTERS)
        assertEquals(StoryArcPlanning.ARC_LENGTH_MAX, StoryBibleCompression.FADED_ABSENCE_CHAPTERS)
    }

    @Test
    fun `压缩prompt_淡出模板行含未再出场锚点_已了结行合并伏笔`() {
        val prompt = StoryBibleCompression.buildBibleCompressionPrompt("旧档案", "第6章角色：主角", 9, "言情")
        // E17：淡出的人也要能被久别回归令看见 → 模板强制标注「第M章后未再出场」
        assertTrue(prompt.contains("已淡出：名字（一句话身份·第M章后未再出场）、名字（一句话身份·第M章后未再出场）"))
        assertTrue(prompt.contains("- 伏笔内容（埋设：第N章｜状态：未回收）"))
        assertTrue(prompt.contains("已了结：伏笔短语（第A章→第B章）、伏笔短语（第A章→第B章）"))
        assertTrue(prompt.contains("5. 已回收的伏笔一律并入「已了结：」行，只留短语并标注（埋设章→回收章），不逐条展开；未回收的伏笔逐条保留"))
        // 两处行前缀单源（模板与提取器共用同一常量）
        assertEquals("已淡出：", StoryBibleCompression.FADED_LINE_PREFIX)
        assertEquals("已了结：", StoryBibleCompression.RESOLVED_LINE_PREFIX)
        assertTrue(prompt.contains(StoryBibleCompression.FADED_LINE_PREFIX))
        assertTrue(prompt.contains(StoryBibleCompression.RESOLVED_LINE_PREFIX))
    }

    @Test
    fun `压缩prompt_只搬不改条含迁移豁免`() {
        val prompt = StoryBibleCompression.buildBibleCompressionPrompt("旧档案", "第6章角色：主角", 9, "言情")
        assertTrue(
            prompt.contains(
                "3. 在「新增逐章记录」里没有出现的角色 = 本轮没有新戏份：这类角色的档案行必须从「已有档案记录」原样照抄，" +
                    "禁止改写措辞、禁止精简；只有有新戏份的角色才允许更新档案行。",
            ),
        )
        // J11：段落迁移豁免必须在同一条里，否则「降入已淡出」会被照抄条判成违规
        assertTrue(prompt.contains("角色在段落之间迁移（升入主要／降入已淡出）时按目标段格式重写，不受本条限制"))
    }

    @Test
    fun `压缩prompt_人头配额与挤压顺序_旧死天花板已退役`() {
        val prompt = StoryBibleCompression.buildBibleCompressionPrompt("旧档案", "第6章角色：主角", 9, "言情")
        assertTrue(
            prompt.contains(
                "4. 篇幅配额：主要角色每人不超过80字，次要角色每人一行不超过25字，全文控制在2500字以内；" +
                    "逼近上限时先压缩「已淡出」和「次要角色」，「主要角色」的配额最后才动",
            ),
        )
        // 三数字与常量互钉
        assertEquals(80, StoryBibleCompression.ARCHIVE_MAIN_CHAR_QUOTA)
        assertEquals(25, StoryBibleCompression.ARCHIVE_MINOR_CHAR_QUOTA)
        assertEquals(2_500, StoryBibleCompression.ARCHIVE_TOTAL_CHAR_BUDGET)
        assertEquals(5_000, StoryBibleCompression.ARCHIVE_REJECT_CHAR_LIMIT)
        // 旧的「1200 字 / 8 个角色放宽到 1500」死天花板不许还在
        assertFalse(prompt.contains("1200 字"))
        assertFalse(prompt.contains("1500 字"))
    }

    // ── T1-C 提取器六例（图纸 §3.3 算法 / §5 E1-E4/E8/E9·全程 fail-open）──

    @Test
    fun `提取器_空基底与纯逐章行基底均得空册`() { // E1
        assertTrue(StoryBibleCompression.extractArchiveNames("").isEmpty())
        assertTrue(StoryBibleCompression.extractArchiveNames("   \n\n  ").isEmpty())
        assertTrue(StoryBibleCompression.extractArchiveNames(flowingBible(1, 3)).isEmpty())
    }

    @Test
    fun `提取器_旧格式角色档案段照提_三段制新格式亦提`() { // E2
        val legacy = listOf(
            "【角色档案】",
            "- 林悦｜女主｜平静｜最后出场：第5章",
            "- 陈默｜男主｜焦虑｜最后出场：第7章",
        ).joinToString("\n")
        assertEquals(setOf("林悦", "陈默"), StoryBibleCompression.extractArchiveNames(legacy))

        val tiered = listOf(
            "【主要角色】（反复出场、牵动主线、或与主角有持续关系的角色）",
            "- 林悦｜女主｜平静｜幼时失怙｜最后出场：第20章",
            "【次要角色】（出过场但戏份轻的角色）",
            "- 周老师｜班主任｜最后出场：第12章",
        ).joinToString("\n")
        assertEquals(setOf("林悦", "周老师"), StoryBibleCompression.extractArchiveNames(tiered))
    }

    @Test
    fun `提取器_无段头的手编笔记行不入册`() { // E3
        val handwritten = listOf(
            "我自己记的：",
            "- 苏晴｜其实是卧底｜别忘了",
            "随手一句备注",
        ).joinToString("\n")
        assertTrue(StoryBibleCompression.extractArchiveNames(handwritten).isEmpty())
    }

    @Test
    fun `提取器_伏笔账本条目行绝不误捕成名字`() { // E4
        val bible = listOf(
            "【主要角色】",
            "- 林悦｜女主｜最后出场：第20章",
            "【伏笔账本】",
            "- 神秘信件（埋设：第3章｜状态：未回收）",
            "- 母亲的旧照片（埋设：第7章｜状态：未回收）",
        ).joinToString("\n")
        assertEquals(setOf("林悦"), StoryBibleCompression.extractArchiveNames(bible))
    }

    @Test
    fun `提取器_淡出行带或不带短横前缀_多行多人均可提`() { // E8
        val bible = listOf(
            "【已淡出】",
            "已淡出：陈默（前男友·第4章后未再出场）、苏晴（同事·第6章后未再出场）",
            "- 已淡出：周老师（班主任·第9章后未再出场）",
        ).joinToString("\n")
        assertEquals(setOf("陈默", "苏晴", "周老师"), StoryBibleCompression.extractArchiveNames(bible))
    }

    @Test
    fun `提取器_单字名与超长候选一律跳过`() { // E9·fail-open 防误杀
        val bible = listOf(
            "【主要角色】",
            "- 甲｜路人｜最后出场：第2章",
            "- 一二三四五六七八九十一二｜十二字名｜最后出场：第3章",
            "- 一二三四五六七八九十一二三｜十三字候选｜最后出场：第4章",
            "【已淡出】",
            "已淡出：乙（路人·第1章后未再出场）、一二三四五六七八九十一二三（超长·第1章后未再出场）",
        ).joinToString("\n")
        assertEquals(setOf("一二三四五六七八九十一二"), StoryBibleCompression.extractArchiveNames(bible))
    }

    // ── T1-E 全链推演：40 章连载跑两轮压缩（split → prompt → 假整理产物 → assemble → 回滚）──

    @Test
    fun `全链推演_40章两轮压缩_尾段恒留5章_水位线递进_回滚后档案零损`() {
        val genre = "言情"
        // ── 第一轮：第 20 章落库后触发（水位线 null=0，20-0=20 ≥ 12 且尾段超 2000 字）──
        val bible1 = flowingBible(1, 20)
        assertTrue(StoryBibleCompression.shouldCompress(bible1, null, 20))
        val through1 = StoryBibleCompression.compressThroughChapter(20)
        assertEquals(15, through1)  // 20 − 留 5 章
        val split1 = StoryBibleCompression.split(bible1, null, through1)
        assertEquals("", split1.base)                    // 首轮无档案基底
        assertEquals(30, split1.compressLines.size)      // 1-15 章 ×2 行
        assertEquals(10, split1.keepLines.size)          // 16-20 章 ×2 行 = 留 5 章
        assertTrue(StoryBibleCompression.extractArchiveNames(split1.base).isEmpty())

        val prompt1 = StoryBibleCompression.buildBibleCompressionPrompt(
            split1.base, split1.compressLines.joinToString("\n"), through1, genre,
        )
        assertTrue(prompt1.contains("（首次整理，无已有记录）"))
        assertTrue(prompt1.contains("截至第15章"))
        assertTrue(prompt1.contains("【主要角色】"))

        // 模型按模板产出的合规档案（含淡出名单与伏笔账本）
        val archive1 = listOf(
            "【主要角色】",
            "- 林晚｜女主·画廊主理人｜与陈默冷战｜幼时被寄养在外婆家｜最后出场：第15章",
            "- 陈默｜男主·摄影师｜准备摊牌｜父亲早逝｜最后出场：第14章",
            "【已淡出】",
            "${StoryBibleCompression.FADED_LINE_PREFIX}苏晴（前同事·第3章后未再出场）",
            "【伏笔账本】",
            "- 神秘信件（埋设：第3章｜状态：未回收）",
        ).joinToString("\n")
        val bible2 = StoryBibleCompression.assembleCompressedBible(archive1, split1.keepLines)
        assertTrue(bible2.startsWith("【主要角色】"))
        assertTrue(bible2.contains("第16章角色："))
        assertTrue(bible2.contains("第20章伏笔："))
        assertFalse("已压缩范围的逐章行不得残留", bible2.contains("第15章角色："))

        // ── 第二轮：连载到第 40 章（水位线 = 15）──
        val bible3 = bible2 + "\n" + flowingBible(21, 40)
        assertTrue(StoryBibleCompression.shouldCompress(bible3, through1, 40))
        val through2 = StoryBibleCompression.compressThroughChapter(40)
        assertEquals(35, through2)
        val split2 = StoryBibleCompression.split(bible3, through1, through2)
        // 基底 = 上一轮档案（逐章行全部按章号分流走），点名册读得回三个记名/淡出角色
        assertTrue(split2.base.startsWith("【主要角色】"))
        assertFalse("档案基底里不该混进逐章行", split2.base.contains("第16章角色："))
        assertEquals(setOf("林晚", "陈默", "苏晴"), StoryBibleCompression.extractArchiveNames(split2.base))
        assertEquals(40, split2.compressLines.size)      // 16-35 章 ×2 行
        assertEquals(10, split2.keepLines.size)          // 36-40 章 ×2 行 = 恒留 5 章

        val prompt2 = StoryBibleCompression.buildBibleCompressionPrompt(
            split2.base, split2.compressLines.joinToString("\n"), through2, genre,
        )
        assertTrue("旧档案整份进「已有档案记录」段", prompt2.contains(archive1))
        assertTrue(prompt2.contains("截至第35章"))

        // 二轮产物：林晚有新戏份更新，陈默无新戏份 → 条 3 要求原样照抄（此处模拟模型遵守）
        val archive2 = listOf(
            "【主要角色】",
            "- 林晚｜女主·画廊主理人｜重新开画展｜幼时被寄养在外婆家｜最后出场：第35章",
            "- 陈默｜男主·摄影师｜准备摊牌｜父亲早逝｜最后出场：第14章",
            "【已淡出】",
            "${StoryBibleCompression.FADED_LINE_PREFIX}苏晴（前同事·第3章后未再出场）",
            "【伏笔账本】",
            "- 神秘信件（埋设：第3章｜状态：未回收）",
            "${StoryBibleCompression.RESOLVED_LINE_PREFIX}母亲的旧照片（第7章→第30章）",
        ).joinToString("\n")
        // 点名对账（C3 闸的判据）：旧基底三人在新产物里一个不落
        assertTrue(StoryBibleCompression.extractArchiveNames(split2.base).all { archive2.contains(it) })
        assertTrue("产物须在拒收线内", archive2.length <= StoryBibleCompression.ARCHIVE_REJECT_CHAR_LIMIT)
        val bible4 = StoryBibleCompression.assembleCompressedBible(archive2, split2.keepLines)

        // ── 回滚交互：用户重写第 40 章 → 只删第 40 章两行，档案与其余尾段零损 ──
        val rolledBack = StoryGenerationPolicy.rollbackBible(bible4, 40)
        assertTrue("档案整段零损", rolledBack!!.startsWith(archive2))
        assertFalse(rolledBack.contains("第40章角色："))
        assertFalse(rolledBack.contains("第40章伏笔："))
        assertTrue(rolledBack.contains("第39章伏笔："))
        assertEquals(8, rolledBack.lines().count { StoryBibleCompression.chapterNumberOf(it) != null })
    }

    // ── T1-D 单源锁：prompt 模板产出的合规档案，提取器必须整份读得回来 ──

    @Test
    fun `单源锁_按prompt模板拼的合规档案_提取器读回全部记名与淡出角色`() {
        // 段头逐字取自 prompt 模板本身（模板改格式而提取器不跟 → 本例立刻红）
        val template = StoryBibleCompression.buildBibleCompressionPrompt("旧档案", "第6章角色：主角", 20, "言情")
        val mainHeading = template.lines().first { it.startsWith("【主要角色】") }
        val minorHeading = template.lines().first { it.startsWith("【次要角色】") }
        val fadedHeading = template.lines().first { it.startsWith("【已淡出】") }
        val archive = listOf(
            mainHeading,
            "- 林悦｜女主·长发｜与陈默冷战中｜幼时被寄养在外婆家｜最后出场：第20章",
            "- 陈默｜男主·摄影师｜准备摊牌｜父亲早逝｜最后出场：第19章",
            minorHeading,
            "- 周老师｜班主任｜最后出场：第12章",
            fadedHeading,
            "${StoryBibleCompression.FADED_LINE_PREFIX}苏晴（前同事·第3章后未再出场）、赵医生（急诊医生·第4章后未再出场）",
            "【伏笔账本】",
            "- 神秘信件（埋设：第3章｜状态：未回收）",
            "${StoryBibleCompression.RESOLVED_LINE_PREFIX}母亲的旧照片（第7章→第18章）",
        ).joinToString("\n")

        assertEquals(
            setOf("林悦", "陈默", "周老师", "苏晴", "赵医生"),
            StoryBibleCompression.extractArchiveNames(archive),
        )
    }
}
