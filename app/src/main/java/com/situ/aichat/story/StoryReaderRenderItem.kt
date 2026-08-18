package com.situ.aichat.story

/**
 * 阅读器渲染项（源自 iOS `StoryReaderRenderItem`，`StoryReaderAnimatedBlocks.swift:3-76`）。
 *
 * 解析器（[StoryContentParser]）把正文拆成 [StoryContentBlock] 序列；[make] 把这些块「摊平」成线性渲染项：
 * 文字/场景/章末三类可见块。
 *
 * **2026-08-03 格式块精简**：mood/weather/pause/effect 四类标签整族退役，随之删掉「沿途折叠」的四个字段
 * （环境 mood/weather、pause 累加的 revealDelay、附着 effect）——摊平现在是纯粹的「可见块过滤 + 首段标记」。
 *
 * 纯数据转换，无 Compose 依赖，单测反推 iOS。
 */
data class StoryReaderRenderItem(
    val id: Int,
    val kind: Kind,
    val isFirstParagraph: Boolean,
) {
    sealed interface Kind {
        data class Text(val text: String, val style: StoryTextStyle) : Kind
        data class Scene(val text: String) : Kind
        data object ChapterEnd : Kind
    }

    companion object {
        /** 把内容块序列摊平成渲染项（源自 iOS `make(from:defaultMood:)`·defaultMood 随心情视觉层退役）。 */
        fun make(blocks: List<StoryContentBlock>): List<StoryReaderRenderItem> {
            val items = mutableListOf<StoryReaderRenderItem>()
            var identifier = 0
            var firstNormalTextSeen = false

            for (block in blocks) {
                when (block) {
                    is StoryContentBlock.Text -> {
                        val trimmed = block.text.trim()
                        if (trimmed.isEmpty()) continue
                        val isFirst = !firstNormalTextSeen && block.style == StoryTextStyle.NORMAL
                        if (isFirst) firstNormalTextSeen = true
                        items += StoryReaderRenderItem(
                            id = identifier,
                            kind = Kind.Text(trimmed, block.style),
                            isFirstParagraph = isFirst,
                        )
                        identifier++
                    }

                    is StoryContentBlock.SceneTransition -> {
                        items += StoryReaderRenderItem(
                            id = identifier,
                            kind = Kind.Scene(block.text),
                            isFirstParagraph = false,
                        )
                        identifier++
                    }

                    StoryContentBlock.ChapterEnd -> {
                        items += StoryReaderRenderItem(
                            id = identifier,
                            kind = Kind.ChapterEnd,
                            isFirstParagraph = false,
                        )
                        identifier++
                    }
                }
            }

            return items
        }
    }
}
