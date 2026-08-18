package com.situ.aichat.prompt

import com.situ.aichat.sticker.BuiltInStickerCatalog
import com.situ.aichat.sticker.StickerService

/**
 * 表情包提示词模块（1:1 iOS `PromptBuilder+Sticker.buildStickerLibraryContent`）。
 * 开关关闭直接返回空串，AI 看不到清单 → 自然不生成 `[sticker:ID]`（配合历史全剥形成双保险）。
 * 文案为硬编码中文（与 iOS 一致，非本地化）；列表与总数由 [StickerService] 生成，只算**启用**的内置 + 自定义。
 */
internal fun buildStickerLibraryContent(ctx: PromptBuilder.BuildContext): String {
    if (!ctx.appSettings.characterCanSendStickersEnabled) return ""

    val customStickers = ctx.customStickers
    val disabled = ctx.disabledStickers
    val stickerList = StickerService.buildStickerListForPrompt(customStickers, disabled)
    val totalCount = BuiltInStickerCatalog.enabled(disabled).size + customStickers.size

    return listOf(
        "## 表情包",
        "你有 $totalCount 个表情包可以使用。想发表情包时，在回复中用 [sticker:ID] 格式标记。",
        "",
        "可用表情包：",
        stickerList,
        "",
        "发送原则：",
        "1. 不要每条消息都发表情包，偶尔发才自然（大约每 3-5 条消息发一次）",
        "2. 选择和当前情绪、话题最匹配的表情包",
        "3. 可以只发表情包不说话（回复仅包含 [sticker:ID]），也可以说完话再附上表情包",
        "4. 表情包 ID 必须**逐字复制**上面列表里某一行开头的 ID，一个字都不能改；如果你不能 100% 确定某个 ID 是否在列表里，宁可这条消息不发表情包，也绝不要凭记忆拼写或创造新 ID",
        "5. 一条消息最多用一个表情包",
        "6. 错误示例（列表里没有这些 ID，绝不要生成）：[sticker:开心_2]、[sticker:emo_难过]、[sticker:happy]",
        "",
        "接收原则（当用户发表情包给你时）：",
        "7. 你会在用户消息里看到 `[非语言情绪：XXX]` 这种标记，它代表用户用一张图传达了 XXX 所描述的情绪或态度",
        "8. 请像真实聊天一样自然回应这个情绪本身。**绝对不要**在你的回复里出现\"表情包\"、\"图\"、\"emoji\"、\"贴纸\"、\"砸过来\"这些字眼，也不要把方括号里的内容复读出来",
        "9. 举例：用户消息是 `[非语言情绪：委屈、想撒娇]`，正确回应是\"怎么啦宝贝，谁惹你不开心了\"这种直接回应情绪的说法；错误回应是\"哎哟这委屈的表情包砸过来\"这种复述标记的说法",
    ).joinToString("\n")
}
