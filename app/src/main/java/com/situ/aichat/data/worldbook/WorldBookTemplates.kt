package com.situ.aichat.data.worldbook

import com.situ.aichat.data.local.entity.WorldBookEntryEntity

/**
 * 预置设定集模板（WB8·契约 §12.2 / D5·内容单源 = `FABLE5_WORLDBOOK_TEMPLATES_DRAFT.md`）。
 * 模板不是「只读内置书」——书架「从模板开始」区一键**复制成「我的书」**（[WorldBookRepository.copyTemplate]
 * 全新 uuid 家族），复制后与模板零关联、随便改随便删。
 */
data class WorldBookTemplate(
    /** 稳定标识（UI 列表 key 用，不落库）。 */
    val id: String,
    val name: String,
    val description: String,
    /** 条目原型：uuid/bookUuid 为占位空串，复制落库时由仓库统一换新（防两次复制撞主键）。 */
    val entries: List<WorldBookEntryEntity>,
)

/** 模板注册表：三套预置世界观（修仙 / 民国旧上海 / 末世废土·D5 拍板·各 48 条）。 */
object WorldBookTemplates {
    val all: List<WorldBookTemplate> by lazy {
        listOf(xianzhouTemplate(), shanghaiTemplate(), wastelandTemplate())
    }
}

/**
 * 模板条目构造糖：只写非默认字段，其余照 [WorldBookEntryEntity] 的 ST 默认值（契约 §2.1）。
 * 命名与契约草稿对齐：`order`=insertionOrder / `group`=groupName / 常驻=constant / 语义=vectorized。
 */
internal fun templateEntry(
    comment: String,
    content: String,
    keys: List<String> = emptyList(),
    secondaryKeys: List<String> = emptyList(),
    constant: Boolean = false,
    vectorized: Boolean = false,
    order: Int = 100,
    position: Int = 0,
    probability: Int = 100,
    sticky: Int? = null,
    cooldown: Int? = null,
    delay: Int? = null,
    group: String = "",
): WorldBookEntryEntity = WorldBookEntryEntity(
    uuid = "",
    bookUuid = "",
    comment = comment,
    content = content,
    keysJson = encodeStringList(keys),
    secondaryKeysJson = encodeStringList(secondaryKeys),
    constant = constant,
    vectorized = vectorized,
    insertionOrder = order,
    position = position,
    probability = probability,
    sticky = sticky,
    cooldown = cooldown,
    delay = delay,
    groupName = group,
)
