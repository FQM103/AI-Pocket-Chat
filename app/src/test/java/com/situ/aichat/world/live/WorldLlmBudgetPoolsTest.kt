package com.situ.aichat.world.live

import com.situ.aichat.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [WorldVividnessPools] T1-2（纯映射·图纸 §7）：档位 → cap 从决策 43 数值**独立反推**（非照搬实现），
 * 类目串锁死。`cap≤0 恒拒` 属 [com.situ.aichat.world.bulletin.WorldLlmBudget]（`WorldLlmBudgetTest` 已覆盖），
 * 此处只钉「lite/未知档 → 0」= 省档天然零额度。
 */
class WorldLlmBudgetPoolsTest {

    @Test
    fun `偷听 cap 三档_决策43①`() {
        assertEquals("省档零额度", 0, WorldVividnessPools.eavesdropCap(AppSettings.WORLD_VIVIDNESS_LITE))
        assertEquals("标准 15", 15, WorldVividnessPools.eavesdropCap(AppSettings.WORLD_VIVIDNESS_STANDARD))
        assertEquals("豪华保险丝 50", 50, WorldVividnessPools.eavesdropCap(AppSettings.WORLD_VIVIDNESS_RICH))
    }

    @Test
    fun `风物志 cap 三档_决策43②`() {
        assertEquals("省档零额度", 0, WorldVividnessPools.loreCap(AppSettings.WORLD_VIVIDNESS_LITE))
        assertEquals("标准 6", 6, WorldVividnessPools.loreCap(AppSettings.WORLD_VIVIDNESS_STANDARD))
        assertEquals("豪华保险丝 12", 12, WorldVividnessPools.loreCap(AppSettings.WORLD_VIVIDNESS_RICH))
    }

    @Test
    fun `未知档恒零_省档语义`() {
        assertEquals(0, WorldVividnessPools.eavesdropCap("garbage"))
        assertEquals(0, WorldVividnessPools.eavesdropCap(""))
        assertEquals(0, WorldVividnessPools.loreCap("garbage"))
        assertEquals(0, WorldVividnessPools.loreCap(""))
    }

    @Test
    fun `预算类目串锁死`() {
        assertEquals("eaves", WorldVividnessPools.EAVES)
        assertEquals("lore", WorldVividnessPools.LORE)
    }
}
