package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 见面记忆前置一次性迁移（2026-07-11 拍板）：把 offlineMeetingMemory 从 SUFFIX 迁到 PREFIX、插缝到「角色记忆」
 * 正后（[PromptModuleService.migrateMeetingMemoryToPrefix] / [PromptModuleService.migratePromptModuleMeetingMemory]）。
 * 老用户持久化的「见面记忆在后置」享受不到新默认，故补此迁移；只翻 position + 插缝 shift、幂等、尊重用户自定义。
 * 断言从图纸 §3.3 规格独立反推（非照抄实现）。
 */
class PromptModuleMeetingMemoryMigrationTest {

    private val PREFIX = PromptModulePosition.PREFIX
    private val SUFFIX = PromptModulePosition.SUFFIX

    private fun mod(
        type: SystemModuleType,
        order: Int,
        position: PromptModulePosition = PREFIX,
    ) = PromptModule(
        id = "id-${type.name}",
        name = type.displayName,
        sortOrder = order,
        systemModuleType = type,
        position = position,
        isSystemGenerated = true,
    )

    private fun MutableList<PromptModule>.orderOf(type: SystemModuleType) =
        first { it.systemModuleType == type }.sortOrder

    private fun MutableList<PromptModule>.of(type: SystemModuleType) =
        first { it.systemModuleType == type }

    // MARK: - T1-1 (E5)：默认位老用户 → 插缝迁移

    @Test
    fun staleSuffix_movesToPrefix_insertsAfterCharacterMemory_withShift() {
        // 老默认态：角色记忆 prefix#5，见面记忆 suffix#16；#6/#7/#9 是「角色记忆之后」的既有模块。
        val mods = mutableListOf(
            mod(SystemModuleType.CORE_RULES, 0),
            mod(SystemModuleType.CHARACTER_MEMORY, 5),
            mod(SystemModuleType.CALENDAR_AWARENESS, 6),
            mod(SystemModuleType.SCHEDULE_AWARENESS, 7),
            mod(SystemModuleType.RESPONSE_STYLE, 9, SUFFIX),
            mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 16, SUFFIX),
        )
        assertTrue(PromptModuleService.migrateMeetingMemoryToPrefix(mods))

        val mm = mods.of(SystemModuleType.OFFLINE_MEETING_MEMORY)
        assertEquals("翻到前置", PREFIX, mm.position)
        assertEquals("插到角色记忆(5)正后 = 6", 6, mm.sortOrder)
        // 角色记忆之前/其身不动；>=target(6) 的既有模块整体 +1。
        assertEquals(0, mods.orderOf(SystemModuleType.CORE_RULES))
        assertEquals(5, mods.orderOf(SystemModuleType.CHARACTER_MEMORY))
        assertEquals(7, mods.orderOf(SystemModuleType.CALENDAR_AWARENESS))
        assertEquals(8, mods.orderOf(SystemModuleType.SCHEDULE_AWARENESS))
        assertEquals(10, mods.orderOf(SystemModuleType.RESPONSE_STYLE))
        // 无并列 sortOrder；排序后见面记忆紧跟角色记忆。
        val orders = mods.map { it.sortOrder }
        assertEquals("无并列值", orders.size, orders.toSet().size)
        val sorted = mods.sortedBy { it.sortOrder }
        val memPos = sorted.indexOfFirst { it.systemModuleType == SystemModuleType.CHARACTER_MEMORY }
        assertEquals(SystemModuleType.OFFLINE_MEETING_MEMORY, sorted[memPos + 1].systemModuleType)
    }

    // MARK: - T1-2 (E4)：用户已挪到非 SUFFIX → 跳过

    @Test
    fun userMovedToNonSuffix_skips_noChange() {
        val mods = mutableListOf(
            mod(SystemModuleType.CHARACTER_MEMORY, 5),
            mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 6), // 用户已挪到 prefix
            mod(SystemModuleType.RESPONSE_STYLE, 9, SUFFIX),
        )
        val before = mods.map { it.copy() }
        assertFalse(PromptModuleService.migrateMeetingMemoryToPrefix(mods))
        assertEquals("跳过时列表逐字不动", before, mods)
    }

    // MARK: - T1-3 (E6)：角色记忆缺席 / 不在 PREFIX → 只翻面、保留 sortOrder

    @Test
    fun characterMemoryInSuffix_onlyFlipsPosition_keepsSortOrder() {
        val mods = mutableListOf(
            mod(SystemModuleType.CORE_RULES, 0),
            mod(SystemModuleType.CHARACTER_MEMORY, 5, SUFFIX), // 极端：用户把角色记忆挪去后置
            mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 16, SUFFIX),
        )
        assertTrue(PromptModuleService.migrateMeetingMemoryToPrefix(mods))
        val mm = mods.of(SystemModuleType.OFFLINE_MEETING_MEMORY)
        assertEquals(PREFIX, mm.position)
        assertEquals("无 PREFIX 角色记忆锚点 → 保留原 sortOrder", 16, mm.sortOrder)
        assertEquals("其余不动", 0, mods.orderOf(SystemModuleType.CORE_RULES))
    }

    @Test
    fun characterMemoryAbsent_onlyFlipsPosition_keepsSortOrder() {
        val mods = mutableListOf(
            mod(SystemModuleType.CORE_RULES, 0),
            mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 16, SUFFIX),
        )
        assertTrue(PromptModuleService.migrateMeetingMemoryToPrefix(mods))
        val mm = mods.of(SystemModuleType.OFFLINE_MEETING_MEMORY)
        assertEquals(PREFIX, mm.position)
        assertEquals(16, mm.sortOrder)
    }

    // MARK: - T1-5 (E8)：幂等

    @Test
    fun idempotent_secondRunIsNoOp() {
        val mods = mutableListOf(
            mod(SystemModuleType.CHARACTER_MEMORY, 5),
            mod(SystemModuleType.CALENDAR_AWARENESS, 6),
            mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 16, SUFFIX),
        )
        assertTrue(PromptModuleService.migrateMeetingMemoryToPrefix(mods))
        assertFalse("迁移后 position=PREFIX，再跑必 false", PromptModuleService.migrateMeetingMemoryToPrefix(mods))
    }

    // MARK: - T1-6 (E9)：无见面记忆模块 → false

    @Test
    fun noMeetingMemoryModule_returnsFalse() {
        val mods = mutableListOf(
            mod(SystemModuleType.CORE_RULES, 0),
            mod(SystemModuleType.CHARACTER_MEMORY, 5),
        )
        assertFalse(PromptModuleService.migrateMeetingMemoryToPrefix(mods))
    }

    // MARK: - T1-4 (E7)：角色 dict 双轨（部分已自定义）

    @Test
    fun characterDict_migratesStaleChar_leavesCustomizedChar() {
        val stale = listOf(
            mod(SystemModuleType.CHARACTER_MEMORY, 5),
            mod(SystemModuleType.CALENDAR_AWARENESS, 6),
            mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 16, SUFFIX),
        )
        val custom = listOf(
            mod(SystemModuleType.CHARACTER_MEMORY, 5),
            mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 6), // 已在 prefix
        )
        var dict = PromptModuleService.setCharacterModules("cA", stale, "")
        dict = PromptModuleService.setCharacterModules("cB", custom, dict)

        val result = PromptModuleService.migratePromptModuleMeetingMemory(globalJson = "", characterJson = dict)
        assertNotNull(result)
        assertNull("全局空 → 不写回", result!!.first)
        assertNotNull("角色 dict 有改动 → 写回", result.second)

        val cA = PromptModuleService.loadCharacterModules("cA", result.second!!)!!
        val mmA = cA.first { it.systemModuleType == SystemModuleType.OFFLINE_MEETING_MEMORY }
        assertEquals(PREFIX, mmA.position)
        assertEquals(6, mmA.sortOrder) // 角色记忆(5)+1

        val cB = PromptModuleService.loadCharacterModules("cB", result.second!!)!!
        val mmB = cB.first { it.systemModuleType == SystemModuleType.OFFLINE_MEETING_MEMORY }
        assertEquals("已自定义角色原样不动", PREFIX, mmB.position)
        assertEquals(6, mmB.sortOrder)
    }

    // MARK: - T1-7 (E10)：wrapper null 语义（含 defaultModules 已在前置 → null）

    @Test
    fun wrapper_defaultModules_alreadyPrefix_returnsNull() {
        // chunk2 后默认 offlineMeetingMemory 已在 PREFIX → 迁移无改动。
        val globalJson = PromptModuleService.encodeModules(PromptModuleService.defaultModules())
        assertNull(PromptModuleService.migratePromptModuleMeetingMemory(globalJson, ""))
    }

    @Test
    fun wrapper_staleGlobal_emptyChar_reordersGlobalOnly() {
        val stale = PromptModuleService.defaultModules().map {
            if (it.systemModuleType == SystemModuleType.OFFLINE_MEETING_MEMORY) {
                it.copy(position = SUFFIX, sortOrder = 16)
            } else {
                it
            }
        }
        val result = PromptModuleService.migratePromptModuleMeetingMemory(
            globalJson = PromptModuleService.encodeModules(stale),
            characterJson = "",
        )
        assertNotNull(result)
        assertNotNull("全局有改动", result!!.first)
        assertNull("无角色覆盖", result.second)

        val reordered = PromptModuleService.loadGlobalModules(result.first!!).sortedBy { it.sortOrder }
        val memPos = reordered.indexOfFirst { it.systemModuleType == SystemModuleType.CHARACTER_MEMORY }
        assertEquals(SystemModuleType.OFFLINE_MEETING_MEMORY, reordered[memPos + 1].systemModuleType)
    }

    @Test
    fun wrapper_emptyGlobal_staleChar_reordersCharOnly() {
        val stale = listOf(
            mod(SystemModuleType.CHARACTER_MEMORY, 5),
            mod(SystemModuleType.OFFLINE_MEETING_MEMORY, 16, SUFFIX),
        )
        val characterJson = PromptModuleService.setCharacterModules("cX", stale, "")
        val result = PromptModuleService.migratePromptModuleMeetingMemory(globalJson = "", characterJson = characterJson)

        assertNotNull(result)
        assertNull("全局空 → 不动", result!!.first)
        assertNotNull("角色覆盖有改动", result.second)
        val mods = PromptModuleService.loadCharacterModules("cX", result.second!!)!!
        val mm = mods.first { it.systemModuleType == SystemModuleType.OFFLINE_MEETING_MEMORY }
        assertEquals(PREFIX, mm.position)
        assertEquals(6, mm.sortOrder)
    }
}
