package com.situ.aichat.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定生成内容脏数据门规则（断言反推 iOS `GeneratedContentValidator.swift`，非反推 Kotlin 输出）：
 * minValidLength=4 / 必须含字母或 CJK / 前缀黑名单(token count·usage:·prompt_tokens·completion_tokens·
 * finish_reason·error:) / 片段黑名单({"error")。回归方向：朋友圈/日记 4 条生成路径过去只 isNotBlank，
 * provider 吐 "Token count: 2937"/{"error":...}/纯数字会原样入库。
 */
class GeneratedContentValidatorTest {

    // ---- 合法正文（isLikelyValid == true）----

    @Test fun `4 字 CJK 心情短句通过`() =
        assertTrue(GeneratedContentValidator.isLikelyValid("今天好冷")) // iOS minValidLength=4 即为兼容此类

    @Test fun `正常中文朋友圈通过`() =
        assertTrue(GeneratedContentValidator.isLikelyValid("今天和朋友去爬山，风景很好，很开心。"))

    @Test fun `英文正文通过`() =
        assertTrue(GeneratedContentValidator.isLikelyValid("Had a great day at the park today."))

    @Test fun `正文中部讨论 Error 不误伤——error 仅作前缀才拦`() =
        // 含 "Error:" 但不在句首、且无 {"error" → 合法（前缀黑名单只判 hasPrefix）
        assertTrue(GeneratedContentValidator.isLikelyValid("今天修 bug，老板说 Error: 数据库的问题也解决了"))

    @Test fun `null 4 字母按 iOS 规则判合法`() =
        // 锁定 iOS 实际规则：只拦 <4字/无字母/debug 前缀/json error，并不专门拦 "null"
        // （iOS 注释里"能拦 null"是夸张，代码对 4 字母 "null" 返回 true）。
        assertTrue(GeneratedContentValidator.isLikelyValid("null"))

    // ---- 脏数据（isLikelyValid == false）----

    @Test fun `空串被拦`() = assertFalse(GeneratedContentValidator.isLikelyValid(""))

    @Test fun `不足 4 字被拦`() {
        assertFalse(GeneratedContentValidator.isLikelyValid("好冷")) // 2 字
        assertFalse(GeneratedContentValidator.isLikelyValid("ok"))   // 2 字
        assertFalse(GeneratedContentValidator.isLikelyValid("   好   ")) // trim 后 1 字
    }

    @Test fun `纯数字纯标点被拦——无字母或 CJK`() {
        assertFalse(GeneratedContentValidator.isLikelyValid("1234"))
        assertFalse(GeneratedContentValidator.isLikelyValid("。。。。"))
        assertFalse(GeneratedContentValidator.isLikelyValid("---- ---"))
    }

    @Test fun `token count 前缀被拦——大小写不敏感`() {
        assertFalse(GeneratedContentValidator.isLikelyValid("Token count: 2937"))
        assertFalse(GeneratedContentValidator.isLikelyValid("token count: 5"))
    }

    @Test fun `usage prompt_tokens completion_tokens finish_reason 前缀被拦`() {
        assertFalse(GeneratedContentValidator.isLikelyValid("usage: { prompt_tokens: 10 }"))
        assertFalse(GeneratedContentValidator.isLikelyValid("prompt_tokens: 500 completion_tokens: 200"))
        assertFalse(GeneratedContentValidator.isLikelyValid("completion_tokens: 200"))
        assertFalse(GeneratedContentValidator.isLikelyValid("finish_reason: stop"))
    }

    @Test fun `error 前缀被拦——含首尾空白也认（先 trim）`() =
        assertFalse(GeneratedContentValidator.isLikelyValid("  Error: rate limit exceeded  "))

    @Test fun `json error 片段在任意位置都被拦`() {
        assertFalse(GeneratedContentValidator.isLikelyValid("""{"error": "invalid_api_key", "code": 401}"""))
        assertFalse(GeneratedContentValidator.isLikelyValid("""结果：{"error": "bad request"}""")) // 中部出现也拦
    }

    // ---- 自定义最短长度门（朋友圈动态路径传 MomentGenerationService.MIN_POST_CONTENT_LENGTH=10）----

    @Test fun `动态门槛 10 字拦聊天腔短回复——默认 4 字门仍放行（评论及日记路径不变）`() {
        // 2026-07-07 事故原文：假模型罐头聊天回复（9 字）过了默认门被当动态入库
        assertFalse(GeneratedContentValidator.isLikelyValid("嗯嗯，刚看到消息。", minLength = 10))
        assertTrue(GeneratedContentValidator.isLikelyValid("嗯嗯，刚看到消息。"))
        // 正常长度动态在 10 字门下照常通过
        assertTrue(GeneratedContentValidator.isLikelyValid("今天和朋友去爬山，风景很好，很开心。", minLength = 10))
    }

    @Test fun `describeInvalidReason 尊重自定义最短长度`() =
        assertTrue(GeneratedContentValidator.describeInvalidReason("嗯嗯，刚看到消息。", minLength = 10).contains("过短"))

    // ---- describeInvalidReason ----

    @Test fun `describeInvalidReason 给出对应原因`() {
        assertTrue(GeneratedContentValidator.describeInvalidReason("好冷").contains("过短"))
        assertTrue(GeneratedContentValidator.describeInvalidReason("1234").contains("无任何字母"))
        assertTrue(GeneratedContentValidator.describeInvalidReason("Token count: 1").contains("token count"))
        assertTrue(GeneratedContentValidator.describeInvalidReason("""{"error":"x"}""").contains("{\"error\""))
    }

    @Test fun `合法内容的 describeInvalidReason 不应被调用——但兜底返回未知原因`() =
        // 合法内容不会进 describe 分支；防御性确认它不抛、且不误报具体原因
        assertEquals("内容校验失败：未知原因", GeneratedContentValidator.describeInvalidReason("今天天气很好"))
}
