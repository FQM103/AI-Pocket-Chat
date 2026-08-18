package com.situ.aichat.prompt

import com.situ.aichat.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 活人感一期 P1 · T1-1（E1）：回复风格块 [buildResponseStyleContent] 的开关行为，解析真实 `pb_style_*` 资源
 * （默认 locale = en）。锁两条红线：
 * - `textingTone=false` → 与旧值（title+l1+l2）**逐字节相等**（关掉口吻不改动任何既有输出）；
 * - `textingTone=true`  → 在旧值末尾**恰好追加 l3 一行**，不多不少、不改前三行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResponseStyleContentTest {

    private fun strings() = PromptStrings(RuntimeEnvironment.getApplication())

    /** 旧值 = title + l1 + l2（改动前 [buildResponseStyleContent] 的完整输出）。 */
    private fun legacyValue(s: PromptStrings): String =
        listOf(
            s.s(R.string.pb_style_title),
            s.s(R.string.pb_style_l1),
            s.s(R.string.pb_style_l2),
        ).joinToString("\n")

    @Test fun `texting tone off is byte-identical to legacy value`() {
        val s = strings()
        assertEquals(legacyValue(s), buildResponseStyleContent(s, textingTone = false))
    }

    @Test fun `texting tone on appends exactly one line l3`() {
        val s = strings()
        val off = buildResponseStyleContent(s, textingTone = false)
        val on = buildResponseStyleContent(s, textingTone = true)
        // 恰好 = 关态 + 换行 + l3，一字不差。
        assertEquals(off + "\n" + s.s(R.string.pb_style_l3), on)
        // 行数恰好多 1（追加而非改写）。
        assertEquals(off.lines().size + 1, on.lines().size)
        assertTrue("末行必须是 l3", on.endsWith(s.s(R.string.pb_style_l3)))
    }
}
