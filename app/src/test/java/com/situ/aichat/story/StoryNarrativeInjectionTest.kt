package com.situ.aichat.story

import com.situ.aichat.data.local.dao.StoryChapterSummaryRow
import com.situ.aichat.data.local.entity.StoryChapterEntity
import com.situ.aichat.data.local.entity.StoryCharacterRoleEntity
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 故事二期卷一**注入面**的装配测试（图纸 §7 T2-1 / T2-2 / T2-3·E2/E8/E9/E10/E11）。
 *
 * 三层：
 * 1. **T2-1 黄金串**：三关路径（本书关主节拍 + 关快照 + 无画像 + 未评分 + 账本空）下，首/续章/弧线三条 prompt
 *    与**改前提交的真值**逐字节相等——基线是从 `97b1840c` 的 worktree 跑出来冻在 resources 里的
 *    （见 [StoryNarrativeGoldenFixture]），不是照抄当前实现。
 * 2. **T2-2 装配矩阵**：每个新段的出现条件、锚点相对顺序、各恰出现一次、世界观段位置不变
 *    （原「A/B 两序」维度随 2026-08-03 B 序固化退役，段序不再是变量）。
 * 3. **T2-3 分派与门**：beats 四分支、快评三档与范围外、区分度 0/1/2。
 */
class StoryNarrativeInjectionTest {

    private val B = StoryGenerationPromptBuilder
    private val C = StoryCraftSections

    private fun golden(name: String): String =
        javaClass.getResourceAsStream("/golden/story/story_r2_three_off_$name.txt")
            ?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("缺少黄金串资源 story_r2_three_off_$name.txt")

    // ── 通用夹具（默认路径：本书什么都没设 → 主节拍走出厂默认）──

    private fun story(
        prompts: CustomStoryPrompts? = null,
        intimacyLedger: String? = null,
        sceneState: String? = null,
        sceneLedger: String? = null,
        pendingChapterBeats: String? = "先在雨里碰面",
        pendingBeatsUserEdited: Boolean = false,
    ) = StoryEntity(
        id = "s1",
        title = "书",
        genre = "言情",
        writingStyle = "古风",
        worldSetting = "民国上海",
        storyOutline = "第一弧：重逢与试探。本弧共 8 章。",
        storySummary = "前情",
        openThreads = "那封信",
        pendingChapterBeats = pendingChapterBeats,
        pendingBeatsUserEdited = pendingBeatsUserEdited,
        intimacyLedger = intimacyLedger,
        sceneState = sceneState,
        sceneLedger = sceneLedger,
        customPromptsJson = prompts?.let { CustomStoryPrompts.encode(it) },
        cachedLatestChapterNumber = 9,
    )

    private fun roles(nonUserCount: Int = 1) = buildList {
        add(StoryCharacterRoleEntity(roleName = "你", roleType = StoryRoleType.PROTAGONIST, isUserRole = true))
        repeat(nonUserCount) { add(StoryCharacterRoleEntity(roleName = "女${it + 1}", roleType = StoryRoleType.SUPPORTING)) }
    }

    private fun chapter(userRating: Int? = null, userChoice: String? = "留下来") = StoryChapterEntity(
        chapterNumber = 9, content = "上一章正文", mood = "peaceful",
        hasChoice = true, userChoice = userChoice, userRating = userRating,
    )

    private fun first(
        story: StoryEntity = story(),
        roleList: List<StoryCharacterRoleEntity> = roles(),
        globalSceneBeats: String? = null,
        globalTasteProfile: String? = null,
    ) = B.buildFirstChapterCreationPrompt(
        story = story, roles = roleList, characterData = emptyMap(), voiceProfiles = "沈青：短句",
        protagonistSpectrum = null, protagonistQuality = null, worldInfoSection = "世界书激活条目",
        globalSceneBeats = globalSceneBeats, globalTasteProfile = globalTasteProfile,
    )

    private fun next(
        story: StoryEntity = story(),
        roleList: List<StoryCharacterRoleEntity> = roles(),
        latestChapter: StoryChapterEntity? = chapter(),
        freeformDirective: String? = null,
        globalSceneBeats: String? = null,
        globalTasteProfile: String? = null,
    ) = B.buildNextChapterCreationPrompt(
        story = story, chapterNumber = 10, roles = roleList, characterData = emptyMap(),
        voiceProfiles = "沈青：短句", chatInfluence = "最近聊到旧信", latestChapter = latestChapter,
        chapterSummaries = listOf(StoryChapterSummaryRow(chapterNumber = 9, chapterSummary = "第九章摘要")),
        worldInfoSection = "世界书激活条目", freeformDirective = freeformDirective,
        globalSceneBeats = globalSceneBeats, globalTasteProfile = globalTasteProfile,
        pendingBeatsUserEdited = story.pendingBeatsUserEdited,
    )

    // ══ T2-1：三关路径黄金串（E2）══

    @Test fun T2_1_三关路径_首章与改前逐字节相等() {
        assertEquals(golden("first"), StoryNarrativeGoldenFixture.firstPrompt())
    }

    @Test fun T2_1_三关路径_续章与改前逐字节相等() {
        assertEquals(golden("next"), StoryNarrativeGoldenFixture.nextPrompt())
    }

    @Test fun T2_1_三关路径_弧线大纲与改前逐字节相等() {
        // 弧线侧的物料 B 是**恒注入**（弧级排布不受本书三态影响·提案 §3.2），台账空 → 台账段不出现。
        // 故这条的口径是「只多出物料 B 那一行，其余逐字节相等」：把那行摘掉后必须与改前基线完全相同。
        val current = StoryNarrativeGoldenFixture.arcPrompt()
        assertTrue("物料 B 必须在弧线 prompt 里", current.contains("\n- " + C.ARC_SCENE_ARRANGEMENT_DIRECTIVE))
        assertEquals(
            "摘掉物料 B 后，弧线 prompt 与基线逐字节相等",
            golden("arc"),
            StoryNarrativeGoldenFixture.arcGolden(),
        )
    }

    // ══ T2-2：装配矩阵 ══

    @Test fun 默认路径_主节拍出厂默认注入_画像不注入() {
        val prompt = first()
        assertTrue("默认就该有主节拍（D-2）", prompt.contains(C.SCENE_BEATS_DEFAULT))
        assertFalse("画像没有出厂默认", prompt.contains(C.TASTE_PROFILE_HEADER))
    }

    @Test fun 画像在主节拍之前_两者都注入时顺序固定() {
        val prompt = first(globalTasteProfile = "爱看强对抗", globalSceneBeats = "全局节拍")
        val profileIdx = prompt.indexOf(C.TASTE_PROFILE_HEADER)
        val beatsIdx = prompt.indexOf("全局节拍")
        assertTrue("画像段必须在主节拍段之前", profileIdx in 0 until beatsIdx)
    }

    @Test fun E11_空书首章_无关系史无状态无台账段() {
        val prompt = first()
        assertFalse(prompt.contains(C.INTIMACY_HISTORY_HEADER))
        assertFalse(prompt.contains(C.SCENE_STATE_HEADER))
        assertFalse("台账空 → 主节拍段尾没有提醒行", prompt.contains("提醒：上一场重点场景是"))
    }

    @Test fun 续章_名片区前置_关系史在伏笔后大纲前_状态段在选择块之前() {
        val prompt = next(
            story(intimacyLedger = "${StoryLedgers.MILESTONE_HEADER}\n第1章·初吻", sceneState = "客厅｜相拥"),
        )
        val intimacyIdx = prompt.indexOf(C.INTIMACY_HISTORY_HEADER)
        val setupIdx = prompt.indexOf("## 故事设定")
        val threadsIdx = prompt.indexOf("## 待回收的伏笔/悬念")
        val outlineIdx = prompt.indexOf("## 大纲（参考方向，不要生硬复述）")
        val endingIdx = prompt.indexOf("## 上一章全文")
        // 人物表前置（2026-08-04）：故事设定属名片区，已搬到上一章全文之前；关系史留在原地（口径一：状态≠名片）
        assertTrue("故事设定必须在上一章全文之前", setupIdx in 0 until endingIdx)
        assertTrue("关系史必须在伏笔段之后", threadsIdx in 0 until intimacyIdx)
        assertTrue("关系史必须在大纲块之前", intimacyIdx in 0 until outlineIdx)

        val stateIdx = prompt.indexOf(C.SCENE_STATE_HEADER)
        val choiceIdx = prompt.indexOf("## 上一章的用户选择")
        assertTrue("状态段必须在上一章结尾之后、选择块之前", endingIdx in 0 until stateIdx && stateIdx < choiceIdx)
    }

    @Test fun 快照开关关掉_状态段整段消失() {
        val prompt = next(story(prompts = CustomStoryPrompts(sceneSnapshotEnabled = false), sceneState = "客厅｜相拥"))
        assertFalse(prompt.contains(C.SCENE_STATE_HEADER))
        assertFalse(prompt.contains("客厅｜相拥"))
    }

    @Test fun 台账提醒寄生主节拍段尾_主节拍关掉则一起消失() {
        val ledger = "第3章·雨夜·车里\n第7章·浴室·热气"
        val withBeats = next(story(sceneLedger = ledger))
        assertTrue(withBeats.contains(C.sceneLedgerReminder("第7章·浴室·热气")))
        assertTrue(
            "提醒必须紧跟在主节拍段尾",
            withBeats.contains(C.SCENE_BEATS_DEFAULT + "\n" + C.sceneLedgerReminder("第7章·浴室·热气")),
        )

        val beatsOff = next(story(prompts = CustomStoryPrompts(sceneBeats = ""), sceneLedger = ledger))
        assertFalse("主节拍关了 → 提醒不另起孤段（J7）", beatsOff.contains("提醒：上一场重点场景是"))
    }

    @Test fun 各新段都恰出现一次_世界观段位置不变() {
        val s = story(
            intimacyLedger = "${StoryLedgers.MILESTONE_HEADER}\n第1章·初吻",
            sceneState = "客厅｜相拥",
            sceneLedger = "第7章·浴室·热气",
        )
        val prompt = next(s, roleList = roles(nonUserCount = 2), latestChapter = chapter(userRating = 2))
        listOf(
            C.SCENE_BEATS_DEFAULT,
            C.INTIMACY_HISTORY_HEADER,
            C.SCENE_STATE_HEADER,
            C.CAST_DISTINCTION_DIRECTIVE,
            C.READER_FEEDBACK_HEADER,
            C.sceneLedgerReminder("第7章·浴室·热气"),
        ).forEach { needle ->
            assertTrue("应出现：${needle.take(16)}", prompt.contains(needle))
            assertEquals(
                "必须恰出现一次：${needle.take(16)}",
                prompt.indexOf(needle), prompt.lastIndexOf(needle),
            )
        }
        // 世界书四锚点之一：世界观段仍在「前情回顾」之前（新段一个都不许插进去）
        val worldIdx = prompt.indexOf("## 世界观设定")
        val recapIdx = prompt.indexOf("## 前情回顾")
        assertTrue("世界观段位置不变", worldIdx in 0 until recapIdx)
    }

    // ══ T2-5：名片区前置（2026-08-04 人物表前置·图纸 §3.1/§3.2/§3.3）══

    @Test fun E6_续章无上一章时_名片区完整输出且其后直接方向段() {
        val prompt = next(roleList = roles(nonUserCount = 2), latestChapter = null)
        val setupIdx = prompt.indexOf("## 故事设定")
        val roleIdx = prompt.indexOf("## 角色")
        val distinctionIdx = prompt.indexOf(C.CAST_DISTINCTION_DIRECTIVE)
        val voiceIdx = prompt.indexOf("## 角色声音档案")
        val directionIdx = prompt.indexOf(C.DRAFT_HEADER)
        // 名片区内序恒定：设定 → 角色 → 区分度 → 声音档案
        assertTrue("设定 < 角色", setupIdx in 0 until roleIdx)
        assertTrue("角色 < 区分度", roleIdx in 0 until distinctionIdx)
        assertTrue("区分度 < 声音档案", distinctionIdx in 0 until voiceIdx)
        // 上一章缺席时，名片区之后直接是方向段（快评/上一章全文/场景状态三块整体缺席）
        assertTrue("声音档案 < 本章计划草稿", voiceIdx in 0 until directionIdx)
        assertFalse(prompt.contains("## 上一章全文"))
        assertFalse(prompt.contains(C.READER_FEEDBACK_HEADER))
    }

    @Test fun 名片区在上一章全文与大纲之前_续章() {
        val prompt = next(roleList = roles(nonUserCount = 2))
        val voiceIdx = prompt.indexOf("## 角色声音档案")
        assertTrue("声音档案 < 上一章全文", voiceIdx in 0 until prompt.indexOf("## 上一章全文"))
        assertTrue("声音档案 < 前情回顾", voiceIdx < prompt.indexOf("## 前情回顾"))
        assertTrue("声音档案 < 大纲", voiceIdx < prompt.indexOf("## 大纲（参考方向，不要生硬复述）"))
        // B 序绝对位置不变：格式块仍在大纲块之后、聊天互动段之前
        val outlineIdx = prompt.indexOf("## 大纲（参考方向，不要生硬复述）")
        val markupIdx = prompt.indexOf("## 内容标记（严格遵守以下规则）")
        assertTrue("大纲 < 内容标记", outlineIdx in 0 until markupIdx)
        assertTrue("内容标记 < 聊天互动数据", markupIdx < prompt.indexOf("## 聊天互动数据"))
    }

    @Test fun 接缝不变式_可缺块任意组合都不出现双空行() {
        // §3.3：名片区每个可缺块自带尾随空行、后继段头行直出 → 任意两相邻段之间恰一个空行。
        // 覆盖 outline 空 / 非用户角色 <2（区分度缺席）/ voiceProfiles 空 / worldInfo 空 / latestChapter null。
        var n = 0
        for (s in listOf(story(), story().copy(storyOutline = null))) {
            for (roleCount in listOf(0, 2)) for (voice in listOf("", "沈青：短句")) for (world in listOf(null, "条目")) {
                val tag = "outline=${s.storyOutline != null} 角色=$roleCount voice=${voice.isNotEmpty()} world=${world != null}"
                val prompts = mutableListOf(
                    "首章 $tag" to B.buildFirstChapterCreationPrompt(
                        story = s, roles = roles(roleCount), characterData = emptyMap(), voiceProfiles = voice,
                        protagonistSpectrum = null, protagonistQuality = null, worldInfoSection = world,
                    ),
                )
                for (latest in listOf(null, chapter())) prompts += "续章 $tag latest=${latest != null}" to
                    B.buildNextChapterCreationPrompt(
                        story = s, chapterNumber = 10, roles = roles(roleCount), characterData = emptyMap(),
                        voiceProfiles = voice, chatInfluence = "最近聊到旧信", latestChapter = latest,
                        chapterSummaries = emptyList(), worldInfoSection = world,
                    )
                for ((label, p) in prompts) {
                    n++
                    assertFalse("[$label] 不许出现连续两个空行", p.contains("\n\n\n"))
                    assertFalse("[$label] 首尾不许留空行", p.startsWith("\n") || p.endsWith("\n"))
                }
            }
        }
        assertEquals("组合数", 48, n)
    }

    // ══ T2-3：分派与门 ══

    /**
     * **T2-6 五分支矩阵**（图纸 2026-08-05 §3.3·E1/E2/E4/E5/E6/E12/E13）——原 E9 四分支的翻案+扩展：
     * beats 草稿化后，AI 预排路的段标题从「## 本章方向提示」换成「## 本章计划草稿（上一章末预排）」，
     * 尾行改二选一（预设点选 → M-F3 选择优先；自然发展/未选 → M-F2 按草稿推进）。
     */
    @Test fun T2_6_草稿段五分支() {
        // ① 用户改过 → 物料 C（走向正交并存·逐字现状）
        val edited = story(pendingChapterBeats = "先冷场再爆发", pendingBeatsUserEdited = true)
        val userEdited = next(edited)
        assertTrue(userEdited.contains(C.USER_BEATS_HEADER + "\n用户亲自指定了本章的展开节拍：\n「先冷场再爆发」"))
        assertFalse(userEdited.contains(C.DRAFT_HEADER))
        val both = next(edited, freeformDirective = "去码头找他")
        assertTrue(both.contains("## 用户亲笔指定的剧情走向（本章的任务书·最高优先）"))
        assertTrue("并存时必须给分工说明（物料 N）", both.contains(C.USER_BEATS_WITH_DIRECTIVE_NOTE))
        assertFalse(both.contains(C.DRAFT_HEADER))
        // 用户改过但清空 → 一个方向段都没有（留白也是指定·E12）
        val cleared = next(story(pendingChapterBeats = "", pendingBeatsUserEdited = true))
        assertFalse(cleared.contains(C.USER_BEATS_HEADER))
        assertFalse(cleared.contains(C.DRAFT_HEADER))

        // ② 有亲笔走向 → 草稿段整段跳过（J3 原语义·E2）
        val withDirective = next(freeformDirective = "去码头找他")
        assertFalse(withDirective.contains(C.DRAFT_HEADER))
        assertTrue(withDirective.contains("## 用户亲笔指定的剧情走向（本章的任务书·最高优先）"))

        // ③ 「跳过选择，直接进入结局」哨兵 → 草稿段整段跳过（E5）
        val skipping = next(latestChapter = chapter(userChoice = StoryChoiceClassifier.SKIP_FOR_ENDING_CHOICE))
        assertFalse(skipping.contains(C.DRAFT_HEADER))

        // ④ 预设点选 → M-F3 服从句（选择优先、草稿降参考·E1）
        val preset = next()
        assertTrue(preset.contains(C.DRAFT_HEADER + "\n先在雨里碰面\n" + C.draftPresetChoiceLine("留下来")))
        assertFalse(preset.contains(C.USER_BEATS_HEADER))
        assertFalse("旧聚焦句随选项方向表退役", preset.contains("请聚焦该选项对应的方向"))

        // ⑤ 「让故事自然发展」哨兵 与 追更未选 → M-F2 按草稿推进（E4/E6）
        // M-F2 字面量钉（R1 复核补·图纸 §4 M-F 锁定文本「重新打字」纪律——此前全树无一处字面量锁该行）
        assertEquals(
            "用户未另指方向：本章按此草稿推进；若草稿与上方的用户意志或已写正文冲突，以后者为准。",
            C.DRAFT_FOLLOW_LINE,
        )
        for (choice in listOf(StoryChoiceClassifier.NATURAL_FLOW_CHOICE, null, "")) {
            val flow = next(latestChapter = chapter(userChoice = choice))
            assertTrue(
                "choice=$choice 应走 M-F2",
                flow.contains(C.DRAFT_HEADER + "\n先在雨里碰面\n" + C.DRAFT_FOLLOW_LINE),
            )
        }

        // beats 为空（重写清空/METADATA 缺失）→ 草稿段整段缺席（E12）
        assertFalse(next(story(pendingChapterBeats = null)).contains(C.DRAFT_HEADER))
        assertFalse(next(story(pendingChapterBeats = "")).contains(C.DRAFT_HEADER))
    }

    @Test fun 用户改过且无走向_不带物料N() {
        val prompt = next(story(pendingChapterBeats = "先冷场再爆发", pendingBeatsUserEdited = true))
        assertTrue(prompt.contains(C.USER_BEATS_HEADER))
        assertFalse("没有并存的走向就不该出现分工说明", prompt.contains(C.USER_BEATS_WITH_DIRECTIVE_NOTE))
    }

    @Test fun E8_快评三档措辞与范围外不注入() {
        assertTrue(next(latestChapter = chapter(userRating = 3)).contains("读者对上一章的评价：非常满意。保持这个方向与水准。"))
        assertTrue(
            next(latestChapter = chapter(userRating = 2))
                .contains("读者对上一章的评价：一般。本章请在场面展开或剧情推进上换个思路、增强张力，不要重复上一章的写法。"),
        )
        // 1 分两态（2026-08-04）：默认夹具两层画像皆空 → 兜底行（不点名并不存在的画像段）
        val noProfile = next(latestChapter = chapter(userRating = 1))
        assertTrue(
            noProfile.contains("读者对上一章的评价：不满意。本章必须做出明显调整：换掉上一章的场景类型或推进方式；检查是否节奏拖沓或描写重复。"),
        )
        assertFalse("无画像时快评不许点名画像段", noProfile.contains("对照「读者口味画像」检查"))
        // 全局有画像 → 1 分原措辞逐字不变（画像段此时真的在 prompt 里）
        val withProfile = next(latestChapter = chapter(userRating = 1), globalTasteProfile = "爱看强对抗")
        assertTrue(
            withProfile.contains("读者对上一章的评价：不满意。本章必须做出明显调整：换掉上一章的场景类型或推进方式；对照「读者口味画像」检查是否偏离口味、节奏拖沓或描写重复。"),
        )
        assertTrue("原措辞点名的画像段必须真的存在", withProfile.contains(C.TASTE_PROFILE_HEADER))
        // 未评 / 畸形值（备份导入）→ 静默不注入，不 clamp 不抛
        assertFalse(next(latestChapter = chapter(userRating = null)).contains(C.READER_FEEDBACK_HEADER))
        assertFalse(next(latestChapter = chapter(userRating = 4)).contains(C.READER_FEEDBACK_HEADER))
        assertFalse(next(latestChapter = chapter(userRating = 0)).contains(C.READER_FEEDBACK_HEADER))
        assertFalse(next(latestChapter = chapter(userRating = -1)).contains(C.READER_FEEDBACK_HEADER))
    }

    @Test fun E10_区分度门_非用户角色0与1不注入_2才注入() {
        assertFalse(first(roleList = roles(nonUserCount = 0)).contains(C.CAST_DISTINCTION_DIRECTIVE))
        assertFalse(first(roleList = roles(nonUserCount = 1)).contains(C.CAST_DISTINCTION_DIRECTIVE))
        assertTrue(first(roleList = roles(nonUserCount = 2)).contains(C.CAST_DISTINCTION_DIRECTIVE))
        assertTrue(next(roleList = roles(nonUserCount = 3)).contains(C.CAST_DISTINCTION_DIRECTIVE))
        // 只有用户角色时不算数（两个用户角色也不注入）
        val twoUserRoles = listOf(
            StoryCharacterRoleEntity(roleName = "你", isUserRole = true),
            StoryCharacterRoleEntity(roleName = "另一个我", isUserRole = true),
        )
        assertFalse(first(roleList = twoUserRoles).contains(C.CAST_DISTINCTION_DIRECTIVE))
    }

    @Test fun 私下反差_跟在该角色行尾_三处共用() {
        val withPersona = listOf(
            StoryCharacterRoleEntity(roleName = "你", roleType = StoryRoleType.PROTAGONIST, isUserRole = true),
            StoryCharacterRoleEntity(
                roleName = "沈青", roleType = StoryRoleType.SUPPORTING,
                roleDescription = "冷淡记者", intimatePersona = "私下极黏人",
            ),
        )
        val expectedRow = "- 沈青（配角）：冷淡记者；私下反差：私下极黏人"
        assertTrue(first(roleList = withPersona).contains(expectedRow))
        assertTrue(next(roleList = withPersona).contains(expectedRow))
        assertTrue("弧线大纲共用同一角色段", buildArcOutlinePrompt(story(), withPersona, emptyMap()).contains(expectedRow))
        // 没填反差的角色行逐字节不变
        assertTrue(first(roleList = withPersona).contains("- 你（主角）：这是用户扮演的角色，请以第二人称「你」来描写行动和感受。"))
    }

    // ══ 弧线侧 ══

    @Test fun 弧线_物料B恒注入_普通弧与终章弧共用() {
        val normal = buildArcOutlinePrompt(story(), roles(), emptyMap())
        val finale = buildFinaleArcOutlinePrompt(
            story().copy(finaleEndingType = StoryEndingType.AI), roles(), emptyMap(),
        )
        listOf(normal, finale).forEach { prompt ->
            assertTrue(prompt.contains(C.ARC_SCENE_ARRANGEMENT_DIRECTIVE))
            assertEquals(
                "恰一次", prompt.indexOf(C.ARC_SCENE_ARRANGEMENT_DIRECTIVE),
                prompt.lastIndexOf(C.ARC_SCENE_ARRANGEMENT_DIRECTIVE),
            )
        }
    }

    @Test fun 弧线_台账段在弧线简史之后_上一弧概述之前() {
        val s = story(sceneLedger = "第3章·雨夜·车里").copy(
            arcHistory = "第1–8章·重逢",
            currentArc = "试探期",
        )
        val prompt = buildArcOutlinePrompt(s, roles(), emptyMap())
        val historyIdx = prompt.indexOf("## 已写过的弧线（一行一弧·避免重复同类冲突与桥段）")
        val ledgerIdx = prompt.indexOf(C.ARC_SCENE_LEDGER_HEADER)
        val prevArcIdx = prompt.indexOf("## 上一个弧线概述")
        assertTrue("台账段夹在弧线简史与上一弧概述之间", historyIdx in 0 until ledgerIdx && ledgerIdx < prevArcIdx)
        assertTrue(prompt.contains("第3章·雨夜·车里"))
    }

    @Test fun 弧线_台账空则整段不出现() {
        assertFalse(buildArcOutlinePrompt(story(), roles(), emptyMap()).contains(C.ARC_SCENE_LEDGER_HEADER))
    }
}
