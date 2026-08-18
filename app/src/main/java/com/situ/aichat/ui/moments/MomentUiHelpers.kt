package com.situ.aichat.ui.moments

import com.situ.aichat.data.local.entity.CharacterEntity
import com.situ.aichat.data.model.MomentAuthorType

/**
 * 朋友圈作者显示名（对齐 iOS 各 `authorName`/`commentAuthorName`）：用户固定 [meLabel]（iOS
 * `String(localized:"Me")`），角色取名字、查不到回落 [aiLabel]。卡片 / 详情 / 通知列表共用，避免重复。
 */
internal fun momentAuthorName(
    authorTypeRaw: String,
    characterUuid: String?,
    characterDict: Map<String, CharacterEntity>,
    meLabel: String,
    aiLabel: String,
): String = when (MomentAuthorType.fromRaw(authorTypeRaw)) {
    MomentAuthorType.USER -> meLabel
    MomentAuthorType.CHARACTER -> characterUuid?.let { characterDict[it]?.name } ?: aiLabel
}
