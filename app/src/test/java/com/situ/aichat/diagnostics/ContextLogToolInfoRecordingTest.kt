package com.situ.aichat.diagnostics

import com.situ.aichat.data.local.dao.LogDao
import com.situ.aichat.data.model.AppSettings
import com.situ.aichat.data.remote.llm.ChatMessageDto
import com.situ.aichat.data.remote.llm.LlmClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import com.situ.aichat.data.repository.SettingsRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具遥测落库契约（上下文日志工具可见性·2026-07-12·T2）：[ContextLogService.recordSuccess] 的
 * `toolInfo` 可选尾参——detail 开存全量（含参数预览）、detail 关剥参数预览（名与计数恒存，
 * 与 fullContext 同一隐私口径）、null → toolInfoJson 空串（旧调用方零波及）。
 * 哨兵手法同 [ContextLogServiceStreamingTest]：MockK LogDao + coVerify(timeout)（落库是 fire-and-forget）。
 */
class ContextLogToolInfoRecordingTest {

    private val json = Json
    private val messages = listOf(ChatMessageDto(role = "user", content = "u"))

    private fun serviceWith(detailEnabled: Boolean, logDao: LogDao): ContextLogService {
        val settingsRepository = mockk<SettingsRepository>()
        every { settingsRepository.appSettings } returns flowOf(AppSettings(logDetailEnabled = detailEnabled))
        return ContextLogService(mockk<LlmClient>(), logDao, settingsRepository, json)
    }

    private fun toolInfo() = LogToolInfo.toolTurn(
        sentToolNames = listOf("suggest_offline_meeting", "end_offline_meeting"),
        calls = listOf("suggest_offline_meeting" to """{"location":"公园"}"""),
        parsedOfflineActions = 1,
    )

    @Test
    fun `detail开_遥测全量落库含参数预览`() = runBlocking {
        val logDao = mockk<LogDao>(relaxed = true)
        serviceWith(detailEnabled = true, logDao = logDao)
            .recordSuccess("chat", "夏晴子", "m", messages, "好呀", 10L, null, toolInfo = toolInfo())
        coVerify(timeout = 2000) {
            logDao.insert(
                match {
                    val decoded = LogToolInfo.decode(json, it.toolInfoJson)
                    decoded != null &&
                        decoded.sentTools == listOf("suggest_offline_meeting", "end_offline_meeting") &&
                        decoded.calls.single().argsPreview == """{"location":"公园"}""" &&
                        decoded.parsedOfflineActions == 1
                },
            )
        }
    }

    @Test
    fun `detail关_参数预览剥除_名与计数恒存`() = runBlocking {
        val logDao = mockk<LogDao>(relaxed = true)
        serviceWith(detailEnabled = false, logDao = logDao)
            .recordSuccess("chat", "夏晴子", "m", messages, "好呀", 10L, null, toolInfo = toolInfo())
        coVerify(timeout = 2000) {
            logDao.insert(
                match {
                    val decoded = LogToolInfo.decode(json, it.toolInfoJson)
                    decoded != null &&
                        decoded.calls.single().name == "suggest_offline_meeting" &&
                        decoded.calls.single().argsPreview == null &&
                        decoded.parsedOfflineActions == 1
                },
            )
        }
    }

    @Test
    fun `无遥测_toolInfoJson存空串_旧调用方零波及`() = runBlocking {
        val logDao = mockk<LogDao>(relaxed = true)
        serviceWith(detailEnabled = true, logDao = logDao)
            .recordSuccess("chat", "夏晴子", "m", messages, "好呀", 10L, null)
        coVerify(timeout = 2000) { logDao.insert(match { it.toolInfoJson == "" }) }
    }

    @Test
    fun `一键去隐私_遥测参数预览剥除_名与计数恒存_损坏行跳过`() = runBlocking {
        // 复核 R1-🟡：purge 原先只跑 SQL 步（三正文列），toolInfoJson 里的 argsPreview 漏清。
        // 正确口径 = 写侧同一 sanitized（剥预览、名与计数恒存），已净行零回写、损坏 JSON 原样跳过。
        val logDao = mockk<LogDao>(relaxed = true)
        val withPreview = toolInfo().encode(json)
        val alreadyClean = toolInfo().sanitized(detailEnabled = false).encode(json)
        coEvery { logDao.purgeFullText() } returns 2
        coEvery { logDao.toolInfoRows() } returns listOf(
            LogToolInfoRow(id = 1, toolInfoJson = withPreview),
            LogToolInfoRow(id = 2, toolInfoJson = alreadyClean),
            LogToolInfoRow(id = 3, toolInfoJson = "{broken"),
        )
        val purged = serviceWith(detailEnabled = true, logDao = logDao).purgeSensitiveText()
        assertEquals("SQL 步返回口径不变", 2, purged)
        coVerify(exactly = 1) {
            logDao.updateToolInfo(
                1,
                match {
                    val d = LogToolInfo.decode(json, it)
                    d != null && d.calls.single().name == "suggest_offline_meeting" &&
                        d.calls.single().argsPreview == null && d.parsedOfflineActions == 1
                },
            )
        }
        coVerify(exactly = 0) { logDao.updateToolInfo(2, any()) }
        coVerify(exactly = 0) { logDao.updateToolInfo(3, any()) }
    }
}
