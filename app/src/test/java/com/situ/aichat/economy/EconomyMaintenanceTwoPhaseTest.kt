package com.situ.aichat.economy

import com.situ.aichat.data.local.entity.CharacterWalletEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-39 两段式拆分的纯函数单测：[selectNeedsInference]（断言从 iOS AppBootstrapService.swift:167 反推：
 * `characters.filter { ($0.wallet?.salaryInferred ?? true) == false }` + :168 空则不开 Task + 无配置短路）。
 */
class EconomyMaintenanceTwoPhaseTest {

    private fun wallet(uuid: String, inferred: Boolean) =
        CharacterWalletEntity(characterUuid = uuid, salaryInferred = inferred)

    @Test
    fun `未推断且有配置 入选`() {
        val result = selectNeedsInference(
            listOf(wallet("a", inferred = false), wallet("b", inferred = false)),
            hasConfig = true,
        )
        assertEquals(setOf("a", "b"), result)
    }

    @Test
    fun `已推断 排除`() {
        // iOS :167：salaryInferred == false 才入选——「推断结果就是 0」（乞丐设定）也算已推断不重推。
        val result = selectNeedsInference(
            listOf(wallet("a", inferred = true), wallet("b", inferred = false)),
            hasConfig = true,
        )
        assertEquals(setOf("b"), result)
    }

    @Test
    fun `无 API 配置 整段空`() {
        // =iOS 推断内部「无配置返回 nil」的提前短路。
        val result = selectNeedsInference(
            listOf(wallet("a", inferred = false)),
            hasConfig = false,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `空钱包列表 空`() {
        // =iOS AppBootstrapService.swift L168 guard：空则不开异步 Task。
        assertTrue(selectNeedsInference(emptyList(), hasConfig = true).isEmpty())
    }
}
