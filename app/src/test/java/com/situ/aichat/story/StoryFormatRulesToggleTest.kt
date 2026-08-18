package com.situ.aichat.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章末选项开关的装配面 T1（图纸二 D3·验收 T1-1；2026-08-03「格式块精简」后**只剩这一个维度**——
 * 本书「沉浸氛围标记」开关连同最小标记集版整链退役，故原 E4 一组用例随之删除）。
 *
 * **E1 = 本组最重的行为锁**：开关没动过（默认 true）时，`chapterRequirements` /
 * `appendStoryCreationOutputFormat` 的输出必须与**当期终稿逐字节相同**（输出格式段的三处精简改动
 * = 2026-08-03 图纸 §3.2 逐字终稿）。期望串在本文件里逐行重打（不是从实现里 copy 引用），
 * 任何一次「顺手改措辞」都会在这里当场红。
 *
 * 关闭态另钉 M1/M2a/M2b 三段锁定文本与 M5 反向哨兵（哨兵句的注入源全库唯一，见图纸二 §4-M5）。
 */
class StoryFormatRulesToggleTest {

    private fun outputFormat(choicesEnabled: Boolean): String {
        val lines = mutableListOf<String>()
        appendStoryCreationOutputFormat(lines, choicesEnabled)
        return lines.joinToString("\n")
    }

    // ── E1：默认路径逐字节回归钉 ──

    @Test
    fun E1_章节要求默认输出_与加开关前逐字节相同() {
        val expected = """
## 章节要求
- 本章目标字数：1200-1800 字。请合理安排叙事节奏，在这个范围内找到一个自然的段落/场景结尾收束，不要在句子中间断开
- 这是第 3 章（无限连载）
- 至少包含一个推动主线的事件和一个加深角色关系的场景

### 角色使用纪律
- 优先起用已有角色推动剧情；新鲜感优先从既有角色的新面向和关系变化里挖掘，不为新鲜感而发明新人物
- 确因剧情需要引入新角色时，单章新增的命名角色原则上不超过一位（题材或场面确需时可例外，如群像、宴会戏）；大纲、方向提示或用户选择的走向里已安排的登场不受此限
- 只出场一次的功能性角色用身份或泛称指代（服务员、司机、老板娘、隔壁阿姨等），不要为其起名字；只有会再次出场、或与主要角色产生实质关系的人物才值得命名——名字是向读者许下的「此人重要，请记住」的承诺

### 章节结尾选择节点（必须）
每章结尾必须设置一个选择节点（hasChoice 必须为 true），给出 2-3 个选项。
choicePrompt 以「你决定……」或「你注意到……你决定……」开头

**选择质量标准：**
- 每个选项都合理，没有明显的"正确答案"
- 选项之间方向明显不同，不只是措辞差异
- 选择对后续剧情有实际影响
- 选择自然融入章节结尾的情境

**选择类型（轮换使用，避免连续两章用同一类型）：**
1. 行动选择：下一步做什么（去某处/找某人/调查某事）
2. 对话选择：对TA说什么（直接质问/试探/假装不知道）
3. 情感选择：内心如何反应（愤怒反击/冷静分析/选择原谅）
4. 策略选择：打算怎么做（公开对抗/暗中调查/寻求帮助）
5. 价值权衡：在两个重要目标间取舍（保护自己 vs 帮助朋友）
6. 氛围选择：不影响主线但建立角色认同（选择回忆/关注哪个细节）

**禁止的选择设计：**
- 被动选择（"被抓走"不是选择）
- 无意义选择（"走左边还是走右边"没有信息差）
- 有明显最优解的选择
        """.trimIndent()

        assertEquals(
            expected,
            StoryWritingTechniques.chapterRequirements(
                chapterNumber = 3,
                chapterLength = 1500,
                isFirstChapter = false,
            ),
        )
    }

    @Test
    fun E1_输出格式默认输出_与图纸终稿逐字节相同() {
        val expected = """

## 输出格式
请直接输出故事内容，不需要 JSON 格式。按以下结构组织：

1. 直接写故事正文（可包含 [scene:xxx]、[text:style]...[/text]、[chapter_end] 标记）
2. 故事正文写完后，另起一行输出分隔符 ---METADATA---
3. 分隔符之后，按以下格式逐行输出元数据（每行一个字段，字段名和值用冒号分隔）：

示例：
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

### 选择分支（最重要，必须严格遵守）
- 除非是最终结局章（isEnding: true），每章结尾必须提供选择分支
- 必须输出以下四个字段，缺一不可：
  hasChoice: true
  choicePrompt: 选择提示文（引导用户做选择的一句话）
  choiceA: 第一个选项
  choiceB: 第二个选项
- choiceC 为可选的第三个选项，建议提供
- nextChapterBeats 必须输出：下一章的计划草稿，单行 2-4 句——顺着本章结尾与用户的走向，写下一章打算写什么、有无重点场景；朝大纲中最近的一个尚未实现的路标推进
- 只有结局章（isEnding: true）才允许 hasChoice: false

### 伏笔与角色状态（保持长篇连续性）
- openThreads 必须继承上一章清单中仍未解决的条目（措辞可精简），再追加本章新增伏笔；只有确已解决的条目才可移除
- characterStates 覆盖本章出场的每个角色，每条 10-25 字；角色较多时总长可放宽到 150 字

### 关系叙事字段（叙事连续性）
intimacyUpdates: [里程碑]或[近况]开头的 0–3 条本章人物关系新进展，分号分隔；多位主要角色时条目以角色名开头；无新进展写 无
sceneEndState: 一行章末场景状态，格式「地点｜在场人物及状态要点」；章末已离开该场景写 无
sceneTag: 一行本章重点场景标签，格式「场景·地点·要点」；本章无重点场景写 无

### 格式规则
- 故事正文部分自由书写，专注于创作质量，不要考虑格式问题
- 标记（[scene:xxx]、[text:style] 等）直接嵌入正文中
- ---METADATA--- 分隔符必须独占一行，前后各空一行
- 元数据部分每行格式：字段名: 值（冒号后有一个空格）
- 不要输出 JSON，不要输出 markdown 代码块，不要添加任何解释性文字
        """.trimIndent()

        assertEquals(expected, outputFormat(choicesEnabled = true))
    }

    @Test
    fun E1_首章默认输出_选择节点段照常在() {
        val first = StoryWritingTechniques.chapterRequirements(
            chapterNumber = 1,
            chapterLength = 1500,
            isFirstChapter = true,
        )
        assertTrue(first.contains("### 章节结尾选择节点（必须）"))
        assertTrue(first.contains(CHOICE_SENTINEL))
        assertTrue("首章特有行仍在", first.contains("- 这是第一章"))
    }

    @Test
    fun E1_人称分支在开启态照常生效() {
        fun hint(person: String, userRoleName: String?) = StoryWritingTechniques.chapterRequirements(
            chapterNumber = 2,
            chapterLength = 1500,
            isFirstChapter = false,
            narrativePerson = person,
            userRoleName = userRoleName,
        )
        assertTrue(hint("first", null).contains("choicePrompt 以「我决定……」或类似句式开头"))
        assertTrue(hint("first", "阿舟").contains("choicePrompt 以「阿舟决定……」或类似句式开头"))
        assertTrue(hint("third", null).contains("choicePrompt 以「接下来……」开头"))
        assertTrue(hint("third", "阿舟").contains("choicePrompt 以「阿舟会……」开头"))
        assertTrue(hint("second", null).contains("choicePrompt 以「你决定……」或「你注意到……你决定……」开头"))
    }

    // ── E2：关选项（M1 / M2a / M2b / M5 反向哨兵）──

    @Test
    fun E2_关选项_章节要求换成M1紧凑段() {
        val closed = StoryWritingTechniques.chapterRequirements(
            chapterNumber = 3,
            chapterLength = 1500,
            isFirstChapter = false,
            choicesEnabled = false,
        )
        val expectedTail = """
### 章节结尾（本书已关闭章末选择）
- hasChoice 必须为 false；不要输出 choicePrompt、choiceA、choiceB、choiceC、choiceD 字段；nextChapterBeats 照常输出下一章计划草稿
- 结尾要让人急着看下一章，优先收在重钩子上：悬念悬停在将揭晓未揭晓的边缘、关键时刻被打断、新的变数刚露头、转折的前夜；重点场景刚写完的章可以用余韵收，但余韵里也要埋下文的引子。不向读者提问、不列选项
        """.trimIndent()

        assertTrue("M1 段逐字出现", closed.endsWith(expectedTail))
        assertFalse("M5 哨兵：选择节点强制句必须消失", closed.contains(CHOICE_SENTINEL))
        assertFalse(closed.contains("**选择质量标准：**"))
        assertFalse(closed.contains("**选择类型（轮换使用，避免连续两章用同一类型）：**"))
        assertTrue("字数与角色纪律段照常保留", closed.contains("### 角色使用纪律"))
        assertTrue(closed.contains("- 本章目标字数：1200-1800 字。请合理安排叙事节奏，在这个范围内找到一个自然的段落/场景结尾收束，不要在句子中间断开"))
    }

    @Test
    fun E2_关选项_人称分支整块跳过_不留半句提示() {
        val closed = StoryWritingTechniques.chapterRequirements(
            chapterNumber = 3,
            chapterLength = 1500,
            isFirstChapter = false,
            narrativePerson = "first",
            userRoleName = "阿舟",
            choicesEnabled = false,
        )
        assertFalse(closed.contains("choicePrompt 以"))
        assertFalse(closed.contains("阿舟"))
    }

    @Test
    fun E2_关选项_示例块与选择分支块换成M2a与M2b() {
        val closed = outputFormat(choicesEnabled = false)

        // 故事二期卷一在 hasChoice 与 isEnding 之间插了三个可选字段（两分支同位置同文本），故不再相邻。
        assertTrue("hasChoice 示例值改 false", closed.contains("\nhasChoice: false\n"))
        assertTrue("isEnding 仍是示例块最后一行", closed.contains("\nisEnding: false"))
        assertFalse(closed.contains("hasChoice: true"))
        listOf("choicePrompt:", "choiceA:", "choiceB:", "choiceC:").forEach {
            assertFalse("选择字段 $it 不该出现在关闭态示例里", closed.contains(it))
        }
        // 2026-08-05 M-E4 翻案：beats 统一草稿化后，关闭态示例也必须给出 nextChapterBeats 行（与开启态逐字同款）
        assertTrue(
            "关闭态示例补 beats 行",
            closed.contains("\nnextChapterBeats: 两人约好周末去美术馆；途中偶遇林晓雨引出旧事，感情线小幅升温；无重点场景，为下一个路标铺垫\n"),
        )

        val expectedM2b = """
### 选择分支（本书已关闭）
- hasChoice 固定输出 false；禁止输出 choicePrompt、choiceA-D 字段
- nextChapterBeats 仍必须输出：下一章的计划草稿，单行 2-4 句——顺着本章结尾与用户的走向，写下一章打算写什么、有无重点场景；朝大纲中最近的一个尚未实现的路标推进
        """.trimIndent()
        assertTrue("M2b 逐字出现", closed.contains(expectedM2b))
        assertFalse(closed.contains("### 选择分支（最重要，必须严格遵守）"))
    }

    @Test
    fun E2_关选项_示例正文与METADATA前七字段与开启态逐字相同() {
        // M2a 的口径：只动尾部选择字段，正文与前七个元数据字段一个字都不许变。
        fun exampleBlock(text: String) = text.substringAfter("示例：\n").substringBefore("\n\n### ")
        val open = exampleBlock(outputFormat(choicesEnabled = true))
        val closed = exampleBlock(outputFormat(choicesEnabled = false))
        val head = open.substringBefore("hasChoice:")
        assertEquals("示例正文 + 前七字段逐字相同", head, closed.substringBefore("hasChoice:"))
    }

    @Test
    fun E2_关选项_其余三段零变化() {
        val open = outputFormat(choicesEnabled = true)
        val closed = outputFormat(choicesEnabled = false)
        listOf("### 伏笔与角色状态（保持长篇连续性）", "### 格式规则").forEach { header ->
            assertEquals(
                "关选项不许动 $header 段",
                open.substringAfter(header),
                closed.substringAfter(header),
            )
        }
    }

    private companion object {
        /** M5 反向哨兵：全库唯一注入源 = StoryWritingTechniques 的选择节点段。 */
        const val CHOICE_SENTINEL = "每章结尾必须设置一个选择节点"
    }
}
