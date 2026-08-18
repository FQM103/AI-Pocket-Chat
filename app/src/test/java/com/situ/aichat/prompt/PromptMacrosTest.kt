package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词模块编辑重设计 · Phase 0 的纯函数单测（[PromptMacros]）。
 *
 * 锁定惰性解析的核心契约（D8）：按需求值、至多一次、不触发无关宏；以及宏目录自身的良构 / 唯一性
 * （宏名是持久化进用户模板的冻结契约，撞名 / 失格会静默破坏老用户模板）。
 */
class PromptMacrosTest {

    // MARK: - resolveLazy：替换正确性

    @Test fun `substitutes a present macro`() {
        val out = PromptMacros.resolveLazy(
            "你好 {{char}}，我是 {{user}}",
            mapOf(PromptMacros.CHAR to { "小满" }, PromptMacros.USER to { "然然" }),
        )
        assertEquals("你好 小满，我是 然然", out)
    }

    @Test fun `empty template returns empty without calling any producer`() {
        var calls = 0
        val out = PromptMacros.resolveLazy("", mapOf(PromptMacros.CHAR to { calls++; "x" }))
        assertEquals("", out)
        assertEquals(0, calls)
    }

    @Test fun `producer returning empty removes the macro`() {
        val out = PromptMacros.resolveLazy(
            "[头]{{角色记忆}}[尾]",
            mapOf(PromptMacros.CHAR_MEMORY to { "" }),
        )
        assertEquals("[头][尾]", out)
    }

    // MARK: - resolveLazy：惰性（契约 D8 的命脉）

    @Test fun `does not evaluate a macro absent from the template`() {
        var memoryCalls = 0
        val out = PromptMacros.resolveLazy(
            "只有名字 {{char}}",
            mapOf(
                PromptMacros.CHAR to { "小满" },
                // 模板里没有 {{角色记忆}} → 绝不该调用（否则等于为核心规则空跑向量检索）
                PromptMacros.CHAR_MEMORY to { memoryCalls++; "一大段检索结果" },
            ),
        )
        assertEquals("只有名字 小满", out)
        assertEquals(0, memoryCalls)
    }

    @Test fun `evaluates a macro at most once even when it appears twice`() {
        var calls = 0
        val out = PromptMacros.resolveLazy(
            "{{今日日程}} ... 再看一次 {{今日日程}}",
            mapOf(PromptMacros.SCHEDULE_TODAY to { calls++; "9点开会" }),
        )
        assertEquals("9点开会 ... 再看一次 9点开会", out)
        assertEquals(1, calls)
    }

    @Test fun `mixes present and absent macros`() {
        var petCalls = 0
        val out = PromptMacros.resolveLazy(
            "[{{char}}的记忆]\n{{角色记忆}}",
            mapOf(
                PromptMacros.CHAR to { "小满" },
                PromptMacros.CHAR_MEMORY to { "记得你喜欢猫" },
                PromptMacros.PET_STATUS to { petCalls++; "猫在睡觉" },
            ),
        )
        assertEquals("[小满的记忆]\n记得你喜欢猫", out)
        assertEquals(0, petCalls)
    }

    // MARK: - 宏目录：良构 + 唯一（冻结契约）

    @Test fun `all macro tokens are well formed and unique`() {
        val all = listOf(
            PromptMacros.CHAR, PromptMacros.USER, PromptMacros.NOW,
            PromptMacros.CHAR_PROFILE, PromptMacros.CHAR_GROWTH,
            PromptMacros.USER_PERSONA, PromptMacros.USER_CITY, PromptMacros.USER_WEATHER,
            PromptMacros.CHAR_MEMORY, PromptMacros.MEMORY_CONTENT, PromptMacros.MEETING_MEMORY,
            PromptMacros.TIME_CONTEXT, PromptMacros.SCHEDULE_TODAY, PromptMacros.CURRENT_MOMENT, PromptMacros.USER_CALENDAR,
            PromptMacros.MOMENTS_CONTEXT, PromptMacros.STICKER_LIBRARY, PromptMacros.PET_STATUS,
            PromptMacros.GIFT_HISTORY, PromptMacros.ECONOMIC_STATE,
            PromptMacros.MOOD_FORMAT, PromptMacros.REPLY_SEGMENTS,
            PromptMacros.BUSY_ACTIVITY, PromptMacros.USER_PENDING_MESSAGES,
        )
        for (m in all) {
            assertTrue("宏须以 {{ 开头：$m", m.startsWith("{{"))
            assertTrue("宏须以 }} 结尾：$m", m.endsWith("}}"))
            assertTrue("宏中段不能为空：$m", m.length > 4)
        }
        assertEquals("宏名撞车（冻结契约要求唯一）", all.size, all.toSet().size)
    }

    @Test fun `compat memory macro keeps the legacy chinese token`() {
        // 现行 PromptBuilder.defaultInjectionPrompt 在用 {{记忆内容}}；改名会破坏老用户的记忆注入模板。
        assertEquals("{{记忆内容}}", PromptMacros.MEMORY_CONTENT)
    }

    @Test fun `protected set holds exactly the parser-coupled macros`() {
        assertTrue(PromptMacros.protectedMacros.contains(PromptMacros.MOOD_FORMAT))
        assertTrue(PromptMacros.protectedMacros.contains(PromptMacros.REPLY_SEGMENTS))
        assertFalse(PromptMacros.protectedMacros.contains(PromptMacros.CHAR_MEMORY))
        assertEquals(2, PromptMacros.protectedMacros.size)
    }
}
