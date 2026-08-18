package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间感知优化·修 A：把 timeAwareness + currentMoment 调到 suffix 末尾的一次性迁移
 * （[PromptModuleService.migrateTimeAwarenessToBottom] / [PromptModuleService.migratePromptModuleTimeOrder]）。
 * 老角色持久化的「时间在风格/格式前面」顺序享受不到新默认，故补此迁移；只改 sortOrder、幂等、尊重用户自定义。
 */
class PromptModuleTimeOrderMigrationTest {

    private fun mod(
        type: SystemModuleType,
        order: Int,
        position: PromptModulePosition = PromptModulePosition.SUFFIX,
    ) = PromptModule(
        id = "id-${type.name}",
        name = type.displayName,
        sortOrder = order,
        systemModuleType = type,
        position = position,
        isSystemGenerated = true,
    )

    // MARK: - migrateTimeAwarenessToBottom

    @Test
    fun staleOrder_movesTimeAndMomentToBottom() {
        // 复刻截图里的老顺序：时间/此刻排在风格/格式前面。
        val mods = mutableListOf(
            mod(SystemModuleType.TIME_AWARENESS, 1),
            mod(SystemModuleType.CURRENT_MOMENT, 2),
            mod(SystemModuleType.RESPONSE_STYLE, 3),
            mod(SystemModuleType.CHAT_FORMAT, 4),
            mod(SystemModuleType.GENERAL_INSTRUCTIONS, 5),
        )
        assertTrue(PromptModuleService.migrateTimeAwarenessToBottom(mods))

        val sorted = mods.sortedBy { it.sortOrder }
        assertEquals(SystemModuleType.CURRENT_MOMENT, sorted.last().systemModuleType)
        assertEquals(SystemModuleType.TIME_AWARENESS, sorted[sorted.size - 2].systemModuleType)
    }

    @Test
    fun alreadyBottom_idempotent_noChange() {
        val mods = mutableListOf(
            mod(SystemModuleType.RESPONSE_STYLE, 1),
            mod(SystemModuleType.GENERAL_INSTRUCTIONS, 2),
            mod(SystemModuleType.TIME_AWARENESS, 3),
            mod(SystemModuleType.CURRENT_MOMENT, 4),
        )
        assertEquals(false, PromptModuleService.migrateTimeAwarenessToBottom(mods))
    }

    @Test
    fun userMovedTimeToPrefix_skips() {
        val mods = mutableListOf(
            mod(SystemModuleType.TIME_AWARENESS, 1, PromptModulePosition.PREFIX),
            mod(SystemModuleType.CURRENT_MOMENT, 2),
            mod(SystemModuleType.RESPONSE_STYLE, 3),
        )
        assertEquals(false, PromptModuleService.migrateTimeAwarenessToBottom(mods))
    }

    @Test
    fun runTwice_secondRunIsNoOp() {
        // 直接验 migrateTimeAwarenessToBottom 的幂等性（启动期可能多次触发，第二次必须不改）。
        val mods = mutableListOf(
            mod(SystemModuleType.TIME_AWARENESS, 1),
            mod(SystemModuleType.CURRENT_MOMENT, 2),
            mod(SystemModuleType.RESPONSE_STYLE, 3),
            mod(SystemModuleType.CHAT_FORMAT, 4),
        )
        assertTrue(PromptModuleService.migrateTimeAwarenessToBottom(mods))
        assertEquals(false, PromptModuleService.migrateTimeAwarenessToBottom(mods))
    }

    @Test
    fun currentMomentAbsent_stillMovesTimeToBottom() {
        // pre-G3 老 JSON 可能没 currentMoment——只抬 timeAwareness。
        val mods = mutableListOf(
            mod(SystemModuleType.TIME_AWARENESS, 1),
            mod(SystemModuleType.RESPONSE_STYLE, 2),
            mod(SystemModuleType.GENERAL_INSTRUCTIONS, 3),
        )
        assertTrue(PromptModuleService.migrateTimeAwarenessToBottom(mods))
        assertEquals(SystemModuleType.TIME_AWARENESS, mods.sortedBy { it.sortOrder }.last().systemModuleType)
    }

    // MARK: - migratePromptModuleTimeOrder（JSON 级 · 全局 / 角色覆盖路由）

    @Test
    fun json_fullStaleSet_global_reordersAfterRoundTrip() {
        // 全量默认模块、但把时间/此刻人为压到低 sortOrder（=老用户持久化态）。
        val stale = PromptModuleService.defaultModules().map {
            when (it.systemModuleType) {
                SystemModuleType.TIME_AWARENESS -> it.copy(sortOrder = 5)
                SystemModuleType.CURRENT_MOMENT -> it.copy(sortOrder = 6)
                else -> it
            }
        }
        val result = PromptModuleService.migratePromptModuleTimeOrder(
            globalJson = PromptModuleService.encodeModules(stale),
            characterJson = "",
        )
        assertNotNull(result)
        assertNotNull(result!!.first) // 全局有改动
        assertNull(result.second) // 无角色覆盖

        val reordered = PromptModuleService.loadGlobalModules(result.first!!).sortedBy { it.sortOrder }
        assertEquals(SystemModuleType.CURRENT_MOMENT, reordered.last().systemModuleType)
        assertEquals(SystemModuleType.TIME_AWARENESS, reordered[reordered.size - 2].systemModuleType)
    }

    @Test
    fun json_defaultSet_noChange_returnsNull() {
        // 全新默认（时间/此刻本就在末尾）→ 无需迁移。
        val globalJson = PromptModuleService.encodeModules(PromptModuleService.defaultModules())
        assertNull(PromptModuleService.migratePromptModuleTimeOrder(globalJson, ""))
    }

    @Test
    fun json_characterOverride_reorders_globalUntouched() {
        val stale = listOf(
            mod(SystemModuleType.TIME_AWARENESS, 1),
            mod(SystemModuleType.CURRENT_MOMENT, 2),
            mod(SystemModuleType.RESPONSE_STYLE, 3),
        )
        val characterJson = PromptModuleService.setCharacterModules("char-uuid", stale, "")
        val result = PromptModuleService.migratePromptModuleTimeOrder(globalJson = "", characterJson = characterJson)

        assertNotNull(result)
        assertNull(result!!.first) // 全局空、不动
        assertNotNull(result.second) // 角色覆盖有改动

        val mods = PromptModuleService.loadCharacterModules("char-uuid", result.second!!)!!.sortedBy { it.sortOrder }
        assertEquals(SystemModuleType.CURRENT_MOMENT, mods.last().systemModuleType)
    }
}
