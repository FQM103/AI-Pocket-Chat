package com.situ.aichat.ui.offline

/**
 * 线下沉浸四步输入的纯组合逻辑（与 Compose 解耦，单测可在普通 JVM 反推 iOS `sendMessage`）。
 *
 * 四步标签顺序 1:1 iOS `OfflineImmersiveInputView.steps`；标签名须与 `OfflineContentParser.parseUserBlocks`
 * 认的 4 种标签一致（环境/动作/对话/内心），否则沉浸输入会静默失效 → round-trip 单测守门。
 */
internal val OfflineInputTags = listOf("环境", "动作", "对话", "内心")

/**
 * 把四步输入组合成标签格式文本（1:1 iOS `sendMessage`）：跳过空步骤，每个非空步骤包成
 * `[标签]内容[/标签]`，用换行连接。全空 → 返回空串（调用方据此不发送）。
 */
internal fun buildImmersiveInputMessage(stepTexts: List<String>): String =
    stepTexts.mapIndexedNotNull { index, text ->
        val trimmed = text.trim()
        if (trimmed.isEmpty() || index >= OfflineInputTags.size) {
            null
        } else {
            val tag = OfflineInputTags[index]
            "[$tag]$trimmed[/$tag]"
        }
    }.joinToString("\n")
