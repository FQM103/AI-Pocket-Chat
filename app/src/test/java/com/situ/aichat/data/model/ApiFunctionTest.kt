package com.situ.aichat.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ApiFunction] T1-1（W13 图纸 §7）：新增 WORLD 枚举项路由入 CONTENT 分类，raw 恒 "world"（改名即指派丢失·§9）。
 * 断言从 §4.8 服务内枚举元数据 + §2 category 归属独立反推。
 */
class ApiFunctionTest {

    @Test
    fun `WORLD raw 恒 world 且元数据逐字`() {
        assertEquals("world", ApiFunction.WORLD.raw)
        assertEquals("世界", ApiFunction.WORLD.displayName)
        // 2026-07-11 副标题加「思考/普通模型」建议后缀（功能 API 分配页文案·拍板见 ApiFunction 枚举头注释）。
        assertEquals("世界小报、偷听、风物志与初遇的润色，普通模型即可", ApiFunction.WORLD.subtitle)
    }

    @Test
    fun `WORLD 归 CONTENT 分类`() {
        assertEquals(ApiFunctionCategory.CONTENT, ApiFunction.WORLD.category)
        assertTrue(ApiFunctionCategory.CONTENT.functions.contains(ApiFunction.WORLD))
    }

    @Test
    fun `fromRaw world 解回 WORLD`() {
        assertEquals(ApiFunction.WORLD, ApiFunction.fromRaw("world"))
    }
}
