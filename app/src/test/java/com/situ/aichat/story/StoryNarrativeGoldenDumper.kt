package com.situ.aichat.story

import com.situ.aichat.data.local.dao.StoryChapterSummaryRow
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import java.io.File

/**
 * 「三关路径」黄金串夹具（故事二期卷一 §7 T2-1 / E2）——**基线生成与回归比对共用同一份原料**。
 *
 * 三关 = 本书关主节拍（`sceneBeats = ""`）+ 关场景快照 + 无口味画像 + 上一章未评分 + 三个账本列全空
 * + 角色只有一个非用户角色（不触发区分度）+ 节拍未被用户改过。此路径下卷一的注入面必须**一个字都不加**，
 * 首/续章/弧线三条 prompt 与改前逐字节相同。
 *
 * 基线文件（`app/src/test/resources/golden/story/story_r2_three_off_` 前缀的 first/next/arc 三个 txt）
 * **由改前提交生成**：在卷一 chunk 4（`97b1840c`，注入面尚未落地）的 worktree 里跑 [dumpTo] 落盘后拷回本仓库。
 * 重新生成前先确认「prompt 变化是有意的」——这三条串就是防「顺手改坏默认路径」的最后一道闸。
 *
 * **2026-08-03 重录 first/next 两条**（图纸 `2026-08-03-B序固化与格式块精简.md` §7·有意 prompt 变化）：
 * ① B 序固化——「内容标记 + 输出格式」两段整体从 system 末位移到声音档案段之后（纯位移，行数守恒）；
 * ② 格式块精简——mood/weather/effect/pause 的标记清单、使用原则、示例与禁止事项行删除，
 * 输出格式段三处提及改写（§3.2 逐字终稿）。**arc.txt 不在本次重录之列**（弧线 prompt 不含格式块，
 * 它与实现的固有差 = 物料 B 那一行，由 T2_1 用例摘行后比对）。
 *
 * **2026-08-04 再次重录 first/next 两条**（图纸 `2026-08-04-故事提示词人物表前置.md` §7·有意 prompt 变化）：
 * 名片区（故事设定→角色→区分度→声音档案）整体前置到提示词头部 + 三处接缝空行重配。**纯位移**——
 * 重录时已取证：非空行多重集排序 diff 两文件皆空，首章行数/空行数一字未变，续章空行 −1 = 原「大纲尾空行 +
 * 角色段前导空行」那处存量双空行随搬消失。快评空画像兜底同卷落地但**不触及本夹具**（`userRating = null` :77）。
 * `arc.txt` 同样不在重录之列（弧线大纲的角色段本就在前情之前，人物表前置零碰它——它正是本卷的金丝雀）。
 *
 * **2026-08-05 重录 arc.txt 一条**（图纸 `2026-08-05-弧线大纲导演手记重构.md` §7 T2-8·有意 prompt 变化）：
 * 弧线结构六行 → 状态路标形态（M-A1）、核心原则八条 → 九条（M-A2）、输出格式块换值（M-A3）、
 * 场景菜单常量换值（M-A4）。**存盘口径同时改为 [arcGolden]**——原先 arc.txt 是「物料 B 尚未落地」的
 * 改前基线、由 T2_1 用例在比对时现摘物料 B 行；现在摘行动作收进 [arcGolden] 单源，落盘与比对同走它，
 * 「固有差 = 物料 B 那一行」的口径一字未变。first/next 两条不在 OL1 重录之列（那一 chunk 只动弧线大纲侧）。
 *
 * **2026-08-05 OL3 重录 first/next 两条**（同图纸 §8 OL3·有意 prompt 变化）：大纲段帽子两行（M-B 服从序 +
 * 钳节奏）插在「## 整体大纲」/「## 大纲」标题行与大纲正文之间，两文件各 +2 行、其余零变。三关夹具
 * `freeformDirective = null`，故 M-C1 三明治换文与 M-D 方向账本（默认不传 = null）**不触及本夹具**。
 *
 * **2026-08-05 OL4 再录 first/next 两条**（同图纸 §8 OL4·有意 prompt 变化）：`nextChapterBeats` 统一草稿化——
 * 输出格式块的 beats 示例行与指令行换文（首/续两条各 2 行），续章另换消费段：「## 本章方向提示 + 请聚焦该选项
 * 对应的方向」→「## 本章计划草稿（上一章末预排）」+ M-F3 预设选择服从行（行数守恒）。arc.txt 不含格式块与
 * 章级方向段，本次不动。
 */
internal object StoryNarrativeGoldenFixture {

    /** 三关路径的故事：本书主节拍关闭 + 快照关闭 + 无画像；三个账本列空；节拍未被用户改过。 */
    val story: StoryEntity = StoryEntity(
        id = "golden-1",
        title = "黄金串",
        genre = "言情",
        writingStyle = "古风",
        chapterLengthPreference = 1500,
        narrativePerson = StoryNarrativePerson.SECOND,
        chatInfluenceWeight = StoryChatInfluenceWeight.MEDIUM,
        worldSetting = "民国上海",
        plotDirection = "重逢",
        storyOutline = "第一弧：重逢与试探。本弧共 8 章。",
        storySummary = "前八章精华",
        currentArc = "试探期",
        characterStates = "女主（戒备）",
        openThreads = "那封没寄出的信",
        storyBible = "圣经正文",
        lastCompressedAtChapter = 3,
        arcHistory = "第1–8章·重逢",
        pendingChapterBeats = "先在雨里碰面",
        pendingBeatsUserEdited = false,
        currentArcStartChapter = 5,
        cachedLatestChapterNumber = 9,
        cachedChapterCount = 9,
        customPromptsJson = CustomStoryPrompts.encode(
            CustomStoryPrompts(sceneBeats = "", tasteProfile = "", sceneSnapshotEnabled = false),
        ),
    )

    val roles: List<StoryCharacterRoleEntity> = listOf(
        StoryCharacterRoleEntity(roleName = "你", roleType = StoryRoleType.PROTAGONIST, isUserRole = true),
        StoryCharacterRoleEntity(roleName = "沈青", roleType = StoryRoleType.SUPPORTING, characterId = "cid"),
    )

    val characterData: Map<String, StoryCharacterSectionData> = mapOf(
        "cid" to StoryCharacterSectionData(
            gender = "女", age = 24, occupation = "记者",
            appearanceDescription = "短发", personalityDescription = "锋利", backstory = "旧上海长大",
        ),
    )

    private val summaries = listOf(
        StoryChapterSummaryRow(chapterNumber = 8, chapterSummary = "第八章摘要"),
        StoryChapterSummaryRow(chapterNumber = 9, chapterSummary = "第九章摘要"),
    )

    private val latestChapter = StoryChapterEntity(
        chapterNumber = 9, content = "上一章正文", mood = "melancholy",
        hasChoice = true, userChoice = "留下来", userRating = null,
    )

    fun firstPrompt(): String = StoryGenerationPromptBuilder.buildFirstChapterCreationPrompt(
        story = story,
        roles = roles,
        characterData = characterData,
        voiceProfiles = "沈青：短句、不解释",
        protagonistSpectrum = null,
        protagonistQuality = null,
        worldInfoSection = "世界书激活条目",
        globalBannedExpressions = null,
    )

    fun nextPrompt(): String = StoryGenerationPromptBuilder.buildNextChapterCreationPrompt(
        story = story,
        chapterNumber = 10,
        roles = roles,
        characterData = characterData,
        voiceProfiles = "沈青：短句、不解释",
        chatInfluence = "最近聊到旧信",
        latestChapter = latestChapter,
        chapterSummaries = summaries,
        worldInfoSection = "世界书激活条目",
        freeformDirective = null,
        globalBannedExpressions = null,
    )

    fun arcPrompt(): String = buildArcOutlinePrompt(story, roles, characterData)

    /**
     * 弧线 prompt 的**黄金串口径**（落盘与比对共用单源）：摘掉物料 B（弧级场景排布）那一行。
     * 物料 B 是恒注入、不受本书三态影响的常量行，也是「三关路径」与实现之间唯一的固有差；
     * 摘行放在此处一处实现，T2_1 弧线用例只负责断言「物料 B 必须在」+「摘掉后与基线逐字节相等」。
     */
    fun arcGolden(): String = arcPrompt().replace("\n- " + StoryCraftSections.ARC_SCENE_ARRANGEMENT_DIRECTIVE, "")

    /** 把三条 prompt 落盘到 [dir]（基线生成用；比对时不调用）。arc 走 [arcGolden] 口径。 */
    fun dumpTo(dir: String) {
        File(dir).mkdirs()
        File(dir, "first.txt").writeText(firstPrompt())
        File(dir, "next.txt").writeText(nextPrompt())
        File(dir, "arc.txt").writeText(arcGolden())
    }
}
