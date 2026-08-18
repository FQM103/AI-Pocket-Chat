package com.situ.aichat.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 家的蛋巢派生态纯函数单测（W12.5 图纸 §7 T1-1·断言从 §3 派生矩阵独立反推）。
 * 矩阵锁死：空键→Empty(不清)；角色不存在→Empty(自愈清键)；已有宠→Empty(自愈清键)；无宠未达标→Incubating；无宠达标→Hatchable。
 */
class EggNestStateTest {

    private val uuid = "char-1"
    private val name = "苏晚"

    // ── 空键：无之约 → Empty，绝不清键 ──
    @Test fun `null pact yields empty no clear`() {
        val d = deriveEggNest(pactUuid = null, characterExists = false, characterName = "", hasPet = false, canAdopt = false)
        assertEquals(EggNestState.Empty, d.state)
        assertFalse(d.clearPact)
    }

    @Test fun `blank pact yields empty no clear`() {
        val d = deriveEggNest(pactUuid = "", characterExists = true, characterName = name, hasPet = false, canAdopt = true)
        assertEquals(EggNestState.Empty, d.state)
        assertFalse(d.clearPact)
    }

    // ── E1：之约角色被删除 → Empty + 自愈清键 ──
    @Test fun `character deleted self-heals to empty and clears`() {
        val d = deriveEggNest(pactUuid = uuid, characterExists = false, characterName = "", hasPet = false, canAdopt = false)
        assertEquals(EggNestState.Empty, d.state)
        assertTrue(d.clearPact)
    }

    // ── E2：无宠→有宠（已出壳或绕道领养）→ Empty + 自愈清键（角色存在且 canAdopt 也不改判：兑现优先）──
    @Test fun `has pet self-heals to empty and clears even when eligible`() {
        val d = deriveEggNest(pactUuid = uuid, characterExists = true, characterName = name, hasPet = true, canAdopt = true)
        assertEquals(EggNestState.Empty, d.state)
        assertTrue(d.clearPact)
    }

    // ── 无宠 · 未达标 → Incubating（带 uuid/name·不清键）──
    @Test fun `no pet not eligible yields incubating`() {
        val d = deriveEggNest(pactUuid = uuid, characterExists = true, characterName = name, hasPet = false, canAdopt = false)
        assertEquals(EggNestState.Incubating(uuid, name), d.state)
        assertFalse(d.clearPact)
    }

    // ── 无宠 · 达标 → Hatchable（E8「备份时在孵、恢复后已可孵化」= 正常 Hatchable·不清键）──
    @Test fun `no pet eligible yields hatchable`() {
        val d = deriveEggNest(pactUuid = uuid, characterExists = true, characterName = name, hasPet = false, canAdopt = true)
        assertEquals(EggNestState.Hatchable(uuid, name), d.state)
        assertFalse(d.clearPact)
    }
}
