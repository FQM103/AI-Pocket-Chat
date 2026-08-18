package com.situ.aichat.diagnostics

import com.situ.aichat.data.local.entity.LogEntryEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 整条日志 → 可分享纯文本（D-3 打磨·②·纯函数）。给详情页「复制全文 / 导出 .txt」用：
 * 元数据头 + 完整上下文（[LogEntryEntity.fullContext] 自带区块排版）+ 回复全文（失败条 = 错误信息），
 * 粘给任何 AI 都零加工可读。纯展示格式（不被检测器/解析器消费·同 [LogContextFormat] 注），可自由演进。
 */
object LogShareFormat {

    /**
     * 复制载荷字节上限（按 Binder 真实编码口径 = UTF-16）。剪贴板经 Binder 传输（事务池 ~1MB 全进程共享），
     * Java String 打包走 UTF-16（2 字节/单元）——复核 R1 修正：原 UTF-8 口径会把纯 ASCII 低估一半
     * （50 万 ASCII 字符 UTF-8=500KB 放行、parcel 实际 ≈1MB 恰踩事务池全额）。超限时复制改走导出文件
     * （[copyPayloadTooLarge] 判定·消费方 LogShareActions）。取池的一半留余量。
     */
    const val CLIP_BYTE_LIMIT = 500_000

    /** 纯判定：UTF-16 字节数（`length × 2` = parcel 真实体量·O(1) 零分配）超 [CLIP_BYTE_LIMIT]。 */
    fun copyPayloadTooLarge(text: String): Boolean = text.length * 2 > CLIP_BYTE_LIMIT

    private val fileNameFmt = DateTimeFormatter.ofPattern("MMdd-HHmm", Locale.ROOT)
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    /** 导出文件名「日志_故事生成_0716-2152.txt」（来源 = [LogSource] 固定中文串，无路径危险字符）。 */
    fun exportFileName(source: String, timestampMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "日志_${source}_${Instant.ofEpochMilli(timestampMillis).atZone(zone).format(fileNameFmt)}.txt"

    /** 整条打包。detail 关（正文未存）→ 占位说明行，元数据仍可分享。 */
    fun entryText(e: LogEntryEntity, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        appendLine(if (e.isSuccess) "状态：成功" else "状态：失败")
        appendLine("时间：" + Instant.ofEpochMilli(e.timestampMillis).atZone(zone).format(timeFmt))
        appendLine("来源：" + e.source)
        appendLine("角色：" + e.characterName.ifBlank { "—" })
        appendLine("模型：" + e.modelName)
        appendLine("消息数：" + e.messageCount)
        e.durationMillis?.let { appendLine("耗时：" + String.format(Locale.ROOT, "%.1fs", it / 1000.0)) }
        if (e.isSuccess) {
            append("Token：输入 ${e.promptTokens} · 输出 ${e.completionTokens}")
            if (e.reasoningTokens > 0) append(" · 思考 ${e.reasoningTokens}")
            if (e.cacheHitTokens + e.cacheMissTokens > 0) {
                append(" · 缓存命中 ${e.cacheHitTokens} · 未命中 ${e.cacheMissTokens}")
            }
            if (e.isTokenEstimated) append("（估算）")
            appendLine()
        }
        e.errorMessage?.takeIf { it.isNotBlank() }?.let { appendLine("错误：$it") }
        appendLine()
        if (e.fullContext.isNotBlank()) {
            appendLine(e.fullContext)
        } else {
            appendLine("（详细记录关闭：未存上下文正文，仅元数据）")
        }
        e.responseContent?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine(SEP)
            appendLine("       回复全文")
            appendLine(SEP)
            appendLine()
            appendLine(it)
        }
    }.trimEnd()

    private const val SEP = "═══════════════════════════════"
}
