package com.situ.aichat.story

import com.situ.aichat.data.model.CustomStoryPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 章末选项开关的存取 T1（图纸二 D3·验收 T1-2）：三态真值表、hasAnyValue 并入、老 JSON 兼容（E7）、
 * 「开 = 省略键」的序列化口径（E5）；另钉 2026-08-03 图纸 §5-E4：**老 JSON 里的已删字段
 * `immersiveMarkupEnabled` 被静默忽略**（`ignoreUnknownKeys`），老书不炸不清空。
 *
 * 断言从规格反推（2026-08-05 用户拍板默认翻转）：`null = 关`（默认·存量书一并变关）、`false = 关`（老 JSON 兼容）、
 * 只有显式 `true = 开`；谓词 `== true` 是唯一取值口径；序列化口径随之反转：**关（默认）= 省略键、开 = 写 true**。
 */
class CustomStoryPromptsToggleTest {

    // ── 谓词三态真值表 ──

    @Test
    fun 谓词_从没动过开关_算关() {
        assertFalse("2026-08-05 拍板：默认关（存量 null 一并变关）", CustomStoryPrompts().effectiveChapterChoices)
    }

    @Test
    fun 谓词_显式true才算开() {
        assertTrue(CustomStoryPrompts(chapterChoicesEnabled = true).effectiveChapterChoices)
    }

    @Test
    fun 谓词_null与false都算关_且不串台到快照开关() {
        val legacyOff = CustomStoryPrompts(chapterChoicesEnabled = false)
        assertFalse("老 JSON 的显式 false 仍是关", legacyOff.effectiveChapterChoices)
        assertTrue("关选项不该顺手关掉场景快照", legacyOff.effectiveSceneSnapshot)

        val choicesOnSnapshotOff = CustomStoryPrompts(chapterChoicesEnabled = true, sceneSnapshotEnabled = false)
        assertTrue("关快照不该顺手关掉显式打开的选项", choicesOnSnapshotOff.effectiveChapterChoices)
        assertFalse(choicesOnSnapshotOff.effectiveSceneSnapshot)
    }

    // ── hasAnyValue 并入（E5：只动过开关的 JSON 不许被判空丢掉）──

    @Test
    fun hasAnyValue_只关了选项也算有值() {
        assertTrue(CustomStoryPrompts(chapterChoicesEnabled = false).hasAnyValue)
    }

    @Test
    fun hasAnyValue_只开了选项也算有值() {
        // 开 = 写 true：开启态必须留住 JSON，否则判空清掉 = 静默丢开关。
        assertTrue(CustomStoryPrompts(chapterChoicesEnabled = true).hasAnyValue)
    }

    @Test
    fun hasAnyValue_只关了快照也算有值() {
        assertTrue(CustomStoryPrompts(sceneSnapshotEnabled = false).hasAnyValue)
    }

    @Test
    fun hasAnyValue_开关恢复默认且其他全空_判空() {
        // 反向：选项关=写 null、快照开=写 null → 整份 JSON 回到「什么都没有」，该被清掉走预设默认。
        assertFalse(CustomStoryPrompts(chapterChoicesEnabled = null, sceneSnapshotEnabled = null).hasAnyValue)
    }

    @Test
    fun hasAnyValue_开关恢复开但还有忌口_不许清() {
        assertTrue(CustomStoryPrompts(bannedExpressions = "少写雨", chapterChoicesEnabled = null).hasAnyValue)
    }

    // ── 「自定义提示词已填写」谓词：只看内容字段（D3 副作用修正）──

    @Test
    fun 内容谓词_只动开关不算已填写() {
        assertFalse(CustomStoryPrompts(chapterChoicesEnabled = false).hasAnyPromptContent)
        assertFalse(CustomStoryPrompts(sceneSnapshotEnabled = false).hasAnyPromptContent)
        assertTrue("落库判空仍要留住 JSON", CustomStoryPrompts(chapterChoicesEnabled = false).hasAnyValue)
    }

    @Test
    fun 内容谓词_五个内容字段任一非空即算已填写() {
        assertTrue(CustomStoryPrompts(genreTechniques = "技法").hasAnyPromptContent)
        assertTrue(CustomStoryPrompts(writerIdentity = "身份").hasAnyPromptContent)
        assertTrue(CustomStoryPrompts(writingRules = "规则").hasAnyPromptContent)
        assertTrue("节奏偏好计入与本卷之前一致", CustomStoryPrompts(pacingPreference = "慢热").hasAnyPromptContent)
        assertTrue(CustomStoryPrompts(bannedExpressions = "少写雨").hasAnyPromptContent)
        assertFalse(CustomStoryPrompts().hasAnyPromptContent)
    }

    // ── 序列化口径 ──

    @Test
    fun 编码_默认态的开关不写进JSON() {
        // 选项默认关、快照默认开——两者的默认态都= null =省略键。
        val json = CustomStoryPrompts.encode(CustomStoryPrompts(pacingPreference = "慢热"))
        assertFalse("默认态不该出现这个键", json.contains("chapterChoicesEnabled"))
        assertFalse(json.contains("sceneSnapshotEnabled"))
    }

    @Test
    fun 编码解码_显式打开的选项往返不丢() {
        val json = CustomStoryPrompts.encode(CustomStoryPrompts(chapterChoicesEnabled = true))
        assertTrue(json.contains("chapterChoicesEnabled"))
        val back = CustomStoryPrompts.decode(json)!!
        assertEquals(true, back.chapterChoicesEnabled)
        assertTrue(back.effectiveChapterChoices)
    }

    @Test
    fun 编码解码_关掉的开关往返不丢() {
        val json = CustomStoryPrompts.encode(
            CustomStoryPrompts(chapterChoicesEnabled = false, sceneSnapshotEnabled = false),
        )
        assertTrue(json.contains("chapterChoicesEnabled"))
        val back = CustomStoryPrompts.decode(json)!!
        assertEquals(false, back.chapterChoicesEnabled)
        assertEquals(false, back.sceneSnapshotEnabled)
        assertFalse(back.effectiveChapterChoices)
        assertFalse(back.effectiveSceneSnapshot)
    }

    @Test
    fun E7_老书JSON没有开关键_解码得null且谓词为关() {
        val legacy = """{"writerIdentity":"你是一位悬疑小说大师","pacingPreference":"慢热，多写日常"}"""
        val p = CustomStoryPrompts.decode(legacy)!!

        assertNull(p.chapterChoicesEnabled)
        assertFalse("2026-08-05 拍板：无键 = 关（存量书一并变关，开关仍可显式打开）", p.effectiveChapterChoices)
        assertEquals("既有字段照常读出", "慢热，多写日常", p.pacingPreference)
    }

    // ── E4（2026-08-03 图纸 §5）：老 JSON 带着已删字段也不许炸 ──

    @Test
    fun E4_老JSON含已删的沉浸标记键_静默忽略且其余字段照解() {
        // 用户在 2026-08-03 之前关过本书「沉浸氛围标记」→ 库里那份 JSON 至今带着这个键。
        val legacy = """{"immersiveMarkupEnabled":false,"chapterChoicesEnabled":false,"bannedExpressions":"少写雨"}"""
        val p = CustomStoryPrompts.decode(legacy)

        assertNotNull("未知键必须被忽略而不是整份解码失败（ignoreUnknownKeys）", p)
        assertEquals(false, p!!.chapterChoicesEnabled)
        assertEquals("其余字段照常读出", "少写雨", p.bannedExpressions)
        // 再编码一次：已删字段自然消失，不留残迹
        assertFalse(CustomStoryPrompts.encode(p).contains("immersiveMarkupEnabled"))
    }

    @Test
    fun E4_老JSON只含已删字段_解码得空对象且判空清JSON() {
        val legacy = """{"immersiveMarkupEnabled":false}"""
        val p = CustomStoryPrompts.decode(legacy)!!
        assertFalse("只剩已删字段 → 这份 JSON 该被判空清掉，回到预设默认", p.hasAnyValue)
    }

    @Test
    fun 单改一个开关_其余四字段原样保留() {
        // copy-merge 范式的数据层前提：新增字段不挤掉任何既有字段。
        val existing = CustomStoryPrompts(
            genreTechniques = "技法", writerIdentity = "身份", writingRules = "规则",
            pacingPreference = "慢热", bannedExpressions = "少写雨",
        )
        val merged = existing.copy(chapterChoicesEnabled = false)
        val back = CustomStoryPrompts.decode(CustomStoryPrompts.encode(merged))!!

        assertEquals("技法", back.genreTechniques)
        assertEquals("身份", back.writerIdentity)
        assertEquals("规则", back.writingRules)
        assertEquals("慢热", back.pacingPreference)
        assertEquals("少写雨", back.bannedExpressions)
        assertFalse(back.effectiveChapterChoices)
    }
}
