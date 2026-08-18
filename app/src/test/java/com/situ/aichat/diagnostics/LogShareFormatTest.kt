package com.situ.aichat.diagnostics

import com.situ.aichat.data.local.entity.LogEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 整条日志分享文本拼装单测（D-3 打磨·②·T1）。断言从 mockup §2 规格独立反推：
 * 元数据头齐全、上下文原样、回复全文带区块头、失败条走错误行、detail 关走占位行、
 * 文件名可读且含来源与时刻、复制超限判定按 UTF-16 字节（= Binder parcel 真实口径·复核 R1 修正）。
 */
class LogShareFormatTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    // 2026-07-16 21:52:18 CST
    private val ts = 1_784_209_938_000L

    private fun entry(
        success: Boolean = true,
        context: String = "═══\n发送给大模型的完整上下文\n═══\n系统提示正文",
        response: String? = "第十四章正文……",
        error: String? = null,
    ) = LogEntryEntity(
        id = 7, timestampMillis = ts, characterName = "", modelName = "deepseek-chat",
        isSuccess = success, source = LogSource.STORY_GENERATION, messageCount = 6,
        durationMillis = 48_200, errorMessage = error, fullContext = context, responseContent = response,
        promptTokens = 18_432, completionTokens = 2_210, reasoningTokens = 512,
        cacheHitTokens = 12_800, cacheMissTokens = 5_632, isTokenEstimated = false,
    )

    @Test
    fun 成功条_元数据头齐全_上下文与回复全在场() {
        val out = LogShareFormat.entryText(entry(), zone)
        assertTrue(out.contains("状态：成功"))
        assertTrue(out.contains("时间：2026-07-16 21:52:18"))
        assertTrue(out.contains("来源：故事生成"))
        assertTrue("空角色名回退长横", out.contains("角色：—"))
        assertTrue(out.contains("模型：deepseek-chat"))
        assertTrue(out.contains("消息数：6"))
        assertTrue(out.contains("耗时：48.2s"))
        assertTrue(out.contains("Token：输入 18432 · 输出 2210 · 思考 512 · 缓存命中 12800 · 未命中 5632"))
        assertFalse("精确 usage 不该带估算尾注", out.contains("（估算）"))
        assertTrue("上下文原样在场", out.contains("系统提示正文"))
        assertTrue("回复区块头", out.contains("回复全文"))
        assertTrue(out.contains("第十四章正文……"))
    }

    @Test
    fun 失败条_错误行在场_无Token行无回复区块() {
        val out = LogShareFormat.entryText(entry(success = false, response = null, error = "SSE 空闲超时"), zone)
        assertTrue(out.contains("状态：失败"))
        assertTrue(out.contains("错误：SSE 空闲超时"))
        assertFalse("失败条不填 token（既有落库口径）", out.contains("Token："))
        assertFalse(out.contains("回复全文"))
    }

    @Test
    fun detail关_正文占位说明_元数据仍可分享() {
        val out = LogShareFormat.entryText(entry(context = "", response = null), zone)
        assertTrue(out.contains("（详细记录关闭：未存上下文正文，仅元数据）"))
        assertTrue(out.contains("来源：故事生成"))
    }

    @Test
    fun 估算token_带估算尾注() {
        val out = LogShareFormat.entryText(entry().copy(isTokenEstimated = true), zone)
        assertTrue(out.contains("（估算）"))
    }

    @Test
    fun 文件名_来源加月日时分() {
        assertEquals(
            "日志_故事生成_0716-2152.txt",
            LogShareFormat.exportFileName(LogSource.STORY_GENERATION, ts, zone),
        )
    }

    @Test
    fun 复制超限判定_按UTF16即Binder真实口径() {
        // 复核 R1 修正：Binder 打包 String 走 UTF-16（2 字节/单元），判定 = length×2。
        // 旧 UTF-8 口径会把纯 ASCII 低估一半（50 万 ASCII 字符 UTF-8=500KB 放行、parcel 实际 ≈1MB 踩池顶）。
        // ASCII 与中文同长同判——这正是与 UTF-8 口径可区分的断言（UTF-8 下同长中文会提前 1.5 倍触发）。
        val half = LogShareFormat.CLIP_BYTE_LIMIT / 2
        assertFalse("恰等上限不超（> 判定）", LogShareFormat.copyPayloadTooLarge("a".repeat(half)))
        assertTrue(LogShareFormat.copyPayloadTooLarge("a".repeat(half + 1)))
        assertFalse("中文同长同判（不再按 UTF-8 提前拦）", LogShareFormat.copyPayloadTooLarge("字".repeat(half)))
        assertTrue(LogShareFormat.copyPayloadTooLarge("字".repeat(half + 1)))
    }
}
