package com.situ.aichat.data.repository

import com.situ.aichat.data.model.ApiFunction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * settings-api-3：配置卡「用于：…」承接提示的纯函数（1:1 iOS APIFunctionRouter.assignedFunctionDisplayNames）。
 * 断言从 iOS 语义反推：显式分配 + 默认配置隐式承接未分配项 + 声明序。
 */
class ApiFunctionRouterTest {

    private val configA = "uuid-a"
    private val configB = "uuid-b"

    @Test
    fun explicitAssignmentsOnly_whenNotDefault() {
        val assignments = mapOf(
            ApiFunction.CHAT to configA,
            ApiFunction.MEMORY_SUMMARY to configB,
        )
        // 非默认配置只承接显式分配到自己的功能。
        assertEquals(
            listOf(ApiFunction.CHAT.displayName),
            ApiFunctionRouter.assignedFunctionDisplayNames(assignments, configA, isDefault = false),
        )
    }

    @Test
    fun defaultPicksUpUnassigned() {
        val assignments = mapOf(ApiFunction.CHAT to configB) // CHAT 显式分给 B
        val names = ApiFunctionRouter.assignedFunctionDisplayNames(assignments, configB, isDefault = true)
        // 默认配置 B：承接显式的 CHAT + 所有未分配功能（= 除 CHAT 外全部）。
        assertEquals(ApiFunction.entries.size, names.size)
        assertEquals(ApiFunction.CHAT.displayName, names.first()) // CHAT 显式且声明序第一
    }

    @Test
    fun defaultDoesNotPickUpFunctionsAssignedElsewhere() {
        val assignments = mapOf(ApiFunction.CHAT to configB) // CHAT 分给 B
        val names = ApiFunctionRouter.assignedFunctionDisplayNames(assignments, configA, isDefault = true)
        // 默认配置 A：CHAT 已属 B 故不承接；其余未分配项全承接。
        assertEquals(ApiFunction.entries.size - 1, names.size)
        assertEquals(false, names.contains(ApiFunction.CHAT.displayName))
    }

    @Test
    fun nonDefaultWithNoAssignments_isEmpty() {
        assertEquals(
            emptyList<String>(),
            ApiFunctionRouter.assignedFunctionDisplayNames(emptyMap(), configA, isDefault = false),
        )
    }

    @Test
    fun followsDeclarationOrder() {
        // 显式给 A 分配 MEMORY_SUMMARY 与 CHAT（map 顺序打乱），输出应按 entries 声明序：CHAT 在前。
        val assignments = linkedMapOf(
            ApiFunction.MEMORY_SUMMARY to configA,
            ApiFunction.CHAT to configA,
        )
        assertEquals(
            listOf(ApiFunction.CHAT.displayName, ApiFunction.MEMORY_SUMMARY.displayName),
            ApiFunctionRouter.assignedFunctionDisplayNames(assignments, configA, isDefault = false),
        )
    }
}
