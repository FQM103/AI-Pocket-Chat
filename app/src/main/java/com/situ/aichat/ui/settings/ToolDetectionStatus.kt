package com.situ.aichat.ui.settings

import com.situ.aichat.data.model.ToolSupportLevel

/**
 * 「工具调用」检测状态在「编辑 API 配置」页的**展示种类** —— 纯逻辑判定，与 Compose/Android 解耦以便 T1 单测
 * （TOOL_CALLING_HARDENING_PLAN §9：把「等级→文案+色」抽成纯函数）。
 *
 * 文案与莫兰迪柔色由 [ToolDetectionStatusBlock] 按本种类映射；判定本身只关心「该显示哪一态」。
 */
enum class ToolDetectionStatusKind {
    /** 正在检测此模型的工具调用能力（转圈）。 */
    Detecting,

    /** 完整支持（柔绿点）。 */
    FullSupport,

    /** 基础支持（琥珀点）。 */
    BasicSupport,

    /** 不支持 → 已自动改用兼容方式（灰点·温和不报警，因 marker 降级照常可用）。 */
    UnsupportedFallback,

    /** 尚未检测（空心灰点）。 */
    NotDetected,
}

/**
 * 把持久检测等级 [level] 与实时「检测中」标志 [detecting] 归并为展示种类。
 *
 * - **检测中优先**：重测期间不再显示上一次的旧结论（否则会看到「完整支持 + 转圈」自相矛盾）。
 * - [ToolSupportLevel.UNKNOWN] 视作「尚未检测」，**绝不**当「不支持」—— 没测过和测出不支持是两回事。
 * - [ToolSupportLevel.UNSUPPORTED] → 「已自动走兼容方式」（产品事实，温和不报警）。
 */
fun resolveToolDetectionKind(level: ToolSupportLevel, detecting: Boolean): ToolDetectionStatusKind =
    when {
        detecting -> ToolDetectionStatusKind.Detecting
        level == ToolSupportLevel.FULL -> ToolDetectionStatusKind.FullSupport
        level == ToolSupportLevel.BASIC -> ToolDetectionStatusKind.BasicSupport
        level == ToolSupportLevel.UNSUPPORTED -> ToolDetectionStatusKind.UnsupportedFallback
        else -> ToolDetectionStatusKind.NotDetected
    }
