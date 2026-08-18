package com.situ.aichat.prompt

import kotlinx.serialization.Serializable

/**
 * 上下文分段信息：发给大模型的某一模块的字符数 + 估算 token（批 D·上下文日志「结构化展示」）。
 *
 * 1:1 iOS `ContextSegment`，但**弃用 iOS 的 `iconName`（SF Symbol 串）**——本项目 UI 按 Fable-5 设计语言
 * 自绘图标，改存 [systemModuleType]（[com.situ.aichat.prompt.SystemModuleType] 的 rawValue，自定义模块=null），
 * 由展示层自行映射图标。序列化为 JSON 存进 `LogEntryEntity.contextSegmentsJson`，仅日志详情页消费。
 *
 * 仅聊天管线（[PromptScene] 四态走 [PromptBuilder.buildMessages] 同一模块系统）产生分段；后台生成类任务
 * （朋友圈/日记/故事/记忆/日程/通知/礼物）不经模块系统，传空分段（1:1 iOS）。
 */
@Serializable
data class ContextSegment(
    /** 模块显示名（= [com.situ.aichat.prompt.PromptModule.name]，如「核心规则」「对话历史」）。 */
    val name: String,
    /** 系统模块类型 rawValue（自定义模块=null）；展示层据此映射 Fable-5 图标。 */
    val systemModuleType: String? = null,
    /** 该段字符数。 */
    val charCount: Int,
    /** [TokenEstimator] 估算的 token 数。 */
    val estimatedTokens: Int,
    /** 位置：[POSITION_PREFIX] 前置区 / [POSITION_HISTORY] 对话历史 / [POSITION_SUFFIX] 后置区。 */
    val position: String,
) {
    companion object {
        const val POSITION_PREFIX = "prefix"
        const val POSITION_HISTORY = "history"
        const val POSITION_SUFFIX = "suffix"
    }
}
