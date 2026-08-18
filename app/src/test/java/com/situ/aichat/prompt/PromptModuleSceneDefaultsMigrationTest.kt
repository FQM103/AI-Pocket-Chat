package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 短信腔四件线下退场一次性迁移（两语境模型 2026-07-12·图纸 §3-C2）：enabledScenes==null（从未手改）的
 * CHAT_FORMAT/RESPONSE_STYLE/MOOD_EXPRESSION/STICKER_LIBRARY → 写 setOf(ONLINE_CHAT)；手改过（非 null）零碰、幂等。
 * ([PromptModuleService.migrateSceneDefaultsForOfflineExit] / [PromptModuleService.migratePromptModuleSceneDefaults])。
 * 断言从图纸 §3-C1/C2 规格独立反推（写值 `setOf(ONLINE_CHAT)` 逐字打字，禁引实现常量 type.defaultEnabledScenes）。
 */
class PromptModuleSceneDefaultsMigrationTest {

    /** 图纸锁定的迁移目标值（独立打字·不引 type.defaultEnabledScenes）。 */
    private val ONLINE_ONLY = setOf(PromptScene.ONLINE_CHAT)
    private val TARGETS = listOf(
        SystemModuleType.CHAT_FORMAT, SystemModuleType.RESPONSE_STYLE,
        SystemModuleType.MOOD_EXPRESSION, SystemModuleType.STICKER_LIBRARY,
    )

    private fun mod(
        type: SystemModuleType,
        order: Int,
        scenes: Set<PromptScene>? = null,
    ) = PromptModule(
        id = "id-${type.name}",
        name = type.displayName,
        sortOrder = order,
        systemModuleType = type,
        position = type.defaultPosition,
        isSystemGenerated = true,
        enabledScenes = scenes,
    )

    private fun List<PromptModule>.scenesOf(type: SystemModuleType) =
        first { it.systemModuleType == type }.enabledScenes

    // MARK: - T1-1：null 老配置 → 恰四模块写 setOf(ONLINE_CHAT)，非目标不动

    @Test
    fun nullDefaults_migratesExactlyFourTargets_toOnlineChatOnly() {
        val mods = mutableListOf(
            mod(SystemModuleType.CORE_RULES, 0),           // 非目标：不动
            mod(SystemModuleType.CHARACTER_MEMORY, 1),     // 非目标：不动
            mod(SystemModuleType.QUALITY_CONTROL, 2),      // 非目标（明确不退场）：不动
            mod(SystemModuleType.GENERAL_INSTRUCTIONS, 3), // 非目标（明确不退场）：不动
            mod(SystemModuleType.CHAT_FORMAT, 4),
            mod(SystemModuleType.RESPONSE_STYLE, 5),
            mod(SystemModuleType.MOOD_EXPRESSION, 6),
            mod(SystemModuleType.STICKER_LIBRARY, 7),
            mod(SystemModuleType.BUSY_REPLY_INSTRUCTION, 8, setOf(PromptScene.BUSY_REPLY)), // 已非 null：不动
        )
        assertTrue(PromptModuleService.migrateSceneDefaultsForOfflineExit(mods))

        // 恰四目标写 setOf(ONLINE_CHAT)。
        for (t in TARGETS) assertEquals("$t → 仅在线聊天", ONLINE_ONLY, mods.scenesOf(t))
        // 非目标 null 保持 null（不被误写）。
        assertNull(mods.scenesOf(SystemModuleType.CORE_RULES))
        assertNull(mods.scenesOf(SystemModuleType.CHARACTER_MEMORY))
        assertNull(mods.scenesOf(SystemModuleType.QUALITY_CONTROL))
        assertNull(mods.scenesOf(SystemModuleType.GENERAL_INSTRUCTIONS))
        // 已 non-null 的 busyReply 原值不动。
        assertEquals(setOf(PromptScene.BUSY_REPLY), mods.scenesOf(SystemModuleType.BUSY_REPLY_INSTRUCTION))
        // 恰四个模块被改（其余 enabledScenes 引用不变）。
        assertEquals(4, mods.count { it.enabledScenes == ONLINE_ONLY })
    }

    // MARK: - T1-2 (E7)：手改过的目标模块 → 零碰

    @Test
    fun handModifiedTarget_untouched() {
        val mods = mutableListOf(
            mod(SystemModuleType.CHAT_FORMAT, 0, setOf(PromptScene.OFFLINE_MEETING)), // 用户勾回线下
            mod(SystemModuleType.RESPONSE_STYLE, 1),                                  // 仍 null → 会迁
        )
        assertTrue("有一个 null 目标 → 整体 changed", PromptModuleService.migrateSceneDefaultsForOfflineExit(mods))
        assertEquals("手改过的零碰", setOf(PromptScene.OFFLINE_MEETING), mods.scenesOf(SystemModuleType.CHAT_FORMAT))
        assertEquals("null 的被迁", ONLINE_ONLY, mods.scenesOf(SystemModuleType.RESPONSE_STYLE))
    }

    // MARK: - T1-3 (E8)：幂等

    @Test
    fun idempotent_secondRunIsNoOp() {
        val mods = mutableListOf(
            mod(SystemModuleType.CHAT_FORMAT, 0),
            mod(SystemModuleType.STICKER_LIBRARY, 1),
        )
        assertTrue(PromptModuleService.migrateSceneDefaultsForOfflineExit(mods))
        assertFalse("迁后全 non-null，再跑必 false", PromptModuleService.migrateSceneDefaultsForOfflineExit(mods))
    }

    @Test
    fun noTargetsPresent_returnsFalse() {
        val mods = mutableListOf(
            mod(SystemModuleType.CORE_RULES, 0),
            mod(SystemModuleType.CHARACTER_MEMORY, 1),
        )
        assertFalse(PromptModuleService.migrateSceneDefaultsForOfflineExit(mods))
    }

    // MARK: - T1-4 (E9)：空 JSON → null（首装冷启用 defaultModules 直享新默认）

    @Test
    fun wrapper_emptyBoth_returnsNull() {
        assertNull(PromptModuleService.migratePromptModuleSceneDefaults("", ""))
    }

    @Test
    fun wrapper_defaultModules_alreadyOnlineOnly_returnsNull() {
        // C1 后 defaultModules 的四目标已是 setOf(ONLINE_CHAT) → 迁移无改动。
        val globalJson = PromptModuleService.encodeModules(PromptModuleService.defaultModules())
        assertNull(PromptModuleService.migratePromptModuleSceneDefaults(globalJson, ""))
    }

    // MARK: - T1-5 (E10)：备份链纯函数侧——陈旧全局 JSON 只迁全局

    @Test
    fun wrapper_staleGlobal_emptyChar_migratesGlobalOnly() {
        // 模拟老备份：defaultModules 但四目标 enabledScenes 被清成 null（迁移前形态）。
        val stale = PromptModuleService.defaultModules().map {
            if (it.systemModuleType in TARGETS) it.copy(enabledScenes = null) else it
        }
        val result = PromptModuleService.migratePromptModuleSceneDefaults(
            globalJson = PromptModuleService.encodeModules(stale),
            characterJson = "",
        )
        assertNotNull(result)
        assertNotNull("全局有改动 → 写回", result!!.first)
        assertNull("无角色覆盖", result.second)

        val migrated = PromptModuleService.loadGlobalModules(result.first!!)
        for (t in TARGETS) assertEquals(ONLINE_ONLY, migrated.scenesOf(t))
    }

    // MARK: - T1-6 (E12)：角色字典双轨（陈旧角色迁、已定制角色零碰）

    @Test
    fun wrapper_characterDict_migratesStaleChar_leavesCustomizedChar() {
        val stale = listOf(
            mod(SystemModuleType.CHAT_FORMAT, 0),
            mod(SystemModuleType.RESPONSE_STYLE, 1),
        )
        val custom = listOf(
            mod(SystemModuleType.CHAT_FORMAT, 0, setOf(PromptScene.OFFLINE_MEETING)),
            mod(SystemModuleType.RESPONSE_STYLE, 1, setOf(PromptScene.OFFLINE_MEETING)),
        )
        var dict = PromptModuleService.setCharacterModules("cA", stale, "")
        dict = PromptModuleService.setCharacterModules("cB", custom, dict)

        val result = PromptModuleService.migratePromptModuleSceneDefaults(globalJson = "", characterJson = dict)
        assertNotNull(result)
        assertNull("全局空 → 不写回", result!!.first)
        assertNotNull("角色 dict 有改动 → 写回", result.second)

        val cA = PromptModuleService.loadCharacterModules("cA", result.second!!)!!
        assertEquals(ONLINE_ONLY, cA.scenesOf(SystemModuleType.CHAT_FORMAT))
        assertEquals(ONLINE_ONLY, cA.scenesOf(SystemModuleType.RESPONSE_STYLE))

        val cB = PromptModuleService.loadCharacterModules("cB", result.second!!)!!
        assertEquals("已定制角色原样不动", setOf(PromptScene.OFFLINE_MEETING), cB.scenesOf(SystemModuleType.CHAT_FORMAT))
        assertEquals(setOf(PromptScene.OFFLINE_MEETING), cB.scenesOf(SystemModuleType.RESPONSE_STYLE))
    }
}
