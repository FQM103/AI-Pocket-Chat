package com.situ.aichat.story

import com.situ.aichat.R
import com.situ.aichat.data.local.entity.StoryEntity
import com.situ.aichat.data.model.CustomStoryPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段注册表 T1（卷二 §7 T1-1）：15 字段登记完备 + kind 归类 + **值标推导全矩阵**。
 *
 * 期望值从图纸 §3.2 的推导表**独立反推**（不看实现分支）：
 *
 * | 字段族 | 本书 | 全局 | 值标 |
 * |---|---|---|---|
 * | 场面节拍 | null | null | 出厂默认 |
 * | 场面节拍 | null | `""` | 全局已关 |
 * | 场面节拍 | null | 文本 | 跟随全局 |
 * | 场面节拍 | `""`/空白 | 任意 | 已关闭 |
 * | 场面节拍 | 文本 | 任意 | 已自定义 |
 * | 口味画像 | null | null 或 `""` | 未设置（**无出厂默认**） |
 * | 文字忌口 | `""`/空白 | 任意 | 本书已关（措辞与节拍有别） |
 * | 二态字段 | 空 | — | 出厂默认；文本 → 已自定义 |
 * | 节奏偏好 | 空 | — | 未设置；文本 → 首行截断回显 |
 */
class StoryEditableFieldsTest {

    private fun story(prompts: CustomStoryPrompts? = null) = StoryEntity(
        id = "s1",
        genre = "言情",
        writingStyle = "轻松幽默",
        customPromptsJson = prompts?.let { CustomStoryPrompts.encode(it) },
    )

    private val noGlobals = StoryGlobalCraftValues()

    // ── 注册完备性（机器点数，不靠人眼数）──

    @Test fun 注册表恰十五项_且路由键互不重复() {
        assertEquals(16, StoryEditableField.entries.size)
        assertEquals(16, StoryEditableField.entries.map { it.key }.toSet().size)
        assertEquals(16, StoryEditableField.entries.map { it.titleRes }.toSet().size)
    }

    @Test fun kind归类_三态三项_二态四项_档案九项() {
        val byKind = StoryEditableField.entries.groupBy { it.kind }
        assertEquals(
            setOf(
                StoryEditableField.SCENE_BEATS,
                StoryEditableField.TASTE_PROFILE,
                StoryEditableField.BANNED_OVERRIDE,
            ),
            byKind[StoryFieldKind.CRAFT_TRI]!!.toSet(),
        )
        assertEquals(
            setOf(
                StoryEditableField.WRITER_IDENTITY,
                StoryEditableField.GENRE_TECHNIQUES,
                StoryEditableField.WRITING_RULES,
                StoryEditableField.PACING,
            ),
            byKind[StoryFieldKind.CRAFT_PLAIN]!!.toSet(),
        )
        // R1 复核 D-9 修复：+CURRENT_ARC（卡①第二行·旧设定屏「当前剧情弧线」编辑口回归）
        assertEquals(9, byKind[StoryFieldKind.ARCHIVE]!!.size)
    }

    @Test fun 字数上限只有节奏偏好有_且等于三百() {
        val limited = StoryEditableField.entries.filter { it.maxChars != null }
        assertEquals(listOf(StoryEditableField.PACING), limited)
        assertEquals(300, StoryEditableField.PACING.maxChars)
    }

    @Test fun 预设chips只给写作身份_且三档全文逐字锁定() {
        assertEquals(
            listOf(StoryEditableField.WRITER_IDENTITY),
            StoryEditableField.entries.filter { it.hasPresetChips },
        )
        assertEquals(3, PersonaPresets.all.size)
        // 逐字锁（提案 §5.5 物料 M）——测试里重新打字，绝不引用实现常量拼装
        assertEquals(
            "你是一位叙事明快的连载小说资深作者。笔下场景具体直接、细节扎实，不绕弯子、不故弄玄虚；" +
                "用词干净利落，靠细节与人物的反应堆出真实感，而不是形容词堆砌。情节推进张弛有度，始终保持代入感。",
            PersonaPresets.DIRECT,
        )
        assertEquals(
            "你是一位文笔细腻的作家。写关键场景重氛围与情绪流动，多用暗示、留白与感官通感，点到即止；" +
                "靠张力与心理描写让读者意会，画面朦胧但温度十足。",
            PersonaPresets.LITERARY,
        )
        assertEquals(
            "你是一位老练的连载小说作者。场景与剧情并重：该细写时细写写透，该收笔时干脆利落转回主线；" +
                "关键场景具体不含糊，但始终服务于人物与关系的推进，让读者既满足又追剧情。",
            PersonaPresets.MIXED,
        )
        assertEquals(listOf(PersonaPresets.DIRECT, PersonaPresets.LITERARY, PersonaPresets.MIXED), PersonaPresets.all.map { it.second })
    }

    @Test fun 路由键反查_全部命中_全局哨兵键不撞车() {
        for (field in StoryEditableField.entries) {
            assertEquals(field, StoryEditableField.fromKey(field.key))
        }
        assertNull(StoryEditableField.fromKey(StoryEditableField.GLOBAL_BANNED_KEY))
        assertNull(StoryEditableField.fromKey(null))
        assertNull(StoryEditableField.fromKey("不存在的字段"))
    }

    // ── 卷四 T1-1：三个全局哨兵键 ──

    @Test fun 全局哨兵恰三枚_互不相同_且fromKey对三者均为null() {
        // 集合恰 3 = 三个字面量互不相同（重复会被 setOf 折叠）
        assertEquals(3, StoryEditableField.GLOBAL_KEYS.size)
        assertEquals(
            setOf("globalBannedExpressions", "globalSceneBeats", "globalTasteProfile"),
            StoryEditableField.GLOBAL_KEYS,
        )
        for (key in StoryEditableField.GLOBAL_KEYS) {
            assertNull("哨兵键 $key 不许撞上任何本书字段", StoryEditableField.fromKey(key))
        }
        // 反向：本书字段的 key 一个都不在哨兵集合里
        assertTrue(StoryEditableField.entries.none { it.key in StoryEditableField.GLOBAL_KEYS })
    }

    @Test fun 全局值标_有出厂默认的项_三分支为出厂默认与已关闭与已自定义() {
        assertEquals(R.string.story_hub_value_default, globalValueLabel(null, hasFactoryDefault = true))
        assertEquals(R.string.story_hub_value_off, globalValueLabel("", hasFactoryDefault = true))
        assertEquals(R.string.story_hub_value_off, globalValueLabel("  \n ", hasFactoryDefault = true))
        assertEquals(R.string.story_hub_value_custom, globalValueLabel("我的忌口", hasFactoryDefault = true))
    }

    @Test fun 全局值标_无出厂默认的项_只有已设置与未设置两种说法() {
        assertEquals(R.string.story_global_value_unset, globalValueLabel(null, hasFactoryDefault = false))
        assertEquals(
            "画像清空与从未设置对用户是同一件事——都没东西注入",
            R.string.story_global_value_unset,
            globalValueLabel("", hasFactoryDefault = false),
        )
        assertEquals(R.string.story_global_value_unset, globalValueLabel(" ", hasFactoryDefault = false))
        assertEquals(R.string.story_global_value_set, globalValueLabel("偏好爽文节奏", hasFactoryDefault = false))
    }

    @Test fun 出厂默认只有五个字段有_档案族一律无() {
        val withDefault = StoryEditableField.entries.filter { it.factoryDefault(story()) != null }
        assertEquals(
            setOf(
                StoryEditableField.WRITER_IDENTITY,
                StoryEditableField.GENRE_TECHNIQUES,
                StoryEditableField.WRITING_RULES,
                StoryEditableField.BANNED_OVERRIDE,
                StoryEditableField.SCENE_BEATS,
            ),
            withDefault.toSet(),
        )
        assertTrue(StoryEditableField.entries.filter { it.kind == StoryFieldKind.ARCHIVE }.all { it.factoryDefault(story()) == null })
    }

    // ── 当前值取法（CRAFT 走 JSON、ARCHIVE 走列）──

    @Test fun 当前值_写法族读JSON_档案族读列() {
        val s = StoryEntity(
            id = "s1",
            storyOutline = "大纲",
            intimacyLedger = "关系史",
            sceneLedger = "台账",
            sceneState = "状态",
            characterStates = "现状",
            openThreads = "伏笔",
            storySummary = "摘要",
            storyBible = "圣经",
            customPromptsJson = CustomStoryPrompts.encode(
                CustomStoryPrompts(
                    writerIdentity = "身份", genreTechniques = "技法", writingRules = "规则",
                    pacingPreference = "节奏", sceneBeats = "节拍", tasteProfile = "画像", bannedExpressions = "忌口",
                ),
            ),
        )
        assertEquals("身份", StoryEditableField.WRITER_IDENTITY.currentValue(s))
        assertEquals("技法", StoryEditableField.GENRE_TECHNIQUES.currentValue(s))
        assertEquals("规则", StoryEditableField.WRITING_RULES.currentValue(s))
        assertEquals("节奏", StoryEditableField.PACING.currentValue(s))
        assertEquals("节拍", StoryEditableField.SCENE_BEATS.currentValue(s))
        assertEquals("画像", StoryEditableField.TASTE_PROFILE.currentValue(s))
        assertEquals("忌口", StoryEditableField.BANNED_OVERRIDE.currentValue(s))
        assertEquals("大纲", StoryEditableField.OUTLINE.currentValue(s))
        assertEquals("关系史", StoryEditableField.INTIMACY.currentValue(s))
        assertEquals("台账", StoryEditableField.SCENE_LEDGER.currentValue(s))
        assertEquals("状态", StoryEditableField.SCENE_STATE.currentValue(s))
        assertEquals("现状", StoryEditableField.CHARACTER_STATES.currentValue(s))
        assertEquals("伏笔", StoryEditableField.OPEN_THREADS.currentValue(s))
        assertEquals("摘要", StoryEditableField.SUMMARY.currentValue(s))
        assertEquals("圣经", StoryEditableField.BIBLE.currentValue(s))
    }

    @Test fun 当前值_老书无JSON无列_一律null() {
        val blank = StoryEntity(id = "s1")
        assertTrue(StoryEditableField.entries.all { it.currentValue(blank) == null })
    }

    // ── 继承层文本（编辑页 FOLLOW 态的只读预览）──

    @Test fun 继承层_三态字段走既有取值单源_其余为null() {
        assertEquals(
            StoryCraftSections.SCENE_BEATS_DEFAULT,
            StoryEditableField.SCENE_BEATS.inheritedText(noGlobals),
        )
        assertEquals("全局节拍", StoryEditableField.SCENE_BEATS.inheritedText(StoryGlobalCraftValues(sceneBeats = "全局节拍")))
        assertNull("全局关掉 → 这一态什么都不注入", StoryEditableField.SCENE_BEATS.inheritedText(StoryGlobalCraftValues(sceneBeats = "")))
        assertNull("画像无出厂默认", StoryEditableField.TASTE_PROFILE.inheritedText(noGlobals))
        assertEquals("全局画像", StoryEditableField.TASTE_PROFILE.inheritedText(StoryGlobalCraftValues(tasteProfile = "全局画像")))
        assertEquals(
            StoryWritingTechniques.bannedExpressionsBaseline,
            StoryEditableField.BANNED_OVERRIDE.inheritedText(noGlobals),
        )
        assertNull(StoryEditableField.WRITER_IDENTITY.inheritedText(noGlobals))
        assertNull(StoryEditableField.SUMMARY.inheritedText(noGlobals))
    }

    // ── 值标矩阵：三态字段 ──

    @Test fun 值标_场面节拍_本书未覆盖时按全局三分() {
        val s = story()
        assertEquals(
            StoryFieldValueLabel(R.string.story_hub_value_default, StoryFieldValueStyle.NEUTRAL),
            StoryEditableField.SCENE_BEATS.valueLabel(s, noGlobals),
        )
        assertEquals(
            StoryFieldValueLabel(R.string.story_hub_value_global_off, StoryFieldValueStyle.NEUTRAL),
            StoryEditableField.SCENE_BEATS.valueLabel(s, StoryGlobalCraftValues(sceneBeats = "")),
        )
        assertEquals(
            StoryFieldValueLabel(R.string.story_hub_value_follow, StoryFieldValueStyle.NEUTRAL),
            StoryEditableField.SCENE_BEATS.valueLabel(s, StoryGlobalCraftValues(sceneBeats = "全局节拍")),
        )
    }

    @Test fun 值标_场面节拍_本书空白等于已关闭_文本等于已自定义() {
        val off = story(CustomStoryPrompts(sceneBeats = ""))
        val blank = story(CustomStoryPrompts(sceneBeats = "  \n "))
        val custom = story(CustomStoryPrompts(sceneBeats = "本书节拍"))
        for (globals in listOf(noGlobals, StoryGlobalCraftValues(sceneBeats = ""), StoryGlobalCraftValues(sceneBeats = "全局"))) {
            assertEquals(
                StoryFieldValueLabel(R.string.story_hub_value_off, StoryFieldValueStyle.OFF),
                StoryEditableField.SCENE_BEATS.valueLabel(off, globals),
            )
            assertEquals(
                StoryFieldValueLabel(R.string.story_hub_value_off, StoryFieldValueStyle.OFF),
                StoryEditableField.SCENE_BEATS.valueLabel(blank, globals),
            )
            assertEquals(
                StoryFieldValueLabel(R.string.story_hub_value_custom, StoryFieldValueStyle.CUSTOM),
                StoryEditableField.SCENE_BEATS.valueLabel(custom, globals),
            )
        }
    }

    @Test fun 值标_口味画像_无出厂默认故两层皆空为未设置() {
        val s = story()
        assertEquals(
            StoryFieldValueLabel(R.string.story_hub_value_unset, StoryFieldValueStyle.NEUTRAL),
            StoryEditableField.TASTE_PROFILE.valueLabel(s, noGlobals),
        )
        assertEquals(
            "画像没有出厂默认，全局清空与从未设置一样都落「未设置」",
            StoryFieldValueLabel(R.string.story_hub_value_unset, StoryFieldValueStyle.NEUTRAL),
            StoryEditableField.TASTE_PROFILE.valueLabel(s, StoryGlobalCraftValues(tasteProfile = "")),
        )
        assertEquals(
            StoryFieldValueLabel(R.string.story_hub_value_follow, StoryFieldValueStyle.NEUTRAL),
            StoryEditableField.TASTE_PROFILE.valueLabel(s, StoryGlobalCraftValues(tasteProfile = "全局画像")),
        )
        assertEquals(
            StoryFieldValueLabel(R.string.story_hub_value_off, StoryFieldValueStyle.OFF),
            StoryEditableField.TASTE_PROFILE.valueLabel(story(CustomStoryPrompts(tasteProfile = " ")), noGlobals),
        )
    }

    @Test fun 值标_文字忌口_关闭态措辞是本书已关() {
        assertEquals(
            StoryFieldValueLabel(R.string.story_hub_value_book_off, StoryFieldValueStyle.OFF),
            StoryEditableField.BANNED_OVERRIDE.valueLabel(story(CustomStoryPrompts(bannedExpressions = "")), noGlobals),
        )
        assertEquals(
            StoryFieldValueLabel(R.string.story_hub_value_default, StoryFieldValueStyle.NEUTRAL),
            StoryEditableField.BANNED_OVERRIDE.valueLabel(story(), noGlobals),
        )
        assertEquals(
            StoryFieldValueLabel(R.string.story_hub_value_global_off, StoryFieldValueStyle.NEUTRAL),
            StoryEditableField.BANNED_OVERRIDE.valueLabel(story(), StoryGlobalCraftValues(bannedExpressions = "")),
        )
        assertEquals(
            StoryFieldValueLabel(R.string.story_hub_value_follow, StoryFieldValueStyle.NEUTRAL),
            StoryEditableField.BANNED_OVERRIDE.valueLabel(story(), StoryGlobalCraftValues(bannedExpressions = "全局忌口")),
        )
    }

    // ── 值标矩阵：二态字段与节奏偏好 ──

    @Test fun 值标_二态字段_空是出厂默认_有文本是已自定义() {
        for (field in listOf(StoryEditableField.WRITER_IDENTITY, StoryEditableField.GENRE_TECHNIQUES, StoryEditableField.WRITING_RULES)) {
            assertEquals(
                "$field 空态",
                StoryFieldValueLabel(R.string.story_hub_value_default, StoryFieldValueStyle.NEUTRAL),
                field.valueLabel(story(), noGlobals),
            )
        }
        val filled = story(CustomStoryPrompts(writerIdentity = "我的身份", genreTechniques = "我的技法", writingRules = "我的规则"))
        for (field in listOf(StoryEditableField.WRITER_IDENTITY, StoryEditableField.GENRE_TECHNIQUES, StoryEditableField.WRITING_RULES)) {
            assertEquals(
                "$field 已填",
                StoryFieldValueLabel(R.string.story_hub_value_custom, StoryFieldValueStyle.CUSTOM),
                field.valueLabel(filled, noGlobals),
            )
        }
    }

    @Test fun 值标_节奏偏好_空是未设置_有文本回显首行截断() {
        assertEquals(
            StoryFieldValueLabel(R.string.story_hub_value_unset, StoryFieldValueStyle.NEUTRAL),
            StoryEditableField.PACING.valueLabel(story(), noGlobals),
        )
        val long = story(CustomStoryPrompts(pacingPreference = "慢炖：前三章只撩不给\n第四章再爆发"))
        val label = StoryEditableField.PACING.valueLabel(long, noGlobals)
        assertNull("回显走 echo 不走词条", label.labelRes)
        assertEquals(StoryFieldValueStyle.CUSTOM, label.style)
        // 现状 NavRow 惯例：换行折成空格后统一截 12 字（不是「只取第一行」）
        assertEquals("慢炖：前三章只撩不给 第…", label.echo)
        // 12 字以内原样，不加省略号
        assertEquals("快节奏别拖", StoryEditableField.PACING.valueLabel(story(CustomStoryPrompts(pacingPreference = "快节奏别拖")), noGlobals).echo)
    }

    @Test fun 值标_档案族_空是未设置_有内容是已自定义() {
        // 档案族没有出厂默认，故空态落「未设置」而非「出厂默认」
        assertEquals(
            StoryFieldValueLabel(R.string.story_hub_value_unset, StoryFieldValueStyle.NEUTRAL),
            StoryEditableField.SUMMARY.valueLabel(story(), noGlobals),
        )
        assertEquals(
            StoryFieldValueLabel(R.string.story_hub_value_custom, StoryFieldValueStyle.CUSTOM),
            StoryEditableField.SUMMARY.valueLabel(StoryEntity(id = "s1", storySummary = "摘要"), noGlobals),
        )
    }

    // ── E13：老书首开书页，值标全落「出厂默认 / 跟随全局」，且不产生任何写 ──

    @Test fun e13_老书首开_写法七行值标全为中性() {
        val legacy = StoryEntity(id = "s1", genre = "都市", writingStyle = "网文爽文")
        val craft = StoryEditableField.entries.filter { it.kind != StoryFieldKind.ARCHIVE }
        assertEquals(7, craft.size)
        for (field in craft) {
            val label = field.valueLabel(legacy, noGlobals)
            assertEquals("$field 不该显示成用户改过", StoryFieldValueStyle.NEUTRAL, label.style)
            assertNotNull("$field 应有词条", label.labelRes)
            assertNull("$field 不该有回显", label.echo)
        }
        // 纯读，不依赖任何写路径：注册表全是纯函数，同一入参重复调用结果恒等
        assertEquals(
            craft.map { it.valueLabel(legacy, noGlobals) },
            craft.map { it.valueLabel(legacy, noGlobals) },
        )
        assertFalse("身份的出厂默认必须随文风真取到文本", StoryEditableField.WRITER_IDENTITY.factoryDefault(legacy).isNullOrBlank())
    }
}
